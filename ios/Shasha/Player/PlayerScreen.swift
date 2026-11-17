import AVKit
import SwiftUI
import UIKit

/// Hosts an engine's video view.
struct VideoSurface: UIViewRepresentable {
    let engine: PlaybackEngine?

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black
        attach(to: container)
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        if let v = engine?.view, v.superview !== container { attach(to: container) }
    }

    private func attach(to container: UIView) {
        container.subviews.forEach { $0.removeFromSuperview() }
        guard let v = engine?.view else { return }
        v.frame = container.bounds
        v.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        container.addSubview(v)
    }
}

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = UIColor(named: "AccentColor") ?? .systemBlue
        v.prioritizesVideoDevices = true
        return v
    }

    func updateUIView(_ uiView: AVRoutePickerView, context: Context) {}
}

/// Locks the screen orientation (landscape in the player on iPhone).
@MainActor
enum Orientation {
    static func set(_ mask: UIInterfaceOrientationMask) {
        AppDelegate.orientationMask = mask
        guard let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first else { return }
        var vc = scene.windows.first(where: \.isKeyWindow)?.rootViewController ?? scene.windows.first?.rootViewController
        while let current = vc {
            current.setNeedsUpdateOfSupportedInterfaceOrientations()
            vc = current.presentedViewController
        }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
    }

    static var appDefault: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .portrait
    }

    static var player: UIInterfaceOrientationMask {
        UIDevice.current.userInterfaceIdiom == .pad ? .all : .landscape
    }
}

struct PlayerScreen: View {
    @ObservedObject var model: PlayerModel
    @ObservedObject private var multi = MultiView.shared
    @Environment(\.layoutDirection) private var direction
    @State private var showTracks = false
    @State private var showList = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VideoSurface(engine: model.engine)
                .id(model.engineVersion)
                .ignoresSafeArea()
                .environment(\.layoutDirection, .leftToRight)

            gestureLayer

            if model.isLoading && model.errorMessage == nil {
                ProgressView()
                    .progressViewStyle(.circular)
                    .tint(.white)
                    .scaleEffect(1.6)
                    .allowsHitTesting(false)
            }

            if model.controlsVisible || model.errorMessage != nil {
                controls
                    .transition(.opacity)
            }

            if let message = model.errorMessage {
                errorCard(message)
            }

            if let remaining = model.upNextRemaining, let next = model.upNextItem {
                upNextCard(remaining, next)
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                    .padding(24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let item = model.announce {
                announceCard(item)
                    .transition(.scale(scale: 0.94).combined(with: .opacity))
                    .allowsHitTesting(false)
            }

            ToastOverlay()

            if model.hiding {
                Color.black.ignoresSafeArea()
            }
        }
        .statusBarHidden(true)
        .persistentSystemOverlays(.hidden)
        .preferredColorScheme(.dark)
        .onAppear {
            Orientation.set(Orientation.player)
            model.start()
        }
        .sheet(isPresented: $showTracks) {
            TracksSheet(engine: model.engine)
        }
        .sheet(isPresented: $showList) {
            QueueSheet(model: model)
        }
    }

    // MARK: Gestures

    private var gestureLayer: some View {
        GeometryReader { geo in
            HStack(spacing: 0) {
                Color.clear.contentShape(Rectangle())
                    .onTapGesture(count: 2) { model.skip(direction == .rightToLeft ? 10 : -10) }
                    .onTapGesture { model.toggleControls() }
                    .frame(width: geo.size.width * 0.35)
                Color.clear.contentShape(Rectangle())
                    .onTapGesture { model.toggleControls() }
                Color.clear.contentShape(Rectangle())
                    .onTapGesture(count: 2) { model.skip(direction == .rightToLeft ? -10 : 10) }
                    .onTapGesture { model.toggleControls() }
                    .frame(width: geo.size.width * 0.35)
            }
        }
        .ignoresSafeArea()
    }

    // MARK: Controls

