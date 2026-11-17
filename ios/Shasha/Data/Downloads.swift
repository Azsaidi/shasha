import Foundation

/// A film or episode saved (or waiting to be saved) on the device for offline viewing.
struct DownloadItem: Codable, Identifiable, Hashable {
    var kind: WatchKind
    var streamId: String
    var accountId: String
    var title: String
    var subtitle: String?
    var image: String?
    var ext: String
    var seriesId: String?
    var seriesName: String?
    var season: Int
    var episode: Int
    var fileName: String
    var addedAt: Date
    var url: String
    var done = false
    /// 0 = no failure; an HTTP code; or one of the reason values.
    var failedReason = 0
    var total: Int64 = 0

    var key: String { WatchEntry.key(kind, streamId) }
    var id: String { key }
}

enum DownloadState: Equatable {
    case queued, running, paused, done, failed
}

struct DownloadStatus: Equatable {
    var state: DownloadState
    var bytes: Int64
    var total: Int64
    /// For failures: an HTTP status code or one of the reason values. For pauses: why it's paused.
    var reason = 0
    /// Bytes per second while running (smoothed).
    var speed: Double = 0

    /// Seconds left, when the size and speed are known.
    var eta: Double? {
        guard state == .running, total > 0, speed > 1 else { return nil }
        return Double(max(total - bytes, 0)) / speed
    }

    var fraction: Double? { total > 0 ? min(max(Double(bytes) / Double(total), 0), 1) : nil }
    var active: Bool { state == .queued || state == .running || state == .paused }
}

/// The download queue.
///
/// Files are fetched in pieces (HTTP ranges) and each finished piece is added to a ".part" file,
/// so stopping a download (to stream on a 1-connection plan, the app being closed, a dropped
/// connection) loses at most the piece in progress instead of starting over. A background
/// URLSession keeps it going after you leave the app. One download runs at a time.
final class Downloads: NSObject, ObservableObject {
    static let shared = Downloads()

    static let reasonDropped = -2
    static let reasonNoSpace = -3
    static let reasonNeedsRedownload = -4
    static let reasonRetrying = -10
    static let reasonStreaming = -11

    private static let maxAttempts = 8
    /// Size of each piece. Smaller loses less when paused; larger makes fewer requests.
    private static let chunkSize: Int64 = 32 << 20
    static let sessionId = "com.azsaidi.shasha.downloads"

    @Published private(set) var items: [DownloadItem] = []
    @Published private(set) var status: [String: DownloadStatus] = [:]
    /// True while something streams from the provider.
    @Published private(set) var streamingNow = false

    /// From the account's plan; on 1-connection plans downloads pause while streaming.
    var maxConnections = 1 {
        didSet { if oldValue != maxConnections { kick() } }
    }

    /// Set by the app delegate when iOS wakes the app to finish background downloads.
    var backgroundCompletion: (() -> Void)?

    private var session: URLSession!
    private var currentTask: URLSessionDownloadTask?
    private var currentKey: String?
    /// Where the running piece starts in the file.
    private var pieceStart: [String: Int64] = [:]
    /// True while a finished piece is being added to its file (the next piece waits for it).
    private var appending = false
    private var stopping = false
    private var attempts: [String: Int] = [:]
    private var outcome: [String: Outcome] = [:]
    private var lastPublish: [String: (time: Date, bytes: Int64)] = [:]
    private var speeds: [String: Double] = [:]
    private let fileQueue = DispatchQueue(label: "downloads.files", qos: .utility)

    private enum Outcome {
        /// A piece arrived (staged at this URL); `total` from Content-Range when known.
        case piece(URL, total: Int64?, requested: Int64)
        /// The server sent the whole file in one go (it ignores ranges).
        case whole(URL)
        /// Asked for bytes past the end: the file is complete.
        case complete
        case retry(Int)
        case fail(Int)
    }

    private let dir: URL
    private let listFile: URL

