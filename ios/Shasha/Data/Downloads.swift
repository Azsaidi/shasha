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

/// The download queue. Uses a background URLSession so downloads keep going after you leave the app.
/// Downloads run one at a time, continue from where they stopped when the server allows, and pause
/// while something streams on plans that allow only one connection.
final class Downloads: NSObject, ObservableObject {
    static let shared = Downloads()

    static let reasonDropped = -2
    static let reasonNoSpace = -3
    static let reasonNeedsRedownload = -4
    static let reasonRetrying = -10
    static let reasonStreaming = -11

    private static let maxAttempts = 5
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
    private var stopping: Stop?
    private var attempts: [String: Int] = [:]
    private var outcome: [String: Outcome] = [:]
    private var lastPublish: [String: (time: Date, bytes: Int64)] = [:]
    private var speeds: [String: Double] = [:]
    /// Downloads started from saved resume data (if that fails, start over instead).
    private var resumed = Set<String>()

    private enum Stop { case pause, cancel }
    private enum Outcome { case done(Int64), retry(Int), fail(Int) }

    private let dir: URL
    private let resumeDir: URL
    private let listFile: URL

    private override init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        dir = support.appendingPathComponent("Downloads", isDirectory: true)
        resumeDir = support.appendingPathComponent("DownloadResume", isDirectory: true)
        listFile = support.appendingPathComponent("downloads.json")
        super.init()
        for d in [dir, resumeDir] {
            try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        }
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var d = dir
        try? d.setResourceValues(values)

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

        // Pick up a download that kept going while the app wasn't running.
        session.getAllTasks { tasks in
            DispatchQueue.main.async {
                for task in tasks {
                    guard let dl = task as? URLSessionDownloadTask, let key = task.taskDescription else { continue }
                    if self.currentTask == nil, task.state == .running, self.findByKey(key) != nil {
                        self.currentTask = dl
                        self.currentKey = key
                        self.status[key]?.state = .running
                    } else {
                        task.cancel()
                    }
                }
                self.kick()
            }
        }
    }

    private func initialStatus(_ item: DownloadItem) -> DownloadStatus {
        let size = fileSize(file(item))
        if item.done && size > 0 { return DownloadStatus(state: .done, bytes: size, total: size) }
        if item.done { return DownloadStatus(state: .failed, bytes: 0, total: 0, reason: Downloads.reasonNeedsRedownload) }
        if item.failedReason != 0 { return DownloadStatus(state: .failed, bytes: 0, total: item.total, reason: item.failedReason) }
        return DownloadStatus(state: .queued, bytes: 0, total: item.total)
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
    private func resumeFile(_ key: String) -> URL { resumeDir.appendingPathComponent(key.replacingOccurrences(of: ":", with: "_")) }

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
        status[item.key] = DownloadStatus(state: .queued, bytes: 0, total: item.total)
        save()
        kick()
    }

    /// Cancels (if running) and deletes the file.
    func remove(_ item: DownloadItem) {
        if currentKey == item.key {
            stopping = .cancel
            currentTask?.cancel()
            currentTask = nil
            currentKey = nil
        }
        try? FileManager.default.removeItem(at: file(item))
        try? FileManager.default.removeItem(at: resumeFile(item.key))
        items.removeAll { $0.key == item.key }
        status[item.key] = nil
        attempts[item.key] = nil
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

    /// Starts the next download if nothing is downloading.
    func kick() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { self.kick() }
            return
        }
        guard session != nil, currentTask == nil, Connectivity.shared.isOnline, let item = next() else { return }
        start(item)
    }

    private func start(_ item: DownloadItem) {
        let task: URLSessionDownloadTask
        let resume = resumeFile(item.key)
        if let data = try? Data(contentsOf: resume) {
            try? FileManager.default.removeItem(at: resume)
            task = session.downloadTask(withResumeData: data)
            resumed.insert(item.key)
        } else {
            resumed.remove(item.key)
            guard let url = URL(string: item.url) else {
                fail(item.key, Downloads.reasonNeedsRedownload)
                return
            }
            var request = URLRequest(url: url)
            request.setValue(Http.userAgent, forHTTPHeaderField: "User-Agent")
            task = session.downloadTask(with: request)
        }
        task.taskDescription = item.key
        currentTask = task
        currentKey = item.key
        stopping = nil
        let previous = status[item.key]
        status[item.key] = DownloadStatus(state: .running, bytes: previous?.bytes ?? 0, total: item.total)
        task.resume()
    }

    private func pauseCurrent() {
        guard let task = currentTask, let key = currentKey else { return }
        stopping = .pause
        currentTask = nil
        currentKey = nil
        let resume = resumeFile(key)
        task.cancel(byProducingResumeData: { data in
            if let data { try? data.write(to: resume, options: .atomic) }
        })
        if var s = status[key] {
            s.state = .paused
            s.reason = Downloads.reasonStreaming
            status[key] = s
        }
    }

    private func fail(_ key: String, _ reason: Int) {
        replace(key) { $0.failedReason = reason }
        let total = findByKey(key)?.total ?? 0
        status[key] = DownloadStatus(state: .failed, bytes: 0, total: total, reason: reason)
        try? FileManager.default.removeItem(at: resumeFile(key))
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
}

