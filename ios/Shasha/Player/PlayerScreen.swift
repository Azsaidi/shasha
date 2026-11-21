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

/// Taps, double-taps and press-and-hold on the picture. UIKit recognisers so a hold doesn't
/// fight the taps; positions are reported as 0…1 across the screen.
struct TouchSurface: UIViewRepresentable {
    var onTouchDown: (CGFloat) -> Void
    var onTap: (CGFloat) -> Void
    var onDoubleTap: (CGFloat) -> Void
    var onHold: (Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> TouchView {
        let v = TouchView()
        v.backgroundColor = .clear
        let c = context.coordinator
        v.onTouchDown = { [weak c] x in c?.parent.onTouchDown(x) }
        let double = UITapGestureRecognizer(target: c, action: #selector(Coordinator.doubleTapped(_:)))
        double.numberOfTapsRequired = 2
        let single = UITapGestureRecognizer(target: c, action: #selector(Coordinator.tapped(_:)))
        single.require(toFail: double)
        let hold = UILongPressGestureRecognizer(target: c, action: #selector(Coordinator.held(_:)))
        hold.minimumPressDuration = 0.4
        v.addGestureRecognizer(double)
        v.addGestureRecognizer(single)
        v.addGestureRecognizer(hold)
        return v
    }

    func updateUIView(_ uiView: TouchView, context: Context) {
        context.coordinator.parent = self
    }

    final class TouchView: UIView {
        var onTouchDown: ((CGFloat) -> Void)?

        override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
            super.touchesBegan(touches, with: event)
            if let t = touches.first, bounds.width > 0 { onTouchDown?(t.location(in: self).x / bounds.width) }
        }
    }

    final class Coordinator: NSObject {
        var parent: TouchSurface

        init(_ parent: TouchSurface) { self.parent = parent }

        private func x(_ g: UIGestureRecognizer) -> CGFloat {
            guard let v = g.view, v.bounds.width > 0 else { return 0.5 }
            return g.location(in: v).x / v.bounds.width
        }

        @objc func tapped(_ g: UITapGestureRecognizer) { parent.onTap(x(g)) }
        @objc func doubleTapped(_ g: UITapGestureRecognizer) { parent.onDoubleTap(x(g)) }

        @objc func held(_ g: UILongPressGestureRecognizer) {
            switch g.state {
            case .began: parent.onHold(true)
            case .ended, .cancelled, .failed: parent.onHold(false)
            default: break
            }
        }
    }
}

/// Recent touches on the picture, to tell a double-tap that strays onto a button from a real
/// press of that button. A class so updating it doesn't redraw the screen.
private final class TapMemory {
    var touchX: CGFloat = 0.5
    var touchAt = Date.distantPast
    /// A single tap that is really part of a double-tap is ignored until then.
    var ignoreTapUntil = Date.distantPast
    /// Previous/next episode, held back a moment in case it's the first tap of a double-tap.
    var pendingZap: Task<Void, Never>?
    var zapSide = 0
    var zapAt = Date.distantPast
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
    @State private var showTracks = false
    @State private var showList = false
    /// Double-tap skip feedback: -1 back, 1 forward, 0 hidden.
    @State private var skipSide = 0
    @State private var skipSeconds = 0
    @State private var skipToken = 0
    @State private var taps = TapMemory()

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

            if !model.hiding && (model.controlsVisible || model.errorMessage != nil) {
                controls
                    .transition(.opacity)
                    // Mid double-tap skipping, further taps go to the picture, not the buttons.
                    .allowsHitTesting(skipSide == 0)
            }

            if model.boosting {
                boostBadge
                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                    .padding(.top, 28)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }

            if !model.hiding, let message = model.errorMessage {
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

            if !model.hiding { ToastOverlay() }
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
        .sheet(isPresented: $model.showSound) {
            SoundSheet(model: model)
        }
    }

    // MARK: Gestures

    /// Left third: double-tap back 10 s. Right third: forward 10 s. Single tap shows/hides controls.
    /// Press and hold: 2× speed until let go. Always left = back, like the time bar, in Arabic too.
    private var gestureLayer: some View {
        GeometryReader { geo in
            ZStack {
                TouchSurface(
                    onTouchDown: { x in touchedDown(x) },
                    onTap: { x in tapped(x) },
                    onDoubleTap: { x in doubleTapped(x) },
                    onHold: { on in held(on) }
                )
                if skipSide != 0 {
                    SkipFeedback(forward: skipSide > 0, seconds: skipSeconds, size: geo.size)
                        .id(skipToken)
                        .transition(.opacity)
                        .allowsHitTesting(false)
                }
            }
        }
        .ignoresSafeArea()
        .environment(\.layoutDirection, .leftToRight)
    }

    /// -1 left third, 1 right third, 0 middle.
    private func side(_ x: CGFloat) -> Int { x < 0.35 ? -1 : (x > 0.65 ? 1 : 0) }

    private func touchedDown(_ x: CGFloat) {
        let now = Date()
        taps.touchX = x
        taps.touchAt = now
        // Previous/next episode tapped a moment ago and now the picture: that was a double-tap.
        if let zap = taps.pendingZap, now.timeIntervalSince(taps.zapAt) < 0.4 {
            zap.cancel()
            taps.pendingZap = nil
            taps.ignoreTapUntil = now.addingTimeInterval(0.6)
            doubleTap(taps.zapSide)
        }
    }

    private func tapped(_ x: CGFloat) {
        if Date() < taps.ignoreTapUntil { return }
        // While skipping, each further tap on that side skips again (no need to double-tap).
        let s = side(x)
        if s != 0, s == skipSide, model.canSkip {
            doubleTap(s)
            return
        }
        model.toggleControls()
    }

    private func doubleTapped(_ x: CGFloat) {
        if Date() < taps.ignoreTapUntil { return }
        let s = side(x)
        if s == 0 {
            model.toggleControls()
        } else {
            doubleTap(s)
        }
    }

    private func held(_ on: Bool) {
        if on {
            model.startBoost()
            if model.boosting { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
        } else {
            model.endBoost()
        }
    }

    /// Previous/next channel or episode. For films and episodes it waits a moment, so a
    /// double-tap to skip that strays onto the button skips instead of changing episode.
    private func zap(_ step: Int) {
        guard model.canSkip else {
            model.change(step)
            return
        }
        let now = Date()
        if now.timeIntervalSince(taps.touchAt) < 0.4 {
            // Second tap of a double-tap that started on the picture.
            let s = side(taps.touchX)
            taps.ignoreTapUntil = now.addingTimeInterval(0.6)
            doubleTap(s != 0 ? s : (step < 0 ? -1 : 1))
            return
        }
        taps.pendingZap?.cancel()
        taps.zapSide = step < 0 ? -1 : 1
        taps.zapAt = now
        let memory = taps
        let player = self.model
        memory.pendingZap = Task { @MainActor in
            try? await Task.sleep(nanoseconds: 350_000_000)
            guard !Task.isCancelled else { return }
            memory.pendingZap = nil
            player.change(step)
        }
    }

    private func doubleTap(_ side: Int) {
        guard model.canSkip, side != 0 else { return }
        model.skip(Double(side * 10), reveal: false)
        // Repeated double-taps on the same side add up: 10, 20, 30…
        if skipSide == side {
            skipSeconds += 10
        } else {
            skipSeconds = 10
        }
        withAnimation(.easeOut(duration: 0.15)) { skipSide = side }
        skipToken += 1
        let token = skipToken
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 750_000_000)
            guard token == skipToken else { return }
            withAnimation(.easeIn(duration: 0.3)) { skipSide = 0 }
        }
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
            if isVod {
                Menu {
                    Picker(L("speed"), selection: Binding(get: { model.speed }, set: { model.setSpeed($0) })) {
                        ForEach(PlayerModel.speeds, id: \.self) { s in
                            Text(PlayerModel.speedLabel(s)).tag(s)
                        }
                    }
                } label: {
                    Label("\(L("speed")): \(PlayerModel.speedLabel(model.speed))", systemImage: "speedometer")
                }
            }
            Button {
                model.showSound = true
            } label: {
                Label(PlayerModel.clarityLabel(model.clarity), systemImage: "waveform")
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
                roundButton("backward.end.fill", 22, L("previous")) { zap(-1) }
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
                roundButton("forward.end.fill", 22, L("next")) { zap(1) }
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

    /// Shown while a finger is held on the picture.
    private var boostBadge: some View {
        HStack(spacing: 6) {
            Text(PlayerModel.speedLabel(PlayerModel.holdSpeed))
                .font(.subheadline.bold())
                .monospacedDigit()
            Image(systemName: "forward.fill")
                .font(.system(size: 13, weight: .bold))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.black.opacity(0.55), in: Capsule())
        .environment(\.layoutDirection, .leftToRight)
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

/// Clearer sound: presets and sliders, applied as you move them, saved for this title.
/// Keeps its own copy of the settings and doesn't follow the player's every update, so the
/// sliders stay smooth while the video plays.
struct SoundSheet: View {
    let model: PlayerModel
    @Environment(\.dismiss) private var dismiss
    @State private var settings: ClaritySettings?

    init(model: PlayerModel) {
        self.model = model
        _settings = State(initialValue: model.clarity)
    }

    private enum Choice: Hashable { case off, clearer, much, custom }

    private var choice: Choice {
        guard let s = settings else { return .off }
        if s == .clearer { return .clearer }
        if s == .muchClearer { return .much }
        return .custom
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(L("sound_title"), selection: Binding(get: { choice }, set: { pick($0) })) {
                        Text(L("sound_normal")).tag(Choice.off)
                        Text(L("sound_clearer")).tag(Choice.clearer)
                        Text(L("sound_much")).tag(Choice.much)
                        if choice == .custom { Text(L("sound_custom")).tag(Choice.custom) }
                    }
                    .pickerStyle(.segmented)
                } footer: {
                    Text(L("sound_hint"))
                }
                Section {
                    SoundSlider(title: L("sound_dialogue"), unit: "dB", range: 0...10, value: binding(\.dialogue), done: save)
                    SoundSlider(title: L("sound_air"), unit: nil, range: 0...1.5, value: binding(\.air), done: save)
                    SoundSlider(title: L("sound_treble"), unit: "dB", range: 0...8, value: binding(\.treble), done: save)
                    SoundSlider(title: L("sound_mud"), unit: "dB", range: 0...6, value: binding(\.mud), done: save)
                    SoundSlider(title: L("sound_focus"), unit: nil, range: 0...1, value: binding(\.focus), done: save)
                    SoundSlider(title: L("sound_tame"), unit: nil, range: 0...1, value: binding(\.tame), done: save)
                }
                .disabled(settings == nil)
                Section {
                    Button(L("sound_reset")) { pick(.clearer) }
                        .disabled(settings == nil || settings == .clearer)
                }
            }
            .navigationTitle(L("sound_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } }
            }
        }
        .presentationDetents([.medium, .large])
        .onDisappear(perform: save)
    }

    private func binding(_ key: WritableKeyPath<ClaritySettings, Float>) -> Binding<Float> {
        Binding(
            get: { (settings ?? .clearer)[keyPath: key] },
            set: { v in
                guard var s = settings else { return }
                s[keyPath: key] = (v * 10).rounded() / 10
                guard s != settings else { return }
                settings = s
                model.previewClarity(s)
            }
        )
    }

    /// Saves (and shows on the Sound button) once a slider is let go.
    private func save() {
        if settings != model.clarity { model.setClarity(settings) }
    }

    private func pick(_ c: Choice) {
        switch c {
        case .off: settings = nil
        case .clearer: settings = .clearer
        case .much: settings = .muchClearer
        case .custom: return
        }
        model.setClarity(settings)
    }
}

private struct SoundSlider: View {
    let title: String
    let unit: String?
    let range: ClosedRange<Float>
    @Binding var value: Float
    let done: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(title)
                Spacer()
                Text(unit == nil ? String(format: "%.0f%%", value / range.upperBound * 100) : String(format: "%.1f %@", value, unit!))
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Slider(value: $value, in: range) { editing in
                if !editing { done() }
            }
        }
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

/// The ripple, arrows and "10 sec" shown on the side you double-tapped.
private struct SkipFeedback: View {
    let forward: Bool
    let seconds: Int
    let size: CGSize
    @State private var pop = false

    var body: some View {
        let width = size.width * 0.38
        ZStack {
            Circle()
                .fill(.white.opacity(0.14))
                .frame(width: size.height * 1.5, height: size.height * 1.5)
                .offset(x: forward ? size.height * 0.55 : -size.height * 0.55)
                .scaleEffect(pop ? 1 : 0.85)
            VStack(spacing: 8) {
                TimelineView(.animation) { context in
                    let step = Int(context.date.timeIntervalSinceReferenceDate * 5) % 4
                    HStack(spacing: 0) {
                        ForEach(0..<3, id: \.self) { i in
                            let lit = forward ? i == step : (2 - i) == step
                            Image(systemName: forward ? "arrowtriangle.right.fill" : "arrowtriangle.left.fill")
                                .font(.system(size: 16, weight: .bold))
                                .opacity(lit ? 1 : 0.35)
                        }
                    }
                }
                Text(formatDuration(Double(seconds)))
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
            }
            .foregroundStyle(.white)
            .scaleEffect(pop ? 1 : 0.8)
        }
        .frame(width: width, height: size.height)
        .clipped()
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: forward ? .trailing : .leading)
        .onAppear {
            withAnimation(.spring(response: 0.25, dampingFraction: 0.6)) { pop = true }
        }
    }
}