    private var controls: some View {
        ZStack {
            LinearGradient(colors: [.black.opacity(0.75), .clear, .clear, .black.opacity(0.8)],
                           startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            VStack(spacing: 0) {
                topBar
                Spacer()
                centerButtons
                Spacer()
                bottomBar
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
    }

    private var topBar: some View {
        HStack(alignment: .top, spacing: 14) {
            Button {
                PlayerPresenter.shared.dismissPlayer()
            } label: {
                Image(systemName: "chevron.down")
                    .font(.system(size: 20, weight: .semibold))
                    .frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("close"))

            VStack(alignment: .leading, spacing: 4) {
                Text(model.current?.title ?? "")
                    .font(.headline)
                    .lineLimit(1)
                if let current = model.current, current.isLive {
                    ForEach(model.epgLines, id: \.self) { line in
                        Text(line).font(.caption).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                    }
                } else if let sub = model.current?.subtitle {
                    Text(sub).font(.subheadline).foregroundStyle(.white.opacity(0.8)).lineLimit(1)
                }
            }
            .padding(.top, 8)

            Spacer(minLength: 8)

            HStack(spacing: 6) {
                if model.canZap {
                    iconButton("list.bullet", L("more")) { showList = true }
                }
                if model.engine?.supportsPip == true {
                    iconButton("pip.enter", L("pip")) { model.togglePip() }
                }
                AirPlayButton().frame(width: 40, height: 40)
                iconButton("captions.bubble", L("tracks")) { showTracks = true }
                moreMenu
            }
        }
        .foregroundStyle(.white)
    }

    private var moreMenu: some View {
        Menu {
            Button {
                model.cycleGravity()
            } label: {
                Label("\(L("aspect")): \(L(model.gravity.labelKey))", systemImage: "aspectratio")
            }
            Button {
                model.cycleSleep()
            } label: {
                Label(model.sleepMinutes == 0 ? L("sleep") : L("sleep_n", model.sleepMinutes), systemImage: "moon.zzz")
            }
            if let item = model.current {
                Button {
                    multi.add(item)
                } label: {
                    Label(L("add_split"), systemImage: "rectangle.split.2x2")
                }
            }
            if !multi.items.isEmpty {
                Button {
                    if let item = model.current { multi.add(item) }
                    multi.open()
                } label: {
                    Label(L("split_banner", multi.items.count), systemImage: "square.grid.2x2")
                }
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .font(.system(size: 20))
                .frame(width: 44, height: 44)
        }
    }

    private func iconButton(_ name: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button {
            action()
            model.touched()
        } label: {
            Image(systemName: name)
                .font(.system(size: 19))
                .frame(width: 44, height: 44)
        }
        .accessibilityLabel(label)
    }

    private var isVod: Bool { !(model.current?.isLive ?? true) && model.duration > 0 }

    private var centerButtons: some View {
        HStack(spacing: 36) {
            if model.canZap {
                roundButton("backward.end.fill", 22, L("previous")) { model.change(-1) }
            }
            if isVod {
                roundButton("gobackward.10", 26, L("skip_back")) { model.skip(-10) }
            }
            roundButton(model.wantsPlay ? "pause.fill" : "play.fill", 34, model.wantsPlay ? L("pause") : L("play")) {
                model.togglePlay()
            }
            .frame(width: 76, height: 76)
            .background(.ultraThinMaterial, in: Circle())
            if isVod {
                roundButton("goforward.10", 26, L("skip_forward")) { model.skip(10) }
            }
            if model.canZap {
                roundButton("forward.end.fill", 22, L("next")) { model.change(1) }
            }
        }
        .foregroundStyle(.white)
        // Media controls keep their left-to-right order in Arabic.
        .environment(\.layoutDirection, .leftToRight)
    }

    private func roundButton(_ name: String, _ size: CGFloat, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: name)
                .font(.system(size: size, weight: .semibold))
                .frame(width: 56, height: 56)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(label)
    }

    @ViewBuilder
    private var bottomBar: some View {
        if isVod {
            VStack(spacing: 6) {
                Slider(
                    value: Binding(
                        get: { model.scrubbing ?? model.position },
                        set: { model.scrubbing = $0 }
                    ),
                    in: 0...max(model.duration, 1),
                    onEditingChanged: { editing in
                        if editing {
                            model.scrubbing = model.position
                            model.showControls(autoHide: false)
                        } else if let target = model.scrubbing {
                            model.seek(to: target)
                            model.scrubbing = nil
                        }
                    }
                )
                .tint(Color.accentColor)
                HStack {
                    Text(formatTime(model.scrubbing ?? model.position))
                    Spacer()
                    Text("-" + formatTime(max(model.duration - (model.scrubbing ?? model.position), 0)))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.white.opacity(0.85))
            }
            .environment(\.layoutDirection, .leftToRight)
        } else if model.current?.isLive == true {
            HStack {
                HStack(spacing: 6) {
                    Circle().fill(.red).frame(width: 8, height: 8)
                    Text("LIVE").font(.caption.bold())
                }
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.black.opacity(0.5), in: Capsule())
                Spacer()
            }
            .foregroundStyle(.white)
        }
    }

    // MARK: Cards

    private func errorCard(_ message: String) -> some View {
        VStack(spacing: 14) {
            Image(systemName: "exclamationmark.triangle.fill").font(.largeTitle).foregroundStyle(.yellow)
            Text(message).multilineTextAlignment(.center).foregroundStyle(.white)
            HStack(spacing: 12) {
                Button(L("try_again")) { model.retry() }
                    .buttonStyle(.borderedProminent)
                if model.canZap {
                    Button(L("next")) { model.change(1) }
                        .buttonStyle(.bordered)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: 420)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private func upNextCard(_ remaining: Double, _ next: PlayItem) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("next_episode_in", max(Int(remaining.rounded(.up)), 1)))
                .font(.caption.bold())
                .foregroundStyle(Color.accentColor)
            Text(episodeLine(next))
                .font(.headline)
                .lineLimit(2)
            ProgressView(value: min(max(1 - remaining / PlayerModel.upNextSeconds, 0), 1))
                .tint(Color.accentColor)
                .frame(width: 240)
            HStack(spacing: 10) {
                Button(L("play_now")) { model.playNextNow() }
                    .buttonStyle(.borderedProminent)
                Button(L("cancel")) { model.cancelUpNext() }
                    .buttonStyle(.bordered)
            }
        }
        .foregroundStyle(.white)
        .padding(18)
        .frame(width: 290, alignment: .leading)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private func announceCard(_ item: PlayItem) -> some View {
        VStack(spacing: 6) {
            Text(L("next_episode").uppercased())
                .font(.caption.bold())
                .foregroundStyle(Color.accentColor)
            Text(episodeLine(item))
                .font(.title2.bold())
                .multilineTextAlignment(.center)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 28)
        .padding(.vertical, 18)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 18))
    }

    private func episodeLine(_ item: PlayItem) -> String {
        let parts = item.title.components(separatedBy: " · ")
        let code = parts.count > 1 ? parts.dropFirst().joined(separator: " · ") : item.title
        return code + (item.subtitle.map { " · \($0)" } ?? "")
    }
}

/// Audio and subtitle choices.
struct TracksSheet: View {
    let engine: PlaybackEngine?
    @Environment(\.dismiss) private var dismiss
    @State private var audio: [MediaTrack] = []
    @State private var subs: [MediaTrack] = []

