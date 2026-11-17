import SwiftUI

/// One window in split screen.
@MainActor
final class Tile: ObservableObject, Identifiable {
    let item: PlayItem
    let engine: PlaybackEngine
    @Published var state: EngineState = .loading
    @Published var muted: Bool { didSet { engine.muted = muted } }
    @Published var volume: Float = 1 { didSet { engine.volume = volume } }
    nonisolated var id: String { item.key }

    init(item: PlayItem, muted: Bool) {
        self.item = item
        self.engine = Engines.make(for: item.url)
        self.muted = muted
        engine.muted = muted
        engine.onState = { [weak self] s in self?.state = s }
        engine.load(item.url, start: 0)
    }

    var isPlaying: Bool { state == .playing }

    func togglePlay() {
        if isPlaying { engine.pause() } else { engine.play() }
    }

    func stop() { engine.stop() }
}

@MainActor
final class MultiViewModel: ObservableObject {
    @Published private(set) var tiles: [Tile] = []
    @Published var selected: String?
    @Published var solo: String?
    @Published var uiVisible = true
    private var hideTask: Task<Void, Never>?

    func sync(_ items: [PlayItem]) {
        let keys = Set(items.map(\.key))
        for t in tiles where !keys.contains(t.id) { t.stop() }
        var next = tiles.filter { keys.contains($0.id) }
        for item in items where !next.contains(where: { $0.id == item.key }) {
            // Only the first window plays sound to begin with.
            next.append(Tile(item: item, muted: !next.isEmpty))
        }
        tiles = items.compactMap { item in next.first { $0.id == item.key } }
        if let s = selected, !keys.contains(s) { selected = nil }
        if let s = solo, !keys.contains(s) { solo = nil }
        if selected == nil { selected = tiles.first?.id }
        Downloads.shared.setStreaming(tiles.contains { $0.item.isRemote })
    }

    func stopAll() {
        tiles.forEach { $0.stop() }
        tiles = []
        Downloads.shared.setStreaming(false)
    }

    func poke() {
        withAnimation(.easeOut(duration: 0.2)) { uiVisible = true }
        hideTask?.cancel()
        hideTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 4_000_000_000)
            guard !Task.isCancelled else { return }
            withAnimation(.easeIn(duration: 0.35)) { self?.uiVisible = false }
        }
    }
}

struct MultiViewScreen: View {
    @ObservedObject private var multi = MultiView.shared
    @ObservedObject private var session = Session.shared
    @ObservedObject private var presenter = PlayerPresenter.shared
    @StateObject private var model = MultiViewModel()
    @State private var picking = false

    private var shownTiles: [Tile] {
        if let solo = model.solo { return model.tiles.filter { $0.id == solo } }
        return model.tiles
    }

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color.black.ignoresSafeArea()
                grid(in: geo.size)
                if model.uiVisible && !presenter.multiHiding {
                    overlay
                        .transition(.opacity)
                }
                ToastOverlay()
            }
        }
        .ignoresSafeArea()
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .onAppear {
            Orientation.set(Orientation.player)
            model.sync(multi.items)
            model.poke()
        }
        .onDisappear {
            model.stopAll()
        }
        .onChange(of: multi.items) { items in
            if items.isEmpty {
                if presenter.multiView { presenter.closeMultiView() }
            } else {
                model.sync(items)
            }
        }
        .sheet(isPresented: $picking) {
            StreamPicker { item in
                picking = false
                multi.add(item)
                model.poke()
            }
        }
    }

    // MARK: Grid

    @ViewBuilder
    private func grid(in size: CGSize) -> some View {
        let tiles = shownTiles
        let landscape = size.width >= size.height
        let columns = tiles.count <= 1 ? 1 : (tiles.count == 2 ? (landscape ? 2 : 1) : 2)
        let rows = Int(ceil(Double(tiles.count) / Double(columns)))
        let w = size.width / CGFloat(columns)
        let h = size.height / CGFloat(max(rows, 1))
        VStack(spacing: 2) {
            ForEach(0..<rows, id: \.self) { r in
                HStack(spacing: 2) {
                    ForEach(0..<columns, id: \.self) { c in
                        let i = r * columns + c
                        if i < tiles.count {
                            TileView(tile: tiles[i], selected: model.selected == tiles[i].id && tiles.count > 1,
                                     showUI: model.uiVisible && !presenter.multiHiding)
                                .frame(width: w - 2, height: h - 2)
                                .contentShape(Rectangle())
                                .onTapGesture(count: 2) {
                                    model.solo = model.solo == nil ? tiles[i].id : nil
                                    model.poke()
                                }
                                .onTapGesture {
                                    model.selected = tiles[i].id
                                    model.poke()
                                }
                        }
                    }
                }
            }
        }
        .environment(\.layoutDirection, .leftToRight)
        .frame(width: size.width, height: size.height)
    }

    // MARK: Controls

    private var overlay: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button {
                    presenter.closeMultiView()
                    multi.clear()
                } label: {
                    Label(L("split_exit"), systemImage: "xmark")
                }
                .buttonStyle(BarButtonStyle())
                if let max = Int(session.auth?.maxConnections ?? ""), max < model.tiles.count {
                    Text(L("split_connections", "\(max)"))
                        .font(.caption)
                        .foregroundStyle(.yellow)
                        .lineLimit(2)
                }
                Spacer()
                if multi.items.count < MultiView.max {
                    Button {
                        picking = true
                    } label: {
                        Label(L("split_add"), systemImage: "plus")
                    }
                    .buttonStyle(BarButtonStyle())
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 14)
            Spacer()
            if let tile = model.tiles.first(where: { $0.id == model.selected }) {
                TileControls(tile: tile, solo: model.solo == tile.id,
                             onSolo: {
                                 model.solo = model.solo == nil ? tile.id : nil
                                 model.poke()
                             },
                             onAlone: {
                                 let item = tile.item
                                 PlayerPresenter.shared.present([item], index: 0)
                                 multi.clear()
                             },
                             onRemove: {
                                 multi.remove(tile.id)
                                 Toast.shared.show(L("split_removed"))
                             },
                             poke: model.poke)
                .padding(.bottom, 16)
            }
        }
        .foregroundStyle(.white)
        .environment(\.layoutDirection, .leftToRight)
    }
}

