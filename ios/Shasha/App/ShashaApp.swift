import AVFoundation
import SwiftUI
import UIKit

@main
struct ShashaApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @ObservedObject private var l10n = L10n.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(\.layoutDirection, l10n.layoutDirection)
                .environment(\.locale, l10n.locale)
                // Rebuild everything when the language changes.
                .id(l10n.language)
                .preferredColorScheme(.dark)
                .tint(Theme.accent)
        }
    }
}

final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientationMask: UIInterfaceOrientationMask = .allButUpsideDown

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        AppDelegate.orientationMask = Orientation.appDefault
        // Keep sound playing with the silent switch on, and allow picture-in-picture.
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        try? AVAudioSession.sharedInstance().setActive(true)
        _ = Connectivity.shared
        _ = Downloads.shared
        return true
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientationMask
    }

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        if identifier == Downloads.sessionId {
            Downloads.shared.backgroundCompletion = completionHandler
        } else {
            completionHandler()
        }
    }
}

enum Theme {
    static let accent = Color(red: 0x38 / 255, green: 0xBD / 255, blue: 0xF8 / 255)
    static let background = Color(red: 0x0B / 255, green: 0x13 / 255, blue: 0x20 / 255)
    static let card = Color(red: 0x15 / 255, green: 0x22 / 255, blue: 0x36 / 255)
    static let cardHigh = Color(red: 0x1E / 255, green: 0x2E / 255, blue: 0x46 / 255)
}

/// Which tab is showing, so banners and buttons can switch tabs.
@MainActor
final class AppNav: ObservableObject {
    static let shared = AppNav()
    enum Tab: Hashable { case home, live, movies, series, library }
    @Published var tab: Tab = .home
    @Published var libraryShowsDownloads = false
    @Published var showSettings = false

    func goToDownloads() {
        libraryShowsDownloads = true
        tab = .library
    }
}