    private override init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = support.appendingPathComponent("Downloads", isDirectory: true)
        listFile = support.appendingPathComponent("downloads.json")
        super.init()
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)
        // Left over from the previous version.
        try? FileManager.default.removeItem(at: support.appendingPathComponent("DownloadResume", isDirectory: true))

        let config = URLSessionConfiguration.background(withIdentifier: Downloads.sessionId)
        config.isDiscretionary = false
        config.sessionSendsLaunchEvents = true
        config.allowsCellularAccess = true
        config.timeoutIntervalForRequest = 60
        config.httpAdditionalHeaders = ["User-Agent": Http.userAgent]
        session = URLSession(configuration: config, delegate: self, delegateQueue: .main)

        if let data = try? Data(contentsOf: listFile),
           let list = try? JSONDecoder().decode([DownloadItem].self, from: data) {
            items = list
        }
        for item in items { status[item.key] = initialStatus(item) }

        // Back in the app: start a piece that iOS was holding back while the app was in the background.
        NotificationCenter.default.addObserver(forName: Notification.Name("UIApplicationWillEnterForegroundNotification"),
                                               object: nil, queue: .main) { [weak self] _ in
            guard let self, let task = self.currentTask, let key = self.currentKey,
                  task.countOfBytesReceived == 0 else { return }
            self.stopping = true
            self.currentTask = nil
            self.currentKey = nil
            task.cancel()
            self.status[key]?.state = .queued
            self.kick()
        }

        // Pieces started before the app was closed: forget them and continue from the saved file.
        session.getAllTasks { tasks in
            DispatchQueue.main.async {
                for task in tasks { task.cancel() }
                self.kick()
            }
        }
    }

    private func initialStatus(_ item: DownloadItem) -> DownloadStatus {
        let size = fileSize(file(item))
        if item.done && size > 0 { return DownloadStatus(state: .done, bytes: size, total: size) }
        if item.done { return DownloadStatus(state: .failed, bytes: 0, total: 0, reason: Downloads.reasonNeedsRedownload) }
        let part = fileSize(partFile(item))
        if item.failedReason != 0 { return DownloadStatus(state: .failed, bytes: part, total: item.total, reason: item.failedReason) }
        return DownloadStatus(state: .queued, bytes: part, total: item.total)
    }

    private func save() {
        let list = items
        let url = listFile
        DispatchQueue.global(qos: .utility).async {
            if let data = try? JSONEncoder().encode(list) { try? data.write(to: url, options: .atomic) }
        }
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value ?? 0
    }

    func file(_ item: DownloadItem) -> URL { dir.appendingPathComponent(item.fileName) }
    private func partFile(_ item: DownloadItem) -> URL { dir.appendingPathComponent(item.fileName + ".part") }
    private func pieceFile(_ key: String) -> URL {
        dir.appendingPathComponent(key.replacingOccurrences(of: ":", with: "_") + ".piece")
    }

    func find(_ kind: WatchKind, _ streamId: String) -> DownloadItem? {
        let key = WatchEntry.key(kind, streamId)
        return items.first { $0.key == key }
    }

    func findByKey(_ key: String) -> DownloadItem? { items.first { $0.key == key } }

    /// Cheap check for posters and lists.
    func isDownloaded(_ kind: WatchKind, _ streamId: String) -> Bool {
        status[WatchEntry.key(kind, streamId)]?.state == .done
    }

    /// A file address for a finished download, so playback works without internet.
    func localURL(_ kind: WatchKind, _ streamId: String) -> URL? {
        guard let item = find(kind, streamId), status[item.key]?.state == .done else { return nil }
        let url = file(item)
        return fileSize(url) > 0 ? url : nil
    }

    private var onePerAccount: Bool { maxConnections <= 1 }

    /// Called by the players. On one-connection plans the download pauses and continues afterwards.
    func setStreaming(_ active: Bool) {
        guard streamingNow != active else { return }
        streamingNow = active
        if active && onePerAccount {
            pauseCurrent()
        } else if !active {
            // Give the provider a few seconds to notice the stream has stopped, or it may refuse
            // the download for using a second connection.
            DispatchQueue.main.asyncAfter(deadline: .now() + 4) { [weak self] in
                guard let self, !self.streamingNow else { return }
                self.kick()
            }
        }
    }

    var canDownloadNow: Bool { !(streamingNow && onePerAccount) }

    private func next() -> DownloadItem? {
        guard canDownloadNow else { return nil }
        return items
            .filter { status[$0.key]?.state == .queued || (status[$0.key]?.state == .paused && status[$0.key]?.reason != Downloads.reasonRetrying) }
            .min { $0.addedAt < $1.addedAt }
    }

    /// Adds a download to the queue; returns false if it couldn't be queued.
    @discardableResult
    func enqueue(url: String, kind: WatchKind, streamId: String, accountId: String, title: String,
                 subtitle: String?, image: String?, ext: String, seriesId: String? = nil,
                 seriesName: String? = nil, season: Int = 0, episode: Int = 0) -> Bool {
        if let existing = find(kind, streamId) {
            if status[existing.key]?.state != .failed { return true }
            remove(existing)
        }
        guard URL(string: url) != nil else { return false }
        let safeExt = ext.isEmpty ? "mp4" : ext
        let item = DownloadItem(
            kind: kind, streamId: streamId, accountId: accountId, title: title, subtitle: subtitle, image: image,
            ext: safeExt, seriesId: seriesId, seriesName: seriesName, season: season, episode: episode,
            fileName: "\(kind.prefix)_\(streamId)_\(Int(Date().timeIntervalSince1970)).\(safeExt)",
            addedAt: Date(), url: url
        )
        items.append(item)
        status[item.key] = DownloadStatus(state: .queued, bytes: 0, total: 0)
        save()
        kick()
        return true
    }

    /// Tries a failed download again, keeping what was already downloaded.
    func retry(_ item: DownloadItem) {
        replace(item.key) { $0.failedReason = 0 }
        attempts[item.key] = 0
        status[item.key] = DownloadStatus(state: .queued, bytes: fileSize(partFile(item)), total: item.total)
        save()
        kick()
    }

    /// Cancels (if running) and deletes the files.
    func remove(_ item: DownloadItem) {
        if currentKey == item.key {
            stopping = true
            currentTask?.cancel()
            currentTask = nil
            currentKey = nil
        }
        for url in [file(item), partFile(item), pieceFile(item.key)] {
            try? FileManager.default.removeItem(at: url)
        }
        items.removeAll { $0.key == item.key }
        status[item.key] = nil
        attempts[item.key] = nil
        forgetSpeed(item.key)
        save()
        kick()
    }

    var usedBytes: Int64 {
        let files = (try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.fileSizeKey])) ?? []
        return files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    var freeBytes: Int64 {
        let values = try? dir.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
        return values?.volumeAvailableCapacityForImportantUsage ?? 0
    }

    private func replace(_ key: String, _ change: (inout DownloadItem) -> Void) {
        guard let i = items.firstIndex(where: { $0.key == key }) else { return }
        change(&items[i])
    }

    // MARK: - Running the queue

    /// Starts the next piece if nothing is downloading.
    func kick() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { self.kick() }
            return
        }
        guard session != nil, currentTask == nil, !appending, Connectivity.shared.isOnline, let item = next() else { return }
        start(item)
    }

    private func start(_ item: DownloadItem) {
        guard let url = URL(string: item.url) else {
            fail(item.key, Downloads.reasonNeedsRedownload)
            return
        }
        let have = fileSize(partFile(item))
        if item.total > 0 && have >= item.total {
            finish(item.key)
            return
        }
        var request = URLRequest(url: url)
        request.setValue(Http.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("bytes=\(have)-\(have + Downloads.chunkSize - 1)", forHTTPHeaderField: "Range")
        let task = session.downloadTask(with: request)
        task.taskDescription = item.key
        currentTask = task
        currentKey = item.key
        pieceStart[item.key] = have
        // Keep measuring speed across pieces; only a fresh start begins a new measurement.
        if lastPublish[item.key] == nil { lastPublish[item.key] = (Date(), have) }
        stopping = false
        let previous = status[item.key]
        status[item.key] = DownloadStatus(state: .running, bytes: have, total: item.total, speed: previous?.speed ?? 0)
        task.resume()
    }

    private func forgetSpeed(_ key: String) {
        lastPublish[key] = nil
        speeds[key] = nil
    }

    private func pauseCurrent() {
        guard let task = currentTask, let key = currentKey else { return }
        forgetSpeed(key)
        stopping = true
        currentTask = nil
        currentKey = nil
        task.cancel()
        if var s = status[key] {
            s.state = .paused
            s.reason = Downloads.reasonStreaming
            s.speed = 0
            s.bytes = findByKey(key).map { fileSize(partFile($0)) } ?? s.bytes
            status[key] = s
        }
    }

    private func fail(_ key: String, _ reason: Int) {
        forgetSpeed(key)
        replace(key) { $0.failedReason = reason }
        let item = findByKey(key)
        status[key] = DownloadStatus(state: .failed, bytes: item.map { fileSize(partFile($0)) } ?? 0,
                                     total: item?.total ?? 0, reason: reason)
        save()
    }

    /// The ".part" file is complete: give it its real name.
    private func finish(_ key: String) {
        guard let item = findByKey(key) else { return }
        let dest = file(item)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: partFile(item), to: dest)
        } catch {
            fail(key, Downloads.reasonNeedsRedownload)
            return
        }
        let size = fileSize(dest)
        replace(key) {
            $0.done = true
            $0.failedReason = 0
            $0.total = size
        }
        status[key] = DownloadStatus(state: .done, bytes: size, total: size)
        attempts[key] = nil
        save()
    }

    private func scheduleRetry(_ key: String, code: Int) {
        let attempt = (attempts[key] ?? 0) + 1
        attempts[key] = attempt
        guard attempt <= Downloads.maxAttempts else {
            fail(key, code)
            return
        }
        if var s = status[key] {
            s.state = .paused
            s.reason = Downloads.reasonRetrying
            s.speed = 0
            status[key] = s
        }
        // Wait a little longer each time (the connection may be busy or flaky).
        let wait = min(Double(attempt) * 5, 60)
        DispatchQueue.main.asyncAfter(deadline: .now() + wait) {
            guard var s = self.status[key], s.state == .paused, s.reason == Downloads.reasonRetrying else { return }
            s.state = .queued
            s.reason = 0
            self.status[key] = s
            self.kick()
        }
    }

    /// Adds a finished piece to the ".part" file (off the main thread), then carries on.
    private func appendPiece(_ key: String, piece: URL, total: Int64?, requested: Int64) {
        guard let item = findByKey(key) else {
            try? FileManager.default.removeItem(at: piece)
            return
        }
        appending = true
        let part = partFile(item)
        fileQueue.async {
            var failure: Int?
            do {
                if !FileManager.default.fileExists(atPath: part.path) {
                    FileManager.default.createFile(atPath: part.path, contents: nil)
                }
                let out = try FileHandle(forWritingTo: part)
                let input = try FileHandle(forReadingFrom: piece)
                defer {
                    try? out.close()
                    try? input.close()
                }
                try out.seekToEnd()
                while let block = try input.read(upToCount: 4 << 20), !block.isEmpty {
                    try out.write(contentsOf: block)
                }
            } catch {
                let ns = error as NSError
                failure = ns.code == NSFileWriteOutOfSpaceError ? Downloads.reasonNoSpace : Downloads.reasonDropped
            }
            let pieceBytes = (try? FileManager.default.attributesOfItem(atPath: piece.path)[.size] as? NSNumber)?.int64Value ?? 0
            try? FileManager.default.removeItem(at: piece)
            DispatchQueue.main.async {
                self.appending = false
                defer { self.kick() }
                guard self.findByKey(key) != nil else { return }
                if let failure {
                    if failure == Downloads.reasonNoSpace { self.fail(key, failure) } else { self.scheduleRetry(key, code: failure) }
                    return
                }
                let have = self.fileSize(part)
                if let total, total > 0, self.findByKey(key)?.total != total {
                    self.replace(key) { $0.total = total }
                    self.save()
                }
                let known = self.findByKey(key)?.total ?? 0
                // Done when we reach the known size, or (size unknown) when a piece comes back short.
                if (known > 0 && have >= known) || (known <= 0 && pieceBytes < requested) {
                    self.finish(key)
                } else if let item = self.findByKey(key) {
                    if self.currentTask == nil, self.canDownloadNow, Connectivity.shared.isOnline {
                        // Straight on to the next piece, keeping the speed and time left on screen.
                        self.start(item)
                    } else if var s = self.status[key] {
                        s.bytes = have
                        s.state = .queued
                        s.reason = 0
                        self.forgetSpeed(key)
                        s.speed = 0
                        self.status[key] = s
                    }
                }
            }
        }
    }
}

