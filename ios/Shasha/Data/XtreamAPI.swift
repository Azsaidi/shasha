import Foundation

enum XtreamError: Error {
    case notXtream
    case wrongLogin
    case invalidAddress
    case unexpected
    case refused(Int)
    case server(Int)
}

enum Http {
    /// Many IPTV servers reject unknown clients, so present ourselves as VLC.
    static let userAgent = "VLC/3.0.20 LibVLC/3.0.20"

    static let session: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 30
        config.timeoutIntervalForResource = 300
        config.httpAdditionalHeaders = ["User-Agent": userAgent]
        config.urlCache = URLCache(memoryCapacity: 32 << 20, diskCapacity: 256 << 20)
        return URLSession(configuration: config)
    }()
}

/// Reads a JSON value as a trimmed string (numbers included); nil for missing, null, "null" or blank.
func str(_ value: Any?) -> String? {
    guard let value, !(value is NSNull) else { return nil }
    let s: String
    if let v = value as? String { s = v } else if let n = value as? NSNumber { s = n.stringValue } else { return nil }
    let t = s.trimmingCharacters(in: .whitespacesAndNewlines)
    return (t.isEmpty || t == "null") ? nil : t
}

/// Data download that reports how many bytes have arrived (for big channel/film lists).
private final class ProgressFetcher: NSObject, URLSessionDataDelegate {
    private var buffer = Data()
    private var response: HTTPURLResponse?
    private var continuation: CheckedContinuation<(Data, HTTPURLResponse), Error>?
    private let onProgress: @Sendable (Int64) -> Void
    private var lastReport = 0

    init(onProgress: @escaping @Sendable (Int64) -> Void) { self.onProgress = onProgress }

    func fetch(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        try await withCheckedThrowingContinuation { cont in
            continuation = cont
            let config = URLSessionConfiguration.default
            config.timeoutIntervalForRequest = 60
            config.httpAdditionalHeaders = ["User-Agent": Http.userAgent]
            let session = URLSession(configuration: config, delegate: self, delegateQueue: nil)
            session.dataTask(with: request).resume()
            session.finishTasksAndInvalidate()
        }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        self.response = response as? HTTPURLResponse
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        buffer.append(data)
        if buffer.count - lastReport >= 256 * 1024 {
            lastReport = buffer.count
            onProgress(Int64(buffer.count))
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error {
            continuation?.resume(throwing: error)
        } else if let response {
            onProgress(Int64(buffer.count))
            continuation?.resume(returning: (buffer, response))
        } else {
            continuation?.resume(throwing: XtreamError.unexpected)
        }
        continuation = nil
    }
}

/// Client for the Xtream Codes "player_api.php" API.
struct XtreamAPI {
    let account: Account
    private var base: String { account.server.trimmingCharacters(in: CharacterSet(charactersIn: "/")) }
    private var user: String { account.username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? account.username }
    private var pass: String { account.password.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/"))) ?? account.password }

    func liveURL(_ id: String, ext: String) -> String { "\(base)/live/\(user)/\(pass)/\(id).\(ext)" }
    func movieURL(_ id: String, ext: String) -> String { "\(base)/movie/\(user)/\(pass)/\(id).\(ext)" }
    func episodeURL(_ id: String, ext: String) -> String { "\(base)/series/\(user)/\(pass)/\(id).\(ext)" }

    private func apiURL(_ action: String? = nil, _ extra: [String: String] = [:]) throws -> URL {
        guard var comps = URLComponents(string: "\(base)/player_api.php") else { throw XtreamError.invalidAddress }
        var items = [URLQueryItem(name: "username", value: account.username), URLQueryItem(name: "password", value: account.password)]
        if let action { items.append(URLQueryItem(name: "action", value: action)) }
        for (k, v) in extra { items.append(URLQueryItem(name: k, value: v)) }
        comps.queryItems = items
        guard let url = comps.url else { throw XtreamError.invalidAddress }
        return url
    }

    private func check(_ response: HTTPURLResponse) throws {
        if response.statusCode == 401 || response.statusCode == 403 { throw XtreamError.refused(response.statusCode) }
        if !(200..<300).contains(response.statusCode) { throw XtreamError.server(response.statusCode) }
    }

    private func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        let (data, response) = try await Http.session.data(for: request)
        if let http = response as? HTTPURLResponse { try check(http) }
        return data
    }

