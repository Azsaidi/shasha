import AVFoundation
import SwiftUI
import UIKit

/// Drives the full-screen player: the queue, the engine, history, the up-next countdown, sleep timer and TV guide.
@MainActor
final class PlayerModel: ObservableObject, Identifiable {
    nonisolated let id = UUID()

    static let upNextSeconds: Double = 15
    static let sleepOptions = [0, 15, 30, 60, 90, 120]

    @Published private(set) var items: [PlayItem]
    @Published private(set) var index: Int
    @Published private(set) var engine: PlaybackEngine?
    @Published private(set) var state: EngineState = .loading
    @Published private(set) var position: Double = 0
    @Published private(set) var duration: Double = 0
    @Published var controlsVisible = true
    @Published private(set) var epgLines: [String] = []
    /// Seconds left before the next episode starts, while the countdown shows.
    @Published private(set) var upNextRemaining: Double?
    @Published private(set) var announce: PlayItem?
    @Published private(set) var sleepMinutes = 0
    @Published var gravity: VideoGravity = .fit { didSet { engine?.gravity = gravity } }
    @Published private(set) var pipActive = false
    @Published var scrubbing: Double?
    /// Bumped when the engine changes so the video view is replaced.
    @Published private(set) var engineVersion = 0
    /// What the person asked for (play or pause); drives the play/pause button.
    @Published private(set) var wantsPlay = true
    /// Black screen while the player rotates away before closing.
    @Published var hiding = false

    private let fromStart: Bool
    private var started = false
    private var closed = false
    private var timer: Timer?
    private var sleepEnd: Date?
    private var upNextCancelledFor: Int?
    private var retried = Set<Int>()
    private var lastSaved = Date()
    private var epgTask: Task<Void, Never>?
    private var hideTask: Task<Void, Never>?
    private var announceTask: Task<Void, Never>?
    private var trackedIndex = -1
    private var lastPosition: Double = 0
    private var lastDuration: Double = 0
    private var playingSince: Date?
    private var noPictureRetried = Set<Int>()

    init(items: [PlayItem], index: Int, fromStart: Bool) {
        self.items = items
        self.index = index
        self.fromStart = fromStart
    }

    var current: PlayItem? { items.indices.contains(index) ? items[index] : nil }
    var isPlaying: Bool { state == .playing }
    var isLoading: Bool { state == .loading }
    var canZap: Bool { items.count > 1 }
    var errorMessage: String? {
        if case .failed(let message) = state { return message }
        return nil
    }

    // MARK: Lifecycle

    func start() {
        guard !started else { return }
        started = true
        // Downloads wait while we stream on plans that allow only one connection.
        Downloads.shared.setStreaming(items.contains { $0.isRemote })
        load(index, seekToSaved: !fromStart)
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
        showControls()
    }

    func close() {
        guard !closed else { return }
        closed = true
        track()
        saveCurrent()
        timer?.invalidate()
        timer = nil
        epgTask?.cancel()
        hideTask?.cancel()
        engine?.stop()
        engine = nil
        Downloads.shared.setStreaming(false)
    }

    // MARK: Loading items

    private func savedPosition(_ item: PlayItem) -> Double {
        guard !item.isLive, let entry = Session.shared.historyEntry(item.kind, item.streamId) else { return 0 }
        return !entry.finished && entry.position > 10 ? entry.position : 0
    }

    private func load(_ i: Int, seekToSaved: Bool = true, url override: URL? = nil, start: Double? = nil, preferVLC: Bool = false) {
        guard items.indices.contains(i) else { return }
        let item = items[i]
        let url = override ?? item.url
        let startAt = start ?? (seekToSaved ? savedPosition(item) : 0)
        engine?.stop()
        playingSince = nil
        wantsPlay = true
        let e = Engines.make(for: url, preferVLC: preferVLC)
        e.gravity = gravity
        e.onState = { [weak self] s in self?.stateChanged(s) }
        e.onPipChange = { [weak self] active in self?.pipActive = active }
        engine = e
        engineVersion += 1
        index = i
        state = .loading
        position = startAt
        duration = 0
        upNextRemaining = nil
        trackedIndex = i
        lastPosition = startAt
        lastDuration = 0
        e.load(url, start: startAt)
        if item.isLive {
            record(item, position: 0, duration: 0)
            loadEpg(item)
        } else {
            epgTask?.cancel()
            epgLines = []
        }
    }