    var body: some View {
        NavigationStack {
            List {
                Section(L("audio")) {
                    if audio.isEmpty { Text("—").foregroundStyle(.secondary) }
                    ForEach(audio) { t in
                        Button {
                            engine?.selectAudio(t.id)
                            reload()
                        } label: {
                            row(t.name, t.selected)
                        }
                    }
                }
                Section(L("subtitles")) {
                    Button {
                        engine?.selectSubtitle(nil)
                        reload()
                    } label: {
                        row(L("off"), !subs.contains { $0.selected })
                    }
                    ForEach(subs) { t in
                        Button {
                            engine?.selectSubtitle(t.id)
                            reload()
                        } label: {
                            row(t.name, t.selected)
                        }
                    }
                }
            }
            .navigationTitle(L("tracks"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .onAppear(perform: reload)
    }

    private func row(_ name: String, _ selected: Bool) -> some View {
        HStack {
            Text(name).foregroundStyle(.primary)
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
        }
    }

    private func reload() {
        audio = engine?.audioTracks ?? []
        subs = engine?.subtitleTracks ?? []
    }
}

/// The channels or episodes in the queue, to jump to one.
struct QueueSheet: View {
    @ObservedObject var model: PlayerModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    ForEach(Array(model.items.enumerated()), id: \.element.id) { i, item in
                        Button {
                            model.playIndex(i)
                            dismiss()
                        } label: {
                            HStack(spacing: 12) {
                                CachedImage(url: item.image, contentMode: .fit)
                                    .frame(width: 56, height: 36)
                                    .background(Color.white.opacity(0.06))
                                    .clipShape(RoundedRectangle(cornerRadius: 6))
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(item.title).lineLimit(1).foregroundStyle(.primary)
                                    if let s = item.subtitle { Text(s).font(.caption).foregroundStyle(.secondary).lineLimit(1) }
                                }
                                Spacer()
                                if i == model.index {
                                    Image(systemName: "speaker.wave.2.fill").foregroundStyle(Color.accentColor)
                                }
                            }
                        }
                        .id(i)
                    }
                }
                .onAppear { proxy.scrollTo(model.index, anchor: .center) }
            }
            .navigationTitle(model.current?.isLive == true ? L("tab_live") : L("tab_series"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}
