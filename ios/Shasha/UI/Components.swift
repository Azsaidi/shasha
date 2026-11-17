import SwiftUI
import UIKit

// MARK: - Images

/// Downloaded images kept in memory (and in the URL cache on disk).
@MainActor
final class ImageCache {
    static let shared = ImageCache()
    private let cache = NSCache<NSString, UIImage>()
    private var running: [String: Task<UIImage?, Never>] = [:]

    init() { cache.countLimit = 600 }

    func cached(_ url: String) -> UIImage? { cache.object(forKey: url as NSString) }

    func load(_ url: String) async -> UIImage? {
        if let image = cached(url) { return image }
        if let task = running[url] { return await task.value }
        let task = Task.detached(priority: .utility) { () -> UIImage? in
            guard let u = URL(string: url) else { return nil }
            var request = URLRequest(url: u)
            request.cachePolicy = .returnCacheDataElseLoad
            guard let result = try? await Http.session.data(for: request),
                  let image = UIImage(data: result.0) else { return nil }
            // Shrink big posters so scrolling stays smooth.
            let maxSide: CGFloat = 600
            let side = max(image.size.width, image.size.height)
            if side > maxSide, let small = image.preparingThumbnail(of: CGSize(width: image.size.width * maxSide / side,
                                                                                height: image.size.height * maxSide / side)) {
                return small
            }
            return image.preparingForDisplay() ?? image
        }
        running[url] = task
        let image = await task.value
        running[url] = nil
        if let image { cache.setObject(image, forKey: url as NSString) }
        return image
    }
}

struct CachedImage: View {
    let url: String?
    var contentMode: ContentMode = .fill
    var placeholder: String = "photo"
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .aspectRatio(contentMode: contentMode)
            } else {
                Image(systemName: placeholder)
                    .font(.title3)
                    .foregroundStyle(.white.opacity(0.25))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
        .task(id: url) {
            guard let url, !url.isEmpty else {
                image = nil
                return
            }
            if let hit = ImageCache.shared.cached(url) {
                image = hit
                return
            }
            image = nil
            image = await ImageCache.shared.load(url)
        }
    }
}

// MARK: - Cards

struct PosterCard: View {
    let title: String
    let image: String?
    var subtitle: String? = nil
    var progress: Double? = nil
    var badge: String? = nil
    var favourite = false
    var downloaded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                Theme.card
                CachedImage(url: image, placeholder: "film")
                if let progress, progress > 0 {
                    ProgressBar(value: progress).padding(6)
                }
            }
            .aspectRatio(2 / 3, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(alignment: .topTrailing) {
                HStack(spacing: 4) {
                    if downloaded { cornerIcon("arrow.down.circle.fill") }
                    if favourite { cornerIcon("star.fill", .yellow) }
                }
                .padding(6)
            }
            .overlay(alignment: .topLeading) {
                if let badge {
                    Text(badge)
                        .font(.caption2.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Theme.accent, in: Capsule())
                        .foregroundStyle(.black)
                        .padding(6)
                }
            }
            Text(title)
                .font(.caption.weight(.semibold))
                .lineLimit(2, reservesSpace: true)
                .foregroundStyle(.white)
            if let subtitle {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
        .contentShape(Rectangle())
    }

    private func cornerIcon(_ name: String, _ color: Color = .white) -> some View {
        Image(systemName: name)
            .font(.caption)
            .foregroundStyle(color)
            .padding(5)
            .background(.black.opacity(0.55), in: Circle())
    }
}

/// A landscape card with a progress bar, for Continue watching.
struct WideCard: View {
    let title: String
    let subtitle: String?
    let image: String?
    var progress: Double = 0
    var badge: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .bottomLeading) {
                Theme.card
                CachedImage(url: image, placeholder: "play.rectangle")
                LinearGradient(colors: [.clear, .black.opacity(0.6)], startPoint: .center, endPoint: .bottom)
                if progress > 0 { ProgressBar(value: progress).padding(8) }
                if let badge {
                    Text(badge)
                        .font(.caption2.bold())
                        .padding(.horizontal, 6)
                        .padding(.vertical, 3)
                        .background(Theme.accent, in: Capsule())
                        .foregroundStyle(.black)
                        .padding(8)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
            }
            .aspectRatio(16 / 9, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            Text(title)
                .font(.caption.weight(.semibold))
                .lineLimit(1)
            if let subtitle {
                Text(subtitle).font(.caption2).foregroundStyle(.secondary).lineLimit(1)
            }
        }
        .contentShape(Rectangle())
    }
}