    private func stateChanged(_ s: EngineState) {
        state = s
        if s == .playing {
            if playingSince == nil { playingSince = Date() }
        } else {
            playingSince = nil
        }
        switch s {
        case .ended:
            finishedCurrent()
        case .failed:
            if tryAlternative() { return }
            showControls(autoHide: false)
        default:
            break
        }
    }

    /// Live channel failed: try the other format once (HLS ↔ TS) before giving up.
    private func tryAlternative() -> Bool {
        guard let item = current, item.isLive, !retried.contains(index) else { return false }
        retried.insert(index)
        let url = item.url.absoluteString
        let alternative: String?
        if url.hasSuffix(".m3u8") {
            alternative = Engines.vlcAvailable ? String(url.dropLast(5)) + ".ts" : nil
        } else if url.hasSuffix(".ts") {
            alternative = String(url.dropLast(3)) + ".m3u8"
        } else {
            alternative = nil
        }
        guard let alternative, let alt = URL(string: alternative) else { return false }
        items[index].url = alt
        load(index, seekToSaved: false, url: alt)
        return true
    }

    func retry() {
        retried.remove(index)
        noPictureRetried.remove(index)
        load(index, seekToSaved: true)
    }

    /// Apple's player sometimes plays the sound but can't show the picture (4K HEVC inside TS
    /// segments, unusual codecs). Hand the stream to VLC instead.
    private func checkPicture() {
        guard Engines.vlcAvailable, let since = playingSince, Date().timeIntervalSince(since) > 4,
              let av = engine as? AVEngine, !av.showsPicture, !noPictureRetried.contains(index),
              let item = current else { return }
        noPictureRetried.insert(index)
        var url = item.url
        if item.isLive, url.absoluteString.hasSuffix(".m3u8"),
           let ts = URL(string: String(url.absoluteString.dropLast(5)) + ".ts") {
            url = ts
            items[index].url = ts
        }
        let at = item.isLive ? 0 : av.position
        load(index, url: url, start: at, preferVLC: true)
    }

    // MARK: Controls

    func togglePlay() {
        guard let engine else { return }
        if wantsPlay {
            engine.pause()
            wantsPlay = false
        } else {
            if errorMessage != nil {
                retry()
                return
            }
            engine.play()
            wantsPlay = true
        }
        showControls()
    }

    func skip(_ seconds: Double) {
        guard let engine, duration > 0 else { return }
        let target = min(max(engine.position + seconds, 0), duration - 1)
        engine.seek(to: target)
        position = target
        showControls()
    }

    func seek(to seconds: Double) {
        engine?.seek(to: seconds)
        position = seconds
        showControls()
    }

    func change(_ step: Int) {
        guard items.count > 1 else { return }
        track()
        saveCurrent()
        let count = items.count
        load(((index + step) % count + count) % count)
        showControls()
    }

    func playIndex(_ i: Int) {
        guard items.indices.contains(i), i != index else { return }
        track()
        saveCurrent()
        load(i)
        showControls()
    }

    func cycleSleep() {
        let options = PlayerModel.sleepOptions
        let next = options[((options.firstIndex(of: sleepMinutes) ?? 0) + 1) % options.count]
        sleepMinutes = next
        if next == 0 {
            sleepEnd = nil
            Toast.shared.show(L("sleep_off"))
        } else {
            sleepEnd = Date().addingTimeInterval(Double(next) * 60)
            Toast.shared.show(L("sleep_set", next))
        }
        showControls()
    }

