import SwiftUI

struct RootView: View {
    @ObservedObject private var session = Session.shared
    @ObservedObject private var presenter = PlayerPresenter.shared
    @ObservedObject private var nav = AppNav.shared
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            if session.account == nil {
                AccountsFlow()
            } else {
                MainTabs()
                    .fullScreenCover(isPresented: Binding(
                        get: { presenter.multiView },
                        set: { presenter.multiView = $0 }
                    )) {
                        MultiViewScreen()
                    }
            }
            ToastOverlay()
        }
        .fullScreenCover(item: Binding(
            get: { presenter.player },
            set: { if $0 == nil { presenter.dismissPlayer() } }
        )) { model in
            PlayerScreen(model: model)
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active { Downloads.shared.kick() }
        }
    }
}

struct MainTabs: View {
    @ObservedObject private var nav = AppNav.shared

    var body: some View {
        TabView(selection: $nav.tab) {
            NavigationStack { HomeView() }
                .tabItem { Label(L("tab_home"), systemImage: "house.fill") }
                .tag(AppNav.Tab.home)
            NavigationStack { BrowserView(type: .live) }
                .tabItem { Label(L("tab_live"), systemImage: "tv") }
                .tag(AppNav.Tab.live)
            NavigationStack { BrowserView(type: .movie) }
                .tabItem { Label(L("tab_movies"), systemImage: "film") }
                .tag(AppNav.Tab.movies)
            NavigationStack { BrowserView(type: .series) }
                .tabItem { Label(L("tab_series"), systemImage: "rectangle.stack") }
                .tag(AppNav.Tab.series)
            NavigationStack { LibraryView() }
                .tabItem { Label(L("library"), systemImage: "star.square.on.square") }
                .tag(AppNav.Tab.library)
        }
        .sheet(isPresented: $nav.showSettings) {
            SettingsView()
        }
    }
}

// MARK: - Accounts

struct AccountsFlow: View {
    @State private var accounts = Store.shared.accounts

    var body: some View {
        NavigationStack {
            if accounts.isEmpty {
                LoginView(existing: nil) { _ in accounts = Store.shared.accounts }
            } else {
                AccountsView(accounts: $accounts)
            }
        }
    }
}

struct AccountsView: View {
    @Binding var accounts: [Account]
    @State private var adding = false
    @State private var editing: Account?
    @State private var removing: Account?

