import Foundation
import SwiftUI

/// App language that can be switched inside the app: system, English (UK) or Arabic (right-to-left).
final class L10n: ObservableObject {
    static let shared = L10n()

    static let system = "system"
    static let english = "en"
    static let arabic = "ar"

    @Published private(set) var language: String
    private(set) var bundle: Bundle = .main
    private(set) var code: String = "en"

    var locale: Locale { Locale(identifier: code == "ar" ? "ar" : "en_GB") }
    var isRTL: Bool { code == "ar" }
    var layoutDirection: LayoutDirection { isRTL ? .rightToLeft : .leftToRight }

    private init() {
        language = UserDefaults.standard.string(forKey: "language") ?? L10n.system
        apply()
    }

    func set(_ value: String) {
        UserDefaults.standard.set(value, forKey: "language")
        language = value
        apply()
    }

    private func apply() {
        if language == L10n.system {
            code = (Locale.preferredLanguages.first ?? "en").hasPrefix("ar") ? "ar" : "en"
        } else {
            code = language
        }
        if let path = Bundle.main.path(forResource: code, ofType: "lproj"), let b = Bundle(path: path) {
            bundle = b
        } else {
            bundle = .main
        }
    }

    func string(_ key: String) -> String {
        bundle.localizedString(forKey: key, value: key, table: nil)
    }
}

/// Localised text for a key.
func L(_ key: String) -> String { L10n.shared.string(key) }

/// Localised text with arguments (%1$@, %2$d …).
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: L10n.shared.string(key), locale: L10n.shared.locale, arguments: args)
}

/// Plural-aware text; the count must be the first argument.
func LP(_ key: String, _ count: Int, _ args: CVarArg...) -> String {
    let format = L10n.shared.bundle.localizedString(forKey: key, value: key, table: nil)
    return String(format: format, locale: L10n.shared.locale, arguments: [count] + args)
}