/// Square-ish channel logo tile.
struct ChannelCard: View {
    let title: String
    let image: String?
    var favourite = false

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                Theme.card
                CachedImage(url: image, contentMode: .fit, placeholder: "tv")
                    .padding(12)
            }
            .aspectRatio(16 / 10, contentMode: .fit)
            .clipShape(RoundedRectangle(cornerRadius: 10))
            .overlay(alignment: .topTrailing) {
                if favourite {
                    Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow).padding(6)
                }
            }
            Text(title)
                .font(.caption.weight(.medium))
                .lineLimit(1)
        }
        .contentShape(Rectangle())
    }
}

struct ProgressBar: View {
    let value: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.3))
                Capsule().fill(Theme.accent).frame(width: geo.size.width * min(max(value, 0), 1))
            }
        }
        .frame(height: 4)
        .environment(\.layoutDirection, .leftToRight)
    }
}

struct SectionHeader: View {
    let title: String
    var action: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title).font(.title3.bold())
            Spacer()
            if let action {
                Button(L("see_all"), action: action).font(.subheadline)
            }
        }
        .padding(.horizontal, 16)
    }
}

struct EmptyState: View {
    let icon: String
    let title: String
    var message: String? = nil
    var action: (title: String, run: () -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 44)).foregroundStyle(.secondary)
            Text(title).font(.headline).multilineTextAlignment(.center)
            if let message {
                Text(message).font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            if let action {
                Button(action.title, action: action.run).buttonStyle(.borderedProminent).padding(.top, 4)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// MARK: - Toasts

struct ToastOverlay: View {
    @ObservedObject private var toast = Toast.shared

    var body: some View {
        VStack {
            Spacer()
            if let message = toast.message {
                Text(message)
                    .font(.subheadline.weight(.medium))
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 12)
                    .background(.black.opacity(0.85), in: Capsule())
                    .overlay(Capsule().stroke(.white.opacity(0.12)))
                    .padding(.horizontal, 24)
                    .padding(.bottom, 70)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .allowsHitTesting(false)
    }
}

// MARK: - Banners

/// Offline, refresh progress, and split-screen banners shown above each tab.
struct Banners: View {
    @ObservedObject private var session = Session.shared
    @ObservedObject private var connectivity = Connectivity.shared
    @ObservedObject private var multi = MultiView.shared
    @ObservedObject private var nav = AppNav.shared

    var body: some View {
        VStack(spacing: 8) {
            if !connectivity.isOnline {
                HStack(spacing: 12) {
                    Image(systemName: "wifi.slash").font(.title3)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("offline_title")).font(.subheadline.bold())
                        Text(L("offline_body")).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    if nav.tab != .library || !nav.libraryShowsDownloads {
                        Button(L("go_downloads")) { nav.goToDownloads() }
                            .font(.caption.bold())
                            .buttonStyle(.borderedProminent)
                    }
                }
                .padding(12)
                .background(Color.orange.opacity(0.18), in: RoundedRectangle(cornerRadius: 12))
            }
            if let r = session.refresh {
                RefreshBanner(state: r, bytes: session.downloadBytes[r.type] ?? 0)
            }
            if !multi.items.isEmpty {
                HStack(spacing: 10) {
                    Image(systemName: "square.grid.2x2.fill").foregroundStyle(Theme.accent)
                    Text(L("split_banner", multi.items.count)).font(.subheadline.weight(.semibold))
                    Spacer()
                    Button(L("clear")) { multi.clear() }.font(.caption)
                    Button(L("watch")) { multi.open() }
                        .font(.caption.bold())
                        .buttonStyle(.borderedProminent)
                }
                .padding(12)
                .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
        .animation(.easeInOut(duration: 0.25), value: connectivity.isOnline)
        .animation(.easeInOut(duration: 0.25), value: multi.items.count)
    }
}

struct RefreshBanner: View {
    let state: RefreshState
    let bytes: Int64

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if state.done {
                Label(state.error.map { L("refresh_failed", $0) } ?? L("refresh_done"),
                      systemImage: state.error == nil ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                    .font(.subheadline)
                    .foregroundStyle(state.error == nil ? .green : .orange)
            } else {
                HStack {
                    Text(L("refresh_progress", L(state.type.nounKey), state.step, state.total))
                        .font(.subheadline.weight(.semibold))
                    Spacer()
                    if bytes > 0 {
                        Text(L("downloaded_mb", megabytes(bytes))).font(.caption).foregroundStyle(.secondary)
                    }
                }
                ProgressView(value: Double(state.step - 1) / Double(max(state.total, 1)))
                    .tint(Theme.accent)
            }
        }
        .padding(12)
        .background(Theme.cardHigh, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Settings gear for the navigation bar.
struct SettingsButton: View {
    var body: some View {
        Button {
            AppNav.shared.showSettings = true
        } label: {
            Image(systemName: "gearshape")
        }
        .accessibilityLabel(L("tab_settings"))
    }
}

extension View {
    /// Dark screen background used everywhere.
    func screenBackground() -> some View {
        background(Theme.background.ignoresSafeArea())
    }
}