    var body: some View {
        List {
            Section {
                ForEach(accounts) { acc in
                    Button {
                        Session.shared.open(acc)
                    } label: {
                        HStack(spacing: 14) {
                            Image(systemName: "person.crop.circle.fill")
                                .font(.title)
                                .foregroundStyle(Theme.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(acc.name).font(.headline).foregroundStyle(.primary)
                                Text("\(acc.username) · \(URL(string: acc.server)?.host ?? acc.server)")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                            Spacer()
                            Image(systemName: "chevron.forward").foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                    }
                    .contextMenu {
                        Button { editing = acc } label: { Label(L("edit_playlist"), systemImage: "pencil") }
                        Button(role: .destructive) { removing = acc } label: { Label(L("remove"), systemImage: "trash") }
                    }
                    .swipeActions {
                        Button(role: .destructive) { removing = acc } label: { Label(L("remove"), systemImage: "trash") }
                        Button { editing = acc } label: { Label(L("edit_playlist"), systemImage: "pencil") }
                    }
                }
            }
            Section {
                Button {
                    adding = true
                } label: {
                    Label(L("add_account"), systemImage: "plus.circle.fill")
                }
            }
        }
        .navigationTitle(L("accounts"))
        .navigationDestination(isPresented: $adding) {
            LoginView(existing: nil) { _ in accounts = Store.shared.accounts }
        }
        .sheet(item: $editing) { acc in
            NavigationStack {
                LoginView(existing: acc) { _ in
                    accounts = Store.shared.accounts
                    editing = nil
                }
            }
        }
        .confirmationDialog(
            removing.map { L("remove_account_title", $0.name) } ?? "",
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button(L("remove"), role: .destructive) {
                if let acc = removing {
                    Store.shared.remove(acc)
                    accounts = Store.shared.accounts
                }
                removing = nil
            }
        } message: {
            Text(L("remove_account_body"))
        }
    }
}

struct LoginView: View {
    let existing: Account?
    let onDone: (Account) -> Void

    @State private var name = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var showPassword = false
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focus: Field?
    @Environment(\.dismiss) private var dismiss

    private enum Field: Hashable { case name, server, username, password }

    init(existing: Account?, onDone: @escaping (Account) -> Void) {
        self.existing = existing
        self.onDone = onDone
        _name = State(initialValue: existing?.name ?? "")
        _server = State(initialValue: existing?.server ?? "")
        _username = State(initialValue: existing?.username ?? "")
        _password = State(initialValue: existing?.password ?? "")
    }

    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 10) {
                    Image(systemName: "tv.inset.filled")
                        .font(.system(size: 40))
                        .foregroundStyle(Theme.accent)
                    Text(existing == nil ? L("app_name") : L("edit_account")).font(.largeTitle.bold())
                    Text(existing == nil ? L("login_subtitle") : L("edit_tip"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 8)
                .listRowBackground(Color.clear)
            }
            Section {
                TextField(L("playlist_name"), text: $name)
                    .textContentType(.name)
                    .focused($focus, equals: .name)
                    .submitLabel(.next)
                    .onSubmit { focus = .server }
                TextField(L("server_field"), text: $server, prompt: Text(L("server_placeholder")))
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .environment(\.layoutDirection, .leftToRight)
                    .focused($focus, equals: .server)
                    .submitLabel(.next)
                    .onSubmit { focus = .username }
                TextField(L("username"), text: $username)
                    .textContentType(.username)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .username)
                    .submitLabel(.next)
                    .onSubmit { focus = .password }
                HStack {
                    Group {
                        if showPassword {
                            TextField(L("password"), text: $password)
                        } else {
                            SecureField(L("password"), text: $password)
                        }
                    }
                    .textContentType(.password)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .focused($focus, equals: .password)
                    .submitLabel(.go)
                    .onSubmit { submit() }
                    Button {
                        showPassword.toggle()
                    } label: {
                        Image(systemName: showPassword ? "eye.slash" : "eye")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(showPassword ? L("hide_password") : L("show_password"))
                }
            } footer: {
                if existing == nil { Text(L("login_tip")) }
            }
            if let error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            }
            Section {
                Button {
                    submit()
                } label: {
                    HStack {
                        Spacer()
                        if busy { ProgressView().padding(.trailing, 6) }
                        Text(existing == nil ? L("log_in") : L("save")).bold()
                        Spacer()
                    }
                }
                .disabled(busy)
            }
        }
        .scrollContentBackground(.hidden)
        // Drag the form down to put the keyboard away.
        .scrollDismissesKeyboard(.interactively)
        .screenBackground()
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if existing != nil {
                ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } }
            }
            // Bar above the keyboard: previous/next field and Done.
            ToolbarItemGroup(placement: .keyboard) {
                Button { moveFocus(-1) } label: { Image(systemName: "chevron.up") }
                    .disabled(focus == .name)
                    .accessibilityLabel(L("previous"))
                Button { moveFocus(1) } label: { Image(systemName: "chevron.down") }
                    .disabled(focus == .password)
                    .accessibilityLabel(L("next"))
                Spacer()
                Button(L("done")) { focus = nil }.bold()
            }
        }
    }

    private func moveFocus(_ step: Int) {
        let order: [Field] = [.name, .server, .username, .password]
        guard let current = focus, let i = order.firstIndex(of: current) else { return }
        focus = order[min(max(i + step, 0), order.count - 1)]
    }

    private func submit() {
        focus = nil
        error = nil
        var user = username.trimmingCharacters(in: .whitespaces)
        var pass = password
        let raw = server.trimmingCharacters(in: .whitespacesAndNewlines)
        // Accept a full M3U link (get.php?username=...&password=...) and pull the login out of it.
        let parsed = URLComponents(string: raw.contains("://") ? raw : "http://\(raw)")
        if user.isEmpty, let u = parsed?.queryItems?.first(where: { $0.name == "username" })?.value { user = u }
        if pass.isEmpty, let p = parsed?.queryItems?.first(where: { $0.name == "password" })?.value { pass = p }
        guard let normalized = XtreamAPI.normalizeServer(raw) else {
            error = L("err_server")
            return
        }
        guard !user.isEmpty, !pass.isEmpty else {
            error = L("err_credentials")
            return
        }
        let trimmedName = name.trimmingCharacters(in: .whitespaces)
        let account = Account(
            id: existing?.id ?? UUID().uuidString,
            name: trimmedName.isEmpty ? (parsed?.host ?? L("default_playlist")) : trimmedName,
            server: normalized,
            username: user,
            password: pass
        )
        busy = true
        Task {
            do {
                let auth = try await XtreamAPI(account: account).login()
                Store.shared.upsert(account)
                busy = false
                if existing != nil, Session.shared.account?.id == account.id {
                    Session.shared.accountEdited(account, auth: auth)
                    Toast.shared.show(L("saved_changes"))
                    onDone(account)
                    dismiss()
                } else if existing != nil {
                    Toast.shared.show(L("saved_changes"))
                    onDone(account)
                    dismiss()
                } else {
                    onDone(account)
                    Session.shared.open(account, knownAuth: auth)
                }
            } catch {
                busy = false
                self.error = friendlyError(error)
            }
        }
    }
}