    private func object(_ url: URL) async throws -> [String: Any] {
        let data = try await fetch(url)
        guard let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw XtreamError.unexpected }
        return obj
    }

    private func array(_ url: URL, progress: (@Sendable (Int64) -> Void)? = nil) async throws -> [[String: Any]] {
        let data: Data
        if let progress {
            let (d, response) = try await ProgressFetcher(onProgress: progress).fetch(URLRequest(url: url))
            try check(response)
            data = d
        } else {
            data = try await fetch(url)
        }
        return (try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]) ?? []
    }

    func login() async throws -> AuthInfo {
        let json = try await object(try apiURL())
        guard let info = json["user_info"] as? [String: Any] else { throw XtreamError.notXtream }
        guard Int(str(info["auth"]) ?? "0") == 1 else { throw XtreamError.wrongLogin }
        let formats = (info["allowed_output_formats"] as? [Any])?.compactMap { str($0)?.lowercased() } ?? []
        return AuthInfo(
            status: str(info["status"]),
            expDate: str(info["exp_date"]).flatMap { Int64($0) },
            maxConnections: str(info["max_connections"]),
            activeConnections: str(info["active_cons"]),
            allowedFormats: formats
        )
    }

    func categories(_ type: ContentType) async throws -> [Category] {
        let action: String
        switch type {
        case .live: action = "get_live_categories"
        case .movie: action = "get_vod_categories"
        case .series: action = "get_series_categories"
        }
        return try await array(try apiURL(action)).compactMap { o in
            guard let id = str(o["category_id"]) else { return nil }
            return Category(id: id, name: str(o["category_name"]) ?? "—")
        }
    }

    func streams(_ type: ContentType, progress: (@Sendable (Int64) -> Void)? = nil) async throws -> [StreamItem] {
        let action: String
        switch type {
        case .live: action = "get_live_streams"
        case .movie: action = "get_vod_streams"
        case .series: action = "get_series"
        }
        let rows = try await array(try apiURL(action), progress: progress)
        var out: [StreamItem] = []
        out.reserveCapacity(rows.count)
        for o in rows {
            if type == .series {
                guard let id = str(o["series_id"]) else { continue }
                let name = str(o["name"]) ?? "—"
                out.append(StreamItem(
                    id: id, name: name, icon: str(o["cover"]), categoryId: str(o["category_id"]), type: type,
                    ext: nil, rating: str(o["rating"]), added: str(o["last_modified"]).flatMap { Int64($0) },
                    year: releaseYear(o, name)
                ))
            } else {
                guard let id = str(o["stream_id"]) else { continue }
                let name = str(o["name"]) ?? "—"
                out.append(StreamItem(
                    id: id, name: name, icon: str(o["stream_icon"]), categoryId: str(o["category_id"]), type: type,
                    ext: str(o["container_extension"]), rating: str(o["rating"]),
                    added: str(o["added"]).flatMap { Int64($0) },
                    year: type == .movie ? releaseYear(o, name) : nil
                ))
            }
        }
        return out
    }

    private func releaseYear(_ o: [String: Any], _ name: String) -> Int? {
        if let y = str(o["year"]).flatMap({ Int($0.prefix(4)) }), (1900...2100).contains(y) { return y }
        if let y = firstYear(in: str(o["releaseDate"]) ?? str(o["release_date"]) ?? str(o["releasedate"])) { return y }
        return yearFromTitle(name)
    }

    func vodInfo(_ id: String) async throws -> VodInfo {
        let json = try await object(try apiURL("get_vod_info", ["vod_id": id]))
        let info = json["info"] as? [String: Any] ?? [:]
        let data = json["movie_data"] as? [String: Any] ?? [:]
        return VodInfo(
            name: str(data["name"]) ?? str(info["name"]),
            plot: str(info["plot"]) ?? str(info["description"]),
            genre: str(info["genre"]),
            duration: str(info["duration"]),
            releaseDate: str(info["releasedate"]) ?? str(info["release_date"]),
            rating: str(info["rating"]),
            cover: str(info["cover_big"]) ?? str(info["movie_image"]),
            director: str(info["director"]),
            cast: str(info["cast"]) ?? str(info["actors"]),
            ext: str(data["container_extension"])
        )
    }

    func seriesInfo(_ id: String) async throws -> SeriesInfo {
        let json = try await object(try apiURL("get_series_info", ["series_id": id]))
        let info = json["info"] as? [String: Any] ?? [:]
        var seasons: [Int: [Episode]] = [:]

        func add(_ o: [String: Any], fallbackSeason: Int) {
            guard let epId = str(o["id"]) else { return }
            let season = str(o["season"]).flatMap { Int($0) } ?? fallbackSeason
            let number = str(o["episode_num"]).flatMap { Int($0) } ?? 0
            let epInfo = o["info"] as? [String: Any]
            let ep = Episode(
                id: epId,
                title: str(o["title"]) ?? "\(number)",
                season: season,
                number: number,
                ext: str(o["container_extension"]) ?? "mp4",
                plot: str(epInfo?["plot"]),
                image: str(epInfo?["movie_image"]),
                duration: str(epInfo?["duration"])
            )
            seasons[season, default: []].append(ep)
        }

        // Servers return episodes either as {"1": [...], "2": [...]} or as [[...], [...]].
        if let dict = json["episodes"] as? [String: Any] {
            for (k, v) in dict {
                for o in (v as? [[String: Any]]) ?? [] { add(o, fallbackSeason: Int(k) ?? 1) }
            }
        } else if let arr = json["episodes"] as? [Any] {
            for (i, v) in arr.enumerated() {
                if let inner = v as? [[String: Any]] {
                    for o in inner { add(o, fallbackSeason: i + 1) }
                } else if let o = v as? [String: Any] {
                    add(o, fallbackSeason: 1)
                }
            }
        }
        for k in seasons.keys { seasons[k]?.sort { $0.number < $1.number } }

        return SeriesInfo(
            name: str(info["name"]),
            cover: str(info["cover"]),
            plot: str(info["plot"]),
            genre: str(info["genre"]),
            releaseDate: str(info["releaseDate"]) ?? str(info["release_date"]),
            rating: str(info["rating"]),
            seasons: seasons
        )
    }

    /// Now/next programme info for a live channel. Empty if the provider has none.
    func shortEpg(_ streamId: String) async -> [EpgEntry] {
        guard let url = try? apiURL("get_short_epg", ["stream_id": streamId, "limit": "4"]),
              let json = try? await object(url),
              let list = json["epg_listings"] as? [[String: Any]] else { return [] }
        return list.compactMap { o in
            guard let raw = str(o["title"]),
                  let start = str(o["start_timestamp"]).flatMap({ Double($0) }),
                  let end = (str(o["stop_timestamp"]) ?? str(o["end_timestamp"])).flatMap({ Double($0) }) else { return nil }
            let title = Data(base64Encoded: raw).flatMap { String(data: $0, encoding: .utf8) } ?? raw
            return EpgEntry(title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                            start: Date(timeIntervalSince1970: start), end: Date(timeIntervalSince1970: end))
        }.sorted { $0.start < $1.start }
    }

    /// "example.com:8080/", "http://example.com:8080/get.php?..." → "http://example.com:8080".
    static func normalizeServer(_ input: String) -> String? {
        var s = input.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.isEmpty { return nil }
        if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") { s = "http://" + s }
        guard let comps = URLComponents(string: s), let scheme = comps.scheme, let host = comps.host, !host.isEmpty else { return nil }
        let port = comps.port.map { ":\($0)" } ?? ""
        return "\(scheme.lowercased())://\(host)\(port)"
    }
}

/// Plain-language message for an error.
func friendlyError(_ error: Error) -> String {
    if let x = error as? XtreamError {
        switch x {
        case .notXtream: return L("err_not_xtream")
        case .wrongLogin: return L("err_wrong_login")
        case .invalidAddress: return L("err_invalid_address")
        case .unexpected: return L("err_unexpected")
        case .refused(let code): return L("err_denied", code)
        case .server(let code): return L("err_server_code", code)
        }
    }
    let ns = error as NSError
    if ns.domain == NSURLErrorDomain {
        switch ns.code {
        case NSURLErrorCannotFindHost, NSURLErrorDNSLookupFailed: return L("err_unknown_host")
        case NSURLErrorTimedOut: return L("err_timeout")
        case NSURLErrorCannotConnectToHost: return L("err_connect")
        case NSURLErrorSecureConnectionFailed, NSURLErrorServerCertificateUntrusted: return L("err_ssl")
        default: return L("err_network")
        }
    }
    return L("err_generic")
}