extension Downloads: URLSessionDownloadDelegate {
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let key = downloadTask.taskDescription, downloadTask == currentTask else { return }
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : (findByKey(key)?.total ?? 0)
        let now = Date()
        var speed = speeds[key] ?? 0
        if let last = lastPublish[key] {
            let dt = now.timeIntervalSince(last.time)
            if dt < 0.5 { return }
            let instant = Double(max(totalBytesWritten - last.bytes, 0)) / dt
            speed = speed > 0 ? speed * 0.8 + instant * 0.2 : instant
            speeds[key] = speed
        }
        lastPublish[key] = (now, totalBytesWritten)
        status[key] = DownloadStatus(state: .running, bytes: totalBytesWritten, total: total, speed: speed)
        if total > 0, findByKey(key)?.total != total { replace(key) { $0.total = total } }
        attempts[key] = 0
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let key = downloadTask.taskDescription, let item = findByKey(key) else { return }
        let code = (downloadTask.response as? HTTPURLResponse)?.statusCode ?? 200
        guard (200..<300).contains(code) else {
            switch code {
            // 403/429/509 usually mean "connection limit reached" (e.g. a stream that only just
            // stopped), so those are retried with a growing wait.
            case 401, 404, 410: outcome[key] = .fail(code)
            default: outcome[key] = .retry(code)
            }
            return
        }
        let dest = file(item)
        try? FileManager.default.removeItem(at: dest)
        do {
            try FileManager.default.moveItem(at: location, to: dest)
            outcome[key] = .done(fileSize(dest))
        } catch {
            let ns = error as NSError
            outcome[key] = ns.code == NSFileWriteOutOfSpaceError ? .fail(Downloads.reasonNoSpace) : .retry(Downloads.reasonDropped)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let key = task.taskDescription else { return }
        let wasCurrent = task == currentTask
        if wasCurrent {
            currentTask = nil
            currentKey = nil
        }
        lastPublish[key] = nil
        speeds[key] = nil
        let fromResume = resumed.remove(key) != nil
        defer { kick() }

        if let error {
            let ns = error as NSError
            if let data = ns.userInfo[NSURLSessionDownloadTaskResumeData] as? Data {
                try? data.write(to: resumeFile(key), options: .atomic)
            }
            // Paused for streaming or removed: nothing more to do.
            if ns.code == NSURLErrorCancelled && (!wasCurrent || stopping != nil) {
                stopping = nil
                return
            }
            guard findByKey(key) != nil else { return }
            // The saved resume point didn't work: start the file again rather than failing.
            if fromResume && ns.userInfo[NSURLSessionDownloadTaskResumeData] == nil {
                try? FileManager.default.removeItem(at: resumeFile(key))
            }
            if ns.code == NSURLErrorCannotWriteToFile || ns.code == NSFileWriteOutOfSpaceError {
                fail(key, Downloads.reasonNoSpace)
            } else {
                scheduleRetry(key, code: Downloads.reasonDropped)
            }
            return
        }

        guard findByKey(key) != nil else { return }
        switch outcome.removeValue(forKey: key) ?? .retry(Downloads.reasonDropped) {
        case .done(let size):
            replace(key) {
                $0.done = true
                $0.failedReason = 0
                $0.total = size
            }
            status[key] = DownloadStatus(state: .done, bytes: size, total: size)
            attempts[key] = nil
            try? FileManager.default.removeItem(at: resumeFile(key))
            save()
        case .fail(let reason):
            fail(key, reason)
        case .retry(let code):
            try? FileManager.default.removeItem(at: resumeFile(key))
            scheduleRetry(key, code: code)
        }
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
