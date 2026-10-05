import Foundation
/// Control adapter only. No claims of implemented iOS microphone or playback.
public actor LanCoordinator {
    private let base: URL
    private let transport: URLSession
    private var token = ""
    public init(base: URL) throws {
        let n = (base.host ?? "").split(separator: ".").compactMap { Int($0) }
        guard base.scheme == "http", n.count == 4, n.allSatisfy({ (0...255).contains($0) }),
              n[0] == 10 || n[0] == 192 && n[1] == 168 || n[0] == 172 && (16...31).contains(n[1]) else { throw URLError(.unsupportedURL) }
        self.base = base
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 3
        config.allowsCellularAccess = false
        self.transport = URLSession(configuration: config)
    }
    public func join(name: String, role: String, code: String) async throws -> Data {
        let data = try await call("join", body: ["name": name, "role": role, "code": code, "client": "iOS preparation"])
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        token = json?["token"] as? String ?? ""
        return data
    }
    public func call(_ path: String, body: [String: Any]) async throws -> Data {
        guard ["join", "poll", "signal", "group", "leave", "info"].contains(path) else { throw URLError(.unsupportedURL) }
        var request = URLRequest(url: base.appendingPathComponent("lan").appendingPathComponent(path))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if !token.isEmpty { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await transport.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw URLError(.userAuthenticationRequired) }
        return data
    }
}
