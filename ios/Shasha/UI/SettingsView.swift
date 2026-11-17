import SwiftUI

struct SettingsView: View {
    @ObservedObject private var session = Session.shared
    @ObservedObject private var l10n = L10n.shared
    @Environment(\.dismiss) private var dismiss

    @State private var editing = false
    @State private var showPassword = false
    @State private var liveFormat = Store.shared.liveFormat
    @State private var autoPip = Store.shared.autoPip
    @State private var autoRefresh = Store.shared.autoRefreshDays
    @State private var confirmHistory = false
    @State private var confirmSearches = false
    @State private var confirmRemove = false

    var body: some View {
        NavigationStack {
            Form {
                if let acc = session.account {
                    Section {
                        Button {
                            editing = true
                        } label: {
                            Label(L("edit_playlist"), systemImage: "pencil")
                        }
                    }

                    Section(acc.name) {
                        row(L("server"), acc.server, ltr: true)
                        row(L("username"), acc.username, ltr: true)
                        HStack {
                            Text(L("password"))
                            Spacer()
                            Text(showPassword ? acc.password : String(repeating: "•", count: min(acc.password.count, 10)))
                                .foregroundStyle(.secondary)
                                .textSelection(.enabled)
                                .environment(\.layoutDirection, .leftToRight)
                            Button {
                                showPassword.toggle()
                            } label: {
                                Image(systemName: showPassword ? "eye.slash" : "eye")
                            }
                            .buttonStyle(.borderless)
                            .accessibilityLabel(showPassword ? L("hide_password") : L("show_password"))
                        }
                        if let auth = session.auth {
                            row(L("status"), auth.status ?? "—")
                            row(L("expires"), session.expiryDate.map {
                                $0.formatted(.dateTime.day().month(.wide).year().locale(l10n.locale))
                            } ?? L("never"))
                            row(L("connections"), L("connections_in_use", auth.activeConnections ?? "0", auth.maxConnections ?? "?"))
                        } else {
                            row(L("status"), L("checking"))
                        }
                    }
                }

                Section {
                    Picker(L("language"), selection: Binding(get: { l10n.language }, set: { l10n.set($0) })) {
                        Text(L("lang_system")).tag(L10n.system)
                        Text(L("lang_en")).tag(L10n.english)
                        Text(L("lang_ar")).tag(L10n.arabic)
                    }
                }

                Section {
                    Toggle(isOn: Binding(get: { session.hideAdult }, set: { session.setHideAdult($0) })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("hide_adult"))
                            Text(L("hide_adult_desc")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Toggle(isOn: $autoPip) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L("auto_pip"))
                            Text(L("auto_pip_desc")).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .onChange(of: autoPip) { Store.shared.autoPip = $0 }
                }

                Section {
                    Picker(L("live_format"), selection: $liveFormat) {
                        Text(L("format_auto")).tag("auto")
                        Text("HLS (m3u8)").tag("m3u8")
                        Text("MPEG-TS").tag("ts")
                    }
                    .onChange(of: liveFormat) { Store.shared.liveFormat = $0 }
                } footer: {
                    Text(L("live_format_hint"))
                }

                Section {
                    Picker(L("auto_refresh"), selection: $autoRefresh) {
                        Text(L("refresh_off")).tag(0)
                        Text(L("refresh_3_days")).tag(3)
                        Text(L("refresh_week")).tag(7)
                    }
                    .onChange(of: autoRefresh) { Store.shared.autoRefreshDays = $0 }
                    Button {
                        session.refreshAll()
                        dismiss()
                    } label: {
                        Label(L("refresh_all"), systemImage: "arrow.clockwise")
                    }
                } footer: {
                    Text(L("auto_refresh_desc"))
                }

                Section(L("history_section")) {
                    Button(role: .destructive) { confirmHistory = true } label: {
                        Label(L("clear_watch_history"), systemImage: "clock.arrow.circlepath")
                    }
                    Button(role: .destructive) { confirmSearches = true } label: {
                        Label(L("clear_search_history"), systemImage: "magnifyingglass")
                    }
                }

                Section {
                    Button {
                        dismiss()
                        session.close()
                    } label: {
                        Label(L("switch_account"), systemImage: "person.2")
                    }
                    Button(role: .destructive) { confirmRemove = true } label: {
                        Label(L("remove_account"), systemImage: "trash")
                    }
                } footer: {
                    Text(L("version_line", Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""))
                        .frame(maxWidth: .infinity)
                        .padding(.top, 12)
                }
            }
            .scrollContentBackground(.hidden)
            .screenBackground()
            .navigationTitle(L("tab_settings"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button(L("done")) { dismiss() } }
            }
            .sheet(isPresented: $editing) {
                if let acc = session.account {
                    NavigationStack {
                        LoginView(existing: acc) { _ in editing = false }
                    }
                }
            }
            .confirmationDialog(L("clear_watch_history"), isPresented: $confirmHistory, titleVisibility: .visible) {
                Button(L("clear"), role: .destructive) {
                    session.clearHistory()
                    Toast.shared.show(L("history_cleared"))
                }
            } message: {
                Text(L("clear_watch_history_body"))
            }
            .confirmationDialog(L("clear_search_history"), isPresented: $confirmSearches, titleVisibility: .visible) {
                Button(L("clear"), role: .destructive) {
                    session.clearSearches()
                    Toast.shared.show(L("searches_cleared"))
                }
            } message: {
                Text(L("clear_search_history_body"))
            }
            .confirmationDialog(L("remove_account_title", session.account?.name ?? ""), isPresented: $confirmRemove,
                                titleVisibility: .visible) {
                Button(L("remove"), role: .destructive) {
                    if let acc = session.account {
                        dismiss()
                        session.close()
                        Store.shared.remove(acc)
                    }
                }
            } message: {
                Text(L("remove_account_body"))
            }
        }
    }

    private func row(_ label: String, _ value: String, ltr: Bool = false) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .truncationMode(.middle)
                .textSelection(.enabled)
                .environment(\.layoutDirection, ltr ? .leftToRight : l10n.layoutDirection)
        }
    }
}