    func cycleGravity() {
        gravity = VideoGravity(rawValue: (gravity.rawValue + 1) % VideoGravity.allCases.count) ?? .fit
        Toast.shared.show(L(gravity.labelKey))
        showControls()
    }

    func togglePip() {
        guard let engine else { return }
        if engine.pipActive {
            engine.stopPip()
        } else if engine.canStartPip {
            engine.startPip()
        } else {
            Toast.shared.show(L("pip_unavailable"))
        }
    }

    func showControls(autoHide: Bool = true) {
        withAnimation(.easeOut(duration: 0.2)) { controlsVisible = true }
        hideTask?.cancel()
        guard autoHide else { return }
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard let self, !Task.isCancelled, self.wantsPlay, self.errorMessage == nil, self.scrubbing == nil else { return }
            withAnimation(.easeIn(duration: 0.3)) { self.controlsVisible = false }
        }
    }

    func toggleControls() {
        if controlsVisible {
            hideTask?.cancel()
            withAnimation(.easeIn(duration: 0.25)) { controlsVisible = false }
        } else {
            showControls()
        }
    }

    /// Keeps the controls up while the person interacts with them.
    func touched() { showControls() }

    // MARK: Up next

    private func nextEpisodeIndex(_ i: Int) -> Int? {
        guard items.indices.contains(i), items.indices.contains(i + 1) else { return nil }
        let item = items[i], next = items[i + 1]
        return item.kind == .episode && next.kind == .episode && next.seriesId == item.seriesId ? i + 1 : nil
    }

    var upNextItem: PlayItem? { nextEpisodeIndex(index).map { items[$0] } }

    func playNextNow() {
        guard let next = nextEpisodeIndex(index) else { return }
        markFinished(index, duration: duration)
        trackedIndex = -1
        load(next, seekToSaved: false)
        announceEpisode()
    }

    func cancelUpNext() {
        upNextCancelledFor = index
        withAnimation(.easeIn(duration: 0.25)) { upNextRemaining = nil }
    }

    private func updateUpNext() {
        guard !pipActive, nextEpisodeIndex(index) != nil, duration >= 60, upNextCancelledFor != index else {
            if upNextRemaining != nil { withAnimation(.easeIn(duration: 0.25)) { upNextRemaining = nil } }
            return
        }
        let remaining = duration - position
        if remaining > PlayerModel.upNextSeconds || remaining < 0 {
            if upNextRemaining != nil { withAnimation(.easeIn(duration: 0.25)) { upNextRemaining = nil } }
            return
        }
        if upNextRemaining == nil {
            withAnimation(.spring(response: 0.45, dampingFraction: 0.85)) { upNextRemaining = remaining }
        } else {
            upNextRemaining = remaining
        }
    }

    /// Fades the new episode in and shows its title for a moment.
    private func announceEpisode() {
        guard !pipActive, let item = current else { return }
        announceTask?.cancel()
        withAnimation(.easeOut(duration: 0.35).delay(0.15)) { announce = item }
        announceTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 2_800_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.5)) { self?.announce = nil }
        }
    }

    private func finishedCurrent() {
        guard let item = current, !item.isLive else {
            // A live stream that "ended" usually dropped: reconnect.
            if current?.isLive == true { load(index, seekToSaved: false) }
            return
        }
        markFinished(index, duration: duration)
        trackedIndex = -1
        if let next = nextEpisodeIndex(index), upNextCancelledFor != index {
            load(next, seekToSaved: false)
            announceEpisode()
        } else {
            wantsPlay = false
            showControls(autoHide: false)
        }
    }

    // MARK: Ticking

    private func tick() {
        guard let engine, !closed else { return }
        if scrubbing == nil {
            let p = engine.position
            if p > 0 || state == .playing { position = p }
        }
        let d = engine.duration
        if d != duration { duration = d }
        track()
        if Date().timeIntervalSince(lastSaved) > 15 {
            lastSaved = Date()
            saveCurrent()
        }
        updateUpNext()
        checkPicture()
        if let end = sleepEnd, Date() >= end {
            sleepEnd = nil
            sleepMinutes = 0
            engine.pause()
            wantsPlay = false
            Toast.shared.show(L("sleep_done"))
            showControls(autoHide: false)
        }
    }

    // MARK: History

    private func track() {
        guard let engine, trackedIndex == index else { return }
        let p = engine.position
        if p > 0 { lastPosition = p }
        let d = engine.duration
        if d > 0 { lastDuration = d }
    }

    private func saveCurrent() {
        guard trackedIndex >= 0, items.indices.contains(trackedIndex) else { return }
        let item = items[trackedIndex]
        guard !item.isLive, lastPosition > 0 else { return }
        let nearEnd = lastDuration > 0 && (lastPosition >= lastDuration - 60 || lastPosition >= lastDuration * 0.95)
        if nearEnd {
            markFinished(trackedIndex, duration: lastDuration)
        } else {
            record(item, position: lastPosition, duration: lastDuration)
        }
    }

    private func markFinished(_ i: Int, duration: Double) {
        guard items.indices.contains(i) else { return }
        let item = items[i]
        guard !item.isLive else { return }
        let known = duration > 0 ? duration : (Session.shared.historyEntry(item.kind, item.streamId)?.duration ?? 0)
        record(item, position: 0, duration: known, finished: true)
        // Put the following episode in "Continue watching" as up next.
        if item.kind == .episode, items.indices.contains(i + 1), items[i + 1].seriesId == item.seriesId {
            let next = items[i + 1]
            let existing = Session.shared.historyEntry(next.kind, next.streamId)
            if existing == nil || existing!.finished || existing!.position <= 10 {
                record(next, position: 0, duration: 0, upNext: true, at: Date().addingTimeInterval(1))
            }
        }
    }

    private func record(_ item: PlayItem, position: Double, duration: Double, finished: Bool = false,
                        upNext: Bool = false, at: Date = Date()) {
        let episode = item.kind == .episode
        let code = item.title.components(separatedBy: " · ").dropFirst().joined(separator: " · ")
        let subtitle: String?
        if episode {
            subtitle = (code.isEmpty ? item.title : code) + (item.subtitle.map { " · \($0)" } ?? "")
        } else {
            subtitle = item.subtitle
        }
        Session.shared.record(WatchEntry(
            kind: item.kind, streamId: item.streamId,
            title: episode ? (item.seriesName ?? item.title) : item.title,
            subtitle: subtitle, image: item.image, ext: item.ext,
            seriesId: item.seriesId, seriesName: item.seriesName,
            position: position, duration: duration, finished: finished, upNext: upNext, updatedAt: at
        ), accountId: item.accountId)
    }

    // MARK: TV guide

    private func loadEpg(_ item: PlayItem) {
        epgTask?.cancel()
        epgLines = []
        guard let api = Session.shared.api, Session.shared.account?.id == item.accountId else { return }
        epgTask = Task { [weak self] in
            let entries = await api.shortEpg(item.streamId)
            guard let self, !Task.isCancelled else { return }
            self.showEpg(entries)
            let now = Date()
            if let current = entries.first(where: { now >= $0.start && now < $0.end }) {
                let wait = max(current.end.timeIntervalSince(now), 30)
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                guard !Task.isCancelled, self.current == item else { return }
                self.loadEpg(item)
            }
        }
    }

    private func showEpg(_ entries: [EpgEntry]) {
        let now = Date()
        let f = DateFormatter()
        f.locale = L10n.shared.locale
        f.timeStyle = .short
        f.dateStyle = .none
        let current = entries.first { now >= $0.start && now < $0.end }
        let next = entries.first { $0.start >= (current?.end ?? now) }
        var lines: [String] = []
        if let current {
            lines.append(L("epg_now", current.title, f.string(from: current.start), f.string(from: current.end)))
        }
        if let next { lines.append(L("epg_next", next.title, f.string(from: next.start))) }
        epgLines = lines
    }

    /// 0…1 through the current programme, for the live progress bar.
    var liveProgress: Double? { nil }
}