extension Downloads: URLSessionDownloadDelegate {
    private func totalFromResponse(_ response: URLResponse?) -> Int64? {
        guard let http = response as? HTTPURLResponse else { return nil }
        // "Content-Range: bytes 0-33554431/2147483648"
        if let range = http.value(forHTTPHeaderField: "Content-Range"),
           let slash = range.lastIndex(of: "/"), let total = Int64(range[range.index(after: slash)...]) {
            return total
        }
        if http.statusCode == 200, http.expectedContentLength > 0 { return http.expectedContentLength }
        return nil
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let key = downloadTask.taskDescription, downloadTask == currentTask else { return }
        if findByKey(key)?.total ?? 0 <= 0, let total = totalFromResponse(downloadTask.response) {
            replace(key) { $0.total = total }
        }
        let total = findByKey(key)?.total ?? 0
        let bytes = (pieceStart[key] ?? 0) + totalBytesWritten
        let now = Date()
        var speed = speeds[key] ?? 0
        if let last = lastPublish[key] {
            let dt = now.timeIntervalSince(last.time)
            if dt < 0.5 { return }
            let instant = Double(max(bytes - last.bytes, 0)) / dt
            speed = speed > 0 ? speed * 0.8 + instant * 0.2 : instant
            speeds[key] = speed
        }
        lastPublish[key] = (now, bytes)
        status[key] = DownloadStatus(state: .running, bytes: bytes, total: total, speed: speed)
        attempts[key] = 0
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let key = downloadTask.taskDescription, findByKey(key) != nil else { return }
        let http = downloadTask.response as? HTTPURLResponse
        let code = http?.statusCode ?? 200
        // The downloaded file is deleted when this returns, so move it now (a quick rename).
        func stage() -> URL? {
            let staged = pieceFile(key)
            try? FileManager.default.removeItem(at: staged)
            do {
                try FileManager.default.moveItem(at: location, to: staged)
                return staged
            } catch {
                return nil
            }
        }
        switch code {
        case 206:
            if let staged = stage() {
                outcome[key] = .piece(staged, total: totalFromResponse(http), requested: Downloads.chunkSize)
            } else {
                outcome[key] = .retry(Downloads.reasonDropped)
            }
        case 200..<300:
            if let staged = stage() { outcome[key] = .whole(staged) } else { outcome[key] = .retry(Downloads.reasonDropped) }
        case 416:
            outcome[key] = .complete
        // 403/429/509 usually mean "connection limit reached" (e.g. a stream that only just
        // stopped), so those are retried with a growing wait.
        case 401, 404, 410:
            outcome[key] = .fail(code)
        default:
            outcome[key] = .retry(code)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let key = task.taskDescription else { return }
        let wasCurrent = task == currentTask
        if wasCurrent {
            currentTask = nil
            currentKey = nil
        }
        let result = outcome.removeValue(forKey: key)
        var continuing = false
        if error == nil, case .some(.piece) = result { continuing = true }
        if !continuing { forgetSpeed(key) }

        if let error {
            // Paused for streaming, removed, or replaced: nothing more to do.
            if !wasCurrent || stopping {
                stopping = false
                kick()
                return
            }
            if findByKey(key) != nil {
                let ns = error as NSError
                if ns.code == NSURLErrorCannotWriteToFile || ns.code == NSFileWriteOutOfSpaceError {
                    fail(key, Downloads.reasonNoSpace)
                } else {
                    scheduleRetry(key, code: Downloads.reasonDropped)
                }
            }
            kick()
            return
        }

        guard wasCurrent, findByKey(key) != nil else {
            if case .piece(let url, _, _) = result { try? FileManager.default.removeItem(at: url) }
            if case .whole(let url) = result { try? FileManager.default.removeItem(at: url) }
            kick()
            return
        }

        switch result ?? .retry(Downloads.reasonDropped) {
        case .piece(let url, let total, let requested):
            appendPiece(key, piece: url, total: total, requested: requested)
            return
        case .whole(let url):
            // The server ignores ranges and sent everything: that's the finished file.
            if let item = findByKey(key) {
                try? FileManager.default.removeItem(at: partFile(item))
                try? FileManager.default.moveItem(at: url, to: partFile(item))
                finish(key)
            }
        case .complete:
            finish(key)
        case .fail(let reason):
            fail(key, reason)
        case .retry(let code):
            scheduleRetry(key, code: code)
        }
        kick()
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }
}

/// Plain-language download status line.
func downloadStatusText(_ status: DownloadStatus?, item: DownloadItem) -> String {
    guard let status else { return L("download_queued") }
    let dl = Downloads.shared
    switch status.state {
    case .done:
        return "\(L("downloaded")) · \(formatBytes(status.total))"
    case .running:
        var parts: [String] = []
        if let f = status.fraction {
            parts.append(L("downloading_pct", Int(f * 100)))
            parts.append("\(formatBytes(status.bytes)) / \(formatBytes(status.total))")
        } else {
            parts.append(L("downloading"))
            parts.append(formatBytes(status.bytes))
        }
        if status.speed > 1 { parts.append("\(formatBytes(Int64(status.speed)))/s") }
        if let eta = status.eta { parts.append(L("time_left", formatDuration(eta))) }
        return parts.joined(separator: " · ")
    case .queued, .paused:
        if status.reason == Downloads.reasonRetrying { return L("dl_retrying") }
        if !Connectivity.shared.isOnline { return L("download_paused") }
        if !dl.canDownloadNow { return L("dl_waiting_stream") }
        if status.state == .queued && dl.items.contains(where: { dl.status[$0.key]?.state == .running }) {
            return L("dl_waiting_turn")
        }
        return L("download_queued")
    case .failed:
        switch status.reason {
        case Downloads.reasonNoSpace: return L("dl_err_space")
        case Downloads.reasonDropped: return L("dl_err_dropped")
        case Downloads.reasonNeedsRedownload: return L("download_failed")
        case 401, 403: return L("dl_err_refused", status.reason, "\(dl.maxConnections)")
        case 404, 410: return L("dl_err_not_found")
        default: return L("dl_err_code", status.reason)
        }
    }
}

/// "1 hr 5 min", "12 min", "40 sec" in the app's language.
func formatDuration(_ seconds: Double) -> String {
    let f = DateComponentsFormatter()
    f.unitsStyle = .abbreviated
    f.allowedUnits = seconds >= 3600 ? [.hour, .minute] : (seconds >= 60 ? [.minute] : [.second])
    f.maximumUnitCount = 2
    var cal = Calendar.current
    cal.locale = L10n.shared.locale
    f.calendar = cal
    return f.string(from: max(seconds, 1)) ?? ""
}