struct BarButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .padding(.horizontal, 14)
            .padding(.vertical, 9)
            .background(.ultraThinMaterial, in: Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

private struct TileView: View {
    @ObservedObject var tile: Tile
    let selected: Bool
    let showUI: Bool

    var body: some View {
        ZStack(alignment: .topLeading) {
            VideoSurface(engine: tile.engine)
            if tile.state == .loading {
                ProgressView().tint(.white).frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if case .failed(let message) = tile.state {
                Text(message)
                    .font(.caption)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            if showUI {
                HStack(spacing: 6) {
                    Image(systemName: tile.muted ? "speaker.slash.fill" : "speaker.wave.2.fill")
                        .font(.caption)
                    Text(tile.item.title)
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                }
                .foregroundStyle(.white)
                .padding(.horizontal, 10)
                .padding(.vertical, 6)
                .background(.black.opacity(0.55), in: Capsule())
                .padding(10)
                .transition(.opacity)
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 2)
                .stroke(Color.accentColor, lineWidth: selected && showUI ? 3 : 0)
        )
        .clipped()
    }
}

private struct TileControls: View {
    @ObservedObject var tile: Tile
    let solo: Bool
    let onSolo: () -> Void
    let onAlone: () -> Void
    let onRemove: () -> Void
    let poke: () -> Void

    var body: some View {
        HStack(spacing: 4) {
            button(tile.isPlaying ? "pause.fill" : "play.fill", tile.isPlaying ? L("pause") : L("play")) { tile.togglePlay() }
            button(tile.muted ? "speaker.slash.fill" : "speaker.wave.2.fill", tile.muted ? L("unmute") : L("mute")) {
                tile.muted.toggle()
            }
            Slider(value: Binding(get: { Double(tile.volume) }, set: {
                tile.volume = Float($0)
                if tile.muted && $0 > 0 { tile.muted = false }
                poke()
            }), in: 0...1)
            .frame(width: 120)
            .tint(Color.accentColor)
            .accessibilityLabel(L("volume_n", Int(tile.volume * 100)))
            button(solo ? "rectangle.grid.2x2" : "arrow.up.left.and.arrow.down.right",
                   solo ? L("split_show_all") : L("split_full_screen"), action: onSolo)
            button("rectangle.portrait.and.arrow.right", L("split_watch_single"), action: onAlone)
            button("minus.circle", L("remove"), action: onRemove)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 4)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private func button(_ icon: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button {
            action()
            poke()
        } label: {
            Image(systemName: icon)
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel(label)
    }
}

/// Picks something to add to split screen.
struct StreamPicker: View {
    let onPick: (PlayItem?) -> Void
    @ObservedObject private var session = Session.shared
    @ObservedObject private var multi = MultiView.shared
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let taken = Set(multi.items.map(\.key))
        let favourites = session.favourites.filter { $0.type == .live && !taken.contains(WatchEntry.key(.live, $0.id)) }
        let recent = session.recentChannels.filter { !taken.contains($0.key) }
        let resume = session.continueWatching.filter { !taken.contains($0.key) }
        let same = Array(PlayerPresenter.shared.lastQueue.filter { !taken.contains($0.key) }.prefix(300))

        NavigationStack {
            Group {
                if favourites.isEmpty && recent.isEmpty && resume.isEmpty && same.isEmpty {
                    Text(L("split_pick_empty"))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.secondary)
                        .padding(32)
                } else {
                    List {
                        if !favourites.isEmpty {
                            Section(L("favourite_channels")) {
                                ForEach(favourites) { f in
                                    row(f.name, f.icon) { onPick(Playback.liveItem(f)) }
                                }
                            }
                        }
                        if !recent.isEmpty {
                            Section(L("recent_channels")) {
                                ForEach(recent) { e in
                                    row(e.title, e.image) { onPick(Playback.entryItem(e)) }
                                }
                            }
                        }
                        if !resume.isEmpty {
                            Section(L("continue_watching")) {
                                ForEach(resume) { e in
                                    row([e.title, e.displaySubtitle].compactMap { $0 }.joined(separator: " · "), e.image) {
                                        onPick(Playback.entryItem(e))
                                    }
                                }
                            }
                        }
                        if !same.isEmpty {
                            Section(L("split_pick_current")) {
                                ForEach(same) { item in
                                    row(item.title, item.image) { onPick(item) }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(L("split_pick_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("close")) { dismiss() } }
            }
        }
    }

    private func row(_ title: String, _ image: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                CachedImage(url: image, contentMode: .fit)
                    .frame(width: 56, height: 36)
                    .background(Color.white.opacity(0.06))
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                Text(title).lineLimit(1).foregroundStyle(.primary)
            }
        }
    }
}
