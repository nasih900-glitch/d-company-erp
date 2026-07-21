import SwiftUI
import Security
import UIKit
import Network
import Vision
import VisionKit
import AudioToolbox
import CoreImage
import PhotosUI
import UserNotifications

private enum Brand {
    static let background = Color(red: 0.018, green: 0.016, blue: 0.011)
    static let surface = Color(red: 0.067, green: 0.052, blue: 0.033)
    static let elevated = Color(red: 0.105, green: 0.079, blue: 0.048)
    static let gold = Color(red: 0.80, green: 0.64, blue: 0.35)
    static let softGold = Color(red: 0.96, green: 0.78, blue: 0.44)
    static let muted = Color(red: 0.66, green: 0.58, blue: 0.43)
    static let danger = Color(red: 0.92, green: 0.32, blue: 0.28)
    static let success = Color(red: 0.25, green: 0.78, blue: 0.47)
    static let hairline = Color.white.opacity(0.08)

    static let appGradient = LinearGradient(
        colors: [
            Color(red: 0.032, green: 0.026, blue: 0.017),
            Color(red: 0.018, green: 0.016, blue: 0.011)
        ],
        startPoint: .top,
        endPoint: .bottom
    )

    static let cardGradient = LinearGradient(
        colors: [
            Color(red: 0.095, green: 0.071, blue: 0.043),
            Color(red: 0.050, green: 0.039, blue: 0.026)
        ],
        startPoint: .topLeading,
        endPoint: .bottomTrailing
    )
}

private final class NetworkMonitor: ObservableObject {
    @Published private(set) var isOnline = true
    @Published private(set) var connectionLabel = "Online"

    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "dcompany.erp.network")

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.isOnline = path.status == .satisfied
                if path.status == .satisfied {
                    if path.usesInterfaceType(.wifi) {
                        self?.connectionLabel = "Wi-Fi"
                    } else if path.usesInterfaceType(.cellular) {
                        self?.connectionLabel = "Cellular"
                    } else {
                        self?.connectionLabel = "Online"
                    }
                } else {
                    self?.connectionLabel = "Offline"
                }
            }
        }
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}

private enum DCompanyAPIError: Error, LocalizedError {
    case invalidURL
    case unauthenticated
    case badStatus(Int, String)
    case decodeFailed(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "The server address is invalid."
        case .unauthenticated:
            return "Please sign in again."
        case .badStatus(_, let message):
            return message
        case .decodeFailed(let message):
            return message
        }
    }

    var isUnauthorized: Bool {
        if case .badStatus(let code, _) = self {
            return code == 401
        }
        return false
    }
}

private struct APIClient {
    static let shared = APIClient()

    // Production default — unchanged from before this became configurable.
    // Kept as the fallback so an Info.plist missing/blank API_BASE_URL (e.g.
    // an older build) behaves identically to today's hardcoded behavior.
    private static let defaultBaseURLString = "https://dcompany.duckdns.org/api/v1/"

    // Overridable via the API_BASE_URL Info.plist key (see Info.plist) so a
    // future staging build can point elsewhere without a code change. Falls
    // back to the production default if the key is absent or empty; if it's
    // present but not a valid URL, the `baseURL` property below falls back
    // to the production default instead.
    static let baseURLString: String = {
        guard let value = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String,
              !value.isEmpty else {
            return defaultBaseURLString
        }
        return value
    }()

    private let baseURL = URL(string: APIClient.baseURLString) ?? URL(string: APIClient.defaultBaseURLString)!

    func get<T: Decodable>(
        _ path: String,
        token: String? = nil,
        queryItems: [URLQueryItem] = [],
        headers: [String: String] = [:]
    ) async throws -> T {
        var request = try makeRequest(path: path, token: token, queryItems: queryItems, headers: headers)
        request.httpMethod = "GET"
        return try await send(request)
    }

    // Raw-bytes GET — for CSV/file exports (GSTR-1, GSTR-3B, analytics
    // export) where the response is a file download, not JSON to decode.
    func getData(
        _ path: String,
        token: String? = nil,
        queryItems: [URLQueryItem] = [],
        headers: [String: String] = [:]
    ) async throws -> Data {
        var request = try makeRequest(path: path, token: token, queryItems: queryItems, headers: headers)
        request.httpMethod = "GET"
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DCompanyAPIError.badStatus(0, "No response from server.")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw DCompanyAPIError.badStatus(http.statusCode, errorMessage(from: data, fallback: "Server error \(http.statusCode)."))
        }
        return data
    }

    func post<T: Decodable, B: Encodable>(
        _ path: String,
        body: B,
        token: String? = nil,
        headers: [String: String] = [:]
    ) async throws -> T {
        var request = try makeRequest(path: path, token: token, headers: headers)
        request.httpMethod = "POST"
        request.httpBody = try JSONEncoder().encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await send(request)
    }

    func patch<T: Decodable, B: Encodable>(
        _ path: String,
        body: B,
        token: String? = nil,
        headers: [String: String] = [:]
    ) async throws -> T {
        var request = try makeRequest(path: path, token: token, headers: headers)
        request.httpMethod = "PATCH"
        request.httpBody = try JSONEncoder().encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        return try await send(request)
    }

    // DELETE /pos/orders/{id} (void) returns 204 No Content — there is
    // nothing to decode, unlike every other endpoint here.
    func delete<B: Encodable>(
        _ path: String,
        body: B,
        token: String? = nil,
        headers: [String: String] = [:]
    ) async throws {
        var request = try makeRequest(path: path, token: token, headers: headers)
        request.httpMethod = "DELETE"
        request.httpBody = try JSONEncoder().encode(body)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        try await sendNoContent(request)
    }

    // Body-less DELETE — most delete endpoints (menu items, ingredients,
    // customers, tables) don't need a request body, only the void-order
    // endpoint does (it requires a reason).
    func delete(
        _ path: String,
        token: String? = nil,
        headers: [String: String] = [:]
    ) async throws {
        var request = try makeRequest(path: path, token: token, headers: headers)
        request.httpMethod = "DELETE"
        try await sendNoContent(request)
    }

    // multipart/form-data upload — only /ocr/uploads needs this; every other
    // write endpoint in this app is JSON. Builds the body by hand since
    // URLSession has no multipart encoder.
    func upload<T: Decodable>(
        _ path: String,
        fileData: Data,
        fileName: String,
        mimeType: String,
        formFields: [String: String],
        token: String? = nil
    ) async throws -> T {
        var request = try makeRequest(path: path, token: token)
        request.httpMethod = "POST"
        let boundary = "DCompanyERP-\(UUID().uuidString)"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()
        for (key, value) in formFields {
            body.append("--\(boundary)\r\n".data(using: .utf8)!)
            body.append("Content-Disposition: form-data; name=\"\(key)\"\r\n\r\n".data(using: .utf8)!)
            body.append("\(value)\r\n".data(using: .utf8)!)
        }
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: \(mimeType)\r\n\r\n".data(using: .utf8)!)
        body.append(fileData)
        body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
        request.httpBody = body

        return try await send(request)
    }

    private func makeRequest(
        path: String,
        token: String?,
        queryItems: [URLQueryItem] = [],
        headers: [String: String] = [:]
    ) throws -> URLRequest {
        let cleanPath = path.hasPrefix("/") ? String(path.dropFirst()) : path
        guard let url = URL(string: cleanPath, relativeTo: baseURL)?.absoluteURL,
              var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
            throw DCompanyAPIError.invalidURL
        }

        if !queryItems.isEmpty {
            components.queryItems = queryItems
        }

        guard let finalURL = components.url else {
            throw DCompanyAPIError.invalidURL
        }

        var request = URLRequest(url: finalURL)
        request.timeoutInterval = 18
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("DCompanyERP-iOSNative/1.0", forHTTPHeaderField: "User-Agent")

        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        for (key, value) in headers {
            request.setValue(value, forHTTPHeaderField: key)
        }

        // Attach this device's resolved terminal to EVERY authenticated
        // request by default, not just the ones a call site remembers to
        // pass it on. Multiple call sites (order lists, shift close) forgot
        // to include it individually and broke as a result — rather than
        // keep hunting for the next one, make it structurally impossible to
        // forget. An explicit value in `headers` above still wins.
        if request.value(forHTTPHeaderField: "X-Terminal-Id") == nil, let terminalId = TerminalStore.read() {
            request.setValue(terminalId, forHTTPHeaderField: "X-Terminal-Id")
        }

        return request
    }

    private func sendNoContent(_ request: URLRequest) async throws {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DCompanyAPIError.badStatus(0, "No response from server.")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw DCompanyAPIError.badStatus(http.statusCode, errorMessage(from: data, fallback: "Server error \(http.statusCode)."))
        }
    }

    private func send<T: Decodable>(_ request: URLRequest) async throws -> T {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw DCompanyAPIError.badStatus(0, "No response from server.")
        }

        guard (200..<300).contains(http.statusCode) else {
            throw DCompanyAPIError.badStatus(http.statusCode, errorMessage(from: data, fallback: "Server error \(http.statusCode)."))
        }

        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let string = try container.decode(String.self)
            if let date = DateFormatters.isoFractional.date(from: string)
                ?? DateFormatters.iso.date(from: string)
                ?? DateFormatters.apiDateOnly.date(from: string) {
                return date
            }
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Invalid date: \(string)")
        }

        do {
            return try decoder.decode(T.self, from: data)
        } catch {
            throw DCompanyAPIError.decodeFailed(decodingMessage(for: error, request: request, data: data, response: http))
        }
    }

    private func decodingMessage(for error: Error, request: URLRequest, data: Data, response: HTTPURLResponse) -> String {
        let endpoint = request.url?.path ?? "API"
        if data.isEmpty {
            return "The server returned an empty response from \(endpoint)."
        }
        if let contentType = response.value(forHTTPHeaderField: "Content-Type"),
           !contentType.localizedCaseInsensitiveContains("json"),
           let body = String(data: Data(data.prefix(120)), encoding: .utf8) {
            return "The server returned \(contentType) from \(endpoint), not JSON. \(body.trimmingCharacters(in: .whitespacesAndNewlines))"
        }
        if let body = String(data: Data(data.prefix(120)), encoding: .utf8),
           body.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("<") {
            return "The server returned an HTML page from \(endpoint), not API JSON."
        }
        let detail: String

        switch error {
        case DecodingError.typeMismatch(let type, let context):
            detail = "Field \(codingPath(context.codingPath)) did not match expected type \(type)."
        case DecodingError.valueNotFound(let type, let context):
            detail = "Field \(codingPath(context.codingPath)) was missing value \(type)."
        case DecodingError.keyNotFound(let key, let context):
            detail = "Missing field \(codingPath(context.codingPath + [key]))."
        case DecodingError.dataCorrupted(let context):
            detail = "Invalid data at \(codingPath(context.codingPath))."
        default:
            detail = error.localizedDescription
        }

        return "The app could not read the server response from \(endpoint). \(detail)"
    }

    private func codingPath(_ path: [CodingKey]) -> String {
        let rendered = path.map(\.stringValue).filter { !$0.isEmpty }.joined(separator: ".")
        return rendered.isEmpty ? "response" : rendered
    }

    private func errorMessage(from data: Data, fallback: String) -> String {
        guard !data.isEmpty else { return fallback }

        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
            // This backend's real error envelope: {"error": {"code", "message", "details"}}
            // (app/core/errors.py register_exception_handlers) — not FastAPI's default
            // {"detail": ...}. Check the real shape first, fall back to "detail" in case
            // any endpoint ever differs (e.g. a raw FastAPI validation error).
            if let errorObj = json["error"] as? [String: Any],
               let message = errorObj["message"] as? String {
                return message
            }
            if let detail = json["detail"] {
                if let text = detail as? String {
                    return text
                }
                if let dict = detail as? [String: Any],
                   let message = dict["message"] as? String {
                    return message
                }
            }
        }

        return String(data: data, encoding: .utf8) ?? fallback
    }
}

// Real-time push over WebSocket — replaces polling-on-a-timer with a
// server-initiated "this changed" signal, the same way any high-end
// live-data app works: a screen doesn't ask "did anything change yet?"
// every N seconds, the server tells it the instant something does.
//
// One shared connection for the whole app. Screens subscribe to a resource
// ("shifts", "tables", "orders", "gaming", "kitchen", "attendance") and get
// called back when it changes — they already have a load() for that
// resource (the one that used to run on a timer), so the callback just
// re-runs it.
//
// Auth is a first-message handshake, not a query-string token — the token
// would otherwise sit in plaintext in server access logs.
private final class RealtimeClient {
    static let shared = RealtimeClient()

    private var task: URLSessionWebSocketTask?
    private var reconnectAttempt = 0
    private var reconnectWorkItem: DispatchWorkItem?
    private var intentionallyClosed = false
    private var listeners: [String: [UUID: () -> Void]] = [:]

    private var wsURL: URL {
        // Derived from APIClient's (now-configurable) baseURL — https->wss,
        // appending /ws — rather than a second independent hardcoded string,
        // so the two can never drift out of sync. With no Info.plist
        // override this resolves to the same value as before:
        // wss://dcompany.duckdns.org/api/v1/ws
        let httpBase = APIClient.baseURLString
        var wsBase: String
        if httpBase.hasPrefix("https://") {
            wsBase = "wss://" + httpBase.dropFirst("https://".count)
        } else if httpBase.hasPrefix("http://") {
            wsBase = "ws://" + httpBase.dropFirst("http://".count)
        } else {
            wsBase = httpBase
        }
        if wsBase.hasSuffix("/") { wsBase.removeLast() }
        return URL(string: wsBase + "/ws") ?? URL(string: "wss://dcompany.duckdns.org/api/v1/ws")!
    }

    func connect() {
        guard let token = TokenStore.read("access_token") else { return }
        intentionallyClosed = false
        guard task == nil else { return }

        let session = URLSession(configuration: .default)
        let newTask = session.webSocketTask(with: wsURL)
        task = newTask
        newTask.resume()
        newTask.send(.string("{\"token\": \"\(token)\"}")) { [weak self] error in
            if error != nil { self?.handleDisconnect() }
        }
        listen(on: newTask)
    }

    func disconnect() {
        intentionallyClosed = true
        reconnectWorkItem?.cancel()
        reconnectWorkItem = nil
        reconnectAttempt = 0
        task?.cancel(with: .goingAway, reason: nil)
        task = nil
    }

    /// Subscribe a screen's refetch closure to a resource. Call the
    /// returned closure (e.g. from `.onDisappear` or a `deinit`-adjacent
    /// path) to unsubscribe.
    func subscribe(_ resource: String, _ callback: @escaping () -> Void) -> () -> Void {
        let id = UUID()
        listeners[resource, default: [:]][id] = callback
        return { [weak self] in self?.listeners[resource]?[id] = nil }
    }

    private func listen(on task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .failure:
                self.handleDisconnect()
            case .success(let message):
                if case .string(let text) = message {
                    self.handle(text)
                }
                // Still the same task (a stale closure from a prior
                // generation would have already returned above via
                // handleDisconnect swapping `self.task`) — keep listening.
                if self.task === task {
                    self.listen(on: task)
                }
            }
        }
    }

    private func handle(_ text: String) {
        guard let data = text.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }
        if type == "connected" {
            reconnectAttempt = 0
        } else if type == "changed", let resource = json["resource"] as? String {
            DispatchQueue.main.async { [weak self] in
                self?.listeners[resource]?.values.forEach { $0() }
            }
        }
        // "ping" needs no reply — the server only sends it to keep the
        // connection active through any intermediate proxy/timeout.
    }

    private func handleDisconnect() {
        task = nil
        guard !intentionallyClosed else { return }
        scheduleReconnect()
    }

    private func scheduleReconnect() {
        guard reconnectWorkItem == nil else { return }
        // Capped exponential backoff — instant retry on a blip, but a flaky
        // network doesn't turn into a reconnect storm.
        let delay = min(30.0, pow(2.0, Double(reconnectAttempt)))
        reconnectAttempt += 1
        let work = DispatchWorkItem { [weak self] in
            self?.reconnectWorkItem = nil
            self?.connect()
        }
        reconnectWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }
}

private enum DateFormatters {
    static let isoFractional: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    static let iso: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        return formatter
    }()

    static let shortDateTime: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    static let apiDateOnly: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Kolkata")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()

    // yyyy_mm — the shape /reports/gstr1.csv and /reports/gstr3b.csv expect.
    static let yearMonth: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone(identifier: "Asia/Kolkata")
        formatter.dateFormat = "yyyy-MM"
        return formatter
    }()

    static let timeOnly: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm"
        return formatter
    }()
}

private enum NumberFormatters {
    static let inr: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.numberStyle = .currency
        formatter.currencyCode = "INR"
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 2
        return formatter
    }()

    static let decimal: NumberFormatter = {
        let formatter = NumberFormatter()
        formatter.maximumFractionDigits = 2
        formatter.minimumFractionDigits = 0
        return formatter
    }()
}

private enum TokenStore {
    private static let service = "cloud.dcompany.erp.native"

    static func save(_ value: String, for account: String) {
        let data = Data(value.utf8)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)

        let addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        ]
        SecItemAdd(addQuery as CFDictionary, nil)
    }

    static func read(_ account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    static func delete(_ account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
    }
}

private struct LoginRequest: Encodable {
    let email: String
    let password: String
}

private struct RefreshRequest: Encodable {
    let refresh_token: String
}

private struct TokenPair: Decodable {
    let access_token: String
    let refresh_token: String
    let token_type: String
    let expires_in: Int
}

// OTP-gated self-service flows — password reset and new-account
// registration. Both are public endpoints (no access token needed): a
// challenge_id is emailed a code to `destination`, then confirmed with the
// 6-digit code. Mirrors frontend/src/modules/auth/Login.tsx's 5-mode state
// machine (login/reset-request/reset-confirm/register-request/register-confirm).
private struct OtpChallengeDTO: Decodable {
    let challenge_id: String
    let expires_in: Int
    let destination: String
}

private struct AccountActionDTO: Decodable {
    let message: String
}

private struct PasswordResetRequestBody: Encodable {
    let email: String
}

private struct PasswordResetConfirmBody: Encodable {
    let challenge_id: String
    let code: String
    let new_password: String
}

private struct RegisterRequestBody: Encodable {
    let email: String
    let name: String
    let phone: String?
    let password: String
}

private struct RegisterConfirmBody: Encodable {
    let challenge_id: String
    let code: String
}

private struct MeResponse: Decodable {
    let user_id: String
    let email: String
    let name: String
    let roles: [String]
    let protected_access: Bool
    let company_id: String
    let branch_id: String?
    let accessible_modules: [String]
}

private struct MenuCategoryDTO: Codable, Identifiable, Hashable {
    let id: String
    let name: String
    let sort_order: Int
}

private struct CategoryCreateRequest: Encodable {
    let name: String
    let sort_order: Int
}

private struct MenuItemDTO: Codable, Identifiable, Hashable {
    let id: String
    let category_id: String?
    let sku: String
    let name: String
    let type: String
    let base_price_minor: Int
    let tax_rate: Double
    let hsn_code: String?
    let price_includes_tax: Bool?
    let is_available: Bool
    let description: String?
}

private struct ItemCreateRequest: Encodable {
    let category_id: String
    let sku: String
    let name: String
    let type: String
    let base_price_minor: Int
    let tax_rate: Double
    let hsn_code: String?
    let price_includes_tax: Bool
    let description: String?
}

// Every field optional (Pydantic ItemUpdate) — Swift's encodeIfPresent
// omits nil ones, which is exactly right here: "not sent" = "don't change".
private struct ItemUpdateRequest: Encodable {
    let category_id: String?
    let name: String?
    let base_price_minor: Int?
    let tax_rate: Double?
    let hsn_code: String?
    let price_includes_tax: Bool?
    let description: String?
    let is_available: Bool?
}

private let menuItemTypes = ["food", "drink", "dessert", "gaming", "event", "hookah", "streaming"]

private struct IngredientDTO: Codable, Identifiable {
    let id: String
    let sku: String
    let name: String
    let base_unit: String
    let current_qty: Double
    let reorder_threshold: Double
    let reorder_qty: Double
    let avg_cost_minor: Int

    var isLowStock: Bool {
        current_qty <= reorder_threshold
    }
}

private let baseUnits = ["ml", "g", "unit"]

private struct IngredientCreateRequest: Encodable {
    let sku: String
    let name: String
    let base_unit: String
    let reorder_threshold: Double
    let reorder_qty: Double
}

private struct IngredientUpdateRequest: Encodable {
    let name: String?
    let base_unit: String?
    let reorder_threshold: Double?
    let reorder_qty: Double?
}

private struct StockAdjustmentRequest: Encodable {
    let ingredient_id: String
    let branch_id: String
    let qty_delta: Double
    let type: String
    let note: String?
}

private struct SupplierDTO: Codable, Identifiable {
    let id: String
    let name: String
    let contact: String?
    let gstin: String?
    let payment_terms: String?
}

private struct SupplierCreateRequest: Encodable {
    let name: String
    let contact: String?
    let gstin: String?
    let payment_terms: String?
}

private struct GrnLineRequest: Encodable {
    let ingredient_id: String
    let qty: Double
    let unit_cost_minor: Int
    let expires_at: String?
    let lot_code: String?
}

private struct GrnPostRequest: Encodable {
    let branch_id: String
    let supplier_id: String?
    let supplier_invoice_no: String?
    let supplier_invoice_amount_minor: Int?
    let received_at: String?
    let notes: String?
    let lines: [GrnLineRequest]
}

private struct GrnPostResponse: Decodable {
    let ok: Bool
    let batches_created: Int
    let batch_ids: [String]
}

// Recipe/RecipeLine (BOM) — what a menu item consumes at checkout. Recipe
// carries no company_id of its own on the backend (tenant scope comes from
// the menu_item_id -> MenuItem join); there is at most one is_active recipe
// per item, which is the one app/services/inventory/deduction.py reads at
// order payment.
private struct RecipeLineDTO: Codable, Identifiable {
    let id: String
    let ingredient_id: String
    let qty: Double
    let wastage_pct: Double // fraction, e.g. 0.05 = 5% — not a percent
}

private struct RecipeDTO: Codable, Identifiable {
    let id: String
    let menu_item_id: String
    let name: String
    let yield_qty: Double
    let version: Int
    let is_active: Bool
    let cost_minor: Int
    let lines: [RecipeLineDTO]
}

private struct RecipeLineRequest: Encodable {
    let ingredient_id: String
    let qty: Double
    let wastage_pct: Double
}

private struct RecipeCreateRequest: Encodable {
    let menu_item_id: String
    let name: String
    let yield_qty: Double
    let lines: [RecipeLineRequest]
}

private struct RecipeLineUpdateRequest: Encodable {
    let ingredient_id: String?
    let qty: Double?
    let wastage_pct: Double?
}

private struct MoneyBucketDTO: Codable {
    let total_minor: Int
}

private struct RevenueBucketDTO: Codable {
    let food_minor: Int
    let gaming_minor: Int
    let hookah_minor: Int
    let event_tickets_minor: Int
    let delivery_aggregator_minor: Int
    let other_minor: Int
    let manual_collections_minor: Int?
    let discounts_and_points_redeemed_minor: Int?
    let total_minor: Int
}

private struct TaxBucketDTO: Codable {
    let cgst_minor: Int
    let sgst_minor: Int
    let igst_minor: Int
    let cess_minor: Int
    let total_minor: Int
}

private struct PaymentsBucketDTO: Codable {
    let cash_minor: Int
    let upi_minor: Int
    let card_minor: Int
    let bank_minor: Int?
    let qr_minor: Int
    let wallet_minor: Int
    let other_minor: Int
    let total_minor: Int
}

private struct ExpenseLineDTO: Codable, Identifiable {
    let category: String
    let amount_minor: Int
    var id: String { category }
}

// Matches the flat payload shape ReportsScreen.tsx's pushToSheets() posts —
// the Apps Script "ERP Entries" sink expects exactly these keys.
private struct ReportSheetPayload: Encodable {
    let date: String
    let time: String
    let period_id: String
    let label: String
    let orders_count: Int
    let food_minor: Int
    let gaming_minor: Int
    let hookah_minor: Int
    let event_tickets_minor: Int
    let delivery_minor: Int
    let gross_revenue_minor: Int
    let net_revenue_minor: Int
    let manual_collections_minor: Int
    let cgst_minor: Int
    let sgst_minor: Int
    let igst_minor: Int
    let expense_total_minor: Int
    let net_profit_minor: Int
}

private struct ReportDTO: Codable {
    let period: String
    let label: String
    let period_start: Date
    let period_end: Date
    let fiscal_year: String
    let orders_count: Int
    let tickets_count: Int
    let avg_ticket_minor: Int
    let revenue: RevenueBucketDTO
    let tax_collected: TaxBucketDTO
    let payments_received: PaymentsBucketDTO
    let manual_collections_minor: Int?
    let refunds_issued_minor: Int
    let net_payments_received_minor: Int
    let expenses: [ExpenseLineDTO]
    let expense_total_minor: Int
    let cogs_minor: Int
    let gross_revenue_minor: Int
    let net_revenue_minor: Int
    let gross_profit_minor: Int
    let net_profit_minor: Int
}

private struct BusinessMetricsDTO: Codable {
    let period_start: Date
    let period_end: Date
    let aov_minor: Int
    let orders_count: Int
    let mrr_minor: Int
    let arr_minor: Int
    let active_members_count: Int
    let cac_minor: Int?
    let new_customers_count: Int
    let marketing_spend_minor: Int
    let ltv_minor: Int
    let customers_count: Int
    let burn_rate_minor: Int
}

private struct TaxComplianceIssueDTO: Decodable, Identifiable {
    let severity: String
    let area: String
    let title: String
    let detail: String
    let count: Int
    let action: String

    var id: String {
        "\(severity)-\(area)-\(title)-\(count)"
    }
}

private struct TaxComplianceDTO: Decodable {
    let period_start: Date
    let period_end: Date
    let company_gst_registered: Bool
    let gstin: String?
    let checked_orders: Int
    let checked_order_lines: Int
    let taxable_minor: Int
    let gst_collected_minor: Int
    let aggregator_delivery_minor: Int
    let event_ticket_revenue_minor: Int
    let critical_count: Int
    let warning_count: Int
    let info_count: Int
    let issues: [TaxComplianceIssueDTO]
}

private struct DashboardKPIsDTO: Decodable {
    let date: Date
    let revenue_food_minor: Int
    let revenue_gaming_minor: Int
    let revenue_hookah_minor: Int
    let revenue_events_minor: Int
    let revenue_manual_collections_minor: Int?
    let revenue_total_minor: Int
    let orders_count: Int
    let tickets_count: Int
    let avg_ticket_minor: Int
    let inventory_value_minor: Int
    let low_stock_items: Int
    let open_sessions: Int
    let net_profit_minor: Int
}

private struct AccountDTO: Decodable, Identifiable {
    let id: String
    let code: String
    let name: String
    let type: String
    let normal_side: String
    let is_active: Bool
}

private struct TBLineDTO: Decodable, Identifiable {
    let account_code: String
    let account_name: String
    let account_type: String
    let debit_minor: Int
    let credit_minor: Int
    let balance_minor: Int
    var id: String { account_code }
}

private struct TrialBalanceDTO: Decodable {
    let as_of: Date
    let lines: [TBLineDTO]
    let total_debit_minor: Int
    let total_credit_minor: Int
    let is_balanced: Bool
}

private struct BSLineDTO: Decodable, Identifiable {
    let name: String
    let amount_minor: Int
    var id: String { name }
}

private struct BSSectionDTO: Decodable {
    let section: String
    let lines: [BSLineDTO]
    let total_minor: Int
}

private struct BalanceSheetDTO: Decodable {
    let as_of: Date
    let assets: BSSectionDTO
    let liabilities: BSSectionDTO
    let equity: BSSectionDTO
    let is_balanced: Bool
}

private struct GLEntryDTO: Decodable, Identifiable {
    let occurred_at: Date
    let ref_type: String
    let ref_id: String?
    let account_code: String
    let account_name: String
    let debit_minor: Int
    let credit_minor: Int
    let memo: String?
    var id: String { "\(occurred_at.timeIntervalSince1970)-\(account_code)-\(ref_id ?? "")" }
}

private struct ValuationLineDTO: Decodable, Identifiable {
    let ingredient_id: String
    let sku: String
    let name: String
    let base_unit: String
    let current_qty: Double
    let avg_cost_minor: Int
    let valuation_minor: Int
    let reorder_threshold: Double
    let is_low_stock: Bool
    var id: String { ingredient_id }
}

private struct InventoryValuationDTO: Decodable {
    let as_of: Date
    let lines: [ValuationLineDTO]
    let total_valuation_minor: Int
    let low_stock_count: Int
}

private struct RecipeMarginDTO: Decodable, Identifiable {
    let menu_item_id: String
    let sku: String
    let name: String
    let type: String
    let sale_price_minor: Int
    let cost_minor: Int
    let margin_minor: Int
    let margin_pct: Double
    var id: String { menu_item_id }
}

private struct TopItemDTO: Decodable, Identifiable {
    let menu_item_id: String
    let name: String
    let type: String
    let qty_sold: Double
    let revenue_minor: Int
    var id: String { menu_item_id }
}

private struct GrowthPeriodDTO: Decodable {
    let label: String
    let revenue_minor: Int
    let manual_collections_minor: Int?
    let orders_count: Int
    let avg_ticket_minor: Int
}

private struct GrowthDTO: Decodable {
    let current: GrowthPeriodDTO
    let previous: GrowthPeriodDTO
    let revenue_delta_pct: Double
    let orders_delta_pct: Double
}

private struct HeatmapCellDTO: Decodable {
    let day_of_week: Int
    let hour: Int
    let revenue_minor: Int
    let orders_count: Int
}

private struct LossLineDTO: Decodable, Identifiable {
    let ingredient_id: String
    let sku: String
    let name: String
    let qty_lost: Double
    let cost_lost_minor: Int
    let movement_count: Int
    var id: String { ingredient_id }
}

private struct LossesDTO: Decodable {
    let from_date: Date
    let to_date: Date
    let waste_minor: Int
    let damage_minor: Int
    let negative_stock_minor: Int
    let total_loss_minor: Int
    let lines: [LossLineDTO]
}

private struct ExpenseCategoryDTO: Decodable, Identifiable {
    let id: String
    let name: String
    let code: String?
}

private struct ExpenseDTO: Decodable, Identifiable {
    let id: String
    let branch_id: String
    let category_id: String
    let supplier_id: String?
    let amount_minor: Int
    let paid_via: String
    let paid_at: Date
    let vendor_name: String?
    let invoice_no: String?
    let note: String?
}

private struct ExpenseCreateRequest: Encodable {
    let branch_id: String
    let category_id: String
    let supplier_id: String?
    let amount_minor: Int
    let paid_via: String
    let paid_at: String
    let vendor_name: String?
    let invoice_no: String?
    let note: String?
}

private struct PartnerDTO: Decodable, Identifiable {
    let id: String
    let name: String
    let share_pct: Double
    let joined_at: Date
    let notes: String?
    let capital_balance_minor: Int
}

private struct PartnerCreateRequest: Encodable {
    let name: String
    let share_pct: Double
    let joined_at: String
    let notes: String?
}

private struct CapitalEntryDTO: Decodable, Identifiable {
    let id: String
    let partner_id: String
    let type: String
    let amount_minor: Int
    let effective_at: Date
    let note: String?
}

private struct CapitalEntryCreateRequest: Encodable {
    let partner_id: String
    let type: String
    let amount_minor: Int
    let effective_at: String
    let note: String?
}

private struct EventDTO: Decodable, Identifiable {
    let id: String
    let name: String
    let description: String?
    let event_type: String
    let screen: String
    let starts_at: Date
    let ends_at: Date?
    let capacity: Int
    let sold: Int
    let remaining: Int
    let base_ticket_price_minor: Int
    let sac_code: String
    let tax_rate: Double
    let status: String
    let poster_url: String?
}

private struct EventTicketDTO: Decodable, Identifiable {
    let id: String
    let ticket_no: String
    let event_id: String
    let event_name: String
    let customer_name: String?
    let customer_phone: String?
    let seat: String?
    let price_paid_minor: Int
    let status: String
    let checked_in_at: Date?
}

private struct EventCreateRequest: Encodable {
    let name: String
    let description: String?
    let event_type: String
    let screen: String?
    let starts_at: String
    let ends_at: String?
    let capacity: Int
    let base_ticket_price_minor: Int
    let poster_url: String?
}

private struct EventUpdateRequest: Encodable {
    let name: String?
    let description: String?
    let event_type: String?
    let screen: String?
    let starts_at: String?
    let ends_at: String?
    let capacity: Int?
    let base_ticket_price_minor: Int?
    let poster_url: String?
    let status: String?
}

private struct SellTicketsRequest: Encodable {
    let customer_name: String
    let customer_phone: String?
    let seat: String?
    let qty: Int
    let note: String?
}

private let eventTypes = ["football", "cricket", "movie", "esports", "other"]
private let eventStatuses = ["scheduled", "live", "ended", "cancelled"]

private struct MembershipTierDTO: Decodable, Identifiable {
    let id: String
    let code: String
    let name: String
    let monthly_price_minor: Int
    let annual_price_minor: Int?
    let food_discount_pct: Double
    let gaming_discount_pct: Double
    let hookah_discount_pct: Double
    let point_multiplier: Double
    let free_gaming_minutes_per_week: Int
    let free_hookah_per_month: Int
    let priority_booking: Bool
    let description: String?
    let sort_order: Int
}

private struct MembershipTierUpdateRequest: Encodable {
    let name: String?
    let monthly_price_minor: Int?
    let annual_price_minor: Int?
    let food_discount_pct: Double?
    let gaming_discount_pct: Double?
    let hookah_discount_pct: Double?
    let point_multiplier: Double?
    let free_gaming_minutes_per_week: Int?
    let free_hookah_per_month: Int?
    let priority_booking: Bool?
    let description: String?

    enum CodingKeys: String, CodingKey {
        case name, monthly_price_minor, annual_price_minor, food_discount_pct,
             gaming_discount_pct, hookah_discount_pct, point_multiplier,
             free_gaming_minutes_per_week, free_hookah_per_month,
             priority_booking, description
    }

    // Every field but annual_price_minor is "omit if unset" (encodeIfPresent).
    // annual_price_minor needs three states — untouched / cleared / set — and
    // the form always has an opinion on it (blank means "clear"), so it must
    // always be sent, with an explicit JSON null when the tier has no annual
    // price. That's why this can't be the default synthesized Encodable.
    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(name, forKey: .name)
        try container.encodeIfPresent(monthly_price_minor, forKey: .monthly_price_minor)
        try container.encode(annual_price_minor, forKey: .annual_price_minor)
        try container.encodeIfPresent(food_discount_pct, forKey: .food_discount_pct)
        try container.encodeIfPresent(gaming_discount_pct, forKey: .gaming_discount_pct)
        try container.encodeIfPresent(hookah_discount_pct, forKey: .hookah_discount_pct)
        try container.encodeIfPresent(point_multiplier, forKey: .point_multiplier)
        try container.encodeIfPresent(free_gaming_minutes_per_week, forKey: .free_gaming_minutes_per_week)
        try container.encodeIfPresent(free_hookah_per_month, forKey: .free_hookah_per_month)
        try container.encodeIfPresent(priority_booking, forKey: .priority_booking)
        try container.encodeIfPresent(description, forKey: .description)
    }
}

private struct SubscriptionDTO: Decodable, Identifiable {
    let id: String
    let customer_id: String
    let tier_id: String
    let tier_code: String
    let tier_name: String
    let billing_cycle: String
    let starts_at: Date
    let expires_at: Date
    let cancelled_at: Date?
    let auto_renew: Bool
    let amount_paid_minor: Int
    let is_active: Bool
}

private struct SubscribeRequest: Encodable {
    let customer_id: String
    let tier_id: String
    let billing_cycle: String?
    let paid_via: String?
}

private struct OcrExtractionDTO: Decodable, Identifiable {
    let id: String
    let vendor_name: String?
    let invoice_no: String?
    let invoice_date: Date?
    let amount_minor: Int?
    let status: String
}

private struct OcrUploadResponseDTO: Decodable {
    let id: String
    let sha256: String
    let byte_size: Int
}

private struct OcrVerifyRequest: Encodable {
    let decision: String
    let notes: String?
}

private struct OcrVerifyResponseDTO: Decodable {
    let id: String
    let extraction_status: String
}

private enum JSONValue: Decodable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            self = .object(try container.decode([String: JSONValue].self))
        }
    }

    var summary: String {
        switch self {
        case .string(let value):
            return value
        case .number(let value):
            return String(value)
        case .bool(let value):
            return value ? "true" : "false"
        case .null:
            return "empty"
        case .array(let values):
            return "\(values.count) item changes"
        case .object(let object):
            return object.keys.sorted().prefix(4).joined(separator: ", ")
        }
    }
}

private struct AuditUnlockRequest: Encodable {
    let password: String
}

private struct AuditUnlockResponse: Decodable {
    let audit_token: String
    let expires_in: Int
}

private struct PricingUnlockResponse: Decodable {
    let pricing_token: String
    let expires_in: Int
}

private struct MenuItemPricingUpdateRequest: Encodable {
    let base_price_minor: Int?
    let tax_rate: Double?
}

private struct AuditEntryDTO: Decodable, Identifiable {
    let id: Int
    let actor_user_id: String?
    let actor_name: String?
    let actor_email: String?
    let action: String
    let entity_type: String
    let entity_id: String?
    let before: JSONValue?
    let after: JSONValue?
    let ip: String?
    let user_agent: String?
    let created_at: Date

    var actorDisplayName: String {
        guard let actor_name, !actor_name.isEmpty else { return "System" }
        return actor_name
    }
}

private enum OrderServiceType: String, CaseIterable, Identifiable, Hashable {
    case dineIn = "dine_in"
    case takeaway
    case delivery

    var id: String { rawValue }

    var title: String {
        switch self {
        case .dineIn: return "Dine in"
        case .takeaway: return "Takeaway"
        case .delivery: return "Delivery"
        }
    }

    var icon: String {
        switch self {
        case .dineIn: return "fork.knife"
        case .takeaway: return "bag"
        case .delivery: return "scooter"
        }
    }
}

private enum PaymentMethod: String, CaseIterable, Identifiable, Hashable {
    case cash
    case card
    case upi
    case qr
    case wallet

    var id: String { rawValue }

    var title: String {
        switch self {
        case .cash: return "Cash"
        case .card: return "Card"
        case .upi: return "UPI"
        case .qr: return "QR"
        case .wallet: return "Wallet"
        }
    }

    var icon: String {
        switch self {
        case .cash: return "banknote"
        case .card: return "creditcard"
        case .upi: return "qrcode"
        case .qr: return "qrcode.viewfinder"
        case .wallet: return "wallet.pass"
        }
    }
}

private enum ReportPeriodScope: String, CaseIterable, Identifiable, Hashable {
    case daily
    case weekly
    case monthly
    case quarterly
    case halfYearly = "half_yearly"
    case yearly

    var id: String { rawValue }

    var title: String {
        switch self {
        case .halfYearly:
            return "Half Year"
        default:
            return rawValue.capitalized
        }
    }

    var endpoint: String {
        switch self {
        case .weekly, .halfYearly:
            return "reports/range"
        default:
            return "reports/\(rawValue)"
        }
    }
}

private enum DeliveryAggregator: String, CaseIterable, Identifiable {
    case inhouse
    case zomato
    case swiggy
    case ubereats
    case other = "other_aggregator"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .inhouse: return "In-house"
        case .zomato: return "Zomato"
        case .swiggy: return "Swiggy"
        case .ubereats: return "Uber Eats"
        case .other: return "Other aggregator"
        }
    }

    // Section 9(5) of the CGST Act: for platform aggregators, the platform
    // is deemed the supplier and pays the GST — not the restaurant. Matches
    // frontend/src/modules/pos/LivePOSScreen.tsx's aggregator-pays-GST note.
    var isThirdPartyAggregator: Bool {
        self != .inhouse
    }
}

private struct CheckoutDraft: Identifiable {
    let id = UUID()
    var serviceType: OrderServiceType = .dineIn
    var paymentMethod: PaymentMethod = .cash
    var customerName = ""
    var customerPhone = ""
    var note = ""
    var cashTenderedMinor: Int?
    var printReceiptAfterCharge = true
    var deliveryAggregator: DeliveryAggregator = .inhouse
    var customerGSTIN = ""
    var customerStateCode = ""
    var isInterstate = false
    // Ignoring GST is intentional here — D Company is GST-unregistered, so
    // this is a flat rupee amount knocked off the final total, same as an
    // unregistered business would write on a plain cash memo.
    var discountRupeesText = ""
    // 10 points = ₹1 of playtime value — must match backend MINOR_PER_POINT.
    // Blind entry: the app has no live customer/points lookup at checkout
    // yet, so the server validates the customer actually has this balance.
    var pointsRedeemedText = ""
    // Voluntary tip — additional money on top of the bill, never folded
    // into the bill total. Sent as its own field on the payment request
    // (see PaymentCreateRequest / _validate_confirmed_payment_balance in
    // app/api/v1/pos/router.py).
    var tipRupeesText = ""

    var discountMinor: Int {
        max(0, Int(discountRupeesText) ?? 0) * 100
    }

    var pointsRedeemed: Int {
        max(0, Int(pointsRedeemedText) ?? 0)
    }

    var tipMinor: Int {
        max(0, Int(tipRupeesText) ?? 0) * 100
    }

    func tenderedMinor(totalMinor: Int) -> Int? {
        paymentMethod == .cash ? (cashTenderedMinor ?? totalMinor) : nil
    }

    func changeDueMinor(totalMinor: Int) -> Int {
        max((cashTenderedMinor ?? totalMinor) - totalMinor, 0)
    }

    func isCashTenderReady(totalMinor: Int) -> Bool {
        paymentMethod != .cash || (cashTenderedMinor ?? totalMinor) >= totalMinor
    }
}

private struct ShiftDTO: Decodable, Identifiable {
    let id: String
    let branch_id: String?
    let terminal_id: String?
    let status: String
    let opened_at: Date
    let closed_at: Date?
    let opening_float_minor: Int
    let expected_minor: Int?
    let counted_minor: Int?
    let variance_minor: Int?
    let opened_by_name: String?
    let opened_by_email: String?
}

private struct TerminalDTO: Decodable, Identifiable {
    let id: String
    let branch_id: String
    let name: String
    let device_id: String?
    let last_seen_at: Date?
}

private struct TerminalCreateRequest: Encodable {
    let branch_id: String
    let name: String
    let device_id: String?
}

private struct EmptyRequest: Encodable {}

private struct ShiftOpenRequest: Encodable {
    let opening_float_minor: Int
}

private struct ShiftOpenResponse: Decodable {
    let id: String
    let status: String
}

private struct ShiftCloseRequest: Encodable {
    let counted_minor: Int
}

private struct ShiftCloseResponseDTO: Decodable {
    let id: String
    let status: String
    let variance_minor: Int
}

private enum GamingStationKind: String, CaseIterable, Identifiable, Hashable {
    case ps5
    case vr
    case simulator
    case projector
    case hookah
    case streaming
    case station

    var id: String { rawValue }

    static func from(_ rawValue: String) -> GamingStationKind {
        switch rawValue.lowercased() {
        case "ps5", "playstation", "console":
            return .ps5
        case "vr":
            return .vr
        case "simulator", "sim":
            return .simulator
        case "projector", "theatre", "theater":
            return .projector
        case "hookah", "shisha":
            return .hookah
        case "streaming", "stream", "booth":
            return .streaming
        default:
            return .station
        }
    }

    var title: String {
        switch self {
        case .ps5: return "PS5"
        case .vr: return "VR"
        case .simulator: return "Simulator"
        case .projector: return "Projector"
        case .hookah: return "Shisha"
        case .streaming: return "Streaming"
        case .station: return "Station"
        }
    }

    var icon: String {
        switch self {
        case .ps5: return "gamecontroller.fill"
        case .vr: return "visionpro"
        case .simulator: return "steeringwheel"
        case .projector: return "projector.fill"
        case .hookah: return "timer"
        case .streaming: return "play.rectangle.fill"
        case .station: return "rectangle.on.rectangle"
        }
    }

    var participantLabel: String {
        switch self {
        case .ps5, .vr, .simulator, .station:
            return "Players"
        case .projector, .hookah, .streaming:
            return "Guests"
        }
    }

    var codePrefix: String {
        switch self {
        case .ps5: return "PS5"
        case .vr: return "VR"
        case .simulator: return "SIM"
        case .projector: return "PROJ"
        case .hookah: return "SHISHA"
        case .streaming: return "STREAM"
        case .station: return "SERV"
        }
    }

    var defaultRateMinor: Int {
        switch self {
        case .ps5: return 20_000
        case .vr: return 30_000
        case .simulator: return 35_000
        case .projector: return 30_000
        case .hookah: return 25_000
        case .streaming: return 30_000
        case .station: return 20_000
        }
    }

    func suggestedName(sequence: Int) -> String {
        switch self {
        case .ps5: return "PS5 Station \(sequence)"
        case .vr: return "VR Bay \(sequence)"
        case .simulator: return "Simulator \(sequence)"
        case .projector: return "Projector Room \(sequence)"
        case .hookah: return "Shisha Table \(sequence)"
        case .streaming: return "Sports Streaming \(sequence)"
        case .station: return "Service Station \(sequence)"
        }
    }
}

private struct GamingStationDTO: Decodable, Identifiable, Hashable {
    let id: String
    let code: String
    let name: String
    let type: String
    let rate_per_hour_minor: Int
    let is_active: Bool

    var kind: GamingStationKind {
        GamingStationKind.from(type)
    }
}

private struct GamingStationCreateRequest: Encodable {
    let code: String
    let name: String
    let type: String
    let rate_per_hour_minor: Int
    let branch_id: String?
    let notes: String?
}

private struct GamingStationUpdateRequest: Encodable {
    let name: String?
    let rate_per_hour_minor: Int?
    let is_active: Bool?
    let notes: String?
}

private struct StationEditorDraft: Identifiable {
    let id = UUID()
    var stationID: String?
    var code: String
    var name: String
    var kind: GamingStationKind
    var rateText: String
    var isActive: Bool
    var notes: String
    var branchID: String?

    var isNew: Bool {
        stationID == nil
    }

    static func blank(branchID: String?) -> StationEditorDraft {
        blank(branchID: branchID, kind: .ps5, code: "", sequence: 1)
    }

    static func blank(branchID: String?, kind: GamingStationKind, code: String, sequence: Int) -> StationEditorDraft {
        StationEditorDraft(
            stationID: nil,
            code: code,
            name: code.isEmpty ? "" : kind.suggestedName(sequence: sequence),
            kind: kind,
            rateText: code.isEmpty ? "" : currencyInput(kind.defaultRateMinor),
            isActive: true,
            notes: "",
            branchID: branchID
        )
    }

    static func editing(_ station: GamingStationDTO) -> StationEditorDraft {
        StationEditorDraft(
            stationID: station.id,
            code: station.code,
            name: station.name,
            kind: station.kind,
            rateText: currencyInput(station.rate_per_hour_minor),
            isActive: station.is_active,
            notes: "",
            branchID: nil
        )
    }
}

private struct GamingSessionStartRequest: Encodable {
    let station_id: String
    let shift_id: String
    let customer_name: String?
    let customer_phone: String?
    let timer_minutes: Int?
}

// Sends the new absolute timer length (minutes from start_at); nil clears
// the timer back to open-ended. There is no separate "extend" endpoint on
// the backend — setting a new value both sets and extends it.
private struct SessionTimerUpdateRequest: Encodable {
    let timer_minutes: Int?
}

private struct GamingSessionDTO: Decodable, Identifiable, Hashable {
    let id: String
    let station_id: String
    let status: String
    let start_at: Date
    let end_at: Date?
    let timer_minutes: Int?
    let timer_ends_at: Date?
    let billable_minutes: Int?
    let amount_minor: Int?
    let customer_name: String?
    let customer_phone: String?
    let rate_per_hour_minor: Int?
    let order_id: String?
}

// POST /gaming/sessions/{id}/send-to-pos returns a raw dict, not SessionRead.
private struct SendToPosResponseDTO: Decodable {
    let order_id: String
    let amount_minor: Int
}

// Gaming station bookings (backend/app/api/v1/gaming/router.py — GET/PATCH /gaming/bookings)
private struct GamingBookingDTO: Decodable, Identifiable, Hashable {
    let id: String
    let station_id: String
    let station_code: String
    let starts_at: Date
    let ends_at: Date
    let guest_name: String
    let contact: String?
    let party_size: Int
    let deposit_minor: Int
    let status: String
    // held | consumed | no_show | cancelled
}

private struct BookingStatusUpdateRequest: Encodable {
    let status: String
}

private struct GamingSessionDraft: Identifiable {
    let id = UUID()
    let station: GamingStationDTO
    var customerName = ""
    var customerPhone = ""
    var partySize = 2
    var timerMinutes: Int?

    var participantSummary: String {
        "\(partySize) \(station.kind.participantLabel.lowercased())"
    }
}

private enum TimerPreset: CaseIterable, Identifiable {
    case none, thirty, sixty, twoHours

    var id: Self { self }

    var minutes: Int? {
        switch self {
        case .none: return nil
        case .thirty: return 30
        case .sixty: return 60
        case .twoHours: return 120
        }
    }

    var title: String {
        switch self {
        case .none: return "No timer"
        case .thirty: return "30m"
        case .sixty: return "1h"
        case .twoHours: return "2h"
        }
    }
}

private struct OrderListItemDTO: Decodable, Identifiable {
    let id: String
    let invoice_no: String?
    let type: String?
    let status: String
    let table_id: String?
    let source_label: String?
    let total_minor: Int
    let items_count: Int
    let customer_name: String?
    let created_at: Date
    // Backend note: `held_at` is declared on OrderListItem but is not
    // actually populated by GET /pos/orders — always nil there even for
    // genuinely held orders. Don't rely on it in the queue list; it's only
    // meaningful on the single-order OrderReadDTO fetched via GET
    // /pos/orders/{id}.
    let held_at: Date?
}

private struct OrderLineReadDTO: Decodable, Identifiable {
    let menu_item_id: String
    let name: String
    let sku: String
    let hsn_or_sac: String
    let qty: Double
    let unit_price_minor: Int
    let line_total_minor: Int
    let taxable_value_minor: Int
    let tax_rate: Double
    let cgst_minor: Int
    let sgst_minor: Int
    let igst_minor: Int

    var id: String { menu_item_id }
}

private struct OrderReadDTO: Decodable, Identifiable {
    let id: String
    let invoice_no: String?
    let fiscal_year: String?
    let status: String
    let type: String
    let table_id: String?
    let source_label: String?
    let subtotal_minor: Int
    let discount_minor: Int
    let manual_discount_minor: Int
    let points_redeemed_minor: Int
    let points_redeemed: Int
    let cgst_minor: Int
    let sgst_minor: Int
    let igst_minor: Int
    let cess_minor: Int
    let tax_minor: Int
    let round_off_minor: Int
    let total_minor: Int
    let delivery_via: String?
    let place_of_supply_state_code: String?
    let customer_name: String?
    let customer_phone: String?
    let customer_gstin: String?
    let customer_state_code: String?
    let held_at: Date?
    let lines: [OrderLineReadDTO]
}

// POST /pos/orders/{id}/lines — append more lines to an open/held order
// (this is how items get added to a Tables order that's already been sent
// to POS, per the backend's "one-way" send-to-pos design).
private struct OrderLinesAppendRequest: Encodable {
    let lines: [OrderLineCreateRequest]
}

// DELETE /pos/orders/{id} — void a held order. Requires the shift's opener
// (or a protected owner); server enforces this, this is just the payload.
private struct VoidOrderRequest: Encodable {
    let reason: String
}

private struct OrderLineCreateRequest: Encodable {
    let menu_item_id: String
    let variant_id: String?
    let qty: Double
    let modifiers: [[String: String]]?
    let note: String?
}

private struct OrderCreateRequest: Encodable {
    let type: String
    let table_id: String?
    let shift_id: String
    let lines: [OrderLineCreateRequest]
    let delivery_via: String?
    let customer_name: String?
    let customer_phone: String?
    let customer_gstin: String?
    let customer_address: String?
    let customer_state_code: String?
    let place_of_supply_state_code: String?
    let notes: String?
}

private struct OrderDiscountRequest: Encodable {
    let manual_discount_minor: Int
}

private struct OrderPointsRequest: Encodable {
    let points: Int
}

private struct PaymentCreateRequest: Encodable {
    let method: String
    let amount_minor: Int
    let tendered_minor: Int?
    let ref_external: String?
    // Additional money collected on top of the bill — never folded into
    // amount_minor, and never part of the exact-amount-due match on the
    // server (see _validate_confirmed_payment_balance in
    // app/api/v1/pos/router.py).
    let tip_minor: Int
}

private struct PaymentResponseDTO: Decodable {
    let id: String
    let amount_minor: Int
    let order_status: String
}

private struct CustomerDTO: Decodable, Identifiable {
    let id: String
    let name: String?
    let phone: String
    let email: String?
    let birthday: Date?
    let visit_count: Int
    let total_spent_minor: Int
    let loyalty_points: Int
    let last_visit_at: Date?
    let notes: String?
}

private struct StaffUserDTO: Decodable, Identifiable {
    let id: String
    let email: String
    let name: String
    let phone: String?
    let status: String
    let roles: [String]
    let last_login_at: Date?
}

// CustomerUpsert doubles as create (phone is the natural key server-side).
private struct CustomerUpsertRequest: Encodable {
    let phone: String
    let name: String?
    let email: String?
    let birthday: String?
    let notes: String?
}

private struct CustomerUpdateRequest: Encodable {
    let name: String?
    let email: String?
    let birthday: String?
    let notes: String?
}

// PATCH /staff/users/{id} works directly; POST (create) and the password
// endpoint always 422 with "OTP approval is required" by design — both
// route through the same auth/register + auth/password-reset OTP flow
// LoginView already implements.
private struct StaffUserUpdateRequest: Encodable {
    let name: String?
    let phone: String?
    let status: String?
    let role_code: String?
}

private let staffRoleCodes = ["owner", "partner", "manager", "cashier", "kitchen", "gaming_supervisor", "auditor"]

// Attendance — clock in/out + who's on shift right now (mirrors StaffScreen.tsx).
private struct ClockInRequest: Encodable {
    let branch_id: String
    let notes: String?
}

private struct ClockInResponse: Decodable {
    let id: String
}

private struct ClockOutResponse: Decodable {
    let id: String
    let clock_out_at: Date
}

private struct OnShiftDTO: Decodable, Identifiable {
    let id: String
    let user_id: String
    let user_name: String?
    let user_email: String?
    let branch_id: String
    let branch_name: String?
    let clock_in_at: Date
    let notes: String?
}

private struct CompanyDTO: Decodable, Identifiable {
    let id: String
    let name: String
    let legal_name: String?
    let currency: String
    let timezone: String
    let country: String?
    let gstin: String?
    let pan: String?
    let gst_registration_type: String
    let is_composition: Bool
    let e_invoicing_enabled: Bool
    let fiscal_year_start_month: Int
    let upi_vpa: String?
}

// Tables module (backend/app/models/tables.py, backend/app/api/v1/tables/router.py)
private struct TableFloorDTO: Decodable, Identifiable, Hashable {
    let id: String
    let name: String
}

private struct TableDTO: Decodable, Identifiable, Hashable {
    let id: String
    let floor_id: String
    let code: String
    let seats: Int
    let shape: String
    let status: String
    // available | occupied | reserved | cleaning | merged
}

private struct TableStatusUpdateRequest: Encodable {
    let status: String
}

// Table reservations (backend/app/api/v1/tables/router.py — GET/PATCH /tables/reservations)
private struct ReservationDTO: Decodable, Identifiable, Hashable {
    let id: String
    let table_id: String
    let table_code: String
    let guest_name: String
    let party_size: Int
    let contact: String?
    let starts_at: Date
    let ends_at: Date
    let notes: String?
    let status: String
    // held | seated | no_show | cancelled
}

private struct ReservationStatusUpdateRequest: Encodable {
    let status: String
}

// Kitchen / KDS (backend/app/api/v1/kitchen/router.py)
private struct KitchenLineDTO: Decodable, Identifiable, Hashable {
    let menu_item_id: String
    let name: String
    let type: String
    let qty: Double
    let notes: String?

    var id: String { menu_item_id }
}

private struct KitchenOrderDTO: Decodable, Identifiable, Hashable {
    let id: String
    let invoice_no: String?
    let type: String
    let table_code: String?
    let customer_name: String?
    let opened_at: Date
    let kitchen_state: String
    let minutes_waiting: Int
    let lines: [KitchenLineDTO]

    static func == (lhs: KitchenOrderDTO, rhs: KitchenOrderDTO) -> Bool {
        lhs.id == rhs.id && lhs.kitchen_state == rhs.kitchen_state && lhs.minutes_waiting == rhs.minutes_waiting
    }

    func hash(into hasher: inout Hasher) {
        hasher.combine(id)
        hasher.combine(kitchen_state)
    }
}

private struct KitchenStateUpdateRequest: Encodable {
    let state: String
}

// Access Control panel (backend/app/api/v1/admin/router.py, protected-owner only)
private struct AccessCellDTO: Decodable, Identifiable, Hashable {
    let role_code: String
    let module: String
    let default_allowed: Bool
    let override: Bool?
    let allowed: Bool

    var id: String { "\(role_code):\(module)" }
}

private struct AccessControlDTO: Decodable {
    let roles: [String: String]
    let modules: [String]
    let cells: [AccessCellDTO]
}

private struct AccessControlUpdateRequest: Encodable {
    let role_code: String
    let module: String
    let allowed: Bool?

    // Swift's auto-synthesized Encodable OMITS a nil Optional key entirely
    // (encodeIfPresent), but the backend's AccessControlUpdate.allowed field
    // is required-but-nullable — omitting the key gets a 422, not the
    // "revert to default" behavior. Explicitly encode null instead.
    enum CodingKeys: String, CodingKey { case role_code, module, allowed }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(role_code, forKey: .role_code)
        try container.encode(module, forKey: .module)
        if let allowed {
            try container.encode(allowed, forKey: .allowed)
        } else {
            try container.encodeNil(forKey: .allowed)
        }
    }
}

private struct BranchDTO: Decodable, Identifiable {
    let id: String
    let name: String
    let code: String?
    let address: String?
    let timezone: String?
    let opens_at: String?
    let closes_at: String?
    let state_code: String?
    let fssai_license_no: String?
    let trade_license_no: String?
    let branch_gstin: String?
}

private struct BranchWriteRequest: Encodable {
    let name: String?
    let code: String?
    let address: String?
    let timezone: String?
    let opens_at: String?
    let closes_at: String?
    let state_code: String?
    let fssai_license_no: String?
    let trade_license_no: String?
    let branch_gstin: String?
}

private struct CompanyUpdateRequest: Encodable {
    let name: String?
    let legal_name: String?
    let gstin: String?
    let pan: String?
    let gst_registration_type: String?
    let upi_vpa: String?
}

private struct OfflineSnapshot: Codable {
    let version: Int
    let savedAt: Date
    let categories: [MenuCategoryDTO]
    let menuItems: [MenuItemDTO]
    let ingredients: [IngredientDTO]
    let dailyReport: ReportDTO?
}

private final class OfflineSnapshotStore {
    static let shared = OfflineSnapshotStore()

    private let fileURL: URL

    private init() {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first!
            .appendingPathComponent("DCompanyERP", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        fileURL = directory.appendingPathComponent("offline-snapshot.json")
    }

    func load() async -> OfflineSnapshot? {
        let url = fileURL
        return await Task.detached(priority: .utility) {
            guard let data = try? Data(contentsOf: url) else { return nil }
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            return try? decoder.decode(OfflineSnapshot.self, from: data)
        }.value
    }

    func save(categories: [MenuCategoryDTO], menuItems: [MenuItemDTO], ingredients: [IngredientDTO], dailyReport: ReportDTO?) async {
        let url = fileURL
        let snapshot = OfflineSnapshot(
            version: 1,
            savedAt: Date(),
            categories: categories,
            menuItems: menuItems,
            ingredients: ingredients,
            dailyReport: dailyReport
        )
        await Task.detached(priority: .utility) {
            let encoder = JSONEncoder()
            encoder.dateEncodingStrategy = .iso8601
            guard let data = try? encoder.encode(snapshot) else { return }
            try? data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }.value
    }

    func clear() async {
        let url = fileURL
        await Task.detached(priority: .utility) {
            try? FileManager.default.removeItem(at: url)
        }.value
    }
}

// Mirrors frontend/src/modules/pos/receipt-business.ts buildUpiPayLink() —
// same upi://pay?pa=...&pn=...&am=...&cu=INR&tn=... link, generated
// client-side (there is no backend endpoint for this).
private func buildUpiPayLink(upiVpa: String?, businessName: String, amountMinor: Int, note: String? = nil) -> String? {
    guard let upiVpa, !upiVpa.isEmpty else { return nil }
    let amount = String(format: "%.2f", Double(amountMinor) / 100.0)
    var parts = [
        "pa=\(upiVpa)",
        "pn=\(businessName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? businessName)",
        "am=\(amount)",
        "cu=INR"
    ]
    if let note = note?.trimmingCharacters(in: .whitespacesAndNewlines), !note.isEmpty {
        parts.append("tn=\(note.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? note)")
    }
    return "upi://pay?\(parts.joined(separator: "&"))"
}

private func generateQRImage(from string: String) -> UIImage? {
    guard let data = string.data(using: .utf8),
          let filter = CIFilter(name: "CIQRCodeGenerator") else { return nil }
    filter.setValue(data, forKey: "inputMessage")
    filter.setValue("M", forKey: "inputCorrectionLevel")
    guard let outputImage = filter.outputImage else { return nil }
    let scale = 8.0
    let transformed = outputImage.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
    let context = CIContext()
    guard let cgImage = context.createCGImage(transformed, from: transformed.extent) else { return nil }
    return UIImage(cgImage: cgImage)
}

private struct UpiQRView: View {
    let upiVpa: String?
    let businessName: String
    let amountMinor: Int

    var body: some View {
        VStack(spacing: 10) {
            if let link = buildUpiPayLink(upiVpa: upiVpa, businessName: businessName, amountMinor: amountMinor),
               let image = generateQRImage(from: link) {
                Image(uiImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 180, height: 180)
                    .padding(10)
                    .background(Color.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                Text("Scan to pay \(inr(amountMinor)) via UPI")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Brand.muted)
            } else {
                InlineEmptyRow(icon: "qrcode", title: "No UPI ID configured", subtitle: "Add a UPI VPA in Settings to accept scan-to-pay.")
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

// Bundles the compliance-relevant context a receipt needs beyond the order
// itself — mirrors buildReceiptBusinessDetails() in
// frontend/src/modules/pos/receipt-business.ts.
private struct ReceiptContext {
    let company: CompanyDTO?
    let cashierName: String?
}

// Converts an integer rupee amount to English words, Indian numbering
// (lakh/crore, not thousand/million) — required on a compliant Tax Invoice.
private func amountInWords(rupees: Int) -> String {
    guard rupees > 0 else { return "Zero Rupees Only" }
    let ones = ["", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
                "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"]
    let tens = ["", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"]

    func twoDigits(_ n: Int) -> String {
        if n == 0 { return "" }
        if n < 20 { return ones[n] }
        let t = n / 10, o = n % 10
        return tens[t] + (o > 0 ? " " + ones[o] : "")
    }

    func threeDigits(_ n: Int) -> String {
        if n < 100 { return twoDigits(n) }
        let h = n / 100, rest = n % 100
        return ones[h] + " Hundred" + (rest > 0 ? " " + twoDigits(rest) : "")
    }

    var n = rupees
    var parts: [String] = []
    let crore = n / 10_000_000; n %= 10_000_000
    let lakh = n / 100_000; n %= 100_000
    let thousand = n / 1_000; n %= 1_000
    let hundred = n

    if crore > 0 { parts.append(threeDigits(crore) + " Crore") }
    if lakh > 0 { parts.append(twoDigits(lakh) + " Lakh") }
    if thousand > 0 { parts.append(twoDigits(thousand) + " Thousand") }
    if hundred > 0 { parts.append(threeDigits(hundred)) }

    return parts.joined(separator: " ") + " Rupees Only"
}

// Mirrors ReportsScreen.tsx's window.print() with a real AirPrint sheet
// instead of a browser print dialog — same content, native mechanism.
private enum ReportPrinter {
    @MainActor
    static func print(report: ReportDTO, periodTitle: String) {
        let controller = UIPrintInteractionController.shared
        let printInfo = UIPrintInfo(dictionary: nil)
        printInfo.outputType = .general
        printInfo.jobName = "D Company \(periodTitle) P&L — \(report.label)"
        controller.printInfo = printInfo
        controller.printFormatter = UISimpleTextPrintFormatter(text: reportText(for: report, periodTitle: periodTitle))
        controller.present(animated: true)
    }

    private static func reportText(for r: ReportDTO, periodTitle: String) -> String {
        var lines: [String] = [
            "D COMPANY ERP",
            "\(periodTitle) P&L — \(r.label)",
            "\(DateFormatters.apiDateOnly.string(from: r.period_start)) to \(DateFormatters.apiDateOnly.string(from: r.period_end)) · FY \(r.fiscal_year)",
            String(repeating: "=", count: 32),
            "",
            "REVENUE",
            row("Food / drinks / desserts", r.revenue.food_minor),
            row("Gaming", r.revenue.gaming_minor)
        ]
        if r.revenue.hookah_minor > 0 { lines.append(row("Hookah", r.revenue.hookah_minor)) }
        lines.append(row("Event tickets", r.revenue.event_tickets_minor))
        lines.append(row("Delivery (aggregator, Sec 9(5))", r.revenue.delivery_aggregator_minor))
        if r.revenue.other_minor > 0 { lines.append(row("Other", r.revenue.other_minor)) }
        if (r.revenue.manual_collections_minor ?? 0) > 0 {
            lines.append(row("Manual collections (unitemized)", r.revenue.manual_collections_minor ?? 0))
        }
        if (r.revenue.discounts_and_points_redeemed_minor ?? 0) > 0 {
            lines.append(row("Less: discounts and points", -(r.revenue.discounts_and_points_redeemed_minor ?? 0)))
        }
        lines.append(String(repeating: "-", count: 32))
        lines.append(row("Gross revenue", r.revenue.total_minor, bold: true))
        if r.refunds_issued_minor > 0 { lines.append(row("Less: refunds", -r.refunds_issued_minor)) }
        lines.append(row("Less: GST collected", -r.tax_collected.total_minor))
        lines.append(String(repeating: "-", count: 32))
        lines.append(row("Net revenue (after GST)", r.net_revenue_minor, bold: true))
        lines.append(row("Less: cost of goods sold", -r.cogs_minor))
        lines.append(String(repeating: "-", count: 32))
        lines.append(row("Gross profit", r.gross_profit_minor, bold: true))
        lines.append("")

        lines.append("GST COLLECTED")
        lines.append(row("CGST", r.tax_collected.cgst_minor))
        lines.append(row("SGST", r.tax_collected.sgst_minor))
        if r.tax_collected.igst_minor > 0 { lines.append(row("IGST (inter-state)", r.tax_collected.igst_minor)) }
        if r.tax_collected.cess_minor > 0 { lines.append(row("Cess", r.tax_collected.cess_minor)) }
        lines.append(String(repeating: "-", count: 32))
        lines.append(row("Total GST", r.tax_collected.total_minor, bold: true))
        lines.append("")

        lines.append("PAYMENT MOVEMENT")
        lines.append(row("Cash", r.payments_received.cash_minor))
        lines.append(row("UPI", r.payments_received.upi_minor))
        lines.append(row("Card", r.payments_received.card_minor))
        if (r.payments_received.bank_minor ?? 0) > 0 {
            lines.append(row("Bank transfer", r.payments_received.bank_minor ?? 0))
        }
        lines.append(row("QR", r.payments_received.qr_minor))
        lines.append(row("Wallet", r.payments_received.wallet_minor))
        if r.payments_received.other_minor > 0 { lines.append(row("Other", r.payments_received.other_minor)) }
        lines.append(String(repeating: "-", count: 32))
        lines.append(row("Gross payments collected", r.payments_received.total_minor))
        if r.refunds_issued_minor > 0 { lines.append(row("Less: refunds issued", -r.refunds_issued_minor)) }
        lines.append(String(repeating: "-", count: 32))
        lines.append(row("Net payment movement", r.net_payments_received_minor, bold: true))
        lines.append("")

        lines.append("EXPENSES")
        if r.expenses.isEmpty {
            lines.append("No expenses recorded in this period.")
        } else {
            for expense in r.expenses {
                lines.append(row(expense.category, expense.amount_minor))
            }
            lines.append(String(repeating: "-", count: 32))
            lines.append(row("Total expenses", r.expense_total_minor, bold: true))
        }
        lines.append("")

        lines.append(String(repeating: "=", count: 32))
        lines.append(row("Net revenue", r.net_revenue_minor, bold: true))
        lines.append(row("COGS + expenses", r.cogs_minor + r.expense_total_minor, bold: true))
        lines.append(row("Net profit", r.net_profit_minor, bold: true))
        lines.append(String(repeating: "=", count: 32))
        lines.append("")
        lines.append("Generated \(DateFormatters.shortDateTime.string(from: Date())) · D Company ERP")
        lines.append("Computed live from journal entries and order data.")

        return lines.joined(separator: "\n")
    }

    private static func row(_ label: String, _ minor: Int, bold: Bool = false) -> String {
        let amount = inr(abs(minor))
        let sign = minor < 0 ? "-" : ""
        let text = "\(label): \(sign)\(amount)"
        return bold ? text.uppercased() : text
    }
}

// A downloaded CSV export (GSTR-1, GSTR-3B, analytics period export),
// written to a temp file so the system share sheet below can present it —
// UIActivityViewController wants a file/URL to offer "Save to Files",
// Mail, AirDrop, etc., not raw in-memory Data.
private struct ShareableFile: Identifiable {
    let id = UUID()
    let url: URL
}

private enum CSVExporter {
    /// Write CSV bytes to a temp file named `filename` and return it ready
    /// for `ActivityShareSheet`. Overwrites any stale file left from a
    /// previous export under the same name.
    static func write(_ data: Data, filename: String) throws -> ShareableFile {
        let directory = FileManager.default.temporaryDirectory
        let url = directory.appendingPathComponent(filename)
        if FileManager.default.fileExists(atPath: url.path) {
            try? FileManager.default.removeItem(at: url)
        }
        try data.write(to: url, options: .atomic)
        return ShareableFile(url: url)
    }
}

// Wraps UIActivityViewController (the standard iOS share sheet) for
// SwiftUI's `.sheet(item:)` — presented the same way GSheetsWebhookSheet
// and other sheets on this screen are.
private struct ActivityShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

private enum ReceiptPrinter {
    @MainActor
    static func print(order: OrderReadDTO, context: ReceiptContext = ReceiptContext(company: nil, cashierName: nil)) {
        present(text: receiptText(for: order, context: context), jobName: "D Company \(order.invoice_no ?? order.id.prefix(8).description)")
    }

    @MainActor
    static func printTestPage() {
        present(
            text: [
                "D Company ERP",
                "Printer test",
                DateFormatters.shortDateTime.string(from: Date()),
                "",
                "AirPrint is available from this device.",
                "Vendor thermal-printer SDK can be connected after the exact printer model is selected."
            ].joined(separator: "\n"),
            jobName: "D Company Printer Test"
        )
    }

    @MainActor
    private static func present(text: String, jobName: String) {
        let controller = UIPrintInteractionController.shared
        let printInfo = UIPrintInfo(dictionary: nil)
        printInfo.outputType = .general
        printInfo.jobName = jobName
        controller.printInfo = printInfo
        controller.printFormatter = UISimpleTextPrintFormatter(text: text)
        controller.present(animated: true)
    }

    private static func receiptText(for order: OrderReadDTO, context: ReceiptContext) -> String {
        let company = context.company
        let isGSTRegistered = company.map { $0.gst_registration_type != "unregistered" } ?? false
        let documentTitle = isGSTRegistered ? "TAX INVOICE" : "BILL OF SUPPLY"

        var lines: [String] = [
            company?.legal_name?.nilIfBlank ?? company?.name ?? "D Company",
            "Cafe | Games | Lounge | After Dark"
        ]
        if let gstin = company?.gstin?.nilIfBlank {
            lines.append("GSTIN: \(gstin)")
        }
        lines.append(String(repeating: "=", count: 32))
        lines.append(documentTitle)
        lines.append(String(repeating: "=", count: 32))
        lines.append("Invoice: \(order.invoice_no ?? order.id)")
        lines.append("Type: \(order.type.replacingOccurrences(of: "_", with: " ").capitalized)")
        if let cashier = context.cashierName?.nilIfBlank {
            lines.append("Served by: \(cashier)")
        }
        if let name = order.customer_name?.nilIfBlank {
            var customerLine = "Customer: \(name)"
            if let phone = order.customer_phone?.nilIfBlank { customerLine += " (\(phone))" }
            lines.append(customerLine)
        }
        if let gstin = order.customer_gstin?.nilIfBlank {
            lines.append("Customer GSTIN: \(gstin)")
        }
        if let placeOfSupply = order.place_of_supply_state_code?.nilIfBlank {
            lines.append("Place of supply: \(placeOfSupply)")
        }
        if let deliveryVia = order.delivery_via, deliveryVia != "inhouse" {
            lines.append("Via: \(deliveryVia.capitalized) (platform pays GST — Sec 9(5))")
        }
        lines.append(String(repeating: "-", count: 32))

        for item in order.lines {
            let quantity = NumberFormatters.decimal.string(from: NSNumber(value: item.qty)) ?? "\(item.qty)"
            lines.append("\(item.name) x \(quantity)  [HSN/SAC \(item.hsn_or_sac)]")
            lines.append("  \(inr(item.line_total_minor))")
        }

        lines.append(String(repeating: "-", count: 32))
        lines.append("Subtotal: \(inr(order.subtotal_minor))")
        if order.discount_minor > 0 {
            lines.append("Discount: -\(inr(order.discount_minor))")
        }
        if isGSTRegistered {
            lines.append("CGST: \(inr(order.cgst_minor))")
            lines.append("SGST: \(inr(order.sgst_minor))")
            if order.igst_minor > 0 {
                lines.append("IGST: \(inr(order.igst_minor))")
            }
            if order.cess_minor > 0 {
                lines.append("Cess: \(inr(order.cess_minor))")
            }
        }
        lines.append("Round off: \(inr(order.round_off_minor))")
        lines.append("Total: \(inr(order.total_minor))")
        lines.append(amountInWords(rupees: order.total_minor / 100))
        lines.append("")
        lines.append("Thank you.")
        return lines.joined(separator: "\n")
    }
}

private struct TerminalIntegrationStatus {
    let provider: String
    let isConfigured: Bool
    let detail: String

    static let current = TerminalIntegrationStatus(
        provider: "Manual POS",
        isConfigured: false,
        detail: "Cash, UPI, QR, wallet, and card payments are recorded in ERP. A certified terminal SDK still needs the selected provider credentials and hardware."
    )
}

@MainActor
private final class AppCache: ObservableObject {
    @Published var categories: [MenuCategoryDTO] = []
    @Published var menuItems: [MenuItemDTO] = []
    @Published var ingredients: [IngredientDTO] = []
    @Published var dailyReport: ReportDTO?
    @Published var lastSyncedAt: Date?

    var hasMenuData: Bool {
        !categories.isEmpty || !menuItems.isEmpty
    }

    var hasInventoryData: Bool {
        !ingredients.isEmpty
    }

    func restoreFromDisk() async {
        guard let snapshot = await OfflineSnapshotStore.shared.load() else { return }
        categories = snapshot.categories
        menuItems = snapshot.menuItems
        ingredients = snapshot.ingredients
        dailyReport = snapshot.dailyReport
        lastSyncedAt = snapshot.savedAt
    }

    func markSynced() async {
        lastSyncedAt = Date()
        await OfflineSnapshotStore.shared.save(
            categories: categories,
            menuItems: menuItems,
            ingredients: ingredients,
            dailyReport: dailyReport
        )
    }

    func clear() {
        categories = []
        menuItems = []
        ingredients = []
        dailyReport = nil
        lastSyncedAt = nil
        Task {
            await OfflineSnapshotStore.shared.clear()
        }
    }
}

@MainActor
// Mirrors frontend/src/lib/operational-context.ts resolveTerminal(). A
// device must be pinned to exactly one terminal per branch so orders/shifts
// are stamped correctly — without this, a branch with more than one
// registered terminal has no way to know which one a given device/session
// should use, and picking "terminals.first" silently mislabels data.
private enum TerminalResolution {
    case ready(terminalId: String, source: String) // source: "stored" | "single"
    case missingBranch
    case noTerminals
    case selectionRequired(terminals: [TerminalDTO])
}

private func resolveTerminal(branchId: String?, storedTerminalId: String?, terminals: [TerminalDTO]) -> TerminalResolution {
    guard let branchId else { return .missingBranch }
    let matching = terminals.filter { $0.branch_id == branchId }
    if let storedTerminalId, matching.contains(where: { $0.id == storedTerminalId }) {
        return .ready(terminalId: storedTerminalId, source: "stored")
    }
    if matching.count == 1 {
        return .ready(terminalId: matching[0].id, source: "single")
    }
    if matching.isEmpty { return .noTerminals }
    return .selectionRequired(terminals: matching)
}

private func terminalResolutionMessage(_ resolution: TerminalResolution) -> String {
    switch resolution {
    case .missingBranch:
        return "This account has no branch assigned. Assign a branch before using POS or Gaming."
    case .noTerminals:
        return "No POS terminal exists for this branch. Ask a protected owner to configure one before using POS or Gaming."
    case .selectionRequired:
        return "Multiple POS terminals exist for this branch. Select the terminal used by this device."
    case .ready:
        return ""
    }
}

// Terminal id is per-device, not secret — UserDefaults (matching web's
// localStorage) is the right store, unlike TokenStore's Keychain use for
// actual credentials.
private enum TerminalStore {
    private static let key = "dcompany.erp.terminal_id"

    static func read() -> String? {
        UserDefaults.standard.string(forKey: key)
    }

    static func save(_ terminalId: String) {
        UserDefaults.standard.set(terminalId, forKey: key)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: key)
    }
}

// Mirrors frontend/src/modules/settings/apps-script.txt exactly — the Apps
// Script the user pastes into Extensions → Apps Script to receive webhook
// pushes. Embedded here so the setup wizard can copy it without a network
// round-trip to the web app.
private let dCompanyAppsScript = """
/**
 * D Company ERP — Google Sheets sink (single-tab mode).
 *
 * INSTALLATION (only needed once):
 *   1. Open the Google Sheet you want to use.
 *   2. Extensions → Apps Script. A new tab opens.
 *   3. Replace the empty Code.gs content with EVERYTHING in this file.
 *   4. Click the disk icon (save).
 *   5. Click Deploy → New deployment → Type: Web app.
 *        • Description:     "D Company ERP webhook"
 *        • Execute as:      Me
 *        • Who has access:  Anyone with the link
 *   6. Click Deploy. Authorize when prompted (the "this app isn't verified"
 *      warning is normal for personal scripts — click Advanced → Go to ...
 *      (unsafe), then Allow).
 *   7. Copy the Web app URL it gives you.
 *   8. In D Company ERP open Settings → Google Sheets and paste the URL.
 *
 * BEHAVIOR
 *   • Every order, ticket, event, and report is appended as ONE row in the
 *     single tab named "Operations" (the name is configurable below).
 *   • Your existing tabs in this Sheet are NEVER touched.
 *   • Rows are idempotent on the unique id column: if the same invoice/ticket
 *     id arrives twice, the existing row is overwritten in place — no dupes.
 *
 * COLUMN LAYOUT (one row per ERP entry)
 *   Date | Time | Type | ID | Description | Customer | Qty | Taxable | CGST |
 *   SGST | IGST | Round-off | Total | Method | Cashier | GSTIN | Place of supply
 *
 *   "Type" is one of: Order, Ticket, Event, Daily Report, Monthly Report,
 *   Quarterly Report, Yearly Report.
 */

const TAB_NAME = "Operations";

const HEADERS = [
  "Date", "Time", "Type", "ID", "Description", "Customer",
  "Qty", "Taxable (₹)", "CGST (₹)", "SGST (₹)", "IGST (₹)",
  "Round-off (₹)", "Total (₹)", "Method", "Cashier",
  "GSTIN", "Place of supply",
];

const TYPE_LABEL = {
  order:             "Order",
  ticket:            "Ticket",
  event:             "Event",
  daily_report:      "Daily Report",
  monthly_report:    "Monthly Report",
  quarterly_report:  "Quarterly Report",
  yearly_report:     "Yearly Report",
  ping:              "Ping",
};

// =============================================================================
// Entrypoints
// =============================================================================
function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    const { kind, payload } = body;
    if (kind === "ping") {
      return json({ ok: true, kind: "ping", tab: TAB_NAME });
    }
    if (!(kind in TYPE_LABEL)) {
      return json({ ok: false, error: "unknown kind: " + kind }, 400);
    }
    const sheet = ensureSheet(TAB_NAME, HEADERS);
    const row = projectRow(kind, payload);
    upsertRow(sheet, /* idColumn = */ 4, row);  // column D = ID
    return json({ ok: true, kind, id: row[3] });
  } catch (err) {
    return json({ ok: false, error: String(err) }, 500);
  }
}

function doGet() {
  return json({
    ok: true,
    service: "D Company ERP webhook",
    mode: "single-tab",
    tab: TAB_NAME,
  });
}

// =============================================================================
// Row projection — keep every row the same width so SUM/QUERY/AVERAGE work.
// =============================================================================
function projectRow(kind, p) {
  const date  = p.date  || "";
  const time  = p.time  || "";
  const label = TYPE_LABEL[kind];

  // Orders, tickets, events all share most columns
  if (kind === "order") {
    return [
      date, time, label, p.invoice_no || "",
      p.items_text || "", p.customer_name || "",
      p.items_count || 0,
      money(p.taxable_minor), money(p.cgst_minor), money(p.sgst_minor),
      money(p.igst_minor || 0), money(p.round_off_minor || 0),
      money(p.total_minor),
      p.method || "", p.cashier || "",
      p.gstin || "", p.place_of_supply || "",
    ];
  }

  if (kind === "ticket") {
    return [
      date, time, label, p.ticket_no || "",
      p.event_name || "", p.customer_name || "",
      1,
      money(p.taxable_minor), money(p.cgst_minor), money(p.sgst_minor),
      money(0), money(0), money(p.price_paid_minor),
      "", "", "", "",
    ];
  }

  if (kind === "event") {
    return [
      date, time, label, p.id || "",
      p.name || "", "",
      p.capacity || 0,
      money(0), money(0), money(0),
      money(0), money(0), money(p.base_ticket_price_minor),
      "", "", "", "",
    ];
  }

  // Reports use the Description column for the period summary and the
  // money columns for revenue / GST / total.
  if (kind === "daily_report" || kind === "monthly_report" ||
      kind === "quarterly_report" || kind === "yearly_report") {
    const sub = subRevenueText(p);
    return [
      date, time, label, p.period_id || p.label || "",
      sub,
      "",                                  // customer
      p.orders_count || 0,                 // qty = order count
      money(p.net_revenue_minor || 0),     // taxable = net revenue
      money(p.cgst_minor),
      money(p.sgst_minor),
      money(p.igst_minor || 0),
      money(0),
      money(p.gross_revenue_minor),
      p.expense_label || ("Expenses: " + money(p.expense_total_minor || 0)),
      "",                                  // cashier (n/a)
      "",                                  // gstin
      "",                                  // place of supply
    ];
  }

  throw new Error("unknown kind: " + kind);
}

function subRevenueText(p) {
  const parts = [];
  if (p.food_minor)              parts.push("Food " + money(p.food_minor));
  if (p.gaming_minor)            parts.push("Gaming " + money(p.gaming_minor));
  if (p.event_tickets_minor)     parts.push("Events " + money(p.event_tickets_minor));
  if (p.delivery_minor)          parts.push("Delivery " + money(p.delivery_minor));
  const profit = p.net_profit_minor;
  if (profit !== undefined && profit !== null) parts.push("Net " + money(profit));
  return parts.join(" · ");
}

// =============================================================================
// Sheet helpers
// =============================================================================
function ensureSheet(name, headers) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(name);

  if (!sheet) {
    // Tab doesn't exist — create with full ERP headers
    sheet = ss.insertSheet(name);
    writeHeaderRow(sheet, headers);
    return sheet;
  }

  // Tab exists — make sure row 1 matches our headers. If the first cell is
  // empty, OR holds a placeholder (e.g. "OPERATIONS COST"), OR the row has
  // fewer columns than we need, we (re)write the header row.
  const firstCell = sheet.getRange(1, 1).getValue();
  const isPlaceholder =
    !firstCell ||
    String(firstCell).trim().toUpperCase() === "OPERATIONS COST" ||
    sheet.getLastColumn() < headers.length;

  if (isPlaceholder && headers[0] && String(firstCell) !== headers[0]) {
    writeHeaderRow(sheet, headers);
  }
  return sheet;
}

function writeHeaderRow(sheet, headers) {
  sheet.getRange(1, 1, 1, headers.length).setValues([headers])
    .setFontWeight("bold").setBackground("#1e2a4d").setFontColor("#ffffff");
  sheet.setFrozenRows(1);
  sheet.setColumnWidth(3, 90);    // Type
  sheet.setColumnWidth(4, 170);   // ID
  sheet.setColumnWidth(5, 240);   // Description
}

function upsertRow(sheet, idColumn, row) {
  const id = row[idColumn - 1];
  if (!id) {
    sheet.appendRow(row);
    return;
  }
  const lastRow = sheet.getLastRow();
  if (lastRow > 1) {
    const ids = sheet.getRange(2, idColumn, lastRow - 1, 1).getValues();
    for (let i = 0; i < ids.length; i++) {
      if (String(ids[i][0]) === String(id)) {
        sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
        return;
      }
    }
  }
  sheet.appendRow(row);
}

function money(minor) {
  return ((minor || 0) / 100);
}

function json(obj, code) {
  const out = ContentService.createTextOutput(JSON.stringify(obj));
  out.setMimeType(ContentService.MimeType.JSON);
  return out;
}
"""

private enum GSheetsStore {
    private static let urlKey = "dcompany.erp.gsheets_webhook_url"
    private static let lastSyncKey = "dcompany.erp.gsheets_last_sync_at"
    private static let lastErrorKey = "dcompany.erp.gsheets_last_error"

    static func readURL() -> String? {
        UserDefaults.standard.string(forKey: urlKey)
    }

    static func save(url: String) {
        UserDefaults.standard.set(url, forKey: urlKey)
    }

    static func clear() {
        UserDefaults.standard.removeObject(forKey: urlKey)
        UserDefaults.standard.removeObject(forKey: lastSyncKey)
        UserDefaults.standard.removeObject(forKey: lastErrorKey)
    }

    static var lastSyncAt: String? {
        UserDefaults.standard.string(forKey: lastSyncKey)
    }

    static var lastError: String? {
        UserDefaults.standard.string(forKey: lastErrorKey)
    }

    static func markSync(error: String?) {
        UserDefaults.standard.set(ISO8601DateFormatter().string(from: Date()), forKey: lastSyncKey)
        if let error {
            UserDefaults.standard.set(error, forKey: lastErrorKey)
        } else {
            UserDefaults.standard.removeObject(forKey: lastErrorKey)
        }
    }
}

// POSTs to the Google Apps Script webhook as text/plain — Apps Script parses
// the JSON body itself, which sidesteps the CORS preflight browsers/URLSession
// would otherwise block. Matches frontend/src/lib/google-sheets.ts exactly.
private enum GSheetsPusher {
    struct PushEnvelope<Payload: Encodable>: Encodable {
        let kind: String
        let payload: Payload
    }

    static func push<Payload: Encodable>(kind: String, payload: Payload) async -> Bool {
        guard let urlString = GSheetsStore.readURL(), let url = URL(string: urlString) else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("text/plain;charset=utf-8", forHTTPHeaderField: "Content-Type")
        do {
            let encoder = JSONEncoder()
            request.httpBody = try encoder.encode(PushEnvelope(kind: kind, payload: payload))
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                GSheetsStore.markSync(error: "No response")
                return false
            }
            guard (200...299).contains(http.statusCode) else {
                GSheetsStore.markSync(error: "HTTP \(http.statusCode)")
                return false
            }
            if let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let ok = (object["ok"] as? Bool) ?? true
                if ok {
                    GSheetsStore.markSync(error: nil)
                } else {
                    GSheetsStore.markSync(error: (object["error"] as? String) ?? "unknown error")
                }
                return ok
            }
            GSheetsStore.markSync(error: nil)
            return true
        } catch {
            GSheetsStore.markSync(error: error.localizedDescription)
            return false
        }
    }

    static func testConnection(url urlString: String) async -> (ok: Bool, message: String) {
        guard urlString.hasPrefix("https://script.google.com/") else {
            return (false, "URL must start with https://script.google.com/macros/s/ — make sure you copied the WEB APP URL, not the editor URL.")
        }
        guard let url = URL(string: urlString) else {
            return (false, "That doesn't look like a valid URL.")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("text/plain;charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: ["kind": "ping", "payload": [String: String]()])
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return (false, "No response from the server.")
            }
            guard (200...299).contains(http.statusCode) else {
                return (false, "Server returned HTTP \(http.statusCode). Re-deploy the Apps Script.")
            }
            let object = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
            if (object["ok"] as? Bool) == true {
                GSheetsStore.markSync(error: nil)
                return (true, (object["message"] as? String) ?? "Connected.")
            }
            return (false, (object["error"] as? String) ?? "Unknown error from the Apps Script.")
        } catch {
            return (false, error.localizedDescription)
        }
    }
}

private final class AppSession: ObservableObject {
    enum Status: Equatable {
        case restoring
        case signedOut
        case signedIn
    }

    @Published var status: Status = .restoring
    @Published var me: MeResponse?
    @Published var lastError: String?
    // Separate from `status`: signing in must NOT flip NativeERPAppView's
    // top-level switch away from LoginView (that switch treats .restoring as
    // "show the full-screen launch splash", which was tearing the login form
    // down — including the button's own inline spinner — the instant Sign In
    // was tapped. That is what "the Sign-In button has no response" was.
    @Published var isAuthenticating = false

    // Resolved once per sign-in/restore (resolveTerminalClaim). terminalId
    // is nil until resolution succeeds; terminalIssue explains why when it
    // hasn't (missing branch, no terminals, or needs manual selection via
    // terminalOptions). POS/Gaming/Tables must treat a nil terminalId as
    // "blocked", not silently fall back to any terminal.
    @Published var terminalId: String?
    @Published var terminalOptions: [TerminalDTO] = []
    @Published var terminalIssue: String?

    private var accessToken: String?
    private var refreshToken: String?

    var displayRole: String {
        me?.roles.first?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Owner"
    }

    var canSeeAudit: Bool {
        // admin.audit.read has no MODULE_PERMISSIONS entry — it's not one of
        // the protected-owner's live-togglable modules, it's hardcoded
        // protected-owner-only on the backend.
        hasProtectedOwnerAccess
    }

    var hasProtectedOwnerAccess: Bool {
        me?.protected_access == true
    }

    // The real gate: /auth/me's accessible_modules, computed server-side
    // from the account's role (see backend/app/core/permissions.py
    // MODULE_PERMISSIONS). A protected owner always sees everything
    // regardless of this list. Module keys: pos, tables, menu, inventory,
    // gaming, finance, ocr, staff, insights_reports.
    var accessibleModules: Set<String> {
        Set(me?.accessible_modules ?? [])
    }

    func hasModule(_ module: String) -> Bool {
        hasProtectedOwnerAccess || accessibleModules.contains(module)
    }

    var canSeeInventory: Bool { hasModule("inventory") }
    var canSeeInsights: Bool { hasModule("insights_reports") }
    var canSeeTables: Bool { hasModule("tables") }
    var canSeeGaming: Bool { hasModule("gaming") }
    var canSeeFinance: Bool { hasModule("finance") }
    var canSeeStaffModule: Bool { hasModule("staff") }
    var canSeeOcr: Bool { hasModule("ocr") }
    var canSeeMenu: Bool { hasModule("menu") }

    // Best-effort client-side mirror of require_shift_opener() — the
    // backend is the real enforcement point (a 403 here always means the
    // server rejected it), this only avoids offering an action that will
    // just bounce, and shows a clearer reason up front. Compared by email
    // since ShiftRead exposes opened_by_email/opened_by_name, not the raw
    // opened_by user id.
    func isShiftOpener(_ shift: ShiftDTO) -> Bool {
        hasProtectedOwnerAccess || (shift.opened_by_email != nil && shift.opened_by_email == me?.email)
    }

    func restore() async {
        guard status == .restoring else { return }
        accessToken = TokenStore.read("access_token")
        refreshToken = TokenStore.read("refresh_token")

        guard accessToken != nil else {
            status = .signedOut
            return
        }

        do {
            let resolvedMe: MeResponse = try await authorized { token in
                try await APIClient.shared.get("auth/me", token: token)
            }
            me = resolvedMe
            await resolveTerminalClaim(for: resolvedMe)
            status = .signedIn
            RealtimeClient.shared.connect()
        } catch {
            signOut()
        }
    }

    func login(email: String, password: String) async {
        lastError = nil
        isAuthenticating = true
        defer { isAuthenticating = false }
        do {
            let token: TokenPair = try await APIClient.shared.post("auth/login", body: LoginRequest(email: email, password: password))
            save(token)
            let resolvedMe: MeResponse = try await APIClient.shared.get("auth/me", token: token.access_token)
            me = resolvedMe
            await resolveTerminalClaim(for: resolvedMe)
            status = .signedIn
            RealtimeClient.shared.connect()
        } catch {
            status = .signedOut
            lastError = readable(error)
        }
    }

    func signOut() {
        RealtimeClient.shared.disconnect()
        TokenStore.delete("access_token")
        TokenStore.delete("refresh_token")
        TerminalStore.clear()
        accessToken = nil
        refreshToken = nil
        me = nil
        terminalId = nil
        terminalOptions = []
        terminalIssue = nil
        status = .signedOut
    }

    // Mirrors AuthContext.tsx resolveTerminalClaim(). Runs once per sign-in
    // or restore. On failure (network error, no terminals, ambiguous
    // terminals) it clears any stale stored terminal id rather than letting
    // a device silently keep using one that's no longer valid.
    func resolveTerminalClaim(for me: MeResponse) async {
        do {
            let terminals: [TerminalDTO] = try await authorized { token in
                try await APIClient.shared.get(
                    "settings/terminals", token: token,
                    queryItems: me.branch_id.map { [URLQueryItem(name: "branch_id", value: $0)] } ?? []
                )
            }
            let resolution = resolveTerminal(branchId: me.branch_id, storedTerminalId: TerminalStore.read(), terminals: terminals)
            switch resolution {
            case .ready(let terminalId, _):
                TerminalStore.save(terminalId)
                self.terminalId = terminalId
                self.terminalOptions = terminals
                self.terminalIssue = nil
            case .selectionRequired(let terminals):
                TerminalStore.clear()
                self.terminalId = nil
                self.terminalOptions = terminals
                self.terminalIssue = terminalResolutionMessage(resolution)
            case .missingBranch, .noTerminals:
                TerminalStore.clear()
                self.terminalId = nil
                self.terminalOptions = []
                self.terminalIssue = terminalResolutionMessage(resolution)
            }
        } catch {
            TerminalStore.clear()
            terminalId = nil
            terminalOptions = []
            terminalIssue = "Terminal validation failed: \(readable(error))"
        }
    }

    func selectTerminal(_ terminal: TerminalDTO) {
        guard let branchId = me?.branch_id, terminal.branch_id == branchId else {
            terminalIssue = "The selected terminal does not belong to this account branch."
            return
        }
        TerminalStore.save(terminal.id)
        terminalId = terminal.id
        terminalIssue = nil
    }

    func authorized<T>(_ operation: (String) async throws -> T) async throws -> T {
        guard let token = accessToken else {
            throw DCompanyAPIError.unauthenticated
        }

        do {
            return try await operation(token)
        } catch let error as DCompanyAPIError where error.isUnauthorized {
            try await refresh()
            guard let refreshed = accessToken else {
                throw DCompanyAPIError.unauthenticated
            }
            return try await operation(refreshed)
        }
    }

    private func refresh() async throws {
        guard let refreshToken else {
            throw DCompanyAPIError.unauthenticated
        }

        do {
            let token: TokenPair = try await APIClient.shared.post("auth/refresh", body: RefreshRequest(refresh_token: refreshToken))
            save(token)
        } catch {
            signOut()
            throw error
        }
    }

    private func save(_ token: TokenPair) {
        accessToken = token.access_token
        refreshToken = token.refresh_token
        TokenStore.save(token.access_token, for: "access_token")
        TokenStore.save(token.refresh_token, for: "refresh_token")
    }
}

struct NativeERPAppView: View {
    @StateObject private var session = AppSession()
    @StateObject private var network = NetworkMonitor()
    @StateObject private var cache = AppCache()
    @State private var restoreStarted = false

    var body: some View {
        ZStack {
            Brand.appGradient.ignoresSafeArea()
            switch session.status {
            case .restoring:
                VStack(spacing: 18) {
                    LogoBadge(size: 74)
                    ProgressView()
                        .tint(Brand.gold)
                    Text("Opening D Company")
                        .font(.headline)
                        .foregroundColor(Brand.softGold)
                }
            case .signedOut:
                LoginView()
                    .environmentObject(session)
                    .environmentObject(network)
            case .signedIn:
                ERPHomeView()
                    .environmentObject(session)
                    .environmentObject(network)
                    .environmentObject(cache)
            }
        }
        .preferredColorScheme(.dark)
        .tint(Brand.gold)
        .onChange(of: session.status) { status in
            if status == .signedOut {
                cache.clear()
            }
        }
        .task {
            guard !restoreStarted else { return }
            restoreStarted = true
            await cache.restoreFromDisk()
            await session.restore()
        }
    }
}

struct DCompanyAppStoreView: View {
    @State private var selectedTab: PublicAppTab = .home

    var body: some View {
        TabView(selection: $selectedTab) {
            PublicHomeView(selectedTab: $selectedTab)
                .tabItem { Label("Home", systemImage: "house.fill") }
                .tag(PublicAppTab.home)

            PublicMenuBrowserView()
                .tabItem { Label("Menu", systemImage: "menucard.fill") }
                .tag(PublicAppTab.menu)

            PublicServicesView()
                .tabItem { Label("Services", systemImage: "gamecontroller.fill") }
                .tag(PublicAppTab.services)

            PublicVisitView()
                .tabItem { Label("Visit", systemImage: "mappin.and.ellipse") }
                .tag(PublicAppTab.visit)
        }
        .preferredColorScheme(.dark)
        .tint(Brand.gold)
        .premiumTabChrome()
        .background(Brand.appGradient)
    }
}

private enum PublicAppTab: Hashable {
    case home
    case menu
    case services
    case visit
}

private enum PublicMenuCategory: String, CaseIterable, Identifiable {
    case drinks
    case cafe
    case desserts

    var id: String { rawValue }

    var title: String {
        switch self {
        case .drinks: return "Drinks"
        case .cafe: return "Cafe"
        case .desserts: return "Desserts"
        }
    }
}

private struct PublicMenuItem: Identifiable {
    let id: String
    let category: PublicMenuCategory
    let name: String
    let detail: String
    let priceMinor: Int
    let icon: String
}

private struct PublicService: Identifiable {
    let id: String
    let name: String
    let detail: String
    let priceMinor: Int
    let icon: String
    let accent: Color
}

private enum PublicCatalog {
    static let menuItems: [PublicMenuItem] = [
        PublicMenuItem(id: "cappuccino", category: .drinks, name: "Cappuccino", detail: "Fresh espresso, steamed milk, cafe foam", priceMinor: 18000, icon: "cup.and.saucer.fill"),
        PublicMenuItem(id: "iced-latte", category: .drinks, name: "Iced Latte", detail: "Cold milk, espresso, light sweetness", priceMinor: 19000, icon: "snowflake"),
        PublicMenuItem(id: "fresh-lime", category: .drinks, name: "Fresh Lime", detail: "Chilled lime, mint, soda or water", priceMinor: 9000, icon: "leaf.fill"),
        PublicMenuItem(id: "club-sandwich", category: .cafe, name: "Club Sandwich", detail: "Toasted bread, egg, chicken, house sauce", priceMinor: 22000, icon: "takeoutbag.and.cup.and.straw.fill"),
        PublicMenuItem(id: "loaded-fries", category: .cafe, name: "Loaded Fries", detail: "Crispy fries, cheese, sauce, seasoning", priceMinor: 18000, icon: "fork.knife"),
        PublicMenuItem(id: "brownie", category: .desserts, name: "Chocolate Brownie", detail: "Warm brownie with vanilla scoop option", priceMinor: 15000, icon: "birthday.cake.fill"),
        PublicMenuItem(id: "waffle", category: .desserts, name: "Waffle", detail: "Crisp waffle, chocolate, fruit topping", priceMinor: 21000, icon: "circle.grid.cross.fill")
    ]

    static let services: [PublicService] = [
        PublicService(id: "ps5", name: "PS5 Stations", detail: "Console gaming bays for solo and group sessions.", priceMinor: 20000, icon: "gamecontroller.fill", accent: Brand.softGold),
        PublicService(id: "vr", name: "VR Bay", detail: "Immersive headset sessions with staff setup.", priceMinor: 25000, icon: "visionpro.fill", accent: Color(red: 0.46, green: 0.70, blue: 1.0)),
        PublicService(id: "simulator", name: "Racing Simulator", detail: "Wheel-and-seat simulator for timed sessions.", priceMinor: 30000, icon: "steeringwheel", accent: Color(red: 0.96, green: 0.56, blue: 0.32)),
        PublicService(id: "streaming", name: "Sports Streaming", detail: "Screened matches and group viewing tables.", priceMinor: 15000, icon: "play.tv.fill", accent: Color(red: 0.40, green: 0.86, blue: 0.58)),
        PublicService(id: "cafe", name: "Cafe Tables", detail: "Walk-in seating for food, drinks, and desserts.", priceMinor: 0, icon: "table.furniture.fill", accent: Color(red: 0.96, green: 0.78, blue: 0.44))
    ]
}

private struct PublicHomeView: View {
    @Binding var selectedTab: PublicAppTab

    private let columns = [GridItem(.adaptive(minimum: 155), spacing: 12)]

    var body: some View {
        AppNavigation {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    PublicHeroCard()

                    LazyVGrid(columns: columns, spacing: 12) {
                        PublicActionCard(title: "Menu", subtitle: "Cafe favourites", icon: "menucard.fill") {
                            selectedTab = .menu
                        }
                        PublicActionCard(title: "Games", subtitle: "PS5, VR, simulator", icon: "gamecontroller.fill") {
                            selectedTab = .services
                        }
                        PublicActionCard(title: "Lounge", subtitle: "Group seating", icon: "person.2.fill") {
                            selectedTab = .services
                        }
                        PublicActionCard(title: "Visit", subtitle: "Hours and support", icon: "mappin.and.ellipse") {
                            selectedTab = .visit
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Today at D Company")
                                .font(.headline)
                                .foregroundColor(.white)
                            HStack(spacing: 10) {
                                PublicInfoPill(title: "Cafe", value: "Open")
                                PublicInfoPill(title: "Games", value: "Walk in")
                                PublicInfoPill(title: "Lounge", value: "Reserve")
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Popular")
                            .font(.title3.weight(.bold))
                            .foregroundColor(.white)

                        ForEach(PublicCatalog.menuItems.prefix(4)) { item in
                            PublicMenuItemRow(item: item)
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 20)
            }
            .background(Brand.appGradient.ignoresSafeArea())
            .navigationTitle("D Company")
            .navigationBarTitleDisplayMode(.large)
        }
    }
}

private struct PublicMenuBrowserView: View {
    @State private var selectedCategory: PublicMenuCategory = .drinks

    private var filteredItems: [PublicMenuItem] {
        PublicCatalog.menuItems.filter { $0.category == selectedCategory }
    }

    private let columns = [GridItem(.adaptive(minimum: 280), spacing: 12)]

    var body: some View {
        AppNavigation {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HeaderBlock(title: "Menu", subtitle: "Cafe, drinks, desserts, and lounge items", icon: "menucard.fill")

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(PublicMenuCategory.allCases) { category in
                                Button {
                                    Haptics.selection()
                                    selectedCategory = category
                                } label: {
                                    Text(category.title)
                                        .font(.subheadline.weight(.bold))
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 10)
                                        .background(selectedCategory == category ? Brand.gold : Brand.elevated)
                                        .foregroundColor(selectedCategory == category ? .black : Brand.softGold)
                                        .clipShape(Capsule())
                                }
                                .buttonStyle(PressableButtonStyle())
                            }
                        }
                        .padding(.horizontal, 1)
                    }

                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(filteredItems) { item in
                            PublicMenuItemCard(item: item)
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 20)
            }
            .background(Brand.appGradient.ignoresSafeArea())
            .navigationTitle("Menu")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct PublicServicesView: View {
    private let columns = [GridItem(.adaptive(minimum: 260), spacing: 12)]

    var body: some View {
        AppNavigation {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HeaderBlock(title: "Games & Lounge", subtitle: "PS5, VR, simulator, sports, and cafe tables", icon: "gamecontroller.fill")

                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(PublicCatalog.services) { service in
                            PublicServiceCard(service: service)
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 20)
            }
            .background(Brand.appGradient.ignoresSafeArea())
            .navigationTitle("Services")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct PublicVisitView: View {
    var body: some View {
        AppNavigation {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    HeaderBlock(title: "Visit D Company", subtitle: "Cafe, games lounge, and after dark seating", icon: "mappin.and.ellipse")

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 14) {
                            PublicVisitRow(icon: "clock.fill", title: "Opening", detail: "Daily hours and special match screenings may change by day.")
                            PublicVisitRow(icon: "mappin.circle.fill", title: "Area", detail: "Nilambur, Malappuram, Kerala")
                            PublicVisitRow(icon: "person.2.fill", title: "Bookings", detail: "Ask staff for group gaming, cafe seating, or sports streaming reservations.")
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Support")
                                .font(.headline)
                                .foregroundColor(.white)
                            Text("For bookings, billing questions, or help with an order, contact D Company support.")
                                .font(.subheadline)
                                .foregroundColor(Brand.muted)
                                .fixedSize(horizontal: false, vertical: true)
                            Link(destination: URL(string: "https://dcompany.duckdns.org/support.html")!) {
                                HStack {
                                    Text("Open support")
                                        .font(.headline)
                                    Spacer()
                                    Image(systemName: "arrow.up.right")
                                }
                                .padding(.horizontal, 16)
                                .padding(.vertical, 13)
                                .background(Brand.gold)
                                .foregroundColor(.black)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                            }
                            .buttonStyle(PressableButtonStyle())
                        }
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 20)
            }
            .background(Brand.appGradient.ignoresSafeArea())
            .navigationTitle("Visit")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

private struct PublicHeroCard: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .center, spacing: 16) {
                LogoBadge(size: 78)
                VStack(alignment: .leading, spacing: 6) {
                    Text("D Company")
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                    Text("Cafe, games, lounge, and after dark")
                        .font(.subheadline.weight(.medium))
                        .foregroundColor(Brand.softGold)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
            }

            HStack(spacing: 10) {
                PublicInfoPill(title: "Menu", value: "\(PublicCatalog.menuItems.count) items")
                PublicInfoPill(title: "Services", value: "\(PublicCatalog.services.count) live")
            }
        }
        .padding(18)
        .background(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(Brand.cardGradient)
                .shadow(color: .black.opacity(0.34), radius: 24, x: 0, y: 14)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(Brand.gold.opacity(0.26), lineWidth: 1)
        )
    }
}

private struct PublicActionCard: View {
    let title: String
    let subtitle: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button {
            Haptics.selection()
            action()
        } label: {
            BrandedCard {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: icon)
                        .font(.title2.weight(.semibold))
                        .foregroundColor(Brand.gold)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(title)
                            .font(.headline)
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 86, alignment: .leading)
            }
        }
        .buttonStyle(PressableButtonStyle())
    }
}

private struct PublicMenuItemRow: View {
    let item: PublicMenuItem

    var body: some View {
        BrandedCard {
            HStack(spacing: 12) {
                PublicIconBox(icon: item.icon, color: Brand.gold)
                VStack(alignment: .leading, spacing: 4) {
                    Text(item.name)
                        .font(.headline)
                        .foregroundColor(.white)
                    Text(item.detail)
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                        .lineLimit(2)
                }
                Spacer()
                Text(inr(item.priceMinor))
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(Brand.softGold)
            }
        }
    }
}

private struct PublicMenuItemCard: View {
    let item: PublicMenuItem

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 13) {
                HStack {
                    PublicIconBox(icon: item.icon, color: Brand.gold)
                    Spacer()
                    Text(inr(item.priceMinor))
                        .font(.headline.weight(.bold))
                        .foregroundColor(Brand.softGold)
                        .monospacedDigit()
                }
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.name)
                        .font(.headline)
                        .foregroundColor(.white)
                    Text(item.detail)
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 128, alignment: .leading)
        }
    }
}

private struct PublicServiceCard: View {
    let service: PublicService

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    PublicIconBox(icon: service.icon, color: service.accent)
                    Spacer()
                    Text(service.priceMinor == 0 ? "Walk in" : "\(inr(service.priceMinor))/hr")
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(service.accent.opacity(0.16))
                        .foregroundColor(service.accent)
                        .clipShape(Capsule())
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text(service.name)
                        .font(.headline)
                        .foregroundColor(.white)
                    Text(service.detail)
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            .frame(maxWidth: .infinity, minHeight: 150, alignment: .leading)
        }
    }
}

private struct PublicVisitRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            PublicIconBox(icon: icon, color: Brand.gold)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(.white)
                Text(detail)
                    .font(.subheadline)
                    .foregroundColor(Brand.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct PublicInfoPill: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(.caption2.weight(.bold))
                .foregroundColor(Brand.muted)
            Text(value)
                .font(.caption.weight(.bold))
                .foregroundColor(Brand.softGold)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 11)
        .padding(.vertical, 9)
        .background(Brand.elevated.opacity(0.72))
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 13, style: .continuous)
                .stroke(Brand.gold.opacity(0.16), lineWidth: 1)
        )
    }
}

private struct PublicIconBox: View {
    let icon: String
    let color: Color

    var body: some View {
        Image(systemName: icon)
            .font(.headline.weight(.bold))
            .foregroundColor(color)
            .frame(width: 42, height: 42)
            .background(color.opacity(0.13))
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

private enum LoginMode {
    case login, resetRequest, resetConfirm, registerRequest, registerConfirm
}

private struct PendingChallenge {
    let id: String
    let destination: String
    let email: String
}

// Mirrors frontend/src/modules/auth/Login.tsx's 5-mode state machine.
// resetRequest/registerRequest hit a public OTP-challenge endpoint (no
// access token), then resetConfirm/registerConfirm submit the 6-digit code
// that was emailed. Plain login still goes through AppSession.login() since
// that's the one path that actually establishes a session.
private struct LoginView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @State private var mode: LoginMode = .login
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var name = ""
    @State private var phone = ""
    @State private var code = ""
    @State private var challenge: PendingChallenge?
    @State private var localError: String?
    @State private var localSuccess: String?
    @State private var isBusy = false
    @FocusState private var focusedField: Field?

    private enum Field {
        case email, password, confirmPassword, name, phone, code
    }

    private var isConfirmMode: Bool {
        mode == .resetConfirm || mode == .registerConfirm
    }

    private var title: String {
        switch mode {
        case .login: return "D Company ERP"
        case .resetRequest, .resetConfirm: return "Reset password"
        case .registerRequest, .registerConfirm: return "Create new login"
        }
    }

    private var subtitle: String {
        switch mode {
        case .login: return "Sign in to continue"
        case .resetRequest: return "Enter the login email to request approval"
        case .registerRequest: return "Add an approved D Company account"
        case .resetConfirm, .registerConfirm:
            return "Approval code sent to \(challenge?.destination ?? "the business mailbox")"
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 36)
                ZStack(alignment: .topLeading) {
                    VStack(spacing: 8) {
                        LogoBadge(size: 92)
                        Text(title)
                            .font(.system(size: mode == .login ? 32 : 26, weight: .bold, design: .rounded))
                            .foregroundColor(.white)
                            .multilineTextAlignment(.center)
                        Text(subtitle)
                            .font(.subheadline)
                            .foregroundColor(Brand.muted)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)

                    if mode != .login {
                        Button {
                            Haptics.selection()
                            switchMode(.login)
                        } label: {
                            Image(systemName: "chevron.left")
                                .font(.headline)
                                .foregroundColor(Brand.muted)
                                .padding(8)
                        }
                        .accessibilityIdentifier("login.back")
                    }
                }

                if !network.isOnline {
                    NetworkBanner(label: network.connectionLabel)
                }

                VStack(spacing: 16) {
                    if mode == .registerRequest {
                        field("Full name") {
                            TextField("", text: $name)
                                .textInputAutocapitalization(.words)
                                .focused($focusedField, equals: .name)
                                .nativeField()
                                .accessibilityIdentifier("login.name")
                        }
                        field("Phone (optional)") {
                            TextField("", text: $phone)
                                .keyboardType(.phonePad)
                                .focused($focusedField, equals: .phone)
                                .nativeField()
                        }
                    }

                    if !isConfirmMode {
                        field("Email") {
                            TextField("name@dcompany.local", text: $email)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .textContentType(.username)
                                .focused($focusedField, equals: .email)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .password }
                                .nativeField()
                                .accessibilityIdentifier("login.email")
                        }
                    }

                    if mode == .login {
                        field("Password") {
                            SecureField("Password", text: $password)
                                .textContentType(.password)
                                .focused($focusedField, equals: .password)
                                .submitLabel(.go)
                                .onSubmit { Task { await primaryAction() } }
                                .nativeField()
                                .accessibilityIdentifier("login.password")
                        }
                    }

                    if mode == .registerRequest || mode == .resetConfirm {
                        field("New password") {
                            SecureField("At least 10 characters", text: $password)
                                .textContentType(.newPassword)
                                .focused($focusedField, equals: .password)
                                .nativeField()
                        }
                        field("Confirm password") {
                            SecureField("", text: $confirmPassword)
                                .textContentType(.newPassword)
                                .focused($focusedField, equals: .confirmPassword)
                                .nativeField()
                        }
                    }

                    if isConfirmMode {
                        field("6-digit approval code") {
                            TextField("", text: $code)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.center)
                                .font(.title2.weight(.bold).monospacedDigit())
                                .focused($focusedField, equals: .code)
                                .onChange(of: code) { newValue in
                                    code = String(newValue.filter(\.isNumber).prefix(6))
                                }
                                .nativeField()
                        }
                    }

                    if let message = localError ?? session.lastError {
                        Text(message)
                            .font(.footnote.weight(.semibold))
                            .foregroundColor(Brand.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    if let localSuccess {
                        Text(localSuccess)
                            .font(.footnote.weight(.semibold))
                            .foregroundColor(Brand.success)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button {
                        Task { await primaryAction() }
                    } label: {
                        HStack {
                            if isBusy || session.isAuthenticating {
                                ProgressView().tint(.black)
                            }
                            Text(primaryButtonTitle)
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                    }
                    .buttonStyle(.plain)
                    .background(Brand.gold)
                    .foregroundColor(.black)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(!canSubmit || isBusy || session.isAuthenticating)
                    .opacity(canSubmit ? 1 : 0.6)
                    .accessibilityIdentifier(mode == .login ? "login.signIn" : "login.primaryAction")
                }
                .padding(20)
                .background(Brand.surface)
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Brand.gold.opacity(0.35), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))

                if mode == .login {
                    HStack(spacing: 10) {
                        Button {
                            Haptics.selection()
                            switchMode(.resetRequest)
                        } label: {
                            Text("Forgot password")
                                .font(.caption.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(Brand.softGold)
                        .background(Brand.elevated)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityIdentifier("login.forgotPassword")

                        Button {
                            Haptics.selection()
                            switchMode(.registerRequest)
                        } label: {
                            Text("New login")
                                .font(.caption.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(Brand.softGold)
                        .background(Brand.elevated)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .accessibilityIdentifier("login.newLogin")
                    }
                }
            }
            .padding(24)
        }
        .background(Brand.appGradient)
    }

    @ViewBuilder
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.caption)
                .foregroundColor(Brand.muted)
            content()
        }
    }

    private var primaryButtonTitle: String {
        switch mode {
        case .login: return "Sign in"
        case .resetRequest: return "Send approval code"
        case .registerRequest: return "Send approval code"
        case .resetConfirm, .registerConfirm: return "Confirm approval code"
        }
    }

    private var canSubmit: Bool {
        switch mode {
        case .login:
            return !email.isEmpty && !password.isEmpty
        case .resetRequest:
            return !email.isEmpty
        case .registerRequest:
            return !name.isEmpty && !email.isEmpty && password.count >= 10 && password == confirmPassword
        case .resetConfirm:
            return code.count == 6 && password.count >= 10 && password == confirmPassword
        case .registerConfirm:
            return code.count == 6
        }
    }

    private func switchMode(_ next: LoginMode) {
        mode = next
        localError = nil
        localSuccess = nil
        code = ""
        challenge = nil
        password = ""
        confirmPassword = ""
        session.lastError = nil
    }

    private func primaryAction() async {
        switch mode {
        case .login:
            await session.login(email: email.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
        case .resetRequest:
            await requestPasswordReset()
        case .resetConfirm:
            await confirmPasswordReset()
        case .registerRequest:
            await requestRegistration()
        case .registerConfirm:
            await confirmRegistration()
        }
    }

    private func requestPasswordReset() async {
        isBusy = true
        defer { isBusy = false }
        localError = nil
        do {
            let result: OtpChallengeDTO = try await APIClient.shared.post(
                "auth/password-reset/request",
                body: PasswordResetRequestBody(email: email.trimmingCharacters(in: .whitespacesAndNewlines))
            )
            challenge = PendingChallenge(id: result.challenge_id, destination: result.destination, email: email)
            mode = .resetConfirm
        } catch is CancellationError {
        } catch {
            localError = readable(error)
        }
    }

    private func confirmPasswordReset() async {
        guard let challenge else {
            localError = "Request a new approval code"
            return
        }
        isBusy = true
        defer { isBusy = false }
        localError = nil
        do {
            let result: AccountActionDTO = try await APIClient.shared.post(
                "auth/password-reset/confirm",
                body: PasswordResetConfirmBody(challenge_id: challenge.id, code: code.trimmingCharacters(in: .whitespacesAndNewlines), new_password: password)
            )
            localSuccess = result.message
            switchMode(.login)
            email = challenge.email
        } catch is CancellationError {
        } catch {
            localError = readable(error)
        }
    }

    private func requestRegistration() async {
        isBusy = true
        defer { isBusy = false }
        localError = nil
        do {
            let result: OtpChallengeDTO = try await APIClient.shared.post(
                "auth/register/request",
                body: RegisterRequestBody(
                    email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                    name: name.trimmingCharacters(in: .whitespacesAndNewlines),
                    phone: phone.trimmingCharacters(in: .whitespacesAndNewlines).nilIfBlank,
                    password: password
                )
            )
            challenge = PendingChallenge(id: result.challenge_id, destination: result.destination, email: email)
            mode = .registerConfirm
        } catch is CancellationError {
        } catch {
            localError = readable(error)
        }
    }

    private func confirmRegistration() async {
        guard let challenge else {
            localError = "Request a new approval code"
            return
        }
        isBusy = true
        defer { isBusy = false }
        localError = nil
        do {
            let result: AccountActionDTO = try await APIClient.shared.post(
                "auth/register/confirm",
                body: RegisterConfirmBody(challenge_id: challenge.id, code: code.trimmingCharacters(in: .whitespacesAndNewlines))
            )
            localSuccess = result.message
            switchMode(.login)
            email = challenge.email
        } catch is CancellationError {
        } catch {
            localError = readable(error)
        }
    }
}

private enum NativeTab: Hashable, CaseIterable {
    case dashboard
    case pos
    case gaming
    case reports
    case workspace

    var title: String {
        switch self {
        case .dashboard: return "Home"
        case .pos: return "POS"
        case .gaming: return "Gaming"
        case .reports: return "Reports"
        case .workspace: return "Workspace"
        }
    }

    var icon: String {
        switch self {
        case .dashboard: return "house"
        case .pos: return "cart"
        case .gaming: return "gamecontroller"
        case .reports: return "chart.bar"
        case .workspace: return "square.grid.2x2"
        }
    }
}

private struct ERPHomeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var selection: NativeTab = .dashboard

    var body: some View {
        TabView(selection: Binding(get: { selection }, set: updateTab)) {
            DashboardNativeView(openTab: updateTab)
                .tabItem { Label(NativeTab.dashboard.title, systemImage: NativeTab.dashboard.icon) }
                .tag(NativeTab.dashboard)

            POSNativeView()
                .tabItem { Label(NativeTab.pos.title, systemImage: NativeTab.pos.icon) }
                .tag(NativeTab.pos)

            GamingNativeView(openTab: updateTab)
                .tabItem { Label(NativeTab.gaming.title, systemImage: NativeTab.gaming.icon) }
                .tag(NativeTab.gaming)

            ReportsNativeView()
                .tabItem { Label(NativeTab.reports.title, systemImage: NativeTab.reports.icon) }
                .tag(NativeTab.reports)

            WorkspaceNativeView(openTab: updateTab)
                .tabItem { Label(NativeTab.workspace.title, systemImage: NativeTab.workspace.icon) }
                .tag(NativeTab.workspace)
        }
        .premiumTabChrome()
    }

    private func updateTab(_ tab: NativeTab) {
        guard tab != selection else { return }
        Haptics.selection()
        withAnimation(.spring(response: 0.26, dampingFraction: 0.86)) {
            selection = tab
        }
    }
}

private struct DashboardNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @EnvironmentObject private var cache: AppCache
    let openTab: (NativeTab) -> Void
    @State private var report: ReportDTO?
    @State private var lowStockCount = 0
    @State private var menuCount = 0
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(
                        title: "D Company",
                        subtitle: "\(session.me?.name ?? "Owner") - \(session.displayRole)",
                        icon: "building.2"
                    )

                    if !network.isOnline {
                        NetworkBanner(label: network.connectionLabel)
                    }

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if let report {
                        LazyVGrid(columns: twoColumns, spacing: 12) {
                            MetricCard(title: "Net Revenue", value: inr(report.net_revenue_minor), detail: report.label, icon: "creditcard")
                            MetricCard(title: "Net Profit", value: inr(report.net_profit_minor), detail: "\(report.orders_count) orders", icon: "chart.bar")
                            MetricCard(title: "GST", value: inr(report.tax_collected.total_minor), detail: "Collected", icon: "doc.text")
                            MetricCard(title: "Average Bill", value: inr(report.avg_ticket_minor), detail: "\(report.tickets_count) tickets", icon: "number")
                        }
                    } else if isLoading {
                        MetricsSkeletonGrid()
                    }

                    OwnerCommandCenter(
                        report: report,
                        lowStockCount: lowStockCount,
                        menuCount: menuCount,
                        canSeeInventory: session.canSeeInventory,
                        isOnline: network.isOnline,
                        connectionLabel: network.connectionLabel
                    )

                    ERPWorkflowCard(openTab: openTab, canUseProtectedControls: session.hasProtectedOwnerAccess)

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Workspace")
                                .font(.headline)
                                .foregroundColor(.white)
                                .padding(.bottom, 4)

                            // Grouped so the day-to-day tools staff open constantly
                            // aren't mixed in with occasional setup/admin screens.
                            // Every link below is unchanged — only regrouped.
                            WorkspaceSectionHeader(title: "Daily Operations", isFirst: true)
                            if session.canSeeTables {
                                NavigationLink {
                                    TablesNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Tables", subtitle: "Build dine-in orders, send to kitchen and POS", icon: "table.furniture")
                                }
                            }
                            if session.canSeeTables || session.canSeeGaming {
                                NavigationLink {
                                    ReservationsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Reservations", subtitle: "Upcoming table and gaming bookings", icon: "calendar.badge.clock")
                                }
                            }
                            NavigationLink {
                                KitchenNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Kitchen", subtitle: "Live order queue, received to served", icon: "flame")
                            }
                            NavigationLink {
                                OrdersNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Orders", subtitle: "Recent bills and payment status", icon: "receipt")
                            }
                            NavigationLink {
                                CustomersNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Customers", subtitle: "Visits, spend, and loyalty points", icon: "person.2")
                            }

                            WorkspaceSectionHeader(title: "Catalog & Stock")
                            if session.canSeeMenu {
                                NavigationLink {
                                    MenuCatalogNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Menu", subtitle: "Catalog, GST rate, and sale price view", icon: "menucard")
                                }
                            }
                            if session.canSeeInventory {
                                NavigationLink {
                                    InventoryNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Inventory", subtitle: "Stock, reorder alerts, and ingredients", icon: "cube.box")
                                }
                            }
                            NavigationLink {
                                MembershipsNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Memberships", subtitle: "D Club tiers, pricing, and perks", icon: "crown")
                            }
                            if session.canSeeGaming {
                                NavigationLink {
                                    EventsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Events", subtitle: "Screenings, seats, and ticket sales", icon: "tv")
                                }
                            }

                            if session.canSeeOcr || session.canSeeInsights || session.canSeeInventory || session.canSeeFinance {
                                WorkspaceSectionHeader(title: "Reports & Money")
                                if session.canSeeOcr {
                                    NavigationLink {
                                        OcrNativeView()
                                    } label: {
                                        WorkspaceLinkRow(title: "OCR — Receipts", subtitle: "Scan bills, extract vendor and amount, approve", icon: "scanner")
                                    }
                                }
                                if session.canSeeInsights {
                                    NavigationLink {
                                        AnalyticsNativeView()
                                    } label: {
                                        WorkspaceLinkRow(title: "Analytics", subtitle: "Today's KPIs and revenue by stream", icon: "chart.line.uptrend.xyaxis")
                                    }
                                }
                                if session.canSeeInsights || session.canSeeInventory || session.canSeeFinance {
                                    NavigationLink {
                                        InsightsNativeView()
                                    } label: {
                                        WorkspaceLinkRow(title: "Insights", subtitle: "Growth, inventory value, accounting, and losses", icon: "chart.bar.doc.horizontal")
                                    }
                                }
                                if session.canSeeFinance {
                                    NavigationLink {
                                        FinanceNativeView()
                                    } label: {
                                        WorkspaceLinkRow(title: "Finance", subtitle: "P&L overview, expenses, and partner capital", icon: "banknote")
                                    }
                                }
                            }

                            WorkspaceSectionHeader(title: "Setup")
                            NavigationLink {
                                StaffNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Staff", subtitle: "Users, roles, and login history", icon: "person.badge.key")
                            }
                            if session.hasProtectedOwnerAccess {
                                NavigationLink {
                                    SettingsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Company", subtitle: "GST, branch, and terminal readiness", icon: "gearshape")
                                }
                                NavigationLink {
                                    DeviceIntegrationsNativeView()
                                        .environmentObject(cache)
                                } label: {
                                    WorkspaceLinkRow(title: "Integrations", subtitle: "Printer, OCR, terminal, and offline store", icon: "externaldrive.connected.to.line.below")
                                }
                                NavigationLink {
                                    AuditNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Audit Log", subtitle: "Protected login and system activity", icon: "shield.checkered")
                                }
                                NavigationLink {
                                    AccessControlNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Access Control", subtitle: "Per-role module access overrides", icon: "lock.shield")
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Home")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button("Refresh") { Task { await load() } }
                        Button("Sign out", role: .destructive) { session.signOut() }
                    } label: {
                        Image(systemName: "person.crop.circle")
                    }
                }
            }
            .background(Brand.background)
        }
        .task { await load() }
    }

    private var twoColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    }

    private var hasCachedSnapshot: Bool {
        report != nil || menuCount > 0 || (session.canSeeInventory && lowStockCount > 0) || cache.dailyReport != nil || cache.hasMenuData || (session.canSeeInventory && cache.hasInventoryData)
    }

    private func load() async {
        applyCachedSnapshot()
        isLoading = !hasCachedSnapshot
        defer { isLoading = false }
        error = nil
        do {
            async let loadedReport: ReportDTO = session.authorized { token in
                try await APIClient.shared.get("reports/daily", token: token)
            }
            async let loadedItems: [MenuItemDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/items", token: token)
            }
            let (freshReport, freshItems) = try await (loadedReport, loadedItems)
            let freshIngredients: [IngredientDTO]
            if session.canSeeInventory {
                freshIngredients = try await session.authorized { token in
                    try await APIClient.shared.get("inventory/ingredients", token: token)
                }
            } else {
                freshIngredients = []
            }
            withAnimation(.easeOut(duration: 0.18)) {
                report = freshReport
                menuCount = freshItems.count
                lowStockCount = session.canSeeInventory ? freshIngredients.filter(\.isLowStock).count : 0
                cache.dailyReport = freshReport
                cache.menuItems = freshItems
                if session.canSeeInventory {
                    cache.ingredients = freshIngredients
                } else {
                    cache.ingredients = []
                }
            }
            await cache.markSynced()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func applyCachedSnapshot() {
        if let cachedReport = cache.dailyReport {
            report = cachedReport
        }
        if cache.hasMenuData {
            menuCount = cache.menuItems.count
        }
        if session.canSeeInventory && cache.hasInventoryData {
            lowStockCount = cache.ingredients.filter(\.isLowStock).count
        } else if !session.canSeeInventory {
            lowStockCount = 0
        }
    }
}

private struct WorkspaceNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var cache: AppCache
    let openTab: (NativeTab) -> Void

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: refresh) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Workspace", subtitle: "Daily work, records, and protected setup", icon: "square.grid.2x2")

                    LazyVGrid(columns: twoColumns, spacing: 12) {
                        QuickActionButton(title: "New bill", subtitle: "POS", icon: "cart.badge.plus") {
                            openTab(.pos)
                        }
                        QuickActionButton(title: "Sessions", subtitle: "PS5, VR, shisha", icon: "gamecontroller.fill") {
                            openTab(.gaming)
                        }
                        QuickActionButton(title: "P&L", subtitle: "Reports", icon: "chart.bar.xaxis") {
                            openTab(.reports)
                        }
                        QuickActionButton(title: "Home", subtitle: "Overview", icon: "house") {
                            openTab(.dashboard)
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Daily Work")
                                .font(.headline)
                                .foregroundColor(.white)
                                .padding(.bottom, 4)

                            Button {
                                Haptics.selection()
                                openTab(.pos)
                            } label: {
                                WorkspaceLinkRow(title: "POS Billing", subtitle: "Food, drinks, shisha, streaming, and gaming bills", icon: "cart")
                            }
                            .buttonStyle(.plain)
                            Button {
                                Haptics.selection()
                                openTab(.gaming)
                            } label: {
                                WorkspaceLinkRow(title: "Cafe Sessions", subtitle: "Start and stop PS5, VR, simulator, shisha, and streaming", icon: "gamecontroller")
                            }
                            .buttonStyle(.plain)
                            if session.canSeeTables {
                                NavigationLink {
                                    TablesNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Tables", subtitle: "Build dine-in orders, send to kitchen and POS", icon: "table.furniture")
                                }
                            }
                            if session.canSeeTables || session.canSeeGaming {
                                NavigationLink {
                                    ReservationsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Reservations", subtitle: "Upcoming table and gaming bookings", icon: "calendar.badge.clock")
                                }
                            }
                            NavigationLink {
                                KitchenNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Kitchen", subtitle: "Live order queue, received to served", icon: "flame")
                            }
                            if session.canSeeMenu {
                                NavigationLink {
                                    MenuCatalogNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Menu", subtitle: "Catalog, GST rate, and sale price view", icon: "menucard")
                                }
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Records")
                                .font(.headline)
                                .foregroundColor(.white)
                                .padding(.bottom, 4)

                            if session.canSeeInventory {
                                NavigationLink {
                                    InventoryNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Inventory", subtitle: "Ingredients, stock levels, reorder alerts", icon: "cube.box")
                                }
                            }
                            NavigationLink {
                                OrdersNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Orders", subtitle: "Recent invoices and payment status", icon: "receipt")
                            }
                            NavigationLink {
                                CustomersNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Customers", subtitle: "Visits, spend, and loyalty points", icon: "person.2")
                            }
                            NavigationLink {
                                StaffNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Staff", subtitle: "Users, roles, and login history", icon: "person.badge.key")
                            }
                            NavigationLink {
                                MembershipsNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Memberships", subtitle: "D Club tiers, pricing, and perks", icon: "crown")
                            }
                            if session.canSeeOcr {
                                NavigationLink {
                                    OcrNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "OCR — Receipts", subtitle: "Scan bills, extract vendor and amount, approve", icon: "scanner")
                                }
                            }
                            if session.canSeeInsights {
                                NavigationLink {
                                    AnalyticsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Analytics", subtitle: "Today's KPIs and revenue by stream", icon: "chart.line.uptrend.xyaxis")
                                }
                            }
                            if session.canSeeInsights || session.canSeeInventory || session.canSeeFinance {
                                NavigationLink {
                                    InsightsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Insights", subtitle: "Growth, inventory value, accounting, and losses", icon: "chart.bar.doc.horizontal")
                                }
                            }
                            if session.canSeeFinance {
                                NavigationLink {
                                    FinanceNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Finance", subtitle: "P&L overview, expenses, and partner capital", icon: "banknote")
                                }
                            }
                            if session.canSeeGaming {
                                NavigationLink {
                                    EventsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Events", subtitle: "Screenings, seats, and ticket sales", icon: "tv")
                                }
                            }
                        }
                    }

                    if session.hasProtectedOwnerAccess {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("Protected Controls")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                    .padding(.bottom, 4)

                                NavigationLink {
                                    PricingNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Pricing", subtitle: "Password-locked menu and session rates", icon: "indianrupeesign.circle")
                                }
                                NavigationLink {
                                    StationManagementNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Stations & Services", subtitle: "PS5, VR, simulator, shisha, and streaming setup", icon: "slider.horizontal.3")
                                }
                                NavigationLink {
                                    SettingsNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Company Settings", subtitle: "GST, branch, and terminal readiness", icon: "gearshape")
                                }
                                NavigationLink {
                                    DeviceIntegrationsNativeView()
                                        .environmentObject(cache)
                                } label: {
                                    WorkspaceLinkRow(title: "Integrations", subtitle: "Printer, OCR, terminal, offline snapshot", icon: "externaldrive.connected.to.line.below")
                                }
                                NavigationLink {
                                    AuditNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Audit Log", subtitle: "Protected owner activity trail", icon: "shield.checkered")
                                }
                                NavigationLink {
                                    AccessControlNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Access Control", subtitle: "Per-role module access overrides", icon: "lock.shield")
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Workspace")
            .background(Brand.background)
        }
    }

    private var twoColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    }

    private func refresh() async {}
}

// Table order-builder: pick a table, add items (Send to Kitchen creates or
// appends to that table's order), then Send to POS to move it into the
// held-orders queue for billing. One-way past that point, same as web —
// more items after Send to POS go through POS's own search, not back
// through here.
private struct TablesNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var tables: [TableDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var selectedTable: TableDTO?
    @State private var unsubscribeRealtime: (() -> Void)?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Tables", subtitle: "\(tables.count) tables", icon: "table.furniture")

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if isLoading && tables.isEmpty {
                        LoadingBlock(title: "Loading tables")
                    } else if tables.isEmpty {
                        BrandedCard {
                            InlineEmptyRow(icon: "table.furniture", title: "No tables set up", subtitle: "Add tables from the web app to start building dine-in orders here.")
                        }
                    } else {
                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                            ForEach(tables) { table in
                                Button {
                                    Haptics.selection()
                                    selectedTable = table
                                } label: {
                                    TableTile(table: table)
                                }
                                .buttonStyle(PressableButtonStyle())
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Tables")
            .background(Brand.background)
            .sheet(item: $selectedTable) { table in
                TableOrderSheet(table: table) {
                    Task { await load() }
                }
            }
        }
        .task { await load() }
        .task { await pollForServerUpdates() }
        .onAppear {
            unsubscribeRealtime = RealtimeClient.shared.subscribe("tables") { Task { await load() } }
        }
        .onDisappear {
            unsubscribeRealtime?()
            unsubscribeRealtime = nil
        }
    }

    // Table status (occupied/available/cleaning) is shared across every
    // device on the floor. Real-time push (see .onAppear above) is the
    // primary mechanism; this is only a safety net for a missed/dropped push.
    private func pollForServerUpdates() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { break }
            await load()
        }
    }

    private func load() async {
        isLoading = tables.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            tables = try await session.authorized { token in
                try await APIClient.shared.get("tables", token: token)
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct TableTile: View {
    let table: TableDTO

    private var statusColor: Color {
        switch table.status {
        case "available": return Brand.success
        case "occupied": return Brand.gold
        case "reserved": return .blue
        case "cleaning": return Brand.muted
        default: return Brand.danger
        }
    }

    var body: some View {
        VStack(spacing: 6) {
            Text(table.code)
                .font(.headline.weight(.bold))
                .foregroundColor(.white)
            Text(table.status.capitalized)
                .font(.caption2.weight(.bold))
                .foregroundColor(statusColor)
            Text("\(table.seats) seats")
                .font(.caption2)
                .foregroundColor(Brand.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .background(Brand.elevated)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(statusColor.opacity(0.6), lineWidth: 1.5)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct TableOrderSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let table: TableDTO
    let onFinished: () -> Void

    @State private var categories: [MenuCategoryDTO] = []
    @State private var items: [MenuItemDTO] = []
    @State private var shifts: [ShiftDTO] = []
    @State private var terminals: [TerminalDTO] = []
    @State private var cart: [String: Int] = [:]
    @State private var order: OrderReadDTO?
    @State private var isLoading = true
    @State private var isSubmitting = false
    @State private var error: String?
    @State private var selectedCategory: String?

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                VStack(spacing: 0) {
                    if let error {
                        ErrorBanner(message: error).padding(16)
                    }

                    if let terminalIssue = session.terminalIssue {
                        ErrorBanner(message: terminalIssue).padding(.horizontal, 16)
                        if !session.terminalOptions.isEmpty {
                            VStack(spacing: 8) {
                                ForEach(session.terminalOptions) { terminal in
                                    Button {
                                        Haptics.selection()
                                        session.selectTerminal(terminal)
                                    } label: {
                                        Text(terminal.name)
                                            .font(.subheadline.weight(.semibold))
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 10)
                                            .background(Brand.elevated)
                                            .foregroundColor(Brand.softGold)
                                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    }
                                }
                            }
                            .padding(.horizontal, 16)
                        }
                    }

                    if let order {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text(order.status == "held" ? "Sent to POS" : "Open order")
                                        .font(.subheadline.weight(.bold))
                                        .foregroundColor(order.status == "held" ? Brand.success : Brand.softGold)
                                    Spacer()
                                    Text(inr(order.total_minor))
                                        .font(.headline)
                                        .foregroundColor(.white)
                                }
                                if order.status == "held" {
                                    Text("Bill this from the POS held-orders queue. More items go through POS search from here on.")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                }
                            }
                        }
                        .padding(16)
                    }

                    List {
                        ForEach(filteredItems) { item in
                            MenuItemRow(item: item, quantity: cart[item.id] ?? 0) {
                                cart[item.id, default: 0] += 1
                            } decrement: {
                                let next = max((cart[item.id] ?? 0) - 1, 0)
                                cart[item.id] = next == 0 ? nil : next
                            }
                            .listRowBackground(Brand.background)
                            .listRowSeparatorTint(Brand.hairline)
                            .disabled(order?.status == "held")
                        }
                    }
                    .listStyle(.plain)
                    .disabled(isLoading)
                }
            }
            .navigationTitle("Table \(table.code)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if order?.status != "held" {
                    VStack(spacing: 8) {
                        Button {
                            Haptics.selection()
                            Task { await sendToKitchen() }
                        } label: {
                            HStack {
                                if isSubmitting { ProgressView().tint(.black) }
                                Text("Send to Kitchen")
                                    .font(.headline)
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 14)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.black)
                        .background(canSendToKitchen ? Brand.gold : Brand.muted.opacity(0.45))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .disabled(!canSendToKitchen)

                        if let order {
                            Button {
                                Haptics.selection()
                                Task { await sendToPos(order) }
                            } label: {
                                Text("Send to POS")
                                    .font(.subheadline.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(Brand.softGold)
                            .background(Brand.elevated)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                            .disabled(isSubmitting || order.lines.isEmpty)
                        }
                    }
                    .padding(16)
                    .background(.ultraThinMaterial)
                }
            }
        }
        .navigationViewStyle(.stack)
        .task { await load() }
    }

    private var filteredItems: [MenuItemDTO] {
        items.filter { $0.type == "food" || $0.type == "drink" || $0.type == "dessert" }
    }

    private var canSendToKitchen: Bool {
        !isSubmitting && !cart.isEmpty && activeShift != nil && order?.status != "held"
    }

    private var activeShift: ShiftDTO? {
        shifts.first { $0.status == "open" }
    }

    private var activeTerminal: TerminalDTO? {
        guard let terminalId = session.terminalId else { return nil }
        return terminals.first { $0.id == terminalId }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            async let loadedCategories: [MenuCategoryDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/categories", token: token)
            }
            async let loadedItems: [MenuItemDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/items", token: token)
            }
            async let loadedShifts: [ShiftDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/shifts", token: token,
                    queryItems: [URLQueryItem(name: "only_open", value: "true"), URLQueryItem(name: "limit", value: "10")]
                )
            }
            async let loadedTerminals: [TerminalDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/terminals", token: token)
            }
            async let loadedOrders: [OrderListItemDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/orders", token: token,
                    queryItems: [URLQueryItem(name: "table_id", value: table.id), URLQueryItem(name: "limit", value: "5")],
                    headers: session.terminalId.map { ["X-Terminal-Id": $0] } ?? [:]
                )
            }
            let (freshCategories, freshItems, freshShifts, freshTerminals, freshOrders) = try await (
                loadedCategories, loadedItems, loadedShifts, loadedTerminals, loadedOrders
            )
            categories = freshCategories
            items = freshItems.filter(\.is_available)
            shifts = freshShifts
            terminals = freshTerminals
            if let existing = freshOrders.first(where: { $0.status == "open" || $0.status == "held" }) {
                let full: OrderReadDTO = try await session.authorized { token in
                    try await APIClient.shared.get("pos/orders/\(existing.id)", token: token)
                }
                order = full
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func sendToKitchen() async {
        guard let shift = activeShift else {
            error = "Open a POS shift before sending an order to the kitchen."
            return
        }
        guard let terminal = activeTerminal else {
            error = "No registered POS terminal is available."
            return
        }
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil

        let lines = cart.compactMap { itemID, qty -> OrderLineCreateRequest? in
            guard qty > 0 else { return nil }
            return OrderLineCreateRequest(menu_item_id: itemID, variant_id: nil, qty: Double(qty), modifiers: nil, note: nil)
        }
        guard !lines.isEmpty else { return }

        do {
            if let existing = order {
                let updated: OrderReadDTO = try await session.authorized { token in
                    try await APIClient.shared.post(
                        "pos/orders/\(existing.id)/lines",
                        body: OrderLinesAppendRequest(lines: lines),
                        token: token
                    )
                }
                order = updated
            } else {
                let request = OrderCreateRequest(
                    type: "dine_in",
                    table_id: table.id,
                    shift_id: shift.id,
                    lines: lines,
                    delivery_via: nil,
                    customer_name: nil,
                    customer_phone: nil,
                    customer_gstin: nil,
                    customer_address: nil,
                    customer_state_code: nil,
                    place_of_supply_state_code: nil,
                    notes: nil
                )
                let headers = ["Idempotency-Key": UUID().uuidString, "X-Terminal-Id": terminal.id]
                let created: OrderReadDTO = try await session.authorized { token in
                    try await APIClient.shared.post("pos/orders", body: request, token: token, headers: headers)
                }
                order = created
            }
            cart.removeAll()
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func sendToPos(_ order: OrderReadDTO) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let updated: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.patch("pos/orders/\(order.id)/send-to-pos", body: EmptyRequest(), token: token)
            }
            self.order = updated
            Haptics.success()
            onFinished()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

// Forward-only KDS: received -> preparing -> ready -> served. Real-time
// push (see .task below) is the primary update mechanism, like the web
// KitchenScreen; the 20s poll is a fallback for a missed/dropped push on
// this kiosk-style display that's meant to stay open all shift.
private struct KitchenNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var orders: [KitchenOrderDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var unsubscribeRealtime: [() -> Void] = []

    private static let stages = ["received", "preparing", "ready", "served"]

    var body: some View {
        AppNavigation {
            ScrollView {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Kitchen", subtitle: "\(orders.count) active orders", icon: "flame")

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if isLoading && orders.isEmpty {
                        LoadingBlock(title: "Loading kitchen queue")
                    } else if orders.isEmpty {
                        BrandedCard {
                            InlineEmptyRow(icon: "checkmark.circle", title: "Queue is clear", subtitle: "No food, drink, or dessert lines waiting.")
                        }
                    } else {
                        ForEach(orders) { order in
                            KitchenOrderCard(order: order) { next in
                                Task { await advance(order, to: next) }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Kitchen")
            .background(Brand.background)
        }
        .task { await pollLoop() }
        .onAppear {
            unsubscribeRealtime = ["kitchen", "tables", "orders"].map { resource in
                RealtimeClient.shared.subscribe(resource) { Task { await load() } }
            }
        }
        .onDisappear {
            unsubscribeRealtime.forEach { $0() }
            unsubscribeRealtime = []
        }
    }

    private func nextStage(after stage: String) -> String? {
        guard let index = Self.stages.firstIndex(of: stage), index + 1 < Self.stages.count else { return nil }
        return Self.stages[index + 1]
    }

    private func load() async {
        error = nil
        do {
            orders = try await session.authorized { token in
                try await APIClient.shared.get("kitchen/queue", token: token)
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func pollLoop() async {
        isLoading = true
        await load()
        isLoading = false
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 20_000_000_000)
            await load()
        }
    }

    private func advance(_ order: KitchenOrderDTO, to nextState: String) async {
        do {
            let updated: KitchenOrderDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "kitchen/orders/\(order.id)/state",
                    body: KitchenStateUpdateRequest(state: nextState),
                    token: token
                )
            }
            if let index = orders.firstIndex(where: { $0.id == updated.id }) {
                if updated.kitchen_state == "served" {
                    orders.remove(at: index)
                } else {
                    orders[index] = updated
                }
            }
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct KitchenOrderCard: View {
    let order: KitchenOrderDTO
    let onAdvance: (String) -> Void

    private static let stages = ["received", "preparing", "ready", "served"]

    private var nextStage: String? {
        guard let index = Self.stages.firstIndex(of: order.kitchen_state), index + 1 < Self.stages.count else { return nil }
        return Self.stages[index + 1]
    }

    private var isOverdue: Bool {
        order.minutes_waiting > 15 && order.kitchen_state != "served"
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(order.table_code ?? (order.invoice_no ?? "Order \(order.id.prefix(8))"))
                            .font(.headline)
                            .foregroundColor(.white)
                        if let customer = order.customer_name, !customer.isEmpty {
                            Text(customer)
                                .font(.caption)
                                .foregroundColor(Brand.muted)
                        }
                    }
                    Spacer()
                    Text("\(order.minutes_waiting)m")
                        .font(.caption.weight(.bold))
                        .foregroundColor(isOverdue ? Brand.danger : Brand.muted)
                }

                ForEach(order.lines) { line in
                    HStack {
                        Text("\(Int(line.qty))x \(line.name)")
                            .font(.subheadline)
                            .foregroundColor(.white)
                        Spacer()
                        if let notes = line.notes, !notes.isEmpty {
                            Text(notes)
                                .font(.caption2)
                                .foregroundColor(Brand.muted)
                        }
                    }
                }

                HStack {
                    Text(order.kitchen_state.capitalized)
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background(Brand.gold.opacity(0.14))
                        .foregroundColor(Brand.softGold)
                        .clipShape(Capsule())
                    Spacer()
                    if let nextStage {
                        Button {
                            Haptics.selection()
                            onAdvance(nextStage)
                        } label: {
                            Text("Mark \(nextStage.capitalized)")
                                .font(.caption.weight(.bold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                        }
                        .buttonStyle(PressableButtonStyle())
                        .foregroundColor(.black)
                        .background(Brand.gold)
                        .clipShape(Capsule())
                    }
                }
            }
        }
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(isOverdue ? Brand.danger.opacity(0.6) : Color.clear, lineWidth: 1.5)
        )
    }
}

// "sez".capitalized reads "Sez", not the acronym web's CompanyTab.tsx shows.
private func gstRegistrationTypeLabel(_ type: String) -> String {
    type == "sez" ? "SEZ" : type.capitalized
}

// Matches frontend/src/modules/settings/tabs/AccessControlTab.tsx's
// MODULE_LABEL exactly — module.capitalized alone reads "Pos"/"Ocr" instead
// of the acronyms, and "insights_reports" needs a slash, not a plain space.
private func accessControlModuleLabel(_ module: String) -> String {
    let labels: [String: String] = [
        "pos": "POS",
        "tables": "Tables",
        "menu": "Menu",
        "inventory": "Inventory",
        "gaming": "Gaming",
        "finance": "Finance",
        "ocr": "OCR",
        "staff": "Staff",
        "insights_reports": "Insights / Reports"
    ]
    return labels[module] ?? module.replacingOccurrences(of: "_", with: " ").capitalized
}

private struct AccessControlNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var data: AccessControlDTO?
    @State private var isLoading = true
    @State private var error: String?
    @State private var pendingCellID: String?

    var body: some View {
        AppNavigation {
            ScrollView {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Access Control", subtitle: "Per-role module access", icon: "lock.shield")

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if isLoading && data == nil {
                        LoadingBlock(title: "Loading access control")
                    } else if let data, !data.modules.isEmpty {
                        ForEach(data.modules, id: \.self) { module in
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(accessControlModuleLabel(module))
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    ForEach(cells(for: module, in: data)) { cell in
                                        HStack {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(data.roles[cell.role_code] ?? cell.role_code)
                                                    .font(.subheadline.weight(.semibold))
                                                    .foregroundColor(.white)
                                                if cell.override != nil {
                                                    Text("Overridden from default (\(cell.default_allowed ? "on" : "off"))")
                                                        .font(.caption2)
                                                        .foregroundColor(Brand.gold)
                                                }
                                            }
                                            Spacer()
                                            if pendingCellID == cell.id {
                                                ProgressView().tint(Brand.gold)
                                            } else {
                                                Toggle("", isOn: Binding(
                                                    get: { cell.allowed },
                                                    set: { newValue in Task { await setOverride(role: cell.role_code, module: module, allowed: newValue) } }
                                                ))
                                                .labelsHidden()
                                                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                                if cell.override != nil {
                                                    Button {
                                                        Task { await clearOverride(role: cell.role_code, module: module) }
                                                    } label: {
                                                        Image(systemName: "arrow.uturn.backward.circle")
                                                            .foregroundColor(Brand.muted)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else if !isLoading {
                        InlineEmptyCard(icon: "lock.shield", title: "No modules configured", subtitle: "Pull to refresh, or check that this account has protected-owner access.")
                    }
                }
                .padding(16)
            }
            .navigationTitle("Access Control")
            .background(Brand.background)
        }
        .task { await load() }
    }

    private func cells(for module: String, in data: AccessControlDTO) -> [AccessCellDTO] {
        data.cells.filter { $0.module == module }.sorted { ($0.role_code) < ($1.role_code) }
    }

    private func load() async {
        isLoading = data == nil
        defer { isLoading = false }
        error = nil
        do {
            data = try await session.authorized { token in
                try await APIClient.shared.get("admin/access-control", token: token)
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func setOverride(role: String, module: String, allowed: Bool) async {
        await applyUpdate(AccessControlUpdateRequest(role_code: role, module: module, allowed: allowed))
    }

    private func clearOverride(role: String, module: String) async {
        await applyUpdate(AccessControlUpdateRequest(role_code: role, module: module, allowed: nil))
    }

    private func applyUpdate(_ request: AccessControlUpdateRequest) async {
        let cellID = "\(request.role_code):\(request.module)"
        pendingCellID = cellID
        defer { pendingCellID = nil }
        error = nil
        do {
            let updatedCell: AccessCellDTO = try await session.authorized { token in
                try await APIClient.shared.patch("admin/access-control", body: request, token: token)
            }
            if let data, let index = data.cells.firstIndex(where: { $0.id == updatedCell.id }) {
                var updatedCells = data.cells
                updatedCells[index] = updatedCell
                self.data = AccessControlDTO(roles: data.roles, modules: data.modules, cells: updatedCells)
            }
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct MenuCatalogNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var cache: AppCache
    @State private var categories: [MenuCategoryDTO] = []
    @State private var items: [MenuItemDTO] = []
    @State private var selectedCategory: String?
    @State private var search = ""
    @State private var isLoading = true
    @State private var error: String?
    @State private var editingItem: MenuItemDTO?
    @State private var isCreatingItem = false
    @State private var newCategoryName = ""
    @State private var showNewCategory = false
    @State private var recipeItem: MenuItemDTO?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Menu", subtitle: "\(filteredItems.count) items", icon: "menucard")

                    if let error {
                        ErrorBanner(message: error)
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            TextField("Search item or SKU", text: $search)
                                .nativeField()

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) {
                                    FilterChip(title: "All", isSelected: selectedCategory == nil) {
                                        Haptics.selection()
                                        selectedCategory = nil
                                    }
                                    ForEach(categories) { category in
                                        FilterChip(title: category.name, isSelected: selectedCategory == category.id) {
                                            Haptics.selection()
                                            selectedCategory = category.id
                                        }
                                    }
                                    Button {
                                        Haptics.selection()
                                        showNewCategory = true
                                    } label: {
                                        Label("Category", systemImage: "plus.circle.fill")
                                            .font(.caption.weight(.bold))
                                    }
                                    .buttonStyle(PressableButtonStyle())
                                }
                            }
                        }
                    }

                    if isLoading && items.isEmpty {
                        LoadingBlock(title: "Loading menu")
                    } else if filteredItems.isEmpty {
                        InlineEmptyCard(icon: "menucard", title: "No menu items", subtitle: "Try another category or search.")
                    } else {
                        BrandedCard {
                            VStack(spacing: 0) {
                                ForEach(filteredItems) { item in
                                    HStack(spacing: 4) {
                                        Button {
                                            Haptics.selection()
                                            editingItem = item
                                        } label: {
                                            MenuCatalogRow(item: item, categoryName: categoryName(for: item.category_id))
                                        }
                                        .buttonStyle(.plain)

                                        // What this item consumes at checkout — separate from the
                                        // main edit sheet since it's a different resource (Recipe/
                                        // RecipeLine, not MenuItem) with its own CRUD lifecycle.
                                        Button {
                                            Haptics.selection()
                                            recipeItem = item
                                        } label: {
                                            Image(systemName: "list.bullet.rectangle")
                                                .font(.subheadline)
                                                .foregroundColor(Brand.muted)
                                                .frame(width: 32, height: 32)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                    if item.id != filteredItems.last?.id {
                                        Divider().background(Brand.hairline)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Menu")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.selection()
                        isCreatingItem = true
                    } label: {
                        Image(systemName: "plus")
                    }
                    .disabled(categories.isEmpty)
                }
            }
            .background(Brand.background)
            .alert("New category", isPresented: $showNewCategory) {
                TextField("Category name", text: $newCategoryName)
                Button("Cancel", role: .cancel) { newCategoryName = "" }
                Button("Create") { Task { await createCategory() } }
            }
            .sheet(item: $editingItem) { item in
                MenuItemFormSheet(categories: categories, existingItem: item) { updated in
                    Task { await save(updated) }
                } onDelete: {
                    Task { await delete(item) }
                }
            }
            .sheet(isPresented: $isCreatingItem) {
                MenuItemFormSheet(categories: categories, existingItem: nil) { updated in
                    Task { await save(updated) }
                } onDelete: {}
            }
            .sheet(item: $recipeItem) { item in
                RecipeEditorSheet(item: item)
            }
        }
        .task { await load() }
    }

    private var filteredItems: [MenuItemDTO] {
        items.filter { item in
            let matchesCategory = selectedCategory == nil || item.category_id == selectedCategory
            let matchesSearch = search.isEmpty || item.name.localizedCaseInsensitiveContains(search) || item.sku.localizedCaseInsensitiveContains(search)
            return matchesCategory && matchesSearch
        }
    }

    private func categoryName(for id: String?) -> String {
        guard let id else { return "Uncategorised" }
        return categories.first { $0.id == id }?.name ?? "Uncategorised"
    }

    private func load() async {
        applyCachedMenu()
        isLoading = items.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let loadedCategories: [MenuCategoryDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/categories", token: token)
            }
            async let loadedItems: [MenuItemDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/items", token: token)
            }
            let (freshCategories, freshItems) = try await (loadedCategories, loadedItems)
            withAnimation(.easeOut(duration: 0.18)) {
                categories = freshCategories
                items = freshItems
                cache.categories = freshCategories
                cache.menuItems = freshItems
            }
            await cache.markSynced()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func applyCachedMenu() {
        if !cache.categories.isEmpty {
            categories = cache.categories
        }
        if !cache.menuItems.isEmpty {
            items = cache.menuItems
        }
    }

    private func createCategory() async {
        let name = newCategoryName.trimmingCharacters(in: .whitespacesAndNewlines)
        newCategoryName = ""
        guard !name.isEmpty else { return }
        error = nil
        do {
            let created: MenuCategoryDTO = try await session.authorized { token in
                try await APIClient.shared.post("menu/categories", body: CategoryCreateRequest(name: name, sort_order: categories.count), token: token)
            }
            categories.append(created)
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func save(_ draft: MenuItemFormDraft) async {
        error = nil
        do {
            if let id = draft.id {
                let updated: MenuItemDTO = try await session.authorized { token in
                    try await APIClient.shared.patch("menu/items/\(id)", body: draft.updateRequest(), token: token)
                }
                if let index = items.firstIndex(where: { $0.id == updated.id }) {
                    items[index] = updated
                }
            } else {
                let created: MenuItemDTO = try await session.authorized { token in
                    try await APIClient.shared.post("menu/items", body: draft.createRequest(), token: token)
                }
                items.append(created)
            }
            editingItem = nil
            isCreatingItem = false
            cache.menuItems = items
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func delete(_ item: MenuItemDTO) async {
        error = nil
        do {
            try await session.authorized { token -> Void in
                try await APIClient.shared.delete("menu/items/\(item.id)", token: token)
            }
            items.removeAll { $0.id == item.id }
            cache.menuItems = items
            editingItem = nil
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct MenuItemFormDraft {
    var id: String?
    var categoryId: String
    var sku: String
    var name: String
    var type: String
    var priceText: String
    var taxRatePercentText: String
    var hsnCode: String
    var priceIncludesTax: Bool
    var description: String
    var isAvailable: Bool

    static func blank(defaultCategoryId: String) -> MenuItemFormDraft {
        MenuItemFormDraft(
            id: nil, categoryId: defaultCategoryId, sku: "", name: "", type: "food",
            priceText: "", taxRatePercentText: "5", hsnCode: "", priceIncludesTax: true,
            description: "", isAvailable: true
        )
    }

    static func editing(_ item: MenuItemDTO) -> MenuItemFormDraft {
        MenuItemFormDraft(
            id: item.id, categoryId: item.category_id ?? "", sku: item.sku, name: item.name, type: item.type,
            priceText: currencyInput(item.base_price_minor), taxRatePercentText: String(format: "%.0f", item.tax_rate * 100),
            hsnCode: item.hsn_code ?? "", priceIncludesTax: item.price_includes_tax ?? true,
            description: item.description ?? "", isAvailable: item.is_available
        )
    }

    private var priceMinor: Int {
        Int(((Double(priceText) ?? 0) * 100).rounded())
    }

    private var taxRate: Double {
        (Double(taxRatePercentText) ?? 0) / 100
    }

    func createRequest() -> ItemCreateRequest {
        ItemCreateRequest(
            category_id: categoryId, sku: sku, name: name, type: type,
            base_price_minor: priceMinor, tax_rate: taxRate,
            hsn_code: hsnCode.nilIfBlank, price_includes_tax: priceIncludesTax,
            description: description.nilIfBlank
        )
    }

    func updateRequest() -> ItemUpdateRequest {
        ItemUpdateRequest(
            category_id: categoryId, name: name, base_price_minor: priceMinor, tax_rate: taxRate,
            hsn_code: hsnCode.nilIfBlank, price_includes_tax: priceIncludesTax,
            description: description.nilIfBlank, is_available: isAvailable
        )
    }
}

private struct MenuItemFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: MenuItemFormDraft
    @State private var isSubmitting = false
    let categories: [MenuCategoryDTO]
    let isEditing: Bool
    let onSave: (MenuItemFormDraft) -> Void
    let onDelete: () -> Void

    init(categories: [MenuCategoryDTO], existingItem: MenuItemDTO?, onSave: @escaping (MenuItemFormDraft) -> Void, onDelete: @escaping () -> Void) {
        self.categories = categories
        self.isEditing = existingItem != nil
        self._draft = State(initialValue: existingItem.map(MenuItemFormDraft.editing) ?? .blank(defaultCategoryId: categories.first?.id ?? ""))
        self.onSave = onSave
        self.onDelete = onDelete
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                labeledField("Name") { TextField("", text: $draft.name).nativeField() }
                                labeledField("SKU") { TextField("", text: $draft.sku).autocorrectionDisabled().nativeField() }
                                labeledField("Category") {
                                    Picker("Category", selection: $draft.categoryId) {
                                        ForEach(categories) { category in
                                            Text(category.name).tag(category.id)
                                        }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.softGold)
                                }
                                labeledField("Type") {
                                    Picker("Type", selection: $draft.type) {
                                        ForEach(menuItemTypes, id: \.self) { type in
                                            Text(type.capitalized).tag(type)
                                        }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.softGold)
                                }
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                labeledField("Price (INR)") {
                                    TextField("0.00", text: $draft.priceText)
                                        .keyboardType(.decimalPad)
                                        .nativeField()
                                }
                                labeledField("GST rate (%)") {
                                    TextField("5", text: $draft.taxRatePercentText)
                                        .keyboardType(.numberPad)
                                        .nativeField()
                                }
                                Toggle(isOn: $draft.priceIncludesTax) {
                                    Text("Price includes GST").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                }
                                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                labeledField("HSN/SAC (optional)") { TextField("", text: $draft.hsnCode).nativeField() }
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                labeledField("Description (optional)") { TextField("", text: $draft.description).nativeField() }
                                if isEditing {
                                    Toggle(isOn: $draft.isAvailable) {
                                        Text("Available on POS").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                    }
                                    .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                }
                            }
                        }

                        if isEditing {
                            Button(role: .destructive) {
                                Haptics.selection()
                                onDelete()
                            } label: {
                                Label("Delete item", systemImage: "trash")
                                    .font(.subheadline.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(Brand.danger)
                            .background(Brand.danger.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(isEditing ? "Edit item" : "New item")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(draft) }
                        .disabled(draft.name.isEmpty || draft.sku.isEmpty || draft.categoryId.isEmpty)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func labeledField<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }
}

// "What does this menu item consume?" — a minimal BOM editor mirroring
// MenuItemFormSheet/GRNReceivingSheet's patterns. Recipe carries no
// company_id of its own on the backend (tenant scope comes from the
// menu_item_id -> MenuItem join), and there is at most one is_active
// recipe per item — deduct_for_order() only ever reads that one at
// checkout, so "Remove recipe" here just deactivates it (soft-delete
// equivalent), same as the backend endpoint.
private struct RecipeEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let item: MenuItemDTO

    @State private var ingredients: [IngredientDTO] = []
    @State private var recipe: RecipeDTO?
    @State private var isLoading = true
    @State private var isBusy = false
    @State private var error: String?

    @State private var newIngredientId = ""
    @State private var newQtyText = ""
    @State private var newWastageText = "0"

    @State private var editingLineId: String?
    @State private var editIngredientId = ""
    @State private var editQtyText = ""
    @State private var editWastageText = ""

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }

                        Text("What one \(item.name) consumes from inventory at checkout. Qty is in each ingredient's stock unit; wastage % is added on top.")
                            .font(.caption)
                            .foregroundColor(Brand.muted)

                        if isLoading {
                            ProgressView().padding(.top, 24)
                        } else if let recipe {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    if recipe.lines.isEmpty {
                                        Text("No ingredients in this recipe yet — add one below.")
                                            .font(.caption)
                                            .foregroundColor(Brand.muted)
                                    } else {
                                        ForEach(recipe.lines) { line in
                                            recipeLineRow(line)
                                            if line.id != recipe.lines.last?.id {
                                                Divider().background(Brand.hairline)
                                            }
                                        }
                                    }
                                }
                            }

                            BrandedCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text("Add ingredient").font(.caption).foregroundColor(Brand.muted)
                                    Picker("", selection: $newIngredientId) {
                                        Text("Select ingredient").tag("")
                                        ForEach(ingredients) { ing in
                                            Text("\(ing.name) (\(ing.base_unit))").tag(ing.id)
                                        }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.gold)
                                    HStack(spacing: 12) {
                                        fieldLabel("Qty") {
                                            TextField("0", text: $newQtyText)
                                                .keyboardType(.decimalPad)
                                                .nativeField()
                                        }
                                        fieldLabel("Waste %") {
                                            TextField("0", text: $newWastageText)
                                                .keyboardType(.decimalPad)
                                                .nativeField()
                                        }
                                    }
                                    Button {
                                        Task { await addLine() }
                                    } label: {
                                        HStack {
                                            if isBusy { ProgressView() }
                                            Label("Add line", systemImage: "plus")
                                        }
                                        .frame(maxWidth: .infinity)
                                    }
                                    .buttonStyle(PressableButtonStyle())
                                    .disabled(isBusy || newIngredientId.isEmpty || (Double(newQtyText) ?? 0) <= 0)
                                }
                            }

                            HStack {
                                Text("Version \(recipe.version) · active")
                                    .font(.caption2)
                                    .foregroundColor(Brand.muted)
                                Spacer()
                                Button(role: .destructive) {
                                    Task { await removeRecipe() }
                                } label: {
                                    Label("Remove recipe", systemImage: "trash")
                                        .font(.caption.weight(.semibold))
                                }
                                .disabled(isBusy)
                            }
                        } else {
                            BrandedCard {
                                VStack(spacing: 12) {
                                    Text("No recipe yet — this item won't deduct any stock at checkout.")
                                        .font(.subheadline)
                                        .foregroundColor(Brand.muted)
                                        .multilineTextAlignment(.center)
                                    Button {
                                        Task { await createRecipe() }
                                    } label: {
                                        HStack {
                                            if isBusy { ProgressView() }
                                            Label("Set up recipe", systemImage: "plus")
                                        }
                                    }
                                    .buttonStyle(PressableButtonStyle())
                                    .disabled(isBusy || ingredients.isEmpty)
                                    if ingredients.isEmpty {
                                        Text("Add ingredients in Inventory first.")
                                            .font(.caption2)
                                            .foregroundColor(Brand.danger)
                                    }
                                }
                                .frame(maxWidth: .infinity)
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Recipe")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
        .task { await load() }
    }

    @ViewBuilder
    private func recipeLineRow(_ line: RecipeLineDTO) -> some View {
        if editingLineId == line.id {
            VStack(alignment: .leading, spacing: 8) {
                Picker("", selection: $editIngredientId) {
                    ForEach(ingredients) { ing in
                        Text("\(ing.name) (\(ing.base_unit))").tag(ing.id)
                    }
                }
                .pickerStyle(.menu)
                .tint(Brand.gold)
                HStack(spacing: 12) {
                    fieldLabel("Qty") {
                        TextField("0", text: $editQtyText).keyboardType(.decimalPad).nativeField()
                    }
                    fieldLabel("Waste %") {
                        TextField("0", text: $editWastageText).keyboardType(.decimalPad).nativeField()
                    }
                }
                HStack {
                    Button("Cancel") { editingLineId = nil }
                        .foregroundColor(Brand.muted)
                    Spacer()
                    Button {
                        Task { await saveLineEdit(lineId: line.id) }
                    } label: {
                        if isBusy { ProgressView() } else { Text("Save") }
                    }
                    .foregroundColor(Brand.gold)
                    .disabled(isBusy)
                }
            }
            .padding(.vertical, 4)
        } else {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(ingredientName(line.ingredient_id))
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.white)
                    Text("\(formattedNumber(line.qty)) \(ingredientUnit(line.ingredient_id)) · \(formattedNumber(line.wastage_pct * 100))% waste")
                        .font(.caption2)
                        .foregroundColor(Brand.muted)
                }
                Spacer()
                Button {
                    Haptics.selection()
                    startEdit(line)
                } label: {
                    Image(systemName: "pencil").foregroundColor(Brand.muted)
                }
                Button {
                    Task { await deleteLine(line.id) }
                } label: {
                    Image(systemName: "trash").foregroundColor(Brand.danger)
                }
                .disabled(isBusy)
            }
            .padding(.vertical, 4)
        }
    }

    @ViewBuilder
    private func fieldLabel<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func ingredientName(_ id: String) -> String {
        ingredients.first { $0.id == id }?.name ?? "Unknown ingredient"
    }
    private func ingredientUnit(_ id: String) -> String {
        ingredients.first { $0.id == id }?.base_unit ?? ""
    }
    private func formattedNumber(_ value: Double) -> String {
        value.truncatingRemainder(dividingBy: 1) == 0 ? String(format: "%.0f", value) : String(format: "%.2f", value)
    }

    private func startEdit(_ line: RecipeLineDTO) {
        editingLineId = line.id
        editIngredientId = line.ingredient_id
        editQtyText = formattedNumber(line.qty)
        editWastageText = formattedNumber(line.wastage_pct * 100)
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            async let loadedIngredients: [IngredientDTO] = session.authorized { token in
                try await APIClient.shared.get("inventory/ingredients", token: token)
            }
            async let loadedRecipes: [RecipeDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "inventory/recipes", token: token,
                    queryItems: [URLQueryItem(name: "menu_item_id", value: item.id)]
                )
            }
            let (freshIngredients, freshRecipes) = try await (loadedIngredients, loadedRecipes)
            ingredients = freshIngredients
            recipe = freshRecipes.first { $0.is_active }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func createRecipe() async {
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let created: RecipeDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "inventory/recipes",
                    body: RecipeCreateRequest(menu_item_id: item.id, name: "\(item.name) recipe", yield_qty: 1, lines: []),
                    token: token
                )
            }
            recipe = created
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func removeRecipe() async {
        guard let recipe else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            try await session.authorized { token -> Void in
                try await APIClient.shared.delete("inventory/recipes/\(recipe.id)", token: token)
            }
            self.recipe = nil
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func addLine() async {
        guard let recipe, let qty = Double(newQtyText), qty > 0, !newIngredientId.isEmpty else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        let wastagePct = (Double(newWastageText) ?? 0) / 100
        do {
            let line: RecipeLineDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "inventory/recipes/\(recipe.id)/lines",
                    body: RecipeLineRequest(ingredient_id: newIngredientId, qty: qty, wastage_pct: wastagePct),
                    token: token
                )
            }
            self.recipe = RecipeDTO(
                id: recipe.id, menu_item_id: recipe.menu_item_id, name: recipe.name,
                yield_qty: recipe.yield_qty, version: recipe.version, is_active: recipe.is_active,
                cost_minor: recipe.cost_minor, lines: recipe.lines + [line]
            )
            newIngredientId = ""
            newQtyText = ""
            newWastageText = "0"
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func saveLineEdit(lineId: String) async {
        guard let recipe else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        let qty = Double(editQtyText) ?? 0
        let wastagePct = (Double(editWastageText) ?? 0) / 100
        do {
            let updated: RecipeLineDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "inventory/recipes/\(recipe.id)/lines/\(lineId)",
                    body: RecipeLineUpdateRequest(ingredient_id: editIngredientId, qty: qty, wastage_pct: wastagePct),
                    token: token
                )
            }
            self.recipe = RecipeDTO(
                id: recipe.id, menu_item_id: recipe.menu_item_id, name: recipe.name,
                yield_qty: recipe.yield_qty, version: recipe.version, is_active: recipe.is_active,
                cost_minor: recipe.cost_minor,
                lines: recipe.lines.map { $0.id == lineId ? updated : $0 }
            )
            editingLineId = nil
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func deleteLine(_ lineId: String) async {
        guard let recipe else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            try await session.authorized { token -> Void in
                try await APIClient.shared.delete("inventory/recipes/\(recipe.id)/lines/\(lineId)", token: token)
            }
            self.recipe = RecipeDTO(
                id: recipe.id, menu_item_id: recipe.menu_item_id, name: recipe.name,
                yield_qty: recipe.yield_qty, version: recipe.version, is_active: recipe.is_active,
                cost_minor: recipe.cost_minor,
                lines: recipe.lines.filter { $0.id != lineId }
            )
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

// Local notifications so overtime/aging alarms fire even when staff aren't
// looking at the exact screen (a different tab, phone locked) — the in-app
// AudioServicesPlaySystemSound alarm in watchForOvertime()/
// watchHeldOrdersOvertime() only fires while that screen is the one on
// front, tied to its SwiftUI .task lifecycle. This is sync-based rather than
// hooked into every start/extend/stop/bill/void call site: every time fresh
// server state loads, it reconciles scheduled notifications to match exactly
// what the server says is active, so it can't drift out of sync with a call
// site that forgets to (re)schedule. Only covers not-yet-due items — anything
// already overdue by the time this syncs relies on the existing in-app alarm,
// same as before; this only adds the "still works if you're not looking"
// case on top.
private enum LocalAlarmScheduler {
    private static var didRequestAuthorization = false
    static let enabledKey = "dcompany.alarmsEnabled"

    // Defaults to on — matches the always-on behavior every build before
    // this setting existed, so upgrading the app doesn't silently go quiet.
    static var isEnabled: Bool {
        let defaults = UserDefaults.standard
        return defaults.object(forKey: enabledKey) == nil ? true : defaults.bool(forKey: enabledKey)
    }

    static func requestAuthorizationIfNeeded() {
        guard isEnabled, !didRequestAuthorization else { return }
        didRequestAuthorization = true
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func syncGamingOvertime(sessions: [GamingSessionDTO], stationName: (String) -> String) {
        let center = UNUserNotificationCenter.current()
        // When disabled, liveIds is empty so the cleanup pass below clears
        // any already-scheduled ones and nothing new gets added — same
        // self-healing sync this already did for stale sessions.
        let liveIds: Set<String> = isEnabled ? Set(sessions.compactMap { gamingSession -> String? in
            guard let endsAt = gamingSession.timer_ends_at, endsAt > Date() else { return nil }
            return "gaming-overtime-\(gamingSession.id)"
        }) : []
        center.getPendingNotificationRequests { requests in
            let staleIds = requests.map(\.identifier).filter { $0.hasPrefix("gaming-overtime-") && !liveIds.contains($0) }
            if !staleIds.isEmpty { center.removePendingNotificationRequests(withIdentifiers: staleIds) }
        }
        guard isEnabled else { return }
        for gamingSession in sessions {
            guard let endsAt = gamingSession.timer_ends_at, endsAt > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.title = "Session time is up"
            content.body = "\(stationName(gamingSession.station_id)) has reached its timer."
            content.sound = .default
            // Clamped, not just guarded above: UNTimeIntervalNotificationTrigger
            // requires a strictly positive interval, and endsAt could fall inside
            // the gap between the guard's Date() read and this one.
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, endsAt.timeIntervalSinceNow), repeats: false)
            center.add(UNNotificationRequest(identifier: "gaming-overtime-\(gamingSession.id)", content: content, trigger: trigger))
        }
    }

    static func syncHeldOrderAging(orders: [OrderListItemDTO]) {
        let center = UNUserNotificationCenter.current()
        let cutoff: TimeInterval = 15 * 60
        let liveIds: Set<String> = isEnabled ? Set(orders.compactMap { order -> String? in
            guard order.created_at.addingTimeInterval(cutoff) > Date() else { return nil }
            return "held-order-\(order.id)"
        }) : []
        center.getPendingNotificationRequests { requests in
            let staleIds = requests.map(\.identifier).filter { $0.hasPrefix("held-order-") && !liveIds.contains($0) }
            if !staleIds.isEmpty { center.removePendingNotificationRequests(withIdentifiers: staleIds) }
        }
        guard isEnabled else { return }
        for order in orders {
            let fireAt = order.created_at.addingTimeInterval(cutoff)
            guard fireAt > Date() else { continue }
            let content = UNMutableNotificationContent()
            content.title = "Held order waiting"
            content.body = "\(order.invoice_no ?? "An order") has been on hold for 15 minutes."
            content.sound = .default
            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, fireAt.timeIntervalSinceNow), repeats: false)
            center.add(UNNotificationRequest(identifier: "held-order-\(order.id)", content: content, trigger: trigger))
        }
    }
}

// Reservations — makes the two booking flows that already existed (table
// reservations, gaming station bookings) visible after creation. Mirrors
// OrdersNativeView's tabbed-queue pattern: a segmented picker up top, a
// list below, real-time push + a poll fallback to stay current across
// devices, and an inline action row on anything still pending.
private enum ReservationsTab: String, CaseIterable, Identifiable {
    case tables, gaming
    var id: String { rawValue }
    var title: String { self == .tables ? "Tables" : "Gaming" }
}

private struct ReservationsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var tab: ReservationsTab = .tables
    @State private var tableReservations: [ReservationDTO] = []
    @State private var bookings: [GamingBookingDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var busyId: String?
    @State private var unsubscribeRealtime: [() -> Void] = []

    var body: some View {
        AppNavigation {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    ForEach(ReservationsTab.allCases) { t in
                        Text(t.title).tag(t)
                    }
                }
                .pickerStyle(.segmented)
                .padding(16)

                ScrollView {
                    VStack(spacing: 16) {
                        if let error {
                            ErrorBanner(message: error)
                        }

                        if tab == .tables {
                            tablesList
                        } else {
                            gamingList
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Reservations")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.selection()
                        Task { await load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .background(Brand.background)
        }
        .task { await load() }
        .task { await pollForServerUpdates() }
        .onAppear {
            unsubscribeRealtime = ["tables", "gaming"].map { resource in
                RealtimeClient.shared.subscribe(resource) { Task { await load() } }
            }
        }
        .onDisappear {
            unsubscribeRealtime.forEach { $0() }
            unsubscribeRealtime = []
        }
    }

    @ViewBuilder
    private var tablesList: some View {
        if isLoading && tableReservations.isEmpty {
            LoadingBlock(title: "Loading reservations")
        } else if tableReservations.isEmpty {
            BrandedCard {
                InlineEmptyRow(icon: "calendar", title: "No upcoming reservations", subtitle: "New bookings taken from Tables will show up here.")
            }
        } else {
            ForEach(tableReservations) { r in
                ReservationCard(
                    title: "Table \(r.table_code)",
                    subtitle: "\(r.guest_name) \u{00B7} \(r.party_size) guest\(r.party_size == 1 ? "" : "s")",
                    startsAt: r.starts_at,
                    contact: r.contact,
                    status: r.status,
                    arrivedLabel: "Mark arrived",
                    isBusy: busyId == r.id,
                    onArrived: { Task { await actOnReservation(r, status: "seated") } },
                    onNoShow: { Task { await actOnReservation(r, status: "no_show") } },
                    onCancel: { Task { await actOnReservation(r, status: "cancelled") } }
                )
            }
        }
    }

    @ViewBuilder
    private var gamingList: some View {
        if isLoading && bookings.isEmpty {
            LoadingBlock(title: "Loading bookings")
        } else if bookings.isEmpty {
            BrandedCard {
                InlineEmptyRow(icon: "gamecontroller", title: "No upcoming bookings", subtitle: "New bookings taken from Gaming will show up here.")
            }
        } else {
            ForEach(bookings) { b in
                ReservationCard(
                    title: b.station_code,
                    subtitle: "\(b.guest_name) \u{00B7} \(b.party_size) guest\(b.party_size == 1 ? "" : "s")",
                    startsAt: b.starts_at,
                    contact: b.contact,
                    status: b.status,
                    arrivedLabel: "Mark used",
                    isBusy: busyId == b.id,
                    onArrived: { Task { await actOnBooking(b, status: "consumed") } },
                    onNoShow: { Task { await actOnBooking(b, status: "no_show") } },
                    onCancel: { Task { await actOnBooking(b, status: "cancelled") } }
                )
            }
        }
    }

    // Real-time push (see .onAppear above) is the primary mechanism; this is
    // only a safety net for a missed/dropped push — same pattern as every
    // other shared-state queue in this app (Tables, Kitchen, Orders).
    private func pollForServerUpdates() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { break }
            await load()
        }
    }

    private func load() async {
        isLoading = tableReservations.isEmpty && bookings.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let loadedReservations: [ReservationDTO] = session.authorized { token in
                try await APIClient.shared.get("tables/reservations", token: token)
            }
            async let loadedBookings: [GamingBookingDTO] = session.authorized { token in
                try await APIClient.shared.get("gaming/bookings", token: token)
            }
            let (freshReservations, freshBookings) = try await (loadedReservations, loadedBookings)
            tableReservations = freshReservations
            bookings = freshBookings
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func actOnReservation(_ r: ReservationDTO, status: String) async {
        busyId = r.id
        defer { busyId = nil }
        do {
            let updated: ReservationDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "tables/reservations/\(r.id)/status",
                    body: ReservationStatusUpdateRequest(status: status),
                    token: token
                )
            }
            if let index = tableReservations.firstIndex(where: { $0.id == updated.id }) {
                tableReservations[index] = updated
            }
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func actOnBooking(_ b: GamingBookingDTO, status: String) async {
        busyId = b.id
        defer { busyId = nil }
        do {
            let updated: GamingBookingDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "gaming/bookings/\(b.id)/status",
                    body: BookingStatusUpdateRequest(status: status),
                    token: token
                )
            }
            if let index = bookings.firstIndex(where: { $0.id == updated.id }) {
                bookings[index] = updated
            }
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct ReservationCard: View {
    let title: String
    let subtitle: String
    let startsAt: Date
    let contact: String?
    let status: String
    let arrivedLabel: String
    let isBusy: Bool
    let onArrived: () -> Void
    let onNoShow: () -> Void
    let onCancel: () -> Void

    // held is the only actionable state — every other status (seated /
    // consumed / no_show / cancelled) is terminal (see the backend's
    // _RESERVATION_TRANSITIONS / _BOOKING_TRANSITIONS), so the action row
    // below only renders while status == "held".
    private var statusColor: Color {
        switch status {
        case "seated", "consumed": return Brand.success
        case "no_show": return Brand.danger
        case "cancelled": return Brand.muted
        default: return Brand.gold
        }
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title)
                            .font(.headline)
                            .foregroundColor(.white)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                        Text(DateFormatters.shortDateTime.string(from: startsAt) + (contact.map { " \u{00B7} \($0)" } ?? ""))
                            .font(.caption2)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Text(status.replacingOccurrences(of: "_", with: " ").capitalized)
                        .font(.caption2.weight(.bold))
                        .foregroundColor(statusColor)
                }

                if status == "held" {
                    HStack(spacing: 8) {
                        Button {
                            Haptics.selection()
                            onArrived()
                        } label: {
                            Label(arrivedLabel, systemImage: "checkmark.circle")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(PressableButtonStyle())
                        .disabled(isBusy)

                        Button {
                            Haptics.selection()
                            onNoShow()
                        } label: {
                            Label("No-show", systemImage: "person.fill.xmark")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(PressableButtonStyle())
                        .disabled(isBusy)

                        Button {
                            Haptics.selection()
                            onCancel()
                        } label: {
                            Label("Cancel", systemImage: "xmark.circle")
                                .font(.caption.weight(.semibold))
                                .foregroundColor(Brand.danger)
                        }
                        .buttonStyle(PressableButtonStyle())
                        .disabled(isBusy)
                    }
                }
            }
        }
    }
}

private struct GamingNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    let openTab: (NativeTab) -> Void
    @State private var stations: [GamingStationDTO] = []
    @State private var activeSessions: [GamingSessionDTO] = []
    @State private var shifts: [ShiftDTO] = []
    @State private var sessionDraft: GamingSessionDraft?
    @State private var finishedSession: GamingSessionDTO?
    @State private var sentSession: SendToPosResponseDTO?
    @State private var isLoading = true
    @State private var isSubmitting = false
    @State private var error: String?
    @State private var alarmedSessionIDs: Set<String> = []
    @State private var unsubscribeRealtime: (() -> Void)?

    private var overtimeSessions: [GamingSessionDTO] {
        activeSessions.filter { gs in
            guard let endsAt = gs.timer_ends_at else { return false }
            return Date() >= endsAt
        }
    }

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(
                        title: "Gaming",
                        subtitle: "\(activeSessions.count) active sessions",
                        icon: "gamecontroller"
                    )

                    if !network.isOnline {
                        NetworkBanner(label: network.connectionLabel)
                    }

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if !overtimeSessions.isEmpty {
                        ErrorBanner(message: "\(overtimeSessions.count) session(s) over their timer: \(overtimeSessions.map { stationName(for: $0.station_id) }.joined(separator: ", "))")
                    }

                    if let finishedSession {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Session closed. Amount \(inr(finishedSession.amount_minor ?? 0)).")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundColor(Brand.success)
                                if let sentSession {
                                    Text("Sent to POS - held order ready to bill (\(inr(sentSession.amount_minor))).")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                } else {
                                    Button {
                                        Task { await sendToPos(finishedSession) }
                                    } label: {
                                        HStack {
                                            if isSubmitting { ProgressView().tint(.black) }
                                            Text("Send to POS")
                                                .font(.subheadline.weight(.bold))
                                        }
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 11)
                                        .background(Brand.gold)
                                        .foregroundColor(.black)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    }
                                    .buttonStyle(.plain)
                                    .disabled(isSubmitting)
                                }
                            }
                        }
                    }

                    ServiceControlCard(canUseProtectedControls: session.hasProtectedOwnerAccess) {
                        openTab(.pos)
                    }

                    ServiceKindSummaryStrip(
                        stations: stations,
                        activeSessions: activeSessions,
                        canManageServices: session.hasProtectedOwnerAccess
                    ) {
                        openTab(.pos)
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 14) {
                            HStack {
                                Text("Active Sessions")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                Spacer()
                                Text(activeShift == nil ? "No open shift" : "Shift open")
                                    .font(.caption.weight(.bold))
                                    .foregroundColor(activeShift == nil ? Brand.danger : Brand.success)
                            }

                            if activeSessions.isEmpty {
                                InlineEmptyRow(icon: "timer", title: "No active sessions", subtitle: "Start a PS5 or gaming station session.")
                            } else {
                                ForEach(activeSessions) { gamingSession in
                                    GamingSessionCard(
                                        session: gamingSession,
                                        stationName: stationName(for: gamingSession.station_id),
                                        isSubmitting: isSubmitting,
                                        onStop: { Task { await stop(gamingSession) } },
                                        onExtend: { Task { await extendTimer(gamingSession) } },
                                        onClearTimer: { Task { await clearTimer(gamingSession) } }
                                    )
                                }
                            }
                        }
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text("Stations")
                            .font(.headline)
                            .foregroundColor(.white)
                            .padding(.horizontal, 2)

                        if isLoading && stations.isEmpty {
                            LoadingBlock(title: "Loading gaming stations")
                        } else if stations.isEmpty {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 12) {
                                    InlineEmptyRow(icon: "gamecontroller", title: "No stations set up", subtitle: "Create PS5, VR, simulator, shisha, and streaming services before taking timed bills.")
                                    if session.hasProtectedOwnerAccess {
                                        NavigationLink {
                                            StationManagementNativeView()
                                        } label: {
                                            Label("Add services", systemImage: "plus.circle.fill")
                                                .font(.subheadline.weight(.bold))
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 12)
                                                .background(Brand.gold)
                                                .foregroundColor(.black)
                                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                        }
                                    }
                                }
                            }
                        } else {
                            LazyVGrid(columns: twoColumns, spacing: 12) {
                                ForEach(stations.filter(\.is_active)) { station in
                                    GamingStationCard(
                                        station: station,
                                        activeSession: activeSession(for: station.id)
                                    ) {
                                        Haptics.selection()
                                        sessionDraft = GamingSessionDraft(station: station)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Gaming")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Task { await load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .background(Brand.background)
            .sheet(item: $sessionDraft) { draft in
                GamingSessionSheet(draft: draft, activeShift: activeShift, isSubmitting: isSubmitting) { updatedDraft in
                    Task { await start(updatedDraft) }
                }
            }
        }
        .task { await load() }
        .task { await watchForOvertime() }
        .task { await pollForServerUpdates() }
        .onAppear {
            LocalAlarmScheduler.requestAuthorizationIfNeeded()
            unsubscribeRealtime = RealtimeClient.shared.subscribe("gaming") { Task { await load() } }
        }
        .onDisappear {
            unsubscribeRealtime?()
            unsubscribeRealtime = nil
        }
    }

    // load() only ran on this device's own actions (start/stop/extend)
    // otherwise. On a multi-device floor, another staff member stopping a
    // session from a different phone would leave this screen showing it as
    // still running — and overtime as still counting — until the push above
    // (or this fallback poll, for a missed/dropped push) catches it up.
    private func pollForServerUpdates() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { break }
            await load()
        }
    }

    private var twoColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    }

    private var activeShift: ShiftDTO? {
        shifts.first { $0.status == "open" }
    }

    private func stationName(for stationID: String) -> String {
        stations.first { $0.id == stationID }?.name ?? "Station"
    }

    private func activeSession(for stationID: String) -> GamingSessionDTO? {
        activeSessions.first { $0.station_id == stationID && $0.status == "active" }
    }

    private func load() async {
        isLoading = stations.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let loadedStations: [GamingStationDTO] = session.authorized { token in
                try await APIClient.shared.get("gaming/stations", token: token)
            }
            async let loadedSessions: [GamingSessionDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "gaming/sessions",
                    token: token,
                    queryItems: [
                        URLQueryItem(name: "status", value: "active"),
                        URLQueryItem(name: "limit", value: "80")
                    ]
                )
            }
            async let loadedShifts: [ShiftDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/shifts",
                    token: token,
                    queryItems: [
                        URLQueryItem(name: "only_open", value: "true"),
                        URLQueryItem(name: "limit", value: "10")
                    ]
                )
            }
            let (freshStations, freshSessions, freshShifts) = try await (loadedStations, loadedSessions, loadedShifts)
            withAnimation(.easeOut(duration: 0.18)) {
                stations = freshStations
                activeSessions = freshSessions
                shifts = freshShifts
            }
            LocalAlarmScheduler.syncGamingOvertime(sessions: freshSessions, stationName: stationName(for:))
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func start(_ draft: GamingSessionDraft) async {
        guard let shift = activeShift else {
            error = "Open a POS shift before starting a gaming session."
            return
        }

        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        finishedSession = nil

        let partyLabel = draft.participantSummary
        let displayName: String?
        if let name = draft.customerName.nilIfBlank {
            displayName = "\(name) - \(partyLabel)"
        } else {
            displayName = partyLabel
        }

        let request = GamingSessionStartRequest(
            station_id: draft.station.id,
            shift_id: shift.id,
            customer_name: displayName,
            customer_phone: draft.customerPhone.nilIfBlank,
            timer_minutes: draft.timerMinutes
        )

        do {
            let created: GamingSessionDTO = try await session.authorized { token in
                try await APIClient.shared.post("gaming/sessions/start", body: request, token: token)
            }
            sessionDraft = nil
            Haptics.success()
            activeSessions.insert(created, at: 0)
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func stop(_ gamingSession: GamingSessionDTO) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil

        do {
            let ended: GamingSessionDTO = try await session.authorized { token in
                try await APIClient.shared.post("gaming/sessions/\(gamingSession.id)/stop", body: EmptyRequest(), token: token)
            }
            finishedSession = ended
            sentSession = nil
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func extendTimer(_ gamingSession: GamingSessionDTO) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        // Absolute minutes-from-start_at, not "add 15 to whatever's left" — the
        // backend has no separate extend endpoint, only "set the new total".
        let elapsedSinceStart = Int(Date().timeIntervalSince(gamingSession.start_at) / 60)
        let currentTarget = gamingSession.timer_minutes ?? max(elapsedSinceStart, 0)
        let request = SessionTimerUpdateRequest(timer_minutes: currentTarget + 15)
        do {
            let updated: GamingSessionDTO = try await session.authorized { token in
                try await APIClient.shared.patch("gaming/sessions/\(gamingSession.id)/timer", body: request, token: token)
            }
            if let index = activeSessions.firstIndex(where: { $0.id == updated.id }) {
                activeSessions[index] = updated
            }
            alarmedSessionIDs.remove(updated.id)
            Haptics.success()
            LocalAlarmScheduler.syncGamingOvertime(sessions: activeSessions, stationName: stationName(for:))
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func clearTimer(_ gamingSession: GamingSessionDTO) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        let request = SessionTimerUpdateRequest(timer_minutes: nil)
        do {
            let updated: GamingSessionDTO = try await session.authorized { token in
                try await APIClient.shared.patch("gaming/sessions/\(gamingSession.id)/timer", body: request, token: token)
            }
            if let index = activeSessions.firstIndex(where: { $0.id == updated.id }) {
                activeSessions[index] = updated
            }
            alarmedSessionIDs.remove(updated.id)
            LocalAlarmScheduler.syncGamingOvertime(sessions: activeSessions, stationName: stationName(for:))
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func sendToPos(_ gamingSession: GamingSessionDTO) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let result: SendToPosResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "gaming/sessions/\(gamingSession.id)/send-to-pos",
                    body: EmptyRequest(),
                    token: token
                )
            }
            sentSession = result
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    // Lightweight overtime alarm: no separate notification-permission flow
    // (unlike the web version's browser Notification API) — plays a system
    // sound + haptic once per session the moment it first crosses into
    // overtime, and again if it's cleared then re-extended. The persistent
    // banner above (overtimeSessions) is what nags continuously; this is
    // just the "you should look at this now" chime.
    private func watchForOvertime() async {
        while !Task.isCancelled {
            for gamingSession in overtimeSessions where !alarmedSessionIDs.contains(gamingSession.id) {
                alarmedSessionIDs.insert(gamingSession.id)
                if LocalAlarmScheduler.isEnabled { AudioServicesPlaySystemSound(1005) }
                Haptics.selection()
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }
}

private enum POSMode: String, CaseIterable, Identifiable {
    case all
    case sessions
    case menu

    var id: String { rawValue }

    var title: String {
        switch self {
        case .all: return "Bill"
        case .sessions: return "Sessions"
        case .menu: return "Menu"
        }
    }

    var showsSessions: Bool {
        self == .all || self == .sessions
    }

    var showsMenu: Bool {
        self == .all || self == .menu
    }
}

private struct POSNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @EnvironmentObject private var cache: AppCache
    @State private var categories: [MenuCategoryDTO] = []
    @State private var items: [MenuItemDTO] = []
    @State private var shifts: [ShiftDTO] = []
    @State private var terminals: [TerminalDTO] = []
    @State private var stations: [GamingStationDTO] = []
    @State private var activeSessions: [GamingSessionDTO] = []
    @State private var heldOrders: [OrderListItemDTO] = []
    @State private var company: CompanyDTO?
    @State private var resumeOrder: OrderReadDTO?
    @State private var voidReason = ""
    // Retry safety: a checkout attempt keeps the SAME idempotency key and
    // remembers the order it already created (if order-creation succeeded
    // but payment failed) so retrying after a network blip reuses that
    // order instead of minting a duplicate. Reset whenever the cart/sheet
    // is dismissed or a charge fully succeeds.
    @State private var checkoutOrderKey = UUID().uuidString
    @State private var checkoutDiscountKey = UUID().uuidString
    @State private var checkoutPointsKey = UUID().uuidString
    @State private var checkoutPaymentKey = UUID().uuidString
    @State private var pendingCheckoutOrder: OrderReadDTO?
    // Held-order aging alarm, mirroring the web app's 15-minute threshold /
    // 30s-poll pattern (frontend/src/lib/alarm.ts): a system sound once per
    // order the moment it first crosses 15 minutes held, plus a persistent
    // banner that stays up the whole time it's overdue.
    @State private var alarmedHeldOrderIDs: Set<String> = []
    @State private var selectedCategory: String?
    @State private var search = ""
    @State private var cart: [String: Int] = [:]
    @State private var checkoutDraft: CheckoutDraft?
    @State private var sessionDraft: GamingSessionDraft?
    @State private var closingShift: ShiftDTO?
    @State private var posMode: POSMode = .all
    @State private var createdInvoice: String?
    @State private var lastChargedOrder: OrderReadDTO?
    @State private var finishedSession: GamingSessionDTO?
    @State private var sessionBillNote: String?
    @State private var isLoading = true
    @State private var isSubmitting = false
    @State private var error: String?
    @State private var unsubscribeRealtime: [() -> Void] = []

    var body: some View {
        AppNavigation {
            VStack(spacing: 0) {
                if !network.isOnline {
                    NetworkBanner(label: network.connectionLabel)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                }

                if let terminalIssue = session.terminalIssue {
                    ErrorBanner(message: terminalIssue)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                    if !session.terminalOptions.isEmpty {
                        VStack(spacing: 8) {
                            ForEach(session.terminalOptions) { terminal in
                                Button {
                                    Haptics.selection()
                                    session.selectTerminal(terminal)
                                } label: {
                                    Text(terminal.name)
                                        .font(.subheadline.weight(.semibold))
                                        .frame(maxWidth: .infinity)
                                        .padding(.vertical, 10)
                                        .background(Brand.elevated)
                                        .foregroundColor(Brand.softGold)
                                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 8)
                    }
                }

                posModeControl
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
                    .padding(.bottom, posMode.showsMenu ? 6 : 10)
                    .background(Brand.background)

                if posMode.showsMenu {
                    categoryScroller
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                        .background(Brand.background)
                }

                if let error {
                    ErrorBanner(message: error)
                        .padding(.horizontal, 16)
                }

                if let createdInvoice {
                    VStack(spacing: 8) {
                        SuccessBanner(message: "Charged \(createdInvoice). Cart is clear.")
                        if let lastChargedOrder {
                            Button {
                                Haptics.selection()
                                ReceiptPrinter.print(order: lastChargedOrder, context: ReceiptContext(company: company, cashierName: session.me?.name))
                            } label: {
                                Label("Print receipt", systemImage: "printer")
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(.black)
                            .background(Brand.gold)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                }

                if let sessionBillNote {
                    SuccessBanner(message: sessionBillNote)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                }

                if !overdueHeldOrders.isEmpty {
                    ErrorBanner(message: "\(overdueHeldOrders.count) held order(s) waiting 15+ minutes: \(overdueHeldOrders.compactMap(\.invoice_no).joined(separator: ", "))")
                        .padding(.horizontal, 16)
                        .padding(.bottom, 8)
                }

                POSShiftCommandCard(
                    shift: activeShift,
                    terminal: activeTerminal,
                    cartItems: cartRows.reduce(0) { $0 + $1.quantity },
                    cartTotalMinor: cartTotal,
                    activeSessionCount: activeSessions.filter { $0.status == "active" }.count,
                    serviceCount: activeServiceStations.count,
                    isSubmitting: isSubmitting
                ) {
                    Haptics.selection()
                    withAnimation(.easeInOut(duration: 0.16)) {
                        posMode = .sessions
                    }
                } openShift: {
                    Haptics.selection()
                    Task { await openShift() }
                } closeShift: {
                    Haptics.selection()
                    closingShift = activeShift
                } openMenu: {
                    Haptics.selection()
                    withAnimation(.easeInOut(duration: 0.16)) {
                        posMode = .menu
                    }
                } reviewBill: {
                    Haptics.selection()
                    if !cartRows.isEmpty {
                        checkoutDraft = CheckoutDraft()
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)

                if !cartRows.isEmpty {
                    POSCartEditorCard(
                        rows: cartRows,
                        totalMinor: cartTotal,
                        increment: increment,
                        decrement: decrement,
                        clear: clearCart
                    ) {
                        Haptics.selection()
                        checkoutDraft = CheckoutDraft()
                    }
                    .padding(.horizontal, 16)
                    .padding(.bottom, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }

                List {
                    if !heldOrders.isEmpty {
                        Section(header: sectionHeader("Held Orders")) {
                            ForEach(heldOrders) { held in
                                Button {
                                    Haptics.selection()
                                    Task { await openHeldOrder(held) }
                                } label: {
                                    OrderHistoryRow(order: held)
                                }
                                .buttonStyle(.plain)
                                .listRowBackground(Brand.background)
                                .listRowSeparatorTint(Brand.hairline)
                            }
                        }
                    }

                    if posMode.showsSessions && (!stations.isEmpty || !activeSessions.isEmpty) {
                        Section(header: sectionHeader("Sessions")) {
                            if activeServiceStations.isEmpty {
                                VStack(alignment: .leading, spacing: 10) {
                                    InlineEmptyRow(icon: "gamecontroller", title: "No service stations", subtitle: "Add PS5, VR, simulator, shisha, or streaming services before billing timed sessions.")
                                    if session.hasProtectedOwnerAccess {
                                        NavigationLink {
                                            StationManagementNativeView()
                                        } label: {
                                            Label("Manage services", systemImage: "slider.horizontal.3")
                                                .font(.subheadline.weight(.bold))
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 12)
                                                .background(Brand.gold)
                                                .foregroundColor(.black)
                                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                        }
                                    }
                                }
                                .listRowBackground(Brand.background)
                                .listRowSeparator(.hidden)
                            } else {
                                ForEach(activeServiceStations) { station in
                                    POSServiceStationRow(
                                        station: station,
                                        activeSession: activeSession(for: station.id),
                                        isSubmitting: isSubmitting
                                    ) {
                                        Haptics.selection()
                                        sessionDraft = GamingSessionDraft(station: station)
                                    } onStop: { gamingSession in
                                        Task { await stop(gamingSession) }
                                    }
                                    .listRowBackground(Brand.background)
                                    .listRowSeparatorTint(Brand.hairline)
                                }
                            }
                        }
                    }

                    if posMode.showsMenu {
                        Section(header: sectionHeader("Menu")) {
                            if isLoading && items.isEmpty {
                                ForEach(0..<6, id: \.self) { _ in
                                    MenuItemSkeletonRow()
                                        .listRowBackground(Brand.background)
                                        .listRowSeparator(.hidden)
                                }
                            } else if filteredItems.isEmpty {
                                InlineEmptyRow(icon: "menucard", title: "No menu items", subtitle: "Try another category or search.")
                                    .listRowBackground(Brand.background)
                                    .listRowSeparator(.hidden)
                            } else {
                                ForEach(filteredItems) { item in
                                    MenuItemRow(item: item, quantity: cart[item.id] ?? 0) {
                                        increment(item)
                                    } decrement: {
                                        decrement(item)
                                    }
                                    .listRowBackground(Brand.background)
                                    .listRowSeparatorTint(Brand.hairline)
                                }
                            }
                        }
                    }
                }
                .listStyle(.plain)
                .premiumListChrome()
                .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search menu")
                .safeAreaInset(edge: .bottom) {
                    if !cartRows.isEmpty {
                        cartSummaryBar
                    }
                }
                .background(Brand.background)
            }
            .navigationTitle("POS")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.selection()
                        Task { await load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .sheet(item: $checkoutDraft) { draft in
                CheckoutSheet(
                    draft: draft,
                    cartRows: cartRows,
                    shift: activeShift,
                    terminal: activeTerminal,
                    totalMinor: cartTotal,
                    isSubmitting: isSubmitting,
                    upiVpa: company?.upi_vpa,
                    businessName: company?.name ?? "D Company"
                ) { updatedDraft in
                    Task { await submitCheckout(updatedDraft) }
                }
            }
            .sheet(item: $sessionDraft) { draft in
                GamingSessionSheet(draft: draft, activeShift: activeShift, isSubmitting: isSubmitting) { updatedDraft in
                    Task { await start(updatedDraft) }
                }
            }
            .sheet(item: $closingShift) { shift in
                CloseShiftSheet(shift: shift) {
                    Task { await load() }
                }
            }
            .sheet(item: $resumeOrder) { order in
                HeldOrderBillSheet(
                    order: order,
                    terminal: activeTerminal,
                    isSubmitting: isSubmitting,
                    canVoid: session.hasProtectedOwnerAccess || activeShift.map(session.isShiftOpener) ?? false,
                    upiVpa: company?.upi_vpa,
                    businessName: company?.name ?? "D Company",
                    onBill: { method, tenderedMinor in
                        Task { await billHeldOrder(order, method: method, tenderedMinor: tenderedMinor) }
                    },
                    onVoid: { reason in
                        Task { await voidHeldOrder(order, reason: reason) }
                    },
                    onAddLines: { lines in
                        Task { await addLinesToHeldOrder(order, lines: lines) }
                    },
                    onApplyDiscount: { manualDiscountMinor in
                        Task { await applyDiscountToHeldOrder(order, manualDiscountMinor: manualDiscountMinor) }
                    },
                    onRedeemPoints: { points in
                        Task { await redeemPointsForHeldOrder(order, points: points) }
                    },
                    menuItems: items
                )
            }
        }
        .task { await load() }
        .task { await watchHeldOrdersOvertime() }
        .task { await pollForServerUpdates() }
        .onAppear {
            LocalAlarmScheduler.requestAuthorizationIfNeeded()
            unsubscribeRealtime = ["orders", "shifts", "gaming"].map { resource in
                RealtimeClient.shared.subscribe(resource) { Task { await load() } }
            }
        }
        .onDisappear {
            unsubscribeRealtime.forEach { $0() }
            unsubscribeRealtime = []
        }
    }

    // Same reasoning as GamingNativeView's pollForServerUpdates: this view's
    // own activeSessions/heldOrders otherwise only ever refresh once at mount
    // plus whenever this device's own actions trigger a reload. Real-time
    // push (see .onAppear above) is the primary mechanism now; this is only
    // a safety net for a missed/dropped push.
    private func pollForServerUpdates() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { break }
            await load()
        }
    }

    private var overdueHeldOrders: [OrderListItemDTO] {
        let cutoff = Date().addingTimeInterval(-15 * 60)
        return heldOrders.filter { $0.created_at < cutoff }
    }

    private func watchHeldOrdersOvertime() async {
        while !Task.isCancelled {
            for held in overdueHeldOrders where !alarmedHeldOrderIDs.contains(held.id) {
                alarmedHeldOrderIDs.insert(held.id)
                if LocalAlarmScheduler.isEnabled { AudioServicesPlaySystemSound(1005) }
                Haptics.selection()
            }
            try? await Task.sleep(nanoseconds: 30_000_000_000)
        }
    }

    private func applyDiscountToHeldOrder(_ order: OrderReadDTO, manualDiscountMinor: Int) async {
        error = nil
        do {
            let updated: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "pos/orders/\(order.id)/discount",
                    body: OrderDiscountRequest(manual_discount_minor: manualDiscountMinor),
                    token: token,
                    headers: ["Idempotency-Key": "held-discount-\(order.id)-\(UUID().uuidString)"]
                )
            }
            resumeOrder = updated
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func redeemPointsForHeldOrder(_ order: OrderReadDTO, points: Int) async {
        error = nil
        do {
            let updated: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "pos/orders/\(order.id)/points",
                    body: OrderPointsRequest(points: points),
                    token: token,
                    headers: ["Idempotency-Key": "held-points-\(order.id)-\(UUID().uuidString)"]
                )
            }
            resumeOrder = updated
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func addLinesToHeldOrder(_ order: OrderReadDTO, lines: [OrderLineCreateRequest]) async {
        guard !lines.isEmpty else { return }
        error = nil
        do {
            let updated: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "pos/orders/\(order.id)/lines",
                    body: OrderLinesAppendRequest(lines: lines),
                    token: token,
                    headers: ["Idempotency-Key": "held-lines-\(order.id)-\(UUID().uuidString)"]
                )
            }
            resumeOrder = updated
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private var categoryScroller: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                FilterChip(title: "All", isSelected: selectedCategory == nil) {
                    Haptics.selection()
                    selectedCategory = nil
                }
                ForEach(categories) { category in
                    FilterChip(title: category.name, isSelected: selectedCategory == category.id) {
                        Haptics.selection()
                        selectedCategory = category.id
                    }
                }
            }
            .padding(.vertical, 2)
        }
    }

    private var posModeControl: some View {
        HStack(spacing: 8) {
            ForEach(POSMode.allCases) { mode in
                Button {
                    Haptics.selection()
                    withAnimation(.easeInOut(duration: 0.16)) {
                        posMode = mode
                    }
                } label: {
                    Text(mode.title)
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 10)
                        .background(posMode == mode ? Brand.gold : Brand.surface)
                        .foregroundColor(posMode == mode ? .black : Brand.softGold)
                        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
                .buttonStyle(.plain)
            }
        }
    }

    private var cartSummaryBar: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(Brand.hairline)
                .frame(height: 1)
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("\(cartRows.reduce(0) { $0 + $1.quantity }) items")
                        .font(.caption.weight(.semibold))
                        .foregroundColor(Brand.muted)
                    Text(inr(cartTotal))
                        .font(.title3.weight(.bold))
                        .foregroundColor(.white)
                }
                Spacer()
                Button {
                    Haptics.selection()
                    checkoutDraft = CheckoutDraft()
                } label: {
                    Label("Review bill", systemImage: "arrow.right.circle.fill")
                        .font(.headline)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 12)
                }
                .buttonStyle(PressableButtonStyle())
                .background(Brand.gold)
                .foregroundColor(.black)
                .clipShape(Capsule())
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial)
        }
    }

    private var filteredItems: [MenuItemDTO] {
        items.filter { item in
            let matchesCategory = selectedCategory == nil || item.category_id == selectedCategory
            let matchesSearch = search.isEmpty || item.name.localizedCaseInsensitiveContains(search) || item.sku.localizedCaseInsensitiveContains(search)
            return matchesCategory && matchesSearch
        }
    }

    private var cartRows: [CartLine] {
        items.compactMap { item in
            guard let quantity = cart[item.id], quantity > 0 else { return nil }
            return CartLine(item: item, quantity: quantity)
        }
    }

    private var cartTotal: Int {
        cartRows.reduce(0) { $0 + ($1.item.base_price_minor * $1.quantity) }
    }

    private func increment(_ item: MenuItemDTO) {
        Haptics.impact()
        withAnimation(.spring(response: 0.22, dampingFraction: 0.85)) {
            cart[item.id, default: 0] += 1
        }
    }

    private func decrement(_ item: MenuItemDTO) {
        Haptics.selection()
        withAnimation(.spring(response: 0.22, dampingFraction: 0.85)) {
            let next = max((cart[item.id] ?? 0) - 1, 0)
            cart[item.id] = next == 0 ? nil : next
        }
    }

    private func clearCart() {
        Haptics.selection()
        withAnimation(.spring(response: 0.22, dampingFraction: 0.85)) {
            cart.removeAll()
        }
        resetCheckoutRetryState()
    }

    private func resetCheckoutRetryState() {
        checkoutOrderKey = UUID().uuidString
        checkoutDiscountKey = UUID().uuidString
        checkoutPointsKey = UUID().uuidString
        checkoutPaymentKey = UUID().uuidString
        pendingCheckoutOrder = nil
    }

    private var activeServiceStations: [GamingStationDTO] {
        stations.filter(\.is_active).sorted { lhs, rhs in
            if lhs.kind.title == rhs.kind.title {
                return lhs.code.localizedStandardCompare(rhs.code) == .orderedAscending
            }
            return lhs.kind.title.localizedStandardCompare(rhs.kind.title) == .orderedAscending
        }
    }

    private var activeShift: ShiftDTO? {
        shifts.first { $0.status == "open" }
    }

    private var activeTerminal: TerminalDTO? {
        // Once a shift is open, its own terminal_id is authoritative (that's
        // the terminal it was actually opened on). Before that, fall back to
        // this device's resolved terminal — never to "whichever terminal the
        // API returned first", which silently mislabels data on any branch
        // with more than one terminal.
        if let shiftTerminalID = activeShift?.terminal_id {
            return terminals.first { $0.id == shiftTerminalID }
        }
        guard let terminalId = session.terminalId else { return nil }
        return terminals.first { $0.id == terminalId }
    }

    private func activeSession(for stationID: String) -> GamingSessionDTO? {
        activeSessions.first { $0.station_id == stationID && $0.status == "active" }
    }

    private func load() async {
        applyCachedMenu()
        isLoading = items.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let loadedCategories: [MenuCategoryDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/categories", token: token)
            }
            async let loadedItems: [MenuItemDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/items", token: token)
            }
            async let loadedShifts: [ShiftDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/shifts",
                    token: token,
                    queryItems: [
                        URLQueryItem(name: "only_open", value: "true"),
                        URLQueryItem(name: "limit", value: "10")
                    ]
                )
            }
            async let loadedTerminals: [TerminalDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/terminals", token: token)
            }
            async let loadedStations: [GamingStationDTO] = session.authorized { token in
                try await APIClient.shared.get("gaming/stations", token: token)
            }
            async let loadedSessions: [GamingSessionDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "gaming/sessions",
                    token: token,
                    queryItems: [
                        URLQueryItem(name: "status", value: "active"),
                        URLQueryItem(name: "limit", value: "80")
                    ]
                )
            }
            async let loadedHeldOrders: [OrderListItemDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/orders",
                    token: token,
                    queryItems: [
                        URLQueryItem(name: "status", value: "held"),
                        URLQueryItem(name: "limit", value: "50")
                    ],
                    headers: session.terminalId.map { ["X-Terminal-Id": $0] } ?? [:]
                )
            }
            async let loadedCompany: CompanyDTO = session.authorized { token in
                try await APIClient.shared.get("settings/company", token: token)
            }

            let (freshCategories, freshItems, freshShifts, freshTerminals, freshStations, freshSessions, freshHeldOrders, freshCompany) = try await (
                loadedCategories,
                loadedItems,
                loadedShifts,
                loadedTerminals,
                loadedStations,
                loadedSessions,
                loadedHeldOrders,
                loadedCompany
            )

            withAnimation(.easeOut(duration: 0.18)) {
                cache.categories = freshCategories
                cache.menuItems = freshItems
                categories = freshCategories
                items = freshItems.filter(\.is_available)
                shifts = freshShifts
                terminals = freshTerminals
                stations = freshStations
                activeSessions = freshSessions
                heldOrders = freshHeldOrders
                company = freshCompany
            }
            // Gaming-overtime notifications are synced from GamingNativeView only
            // (its .task fires regardless of which tab is selected, same as this
            // one — SwiftUI's TabView mounts every tab eagerly). Syncing the same
            // "gaming-overtime-*" identifier namespace from two independently
            // timed fetches raced: a slower/stale response here could resurrect a
            // notification Gaming's own load() had just correctly cancelled.
            LocalAlarmScheduler.syncHeldOrderAging(orders: freshHeldOrders)
            await cache.markSynced()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func openHeldOrder(_ item: OrderListItemDTO) async {
        error = nil
        do {
            let full: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.get("pos/orders/\(item.id)", token: token)
            }
            resumeOrder = full
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func billHeldOrder(_ order: OrderReadDTO, method: PaymentMethod, tenderedMinor: Int?) async {
        guard let terminal = activeTerminal else {
            error = "No registered POS terminal is available for this shift."
            return
        }
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        let paymentRequest = PaymentCreateRequest(
            method: method.rawValue,
            amount_minor: order.total_minor,
            tendered_minor: tenderedMinor,
            ref_external: nil,
            tip_minor: 0
        )
        // Deterministic per-order key (not a fresh UUID every call) so
        // retrying a failed bill attempt on this same order never risks a
        // double charge.
        let headers = ["Idempotency-Key": "bill-\(order.id)", "X-Terminal-Id": terminal.id]
        do {
            let _: PaymentResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post("pos/orders/\(order.id)/payments", body: paymentRequest, token: token, headers: headers)
            }
            resumeOrder = nil
            createdInvoice = order.invoice_no ?? "Order \(order.id.prefix(8))"
            lastChargedOrder = order
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func voidHeldOrder(_ order: OrderReadDTO, reason: String) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            try await session.authorized { token -> Void in
                try await APIClient.shared.delete("pos/orders/\(order.id)", body: VoidOrderRequest(reason: reason), token: token)
            }
            resumeOrder = nil
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func applyCachedMenu() {
        if !cache.categories.isEmpty {
            categories = cache.categories
        }
        if !cache.menuItems.isEmpty {
            items = cache.menuItems.filter(\.is_available)
        }
    }

    private func openShift() async {
        guard activeShift == nil else { return }
        guard let terminal = activeTerminal else {
            error = session.terminalIssue ?? "No POS terminal is registered. Add a terminal in Settings before opening a shift."
            return
        }

        isSubmitting = true
        defer { isSubmitting = false }
        error = nil

        do {
            let _: ShiftOpenResponse = try await session.authorized { token in
                try await APIClient.shared.post(
                    "pos/shifts/open",
                    body: ShiftOpenRequest(opening_float_minor: 0),
                    token: token,
                    headers: ["X-Terminal-Id": terminal.id]
                )
            }
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
            // Most likely cause of this failing is someone else already
            // opened a shift here since this screen last synced — refresh
            // now so the card immediately flips to "Shift: Open" instead of
            // still looking like nothing is open (load() clears the error
            // above once it has the real state, which is the point: the
            // corrected shift status is more useful here than the message).
            await load()
        }
    }

    private func start(_ draft: GamingSessionDraft) async {
        guard let shift = activeShift else {
            error = "Open a POS shift before starting a session."
            return
        }

        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        finishedSession = nil
        sessionBillNote = nil

        let partyLabel = draft.participantSummary
        let displayName: String?
        if let name = draft.customerName.nilIfBlank {
            displayName = "\(name) - \(partyLabel)"
        } else {
            displayName = partyLabel
        }

        let request = GamingSessionStartRequest(
            station_id: draft.station.id,
            shift_id: shift.id,
            customer_name: displayName,
            customer_phone: draft.customerPhone.nilIfBlank,
            timer_minutes: draft.timerMinutes
        )

        do {
            let created: GamingSessionDTO = try await session.authorized { token in
                try await APIClient.shared.post("gaming/sessions/start", body: request, token: token)
            }
            sessionDraft = nil
            activeSessions.insert(created, at: 0)
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func stop(_ gamingSession: GamingSessionDTO) async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil

        do {
            let ended: GamingSessionDTO = try await session.authorized { token in
                try await APIClient.shared.post("gaming/sessions/\(gamingSession.id)/stop", body: EmptyRequest(), token: token)
            }
            let addedToCart = addSessionChargeToCart(for: ended)
            finishedSession = ended
            if addedToCart {
                sessionBillNote = "Session closed. Matching service added to the bill. Estimate: \(inr(ended.amount_minor ?? 0))."
            } else {
                sessionBillNote = "Session closed at \(inr(ended.amount_minor ?? 0)). No matching service item was found, so add it manually before charging."
            }
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func addSessionChargeToCart(for ended: GamingSessionDTO) -> Bool {
        guard let station = stations.first(where: { $0.id == ended.station_id }),
              let item = serviceMenuItem(for: station) else {
            return false
        }
        withAnimation(.spring(response: 0.22, dampingFraction: 0.85)) {
            cart[item.id, default: 0] += 1
        }
        return true
    }

    private func serviceMenuItem(for station: GamingStationDTO) -> MenuItemDTO? {
        let tokens = serviceTokens(for: station.kind)
        return items.first { item in
            let haystack = "\(item.name) \(item.sku) \(item.type)".uppercased()
            return tokens.contains { haystack.contains($0) }
        }
    }

    private func serviceTokens(for kind: GamingStationKind) -> [String] {
        switch kind {
        case .ps5:
            return ["PS5", "PLAYSTATION", "CONSOLE"]
        case .vr:
            return ["VR", "VIRTUAL"]
        case .simulator:
            return ["SIMULATOR", "SIM "]
        case .projector:
            return ["PROJECTOR", "THEATRE", "THEATER"]
        case .hookah:
            return ["SHISHA", "HOOKAH"]
        case .streaming:
            return ["STREAMING", "STREAM", "BOOTH"]
        case .station:
            return ["SESSION", "SERVICE"]
        }
    }

    private func submitCheckout(_ draft: CheckoutDraft) async {
        guard !cartRows.isEmpty else { return }
        guard let shift = activeShift else {
            error = "No open POS shift. Open a shift before charging."
            return
        }
        guard let terminal = activeTerminal else {
            error = "No registered POS terminal is available for this shift."
            return
        }
        guard draft.isCashTenderReady(totalMinor: max(0, cartTotal - draft.discountMinor - draft.pointsRedeemed * 10) + draft.tipMinor) else {
            error = "Cash received is below the bill total."
            return
        }

        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        createdInvoice = nil
        lastChargedOrder = nil

        do {
            // Reuse the order from a prior attempt on this same checkout if
            // one exists (order-creation succeeded but payment failed last
            // time) — both the order-creation and payment calls keep the
            // SAME idempotency key across retries, so a network blip never
            // creates a duplicate order or double-charges.
            var order: OrderReadDTO
            if let pendingCheckoutOrder {
                order = pendingCheckoutOrder
            } else {
                let orderRequest = OrderCreateRequest(
                    type: draft.serviceType.rawValue,
                    table_id: nil,
                    shift_id: shift.id,
                    lines: cartRows.map {
                        OrderLineCreateRequest(
                            menu_item_id: $0.item.id,
                            variant_id: nil,
                            qty: Double($0.quantity),
                            modifiers: nil,
                            note: nil
                        )
                    },
                    delivery_via: draft.serviceType == .delivery ? draft.deliveryAggregator.rawValue : nil,
                    customer_name: draft.customerName.nilIfBlank,
                    customer_phone: draft.customerPhone.nilIfBlank,
                    customer_gstin: draft.customerGSTIN.nilIfBlank,
                    customer_address: nil,
                    customer_state_code: draft.isInterstate ? draft.customerStateCode.nilIfBlank : nil,
                    place_of_supply_state_code: draft.isInterstate ? draft.customerStateCode.nilIfBlank : nil,
                    notes: draft.note.nilIfBlank
                )
                let orderHeaders = [
                    "Idempotency-Key": checkoutOrderKey,
                    "X-Terminal-Id": terminal.id
                ]
                let created: OrderReadDTO = try await session.authorized { token in
                    try await APIClient.shared.post("pos/orders", body: orderRequest, token: token, headers: orderHeaders)
                }
                pendingCheckoutOrder = created
                order = created
            }

            // Applied after order creation (not baked into the create-order
            // payload) so the same PATCH can be safely retried under its own
            // idempotency key without touching order creation or payment.
            if draft.discountMinor > 0 && draft.discountMinor != order.manual_discount_minor {
                let discountRequest = OrderDiscountRequest(manual_discount_minor: draft.discountMinor)
                let discountHeaders = [
                    "Idempotency-Key": checkoutDiscountKey,
                    "X-Terminal-Id": terminal.id
                ]
                let discounted: OrderReadDTO = try await session.authorized { token in
                    try await APIClient.shared.patch("pos/orders/\(order.id)/discount", body: discountRequest, token: token, headers: discountHeaders)
                }
                pendingCheckoutOrder = discounted
                order = discounted
            }

            if draft.pointsRedeemed > 0 && draft.pointsRedeemed != order.points_redeemed {
                let pointsRequest = OrderPointsRequest(points: draft.pointsRedeemed)
                let pointsHeaders = [
                    "Idempotency-Key": checkoutPointsKey,
                    "X-Terminal-Id": terminal.id
                ]
                let redeemed: OrderReadDTO = try await session.authorized { token in
                    try await APIClient.shared.patch("pos/orders/\(order.id)/points", body: pointsRequest, token: token, headers: pointsHeaders)
                }
                pendingCheckoutOrder = redeemed
                order = redeemed
            }

            let paymentRequest = PaymentCreateRequest(
                method: draft.paymentMethod.rawValue,
                amount_minor: order.total_minor,
                tendered_minor: draft.tenderedMinor(totalMinor: order.total_minor + draft.tipMinor),
                ref_external: nil,
                tip_minor: draft.tipMinor
            )
            let paymentHeaders = [
                "Idempotency-Key": checkoutPaymentKey,
                "X-Terminal-Id": terminal.id
            ]
            let _: PaymentResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post("pos/orders/\(order.id)/payments", body: paymentRequest, token: token, headers: paymentHeaders)
            }

            checkoutDraft = nil
            cart.removeAll()
            createdInvoice = order.invoice_no ?? "Order \(order.id.prefix(8))"
            lastChargedOrder = order
            resetCheckoutRetryState()
            if draft.printReceiptAfterCharge {
                let receiptContext = ReceiptContext(company: company, cashierName: session.me?.name)
                await MainActor.run {
                    ReceiptPrinter.print(order: order, context: receiptContext)
                }
            }
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct InventoryNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @EnvironmentObject private var cache: AppCache
    @State private var ingredients: [IngredientDTO] = []
    @State private var branches: [BranchDTO] = []
    @State private var search = ""
    @State private var showLowOnly = false
    @State private var isLoading = true
    @State private var error: String?
    @State private var editingIngredient: IngredientDTO?
    @State private var adjustingIngredient: IngredientDTO?
    @State private var isCreatingIngredient = false
    @State private var showSuppliers = false
    @State private var showGRN = false

    var body: some View {
        AppNavigation {
            VStack(spacing: 0) {
                if !network.isOnline {
                    NetworkBanner(label: network.connectionLabel)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
                }

                Toggle(isOn: $showLowOnly) {
                    Label("Low stock only", systemImage: "exclamationmark.triangle")
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.white)
                }
                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                .padding(.horizontal, 16)
                .padding(.vertical, 12)

                if let error {
                    ErrorBanner(message: error)
                        .padding(.horizontal, 16)
                }

                if !ingredients.isEmpty {
                    InventorySnapshotHeader(ingredients: ingredients)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                }

                List {
                    if isLoading && ingredients.isEmpty {
                        ForEach(0..<7, id: \.self) { _ in
                            InventorySkeletonRow()
                                .listRowBackground(Brand.background)
                                .listRowSeparator(.hidden)
                        }
                    } else if filteredIngredients.isEmpty {
                        InlineEmptyRow(icon: "cube.box", title: "No stock found", subtitle: showLowOnly ? "No low stock items match this search." : "Try another search.")
                            .listRowBackground(Brand.background)
                            .listRowSeparator(.hidden)
                    } else {
                        ForEach(filteredIngredients) { ingredient in
                            Button {
                                Haptics.selection()
                                editingIngredient = ingredient
                            } label: {
                                InventoryRow(ingredient: ingredient)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing) {
                                Button {
                                    Haptics.selection()
                                    adjustingIngredient = ingredient
                                } label: {
                                    Label("Adjust", systemImage: "plusminus")
                                }
                                .tint(Brand.gold)
                            }
                            .listRowBackground(Brand.background)
                            .listRowSeparatorTint(Brand.hairline)
                        }
                    }
                }
                .listStyle(.plain)
                .premiumListChrome()
                .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search stock")
                .background(Brand.background)
            }
            .navigationTitle("Inventory")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button { isCreatingIngredient = true } label: { Label("New ingredient", systemImage: "plus") }
                        Button { showGRN = true } label: { Label("Receive stock (GRN)", systemImage: "tray.and.arrow.down") }
                        Button { showSuppliers = true } label: { Label("Suppliers", systemImage: "shippingbox") }
                        Button { Task { await load() } } label: { Label("Refresh", systemImage: "arrow.clockwise") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .background(Brand.background)
            .sheet(item: $editingIngredient) { ingredient in
                IngredientFormSheet(existingIngredient: ingredient) { draft in
                    Task { await save(draft) }
                } onDelete: {
                    Task { await delete(ingredient) }
                }
            }
            .sheet(isPresented: $isCreatingIngredient) {
                IngredientFormSheet(existingIngredient: nil) { draft in
                    Task { await save(draft) }
                } onDelete: {}
            }
            .sheet(item: $adjustingIngredient) { ingredient in
                StockAdjustmentSheet(ingredient: ingredient, branches: branches) { qtyDelta, type, note, branchId in
                    Task { await adjust(ingredient, qtyDelta: qtyDelta, type: type, note: note, branchId: branchId) }
                }
            }
            .sheet(isPresented: $showSuppliers) {
                SuppliersSheet()
            }
            .sheet(isPresented: $showGRN) {
                GRNReceivingSheet(ingredients: ingredients, branches: branches) {
                    Task { await load() }
                }
            }
        }
        .task { await load() }
    }

    private var filteredIngredients: [IngredientDTO] {
        ingredients.filter { item in
            let matchesSearch = search.isEmpty || item.name.localizedCaseInsensitiveContains(search) || item.sku.localizedCaseInsensitiveContains(search)
            let matchesStock = !showLowOnly || item.isLowStock
            return matchesSearch && matchesStock
        }
    }

    private func load() async {
        if cache.hasInventoryData {
            ingredients = cache.ingredients
        }
        isLoading = ingredients.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let loadedIngredients: [IngredientDTO] = session.authorized { token in
                try await APIClient.shared.get("inventory/ingredients", token: token)
            }
            async let loadedBranches: [BranchDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/branches", token: token)
            }
            let (freshIngredients, freshBranches) = try await (loadedIngredients, loadedBranches)
            withAnimation(.easeOut(duration: 0.18)) {
                ingredients = freshIngredients
                branches = freshBranches
                cache.ingredients = freshIngredients
            }
            await cache.markSynced()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func save(_ draft: IngredientFormDraft) async {
        error = nil
        do {
            if let id = draft.id {
                let updated: IngredientDTO = try await session.authorized { token in
                    try await APIClient.shared.patch("inventory/ingredients/\(id)", body: draft.updateRequest(), token: token)
                }
                if let index = ingredients.firstIndex(where: { $0.id == updated.id }) {
                    ingredients[index] = updated
                }
            } else {
                let created: IngredientDTO = try await session.authorized { token in
                    try await APIClient.shared.post("inventory/ingredients", body: draft.createRequest(), token: token)
                }
                ingredients.append(created)
            }
            editingIngredient = nil
            isCreatingIngredient = false
            cache.ingredients = ingredients
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func delete(_ ingredient: IngredientDTO) async {
        error = nil
        do {
            try await session.authorized { token -> Void in
                try await APIClient.shared.delete("inventory/ingredients/\(ingredient.id)", token: token)
            }
            ingredients.removeAll { $0.id == ingredient.id }
            cache.ingredients = ingredients
            editingIngredient = nil
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func adjust(_ ingredient: IngredientDTO, qtyDelta: Double, type: String, note: String?, branchId: String) async {
        error = nil
        do {
            let _: JSONValue = try await session.authorized { token in
                try await APIClient.shared.post(
                    "inventory/adjustments",
                    body: StockAdjustmentRequest(ingredient_id: ingredient.id, branch_id: branchId, qty_delta: qtyDelta, type: type, note: note),
                    token: token
                )
            }
            adjustingIngredient = nil
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct IngredientFormDraft {
    var id: String?
    var sku: String
    var name: String
    var baseUnit: String
    var reorderThresholdText: String
    var reorderQtyText: String

    static func blank() -> IngredientFormDraft {
        IngredientFormDraft(id: nil, sku: "", name: "", baseUnit: "unit", reorderThresholdText: "0", reorderQtyText: "0")
    }

    static func editing(_ ingredient: IngredientDTO) -> IngredientFormDraft {
        IngredientFormDraft(
            id: ingredient.id, sku: ingredient.sku, name: ingredient.name, baseUnit: ingredient.base_unit,
            reorderThresholdText: String(ingredient.reorder_threshold), reorderQtyText: String(ingredient.reorder_qty)
        )
    }

    func createRequest() -> IngredientCreateRequest {
        IngredientCreateRequest(sku: sku, name: name, base_unit: baseUnit, reorder_threshold: Double(reorderThresholdText) ?? 0, reorder_qty: Double(reorderQtyText) ?? 0)
    }

    func updateRequest() -> IngredientUpdateRequest {
        IngredientUpdateRequest(name: name, base_unit: baseUnit, reorder_threshold: Double(reorderThresholdText), reorder_qty: Double(reorderQtyText))
    }
}

private struct IngredientFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: IngredientFormDraft
    let isEditing: Bool
    let onSave: (IngredientFormDraft) -> Void
    let onDelete: () -> Void

    init(existingIngredient: IngredientDTO?, onSave: @escaping (IngredientFormDraft) -> Void, onDelete: @escaping () -> Void) {
        self.isEditing = existingIngredient != nil
        self._draft = State(initialValue: existingIngredient.map(IngredientFormDraft.editing) ?? .blank())
        self.onSave = onSave
        self.onDelete = onDelete
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                fieldLabel("Name") { TextField("", text: $draft.name).nativeField() }
                                fieldLabel("SKU") { TextField("", text: $draft.sku).autocorrectionDisabled().nativeField() }
                                fieldLabel("Base unit") {
                                    Picker("Unit", selection: $draft.baseUnit) {
                                        ForEach(baseUnits, id: \.self) { unit in Text(unit).tag(unit) }
                                    }
                                    .pickerStyle(.segmented)
                                    .tint(Brand.gold)
                                }
                                fieldLabel("Reorder threshold") {
                                    TextField("0", text: $draft.reorderThresholdText).keyboardType(.decimalPad).nativeField()
                                }
                                fieldLabel("Reorder quantity") {
                                    TextField("0", text: $draft.reorderQtyText).keyboardType(.decimalPad).nativeField()
                                }
                            }
                        }
                        if isEditing {
                            Button(role: .destructive) {
                                Haptics.selection()
                                onDelete()
                            } label: {
                                Label("Delete ingredient", systemImage: "trash")
                                    .font(.subheadline.weight(.bold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(Brand.danger)
                            .background(Brand.danger.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(isEditing ? "Edit ingredient" : "New ingredient")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(draft) }.disabled(draft.name.isEmpty || draft.sku.isEmpty)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func fieldLabel<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }
}

private struct StockAdjustmentSheet: View {
    @Environment(\.dismiss) private var dismiss
    let ingredient: IngredientDTO
    let branches: [BranchDTO]
    let onSubmit: (Double, String, String?, String) -> Void

    @State private var qtyText = ""
    @State private var isNegative = true
    @State private var type = "waste"
    @State private var note = ""
    @State private var branchId: String

    private static let types = ["waste", "damage", "transfer", "adjustment"]

    init(ingredient: IngredientDTO, branches: [BranchDTO], onSubmit: @escaping (Double, String, String?, String) -> Void) {
        self.ingredient = ingredient
        self.branches = branches
        self.onSubmit = onSubmit
        self._branchId = State(initialValue: branches.first?.id ?? "")
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                VStack(spacing: 16) {
                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("\(ingredient.name) — current \(NumberFormatters.decimal.string(from: NSNumber(value: ingredient.current_qty)) ?? "0") \(ingredient.base_unit)")
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(.white)

                            Picker("Direction", selection: $isNegative) {
                                Text("Remove").tag(true)
                                Text("Add").tag(false)
                            }
                            .pickerStyle(.segmented)
                            .tint(Brand.gold)

                            TextField("Quantity", text: $qtyText)
                                .keyboardType(.decimalPad)
                                .nativeField()

                            Picker("Reason", selection: $type) {
                                ForEach(Self.types, id: \.self) { t in Text(t.capitalized).tag(t) }
                            }
                            .pickerStyle(.segmented)
                            .tint(Brand.gold)

                            if branches.count > 1 {
                                Picker("Branch", selection: $branchId) {
                                    ForEach(branches) { branch in Text(branch.name).tag(branch.id) }
                                }
                                .pickerStyle(.menu)
                            }

                            TextField("Note (optional)", text: $note).nativeField()
                        }
                    }
                    Spacer()
                }
                .padding(16)
            }
            .navigationTitle("Adjust stock")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    guard let qty = Double(qtyText), !branchId.isEmpty else { return }
                    onSubmit(isNegative ? -qty : qty, type, note.nilIfBlank, branchId)
                } label: {
                    Text("Apply adjustment")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background((Double(qtyText) != nil && !branchId.isEmpty) ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(Double(qtyText) == nil || branchId.isEmpty)
            }
        }
        .navigationViewStyle(.stack)
    }
}

// Per-line draft for GRNReceivingSheet — expiry/lot code are both optional,
// mirroring the web GRNForm: dry goods with no expiry tracking are posted
// with expires_at/lot_code omitted, exactly like today's behavior.
private struct GrnLineDraft: Identifiable {
    let id = UUID()
    var ingredientId: String
    var qtyText: String = ""
    var unitCostText: String = ""
    var hasExpiry: Bool = false
    var expiresAt: Date = Date()
    var lotCode: String = ""
}

private struct GRNReceivingSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let ingredients: [IngredientDTO]
    let branches: [BranchDTO]
    let onSuccess: () -> Void

    @State private var branchId: String
    @State private var suppliers: [SupplierDTO] = []
    @State private var supplierId = ""
    @State private var invoiceNo = ""
    @State private var notes = ""
    @State private var lines: [GrnLineDraft]
    @State private var isSubmitting = false
    @State private var error: String?

    init(ingredients: [IngredientDTO], branches: [BranchDTO], onSuccess: @escaping () -> Void) {
        self.ingredients = ingredients
        self.branches = branches
        self.onSuccess = onSuccess
        self._branchId = State(initialValue: branches.first?.id ?? "")
        self._lines = State(initialValue: [GrnLineDraft(ingredientId: ingredients.first?.id ?? "")])
    }

    private var canSubmit: Bool {
        !branchId.isEmpty && !supplierId.isEmpty && !isSubmitting
            && lines.contains { !$0.ingredientId.isEmpty && (Double($0.qtyText) ?? 0) > 0 }
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                fieldLabel("Branch") {
                                    Picker("", selection: $branchId) {
                                        Text("Select branch").tag("")
                                        ForEach(branches) { branch in Text(branch.name).tag(branch.id) }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.gold)
                                }
                                fieldLabel("Supplier") {
                                    Picker("", selection: $supplierId) {
                                        Text("Select supplier").tag("")
                                        ForEach(suppliers) { supplier in Text(supplier.name).tag(supplier.id) }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.gold)
                                }
                                fieldLabel("Supplier invoice no. (optional)") {
                                    TextField("", text: $invoiceNo).nativeField()
                                }
                            }
                        }

                        ForEach($lines) { $line in
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    HStack {
                                        Text("Line").font(.caption).foregroundColor(Brand.muted)
                                        Spacer()
                                        if lines.count > 1 {
                                            Button {
                                                Haptics.selection()
                                                lines.removeAll { $0.id == line.id }
                                            } label: {
                                                Image(systemName: "trash")
                                                    .foregroundColor(Brand.danger)
                                            }
                                        }
                                    }
                                    fieldLabel("Ingredient") {
                                        Picker("", selection: $line.ingredientId) {
                                            Text("Select ingredient").tag("")
                                            ForEach(ingredients) { ing in
                                                Text("\(ing.name) (\(ing.base_unit))").tag(ing.id)
                                            }
                                        }
                                        .pickerStyle(.menu)
                                        .tint(Brand.gold)
                                    }
                                    HStack(spacing: 12) {
                                        fieldLabel("Qty") {
                                            TextField("0", text: $line.qtyText)
                                                .keyboardType(.decimalPad)
                                                .nativeField()
                                        }
                                        fieldLabel("₹ / unit") {
                                            TextField("0.00", text: $line.unitCostText)
                                                .keyboardType(.decimalPad)
                                                .nativeField()
                                        }
                                    }
                                    Toggle("Track expiry", isOn: $line.hasExpiry)
                                        .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                        .foregroundColor(.white)
                                        .font(.caption.weight(.semibold))
                                    if line.hasExpiry {
                                        fieldLabel("Expiry date") {
                                            DatePicker("", selection: $line.expiresAt, displayedComponents: [.date])
                                                .labelsHidden()
                                                .datePickerStyle(.compact)
                                                .colorScheme(.dark)
                                        }
                                    }
                                    fieldLabel("Lot / batch code (optional)") {
                                        TextField("", text: $line.lotCode).nativeField()
                                    }
                                }
                            }
                        }

                        Button {
                            Haptics.selection()
                            lines.append(GrnLineDraft(ingredientId: ingredients.first?.id ?? ""))
                        } label: {
                            Label("Add line", systemImage: "plus")
                                .font(.caption.weight(.bold))
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(Brand.gold)

                        BrandedCard {
                            fieldLabel("Notes (optional)") {
                                TextField("", text: $notes).nativeField()
                            }
                        }
                    }
                    .padding(16)
                    .padding(.bottom, 90)
                }
            }
            .navigationTitle("Receive stock (GRN)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            }
            .task { await loadSuppliers() }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Task { await submit() }
                } label: {
                    HStack {
                        if isSubmitting { ProgressView() }
                        Text("Record receipt").font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(canSubmit ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(!canSubmit)
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func fieldLabel<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func loadSuppliers() async {
        do {
            suppliers = try await session.authorized { token in
                try await APIClient.shared.get("inventory/suppliers", token: token)
            }
        } catch is CancellationError {
        } catch {
            // Non-fatal — the supplier picker just stays empty; the submit
            // button stays disabled until a supplier loads and is selected.
        }
    }

    private func submit() async {
        let linesOut: [GrnLineRequest] = lines.compactMap { line in
            guard !line.ingredientId.isEmpty, let qty = Double(line.qtyText), qty > 0 else { return nil }
            let costRupees = Double(line.unitCostText) ?? 0
            return GrnLineRequest(
                ingredient_id: line.ingredientId,
                qty: qty,
                unit_cost_minor: Int((costRupees * 100).rounded()),
                // Date-only value (the picker only lets staff choose a
                // calendar day) — apiDateOnly avoids DateFormatters.iso's
                // UTC conversion silently shifting the day back whenever
                // this is filled in before ~05:30 IST.
                expires_at: line.hasExpiry ? DateFormatters.apiDateOnly.string(from: line.expiresAt) : nil,
                lot_code: line.lotCode.nilIfBlank
            )
        }
        guard !branchId.isEmpty, !supplierId.isEmpty, !linesOut.isEmpty else {
            error = "Select a branch, a supplier, and add at least one line."
            return
        }
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let _: GrnPostResponse = try await session.authorized { token in
                try await APIClient.shared.post(
                    "inventory/grn",
                    body: GrnPostRequest(
                        branch_id: branchId,
                        supplier_id: supplierId.nilIfBlank,
                        supplier_invoice_no: invoiceNo.nilIfBlank,
                        supplier_invoice_amount_minor: nil,
                        received_at: nil,
                        notes: notes.nilIfBlank,
                        lines: linesOut
                    ),
                    token: token
                )
            }
            Haptics.success()
            onSuccess()
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct SuppliersSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    @State private var suppliers: [SupplierDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var newName = ""
    @State private var newContact = ""
    @State private var newGSTIN = ""
    @State private var isSubmitting = false

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Add supplier").font(.headline).foregroundColor(.white)
                                TextField("Name", text: $newName).nativeField()
                                TextField("Contact (optional)", text: $newContact).nativeField()
                                TextField("GSTIN (optional)", text: $newGSTIN).autocorrectionDisabled().nativeField()
                                Button {
                                    Task { await create() }
                                } label: {
                                    HStack {
                                        if isSubmitting { ProgressView() }
                                        Text("Add")
                                            .font(.subheadline.weight(.bold))
                                    }
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 11)
                                }
                                .buttonStyle(.plain)
                                .foregroundColor(.black)
                                .background(newName.isEmpty ? Brand.muted.opacity(0.45) : Brand.gold)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .disabled(newName.isEmpty || isSubmitting)
                            }
                        }

                        if isLoading && suppliers.isEmpty {
                            LoadingBlock(title: "Loading suppliers")
                        } else if suppliers.isEmpty {
                            InlineEmptyCard(icon: "shippingbox", title: "No suppliers yet", subtitle: "Add your first supplier above.")
                        } else {
                            BrandedCard {
                                VStack(spacing: 0) {
                                    ForEach(suppliers) { supplier in
                                        VStack(alignment: .leading, spacing: 3) {
                                            Text(supplier.name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                            if let contact = supplier.contact, !contact.isEmpty {
                                                Text(contact).font(.caption).foregroundColor(Brand.muted)
                                            }
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                        .padding(.vertical, 6)
                                        if supplier.id != suppliers.last?.id {
                                            Divider().background(Brand.hairline)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Suppliers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
        .task { await load() }
    }

    private func load() async {
        isLoading = suppliers.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            suppliers = try await session.authorized { token in
                try await APIClient.shared.get("inventory/suppliers", token: token)
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func create() async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let created: SupplierDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "inventory/suppliers",
                    body: SupplierCreateRequest(name: newName, contact: newContact.nilIfBlank, gstin: newGSTIN.nilIfBlank, payment_terms: nil),
                    token: token
                )
            }
            suppliers.append(created)
            newName = ""; newContact = ""; newGSTIN = ""
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct ReportsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @EnvironmentObject private var cache: AppCache
    @State private var report: ReportDTO?
    @State private var taxCompliance: TaxComplianceDTO?
    @State private var period: ReportPeriodScope = .daily
    @State private var isLoading = true
    @State private var error: String?
    @State private var isPushing = false
    @State private var pushMessage: String?
    @State private var pushSucceeded = false
    @State private var showWebhookConfig = false
    @State private var isExportingGstr1 = false
    @State private var isExportingGstr3b = false
    @State private var exportError: String?
    @State private var shareFile: ShareableFile?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Reports", subtitle: report?.label ?? "\(period.title) P&L", icon: "chart.bar")

                    if !network.isOnline {
                        NetworkBanner(label: network.connectionLabel)
                    }

                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 10) {
                            ForEach(ReportPeriodScope.allCases) { scope in
                                FilterChip(title: scope.title, isSelected: period == scope) {
                                    Haptics.selection()
                                    period = scope
                                }
                            }
                        }
                        .padding(.vertical, 2)
                    }

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if let pushMessage {
                        pushSucceeded ? AnyView(SuccessBanner(message: pushMessage)) : AnyView(ErrorBanner(message: pushMessage))
                    }

                    if let exportError {
                        ErrorBanner(message: exportError)
                    }

                    if let report {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 16) {
                                Text("P&L Summary")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                PNLRow(title: "Gross revenue", value: inr(report.gross_revenue_minor))
                                if report.refunds_issued_minor > 0 {
                                    PNLRow(title: "Refunds issued", value: "-\(inr(report.refunds_issued_minor))")
                                }
                                PNLRow(title: "Net revenue", value: inr(report.net_revenue_minor))
                                PNLRow(title: "Cost of goods sold", value: "-\(inr(report.cogs_minor))")
                                PNLRow(title: "Gross profit", value: inr(report.gross_profit_minor))
                                PNLRow(title: "Expenses", value: "-\(inr(report.expense_total_minor))")
                                Divider().background(Brand.gold.opacity(0.35))
                                PNLRow(title: "Net profit", value: inr(report.net_profit_minor), highlight: true)
                            }
                        }

                        if let taxCompliance {
                            GSTComplianceCard(compliance: taxCompliance)
                        }

                        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                            MetricCard(title: "Payments", value: inr(report.payments_received.total_minor), detail: "Received", icon: "creditcard")
                            MetricCard(title: "GST", value: inr(report.tax_collected.total_minor), detail: "Collected", icon: "doc.text")
                            MetricCard(title: "Orders", value: "\(report.orders_count)", detail: period.title, icon: "list.bullet")
                            MetricCard(title: "Avg Ticket", value: inr(report.avg_ticket_minor), detail: "\(report.tickets_count) tickets", icon: "number")
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Revenue by stream").font(.headline).foregroundColor(.white)
                                PNLRow(title: "Food / drinks / desserts", value: inr(report.revenue.food_minor))
                                PNLRow(title: "Gaming", value: inr(report.revenue.gaming_minor))
                                if report.revenue.hookah_minor > 0 {
                                    PNLRow(title: "Hookah", value: inr(report.revenue.hookah_minor))
                                }
                                PNLRow(title: "Event tickets", value: inr(report.revenue.event_tickets_minor))
                                PNLRow(title: "Delivery (aggregator)", value: inr(report.revenue.delivery_aggregator_minor))
                                if report.revenue.other_minor > 0 {
                                    PNLRow(title: "Other", value: inr(report.revenue.other_minor))
                                }
                                if (report.revenue.manual_collections_minor ?? 0) > 0 {
                                    PNLRow(title: "Manual collections (unitemized)", value: inr(report.revenue.manual_collections_minor ?? 0))
                                    Text("No POS order, item mix, tax invoice, or automatic COGS is attached.")
                                        .font(.caption2)
                                        .foregroundColor(Brand.muted)
                                }
                                if (report.revenue.discounts_and_points_redeemed_minor ?? 0) > 0 {
                                    PNLRow(title: "Less: discounts and points", value: "-\(inr(report.revenue.discounts_and_points_redeemed_minor ?? 0))")
                                }
                                Text("Aggregator delivery: platform pays GST — Sec 9(5)")
                                    .font(.caption2)
                                    .foregroundColor(Brand.muted)
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("GST collected").font(.headline).foregroundColor(.white)
                                PNLRow(title: "CGST", value: inr(report.tax_collected.cgst_minor))
                                PNLRow(title: "SGST", value: inr(report.tax_collected.sgst_minor))
                                if report.tax_collected.igst_minor > 0 {
                                    PNLRow(title: "IGST (inter-state)", value: inr(report.tax_collected.igst_minor))
                                }
                                if report.tax_collected.cess_minor > 0 {
                                    PNLRow(title: "Cess", value: inr(report.tax_collected.cess_minor))
                                }
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Payment movement").font(.headline).foregroundColor(.white)
                                PNLRow(title: "Cash", value: inr(report.payments_received.cash_minor))
                                PNLRow(title: "UPI", value: inr(report.payments_received.upi_minor))
                                PNLRow(title: "Card", value: inr(report.payments_received.card_minor))
                                if (report.payments_received.bank_minor ?? 0) > 0 {
                                    PNLRow(title: "Bank transfer", value: inr(report.payments_received.bank_minor ?? 0))
                                }
                                PNLRow(title: "QR", value: inr(report.payments_received.qr_minor))
                                PNLRow(title: "Wallet", value: inr(report.payments_received.wallet_minor))
                                if report.payments_received.other_minor > 0 {
                                    PNLRow(title: "Other", value: inr(report.payments_received.other_minor))
                                }
                                if report.refunds_issued_minor > 0 {
                                    Divider().background(Brand.gold.opacity(0.35))
                                    PNLRow(title: "Less: refunds issued", value: "-\(inr(report.refunds_issued_minor))")
                                }
                                Divider().background(Brand.gold.opacity(0.35))
                                PNLRow(title: "Net payment movement", value: inr(report.net_payments_received_minor), highlight: true)
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Expenses").font(.headline).foregroundColor(.white)
                                if report.expenses.isEmpty {
                                    Text("No expenses recorded in this period.")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                } else {
                                    ForEach(report.expenses) { expense in
                                        PNLRow(title: expense.category, value: inr(expense.amount_minor))
                                    }
                                    Divider().background(Brand.gold.opacity(0.35))
                                    PNLRow(title: "Total expenses", value: inr(report.expense_total_minor), highlight: true)
                                }
                            }
                        }
                    } else if isLoading {
                        ReportsSkeletonView()
                    } else {
                        InlineEmptyCard(icon: "chart.bar", title: "No report yet", subtitle: "Pull to refresh after sales data is available.")
                    }
                }
                .padding(16)
            }
            .navigationTitle("Reports")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.selection()
                        Task { await load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Menu {
                        Button {
                            Haptics.selection()
                            if let report { ReportPrinter.print(report: report, periodTitle: period.title) }
                        } label: {
                            Label("Print report", systemImage: "printer")
                        }
                        .disabled(report == nil)

                        Button {
                            Haptics.selection()
                            Task { await pushToSheets() }
                        } label: {
                            Label("Push to Sheets", systemImage: "tray.and.arrow.up")
                        }
                        .disabled(report == nil || isPushing)

                        Button {
                            showWebhookConfig = true
                        } label: {
                            Label(
                                GSheetsStore.readURL() == nil ? "Configure Sheets webhook" : "Sheets webhook settings",
                                systemImage: "link"
                            )
                        }

                        // GST filing exports — same analytics.export permission as
                        // the report itself; gated the same way canSeeInsights
                        // gates the Insights/Analytics tabs elsewhere in the app.
                        if session.canSeeInsights {
                            Divider()

                            Button {
                                Haptics.selection()
                                Task { await exportGstr1() }
                            } label: {
                                Label("Export GSTR-1 CSV", systemImage: "square.and.arrow.up")
                            }
                            .disabled(isExportingGstr1)

                            Button {
                                Haptics.selection()
                                Task { await exportGstr3b() }
                            } label: {
                                Label("Export GSTR-3B CSV", systemImage: "square.and.arrow.up")
                            }
                            .disabled(isExportingGstr3b)
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .background(Brand.background)
        }
        .task { await load() }
        .sheet(item: $shareFile) { file in
            ActivityShareSheet(activityItems: [file.url])
        }
        .onChange(of: period) { _ in
            Task { await load() }
        }
        .sheet(isPresented: $showWebhookConfig) {
            GSheetsWebhookSheet()
        }
    }

    private func pushToSheets() async {
        guard let report else { return }
        guard GSheetsStore.readURL() != nil else {
            showWebhookConfig = true
            return
        }
        isPushing = true
        pushMessage = nil
        defer { isPushing = false }
        let payload = ReportSheetPayload(
            date: DateFormatters.apiDateOnly.string(from: report.period_start),
            time: DateFormatters.timeOnly.string(from: Date()),
            period_id: report.label,
            label: report.label,
            orders_count: report.orders_count,
            food_minor: report.revenue.food_minor,
            gaming_minor: report.revenue.gaming_minor,
            hookah_minor: report.revenue.hookah_minor,
            event_tickets_minor: report.revenue.event_tickets_minor,
            delivery_minor: report.revenue.delivery_aggregator_minor,
            gross_revenue_minor: report.gross_revenue_minor,
            net_revenue_minor: report.net_revenue_minor,
            manual_collections_minor: report.manual_collections_minor ?? 0,
            cgst_minor: report.tax_collected.cgst_minor,
            sgst_minor: report.tax_collected.sgst_minor,
            igst_minor: report.tax_collected.igst_minor,
            expense_total_minor: report.expense_total_minor,
            net_profit_minor: report.net_profit_minor
        )
        let ok = await GSheetsPusher.push(kind: "\(period.rawValue)_report", payload: payload)
        pushSucceeded = ok
        pushMessage = ok ? "Report pushed to Google Sheets (ERP Entries tab)." : "Failed to push to Google Sheets. Check the webhook settings."
        if ok { Haptics.success() }
    }

    // GSTR-1/GSTR-3B are always a full calendar month (yyyy_mm), independent
    // of whichever P&L period tab is currently selected — default to the
    // current month rather than trying to derive one from `period`, which
    // may be daily/weekly/quarterly and wouldn't map onto a single month.
    private var currentFilingMonth: String {
        DateFormatters.yearMonth.string(from: Date())
    }

    private func exportGstr1() async {
        guard !isExportingGstr1 else { return }
        isExportingGstr1 = true
        exportError = nil
        defer { isExportingGstr1 = false }
        let month = currentFilingMonth
        do {
            let data: Data = try await session.authorized { token in
                try await APIClient.shared.getData(
                    "reports/gstr1.csv",
                    token: token,
                    queryItems: [URLQueryItem(name: "yyyy_mm", value: month)]
                )
            }
            shareFile = try CSVExporter.write(data, filename: "GSTR1-\(month).csv")
            Haptics.success()
        } catch is CancellationError {
        } catch {
            exportError = readable(error)
        }
    }

    private func exportGstr3b() async {
        guard !isExportingGstr3b else { return }
        isExportingGstr3b = true
        exportError = nil
        defer { isExportingGstr3b = false }
        let month = currentFilingMonth
        do {
            let data: Data = try await session.authorized { token in
                try await APIClient.shared.getData(
                    "reports/gstr3b.csv",
                    token: token,
                    queryItems: [URLQueryItem(name: "yyyy_mm", value: month)]
                )
            }
            shareFile = try CSVExporter.write(data, filename: "GSTR3B-\(month).csv")
            Haptics.success()
        } catch is CancellationError {
        } catch {
            exportError = readable(error)
        }
    }

    private func load() async {
        if period == .daily, let cachedDailyReport = cache.dailyReport {
            report = cachedDailyReport
        } else {
            report = nil
        }
        isLoading = report == nil
        defer { isLoading = false }
        error = nil
        taxCompliance = nil
        do {
            let request = reportRequest(for: period)
            let loadedReport: ReportDTO = try await session.authorized { token in
                try await APIClient.shared.get(request.path, token: token, queryItems: request.queryItems)
            }
            let shouldCacheDailyReport = period == .daily
            withAnimation(.easeOut(duration: 0.18)) {
                report = loadedReport
                if shouldCacheDailyReport {
                    cache.dailyReport = loadedReport
                }
            }
            if shouldCacheDailyReport {
                await cache.markSynced()
            }
            taxCompliance = try? await session.authorized { token in
                try await APIClient.shared.get(
                    "reports/tax-compliance",
                    token: token,
                    queryItems: [
                        URLQueryItem(name: "from_date", value: DateFormatters.apiDateOnly.string(from: loadedReport.period_start)),
                        URLQueryItem(name: "to_date", value: DateFormatters.apiDateOnly.string(from: loadedReport.period_end))
                    ]
                )
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func reportRequest(for scope: ReportPeriodScope) -> (path: String, queryItems: [URLQueryItem]) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "Asia/Kolkata") ?? .current
        switch scope {
        case .weekly:
            let now = Date()
            let start = calendar.dateInterval(of: .weekOfYear, for: now)?.start ?? now
            return rangeRequest(from: start, to: now)
        case .halfYearly:
            let now = Date()
            let components = calendar.dateComponents([.year, .month], from: now)
            let startMonth = (components.month ?? 1) <= 6 ? 1 : 7
            let start = calendar.date(from: DateComponents(year: components.year, month: startMonth, day: 1)) ?? now
            return rangeRequest(from: start, to: now)
        default:
            return (scope.endpoint, [])
        }
    }

    private func rangeRequest(from start: Date, to end: Date) -> (path: String, queryItems: [URLQueryItem]) {
        (
            "reports/range",
            [
                URLQueryItem(name: "from_date", value: DateFormatters.apiDateOnly.string(from: start)),
                URLQueryItem(name: "to_date", value: DateFormatters.apiDateOnly.string(from: end))
            ]
        )
    }
}

private struct GSheetsWebhookSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var url: String = GSheetsStore.readURL() ?? ""
    @State private var isTesting = false
    @State private var testResult: (ok: Bool, message: String)?
    @State private var copied = false

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Google Sheets").font(.headline).foregroundColor(.white)
                                Text("Every order, ticket, event, and P&L report automatically appears as a row in your sheet's Operations tab. Your other tabs stay untouched.")
                                    .font(.caption)
                                    .foregroundColor(Brand.muted)
                                TextField("https://script.google.com/macros/s/…", text: $url)
                                    .keyboardType(.URL)
                                    .textInputAutocapitalization(.never)
                                    .autocorrectionDisabled()
                                    .nativeField()
                            }
                        }

                        if GSheetsStore.readURL() == nil {
                            GSheetsSetupWizard(copied: copied, onCopy: copyScript)
                        }

                        if let testResult {
                            testResult.ok ? AnyView(SuccessBanner(message: testResult.message)) : AnyView(ErrorBanner(message: testResult.message))
                        }

                        if let lastSync = GSheetsStore.lastSyncAt {
                            Text("Last synced: \(lastSync)")
                                .font(.caption2)
                                .foregroundColor(Brand.muted)
                        }

                        Button {
                            Haptics.selection()
                            Task { await testConnection() }
                        } label: {
                            HStack {
                                if isTesting { ProgressView() }
                                Text("Test connection")
                            }
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.white)
                        .background(Brand.surface)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .disabled(url.trimmingCharacters(in: .whitespaces).isEmpty || isTesting)

                        if GSheetsStore.readURL() != nil {
                            Button(role: .destructive) {
                                Haptics.selection()
                                GSheetsStore.clear()
                                url = ""
                                testResult = nil
                            } label: {
                                Text("Remove webhook")
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 12)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(Brand.danger)
                            .background(Brand.danger.opacity(0.12))
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 8) {
                                Text("What appears in your sheet").font(.headline).foregroundColor(.white)
                                Text("All entries land in a single tab called Operations with 17 columns: Date, Time, Type, ID, Description, Customer, Qty, Taxable, CGST, SGST, IGST, Round-off, Total, Method, Cashier, GSTIN, Place of supply.\n\nThe Type column tells you what each row is: Order, Ticket, Event, Daily Report, Monthly Report, Quarterly Report, Yearly Report. Rows are idempotent on the ID column — if the same invoice arrives twice, the existing row is overwritten in place.")
                                    .font(.caption2)
                                    .foregroundColor(Brand.muted)
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Sheets Webhook")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let trimmed = url.trimmingCharacters(in: .whitespaces)
                        if trimmed.isEmpty {
                            GSheetsStore.clear()
                        } else {
                            GSheetsStore.save(url: trimmed)
                        }
                        Haptics.success()
                        dismiss()
                    }
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func testConnection() async {
        isTesting = true
        defer { isTesting = false }
        testResult = await GSheetsPusher.testConnection(url: url.trimmingCharacters(in: .whitespaces))
    }

    private func copyScript() {
        UIPasteboard.general.string = dCompanyAppsScript
        copied = true
        Haptics.success()
        Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            copied = false
        }
    }
}

private struct GSheetsSetupWizard: View {
    let copied: Bool
    let onCopy: () -> Void

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                WizardStep(number: 1, title: "Open your Google Sheet") {
                    Text("Use an existing sheet, or create a new one at sheets.new. A tab called Operations is added automatically — your other tabs stay untouched.")
                }
                WizardStep(number: 2, title: "Open the script editor") {
                    Text("In the menu bar: Extensions → Apps Script. A new tab opens with an empty Code.gs.")
                }
                WizardStep(number: 3, title: "Copy and paste this script") {
                    Button(action: onCopy) {
                        Label(copied ? "Copied!" : "Copy the Apps Script", systemImage: copied ? "checkmark" : "doc.on.doc")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(copied ? Brand.success : Brand.softGold)
                    Text("Paste over the empty Code.gs content, then click the disk icon (Save).")
                }
                WizardStep(number: 4, title: "Deploy as a Web app") {
                    Text("Deploy → New deployment. Type: Web app. Execute as: Me. Who has access: Anyone with the link. Click Deploy, then authorize when prompted.")
                }
                WizardStep(number: 5, title: "Copy the Web app URL") {
                    Text("Paste it into the box above and tap Test connection.")
                }
            }
        }
    }
}

private struct WizardStep<Content: View>: View {
    let number: Int
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Text("\(number)")
                .font(.caption.weight(.bold))
                .foregroundColor(.black)
                .frame(width: 22, height: 22)
                .background(Brand.gold)
                .clipShape(Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                content
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
            }
        }
    }
}

private struct SimpleBarChart: View {
    let bars: [(label: String, minor: Int)]
    private let maxHeight: CGFloat = 140

    private var maxValue: Int { max(bars.map(\.minor).max() ?? 0, 1) }

    var body: some View {
        HStack(alignment: .bottom, spacing: 14) {
            ForEach(bars, id: \.label) { bar in
                VStack(spacing: 6) {
                    Text(inr(bar.minor))
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(Brand.muted)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Brand.gold.opacity(0.85))
                        .frame(height: max(6, CGFloat(bar.minor) / CGFloat(maxValue) * maxHeight))
                    Text(bar.label)
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(.white)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: maxHeight + 50, alignment: .bottom)
    }
}

private struct AnalyticsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @State private var data: DashboardKPIsDTO?
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Analytics", subtitle: "Today's performance, live", icon: "chart.line.uptrend.xyaxis")

                    if !network.isOnline {
                        NetworkBanner(label: network.connectionLabel)
                    }

                    if let error {
                        ErrorBanner(message: error)
                    }

                    if let data {
                        LazyVGrid(columns: twoColumns, spacing: 12) {
                            MetricCard(title: "Revenue", value: inr(data.revenue_total_minor), detail: "Today", icon: "wallet.pass")
                            MetricCard(title: "Orders", value: "\(data.orders_count)", detail: "\(data.tickets_count) tickets", icon: "bag")
                            MetricCard(title: "Avg Ticket", value: inr(data.avg_ticket_minor), detail: "Per receipt", icon: "arrow.up.right")
                            MetricCard(title: "Net Profit", value: inr(data.net_profit_minor), detail: "Today", icon: "chart.line.uptrend.xyaxis")
                        }

                        LazyVGrid(columns: threeColumns, spacing: 12) {
                            MetricCard(title: "Inventory Value", value: inr(data.inventory_value_minor), detail: "All branches", icon: "cube.box")
                            MetricCard(title: "Low Stock", value: "\(data.low_stock_items)", detail: "Need restock", icon: "exclamationmark.triangle")
                            MetricCard(title: "Gaming Sessions", value: "\(data.open_sessions)", detail: "Active now", icon: "gamecontroller")
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Revenue by stream (today)").font(.headline).foregroundColor(.white)
                                SimpleBarChart(bars: [
                                    (label: "Food", minor: data.revenue_food_minor),
                                    (label: "Gaming", minor: data.revenue_gaming_minor),
                                    (label: "Hookah", minor: data.revenue_hookah_minor),
                                    (label: "Events", minor: data.revenue_events_minor),
                                    (label: "Manual", minor: data.revenue_manual_collections_minor ?? 0)
                                ])
                            }
                        }
                    } else if isLoading {
                        MetricsSkeletonGrid()
                    } else {
                        InlineEmptyCard(icon: "chart.line.uptrend.xyaxis", title: "No data yet", subtitle: "Pull to refresh after sales data is available.")
                    }
                }
                .padding(16)
            }
            .navigationTitle("Analytics")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.selection()
                        Task { await load() }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .background(Brand.background)
        }
        .task { await load() }
    }

    private var twoColumns: [GridItem] { [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)] }
    private var threeColumns: [GridItem] { [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)] }

    private func load() async {
        isLoading = data == nil
        defer { isLoading = false }
        error = nil
        do {
            let today = DateFormatters.apiDateOnly.string(from: Date())
            let result: DashboardKPIsDTO = try await session.authorized { token in
                try await APIClient.shared.get("analytics/dashboard", token: token, queryItems: [URLQueryItem(name: "on_date", value: today)])
            }
            withAnimation(.easeOut(duration: 0.18)) { data = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private enum InsightsTab: String, CaseIterable, Identifiable {
    case growth, inventory, accounting, losses

    var id: String { rawValue }

    var title: String {
        switch self {
        case .growth: return "Growth"
        case .inventory: return "Inventory"
        case .accounting: return "Accounting"
        case .losses: return "Losses"
        }
    }
}

private struct InsightsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var tab: InsightsTab = .growth

    private var availableTabs: [InsightsTab] {
        InsightsTab.allCases.filter(isAvailable)
    }

    private func isAvailable(_ t: InsightsTab) -> Bool {
        switch t {
        case .growth: return session.canSeeInsights
        case .inventory: return session.canSeeInventory
        case .accounting: return session.canSeeFinance
        case .losses: return session.canSeeInventory
        }
    }

    // Never render `tab` directly: on first appearance it defaults to .growth
    // regardless of permissions, which would mount InsightsGrowthTab (and
    // fire its analytics.read-gated fetches) for a user who only has, say,
    // canSeeFinance — before onAppear gets a chance to correct it. Always
    // route rendering through the permission-checked fallback instead.
    private var effectiveTab: InsightsTab {
        isAvailable(tab) ? tab : (availableTabs.first ?? tab)
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(availableTabs) { t in
                        FilterChip(title: t.title, isSelected: effectiveTab == t) {
                            Haptics.selection()
                            tab = t
                        }
                    }
                }
                .padding(16)
            }

            Group {
                switch effectiveTab {
                case .growth: InsightsGrowthTab()
                case .inventory: InsightsInventoryTab()
                case .accounting: InsightsAccountingTab()
                case .losses: InsightsLossesTab()
                }
            }
        }
        .navigationTitle("Insights")
        .background(Brand.background)
        .onAppear {
            if !isAvailable(tab), let first = availableTabs.first {
                tab = first
            }
        }
    }
}

private struct InsightsGrowthTab: View {
    private enum GrowthPeriod: String, CaseIterable, Identifiable {
        case wow, mom, yoy
        var id: String { rawValue }
        var title: String {
            switch self {
            case .wow: return "Week"
            case .mom: return "Month"
            case .yoy: return "Year"
            }
        }
    }

    @EnvironmentObject private var session: AppSession
    @State private var period: GrowthPeriod = .mom
    @State private var growth: GrowthDTO?
    @State private var topItems: [TopItemDTO] = []
    @State private var heatmap: [HeatmapCellDTO] = []
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                Picker("", selection: $period) {
                    ForEach(GrowthPeriod.allCases) { p in Text(p.title).tag(p) }
                }
                .pickerStyle(.segmented)

                if let error { ErrorBanner(message: error) }

                if let growth {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        GrowthComparisonCard(
                            title: "Revenue · \(growth.current.label)",
                            current: Double(growth.current.revenue_minor),
                            previous: Double(growth.previous.revenue_minor),
                            deltaPct: growth.revenue_delta_pct,
                            previousLabel: growth.previous.label,
                            isCurrency: true
                        )
                        GrowthComparisonCard(
                            title: "Orders · \(growth.current.label)",
                            current: Double(growth.current.orders_count),
                            previous: Double(growth.previous.orders_count),
                            deltaPct: growth.orders_delta_pct,
                            previousLabel: growth.previous.label,
                            isCurrency: false
                        )
                    }

                    if (growth.current.manual_collections_minor ?? 0) > 0 ||
                        (growth.previous.manual_collections_minor ?? 0) > 0 {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Manual collections included in revenue growth")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundColor(Brand.softGold)
                                Text("Current \(inr(growth.current.manual_collections_minor ?? 0)) · Previous \(inr(growth.previous.manual_collections_minor ?? 0)). Orders, average order value, top items, and the heatmap remain POS-only.")
                                    .font(.caption)
                                    .foregroundColor(Brand.muted)
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Top items (this month)").font(.headline).foregroundColor(.white)
                            if topItems.isEmpty {
                                Text("No sales yet.").font(.caption).foregroundColor(Brand.muted)
                            } else {
                                ForEach(topItems) { item in
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(item.name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                            Text(item.type.capitalized).font(.caption2).foregroundColor(Brand.muted)
                                        }
                                        Spacer()
                                        VStack(alignment: .trailing, spacing: 2) {
                                            Text(inr(item.revenue_minor)).font(.subheadline.weight(.semibold)).foregroundColor(Brand.softGold)
                                            Text("\(NumberFormatters.decimal.string(from: NSNumber(value: item.qty_sold)) ?? "\(item.qty_sold)") sold")
                                                .font(.caption2)
                                                .foregroundColor(Brand.muted)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                    if item.id != topItems.last?.id {
                                        Divider().background(Brand.hairline)
                                    }
                                }
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("When customers buy (last 30 days)").font(.headline).foregroundColor(.white)
                            HeatmapGridView(cells: heatmap)
                            Text("Darker cells = more revenue. Use this to plan staffing.")
                                .font(.caption2)
                                .foregroundColor(Brand.muted)
                        }
                    }
                } else if isLoading {
                    LoadingBlock(title: "Loading growth")
                }
            }
            .padding(16)
        }
        .task { await load() }
        .onChange(of: period) { _ in Task { await load() } }
    }

    private func load() async {
        isLoading = growth == nil
        defer { isLoading = false }
        error = nil
        do {
            async let g: GrowthDTO = session.authorized { token in
                try await APIClient.shared.get("insights/growth", token: token, queryItems: [URLQueryItem(name: "period", value: period.rawValue)])
            }
            async let t: [TopItemDTO] = session.authorized { token in
                try await APIClient.shared.get("insights/top-items", token: token, queryItems: [URLQueryItem(name: "limit", value: "10")])
            }
            async let h: [HeatmapCellDTO] = session.authorized { token in
                try await APIClient.shared.get("insights/heatmap", token: token)
            }
            let (freshGrowth, freshTop, freshHeatmap) = try await (g, t, h)
            withAnimation(.easeOut(duration: 0.18)) {
                growth = freshGrowth
                topItems = freshTop
                heatmap = freshHeatmap
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct GrowthComparisonCard: View {
    let title: String
    let current: Double
    let previous: Double
    let deltaPct: Double
    let previousLabel: String
    let isCurrency: Bool

    private var isUp: Bool { deltaPct >= 0 }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(title.uppercased()).font(.caption2.weight(.semibold)).foregroundColor(Brand.muted)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(isCurrency ? inr(Int(current)) : "\(Int(current))")
                        .font(.title2.weight(.bold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                    HStack(spacing: 2) {
                        Image(systemName: isUp ? "arrow.up" : "arrow.down")
                        Text(String(format: "%.1f%%", abs(deltaPct)))
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundColor(isUp ? Brand.success : Brand.danger)
                }
                Text("vs \(previousLabel): \(isCurrency ? inr(Int(previous)) : "\(Int(previous))")")
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
            }
        }
    }
}

private struct HeatmapGridView: View {
    let cells: [HeatmapCellDTO]
    private let days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    private var maxRevenue: Int {
        max(cells.map(\.revenue_minor).max() ?? 0, 1)
    }

    private func revenue(day: Int, hour: Int) -> Int {
        cells.first { $0.day_of_week == day && $0.hour == hour }?.revenue_minor ?? 0
    }

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            VStack(alignment: .leading, spacing: 3) {
                ForEach(0..<7, id: \.self) { day in
                    HStack(spacing: 2) {
                        Text(days[day])
                            .font(.system(size: 9, weight: .semibold))
                            .foregroundColor(Brand.muted)
                            .frame(width: 30, alignment: .leading)
                        ForEach(0..<24, id: \.self) { hour in
                            let value = revenue(day: day, hour: hour)
                            let intensity = Double(value) / Double(maxRevenue)
                            RoundedRectangle(cornerRadius: 3, style: .continuous)
                                .fill(value > 0 ? Brand.gold.opacity(0.15 + intensity * 0.7) : Brand.surface)
                                .frame(width: 16, height: 18)
                        }
                    }
                }
            }
            .padding(.vertical, 4)
        }
    }
}

private struct InsightsInventoryTab: View {
    @EnvironmentObject private var session: AppSession
    @State private var valuation: InventoryValuationDTO?
    @State private var margins: [RecipeMarginDTO] = []
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                if let error { ErrorBanner(message: error) }

                if let valuation {
                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        MetricCard(title: "Stock Value", value: inr(valuation.total_valuation_minor), detail: "Current", icon: "indianrupeesign.circle")
                        MetricCard(title: "Ingredients", value: "\(valuation.lines.count)", detail: "Tracked", icon: "list.bullet")
                        MetricCard(title: "Low Stock", value: "\(valuation.low_stock_count)", detail: "Need restock", icon: "exclamationmark.triangle")
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Inventory valuation").font(.headline).foregroundColor(.white)
                            Text("Current stock × average cost").font(.caption2).foregroundColor(Brand.muted)
                            if valuation.lines.isEmpty {
                                Text("No ingredients yet.").font(.caption).foregroundColor(Brand.muted)
                            } else {
                                let sortedLines = valuation.lines.sorted { $0.valuation_minor > $1.valuation_minor }
                                ForEach(sortedLines) { line in
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(line.name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                            Text(line.sku).font(.caption2).foregroundColor(Brand.muted)
                                        }
                                        Spacer()
                                        VStack(alignment: .trailing, spacing: 2) {
                                            Text(inr(line.valuation_minor)).font(.subheadline.weight(.semibold)).foregroundColor(Brand.softGold)
                                            Text("\(NumberFormatters.decimal.string(from: NSNumber(value: line.current_qty)) ?? "") \(line.base_unit)")
                                                .font(.caption2)
                                                .foregroundColor(line.is_low_stock ? Brand.danger : Brand.muted)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                    if line.id != sortedLines.last?.id {
                                        Divider().background(Brand.hairline)
                                    }
                                }
                            }
                        }
                    }
                } else if isLoading {
                    LoadingBlock(title: "Loading inventory value")
                }

                BrandedCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("Recipe margins").font(.headline).foregroundColor(.white)
                        Text("Which items make money").font(.caption2).foregroundColor(Brand.muted)
                        if margins.isEmpty {
                            Text("No recipes defined yet. Add ingredients and recipes in Menu to see margin analysis.")
                                .font(.caption)
                                .foregroundColor(Brand.muted)
                        } else {
                            ForEach(margins) { m in
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(m.name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                        Text("\(inr(m.sale_price_minor)) sale · \(inr(m.cost_minor)) cost").font(.caption2).foregroundColor(Brand.muted)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text(inr(m.margin_minor))
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundColor(m.margin_minor >= 0 ? Brand.success : Brand.danger)
                                        Text(String(format: "%.0f%%", m.margin_pct))
                                            .font(.caption2.weight(.semibold))
                                            .foregroundColor(m.margin_pct >= 60 ? Brand.success : (m.margin_pct >= 30 ? Brand.gold : Brand.danger))
                                    }
                                }
                                .padding(.vertical, 4)
                                if m.id != margins.last?.id {
                                    Divider().background(Brand.hairline)
                                }
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .task { await load() }
    }

    private func load() async {
        isLoading = valuation == nil
        defer { isLoading = false }
        error = nil
        do {
            async let v: InventoryValuationDTO = session.authorized { token in
                try await APIClient.shared.get("insights/inventory/valuation", token: token)
            }
            async let m: [RecipeMarginDTO] = session.authorized { token in
                try await APIClient.shared.get("insights/menu/recipe-margin", token: token)
            }
            let (freshValuation, freshMargins) = try await (v, m)
            withAnimation(.easeOut(duration: 0.18)) {
                valuation = freshValuation
                margins = freshMargins
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private enum AccountingViewMode: String, CaseIterable, Identifiable {
    case trialBalance, balanceSheet, chartOfAccounts, generalLedger
    var id: String { rawValue }
    var title: String {
        switch self {
        case .trialBalance: return "Trial Balance"
        case .balanceSheet: return "Balance Sheet"
        case .chartOfAccounts: return "Chart of Accounts"
        case .generalLedger: return "General Ledger"
        }
    }
}

private struct InsightsAccountingTab: View {
    @EnvironmentObject private var session: AppSession
    @State private var trialBalance: TrialBalanceDTO?
    @State private var balanceSheet: BalanceSheetDTO?
    @State private var chartOfAccounts: [AccountDTO] = []
    @State private var generalLedger: [GLEntryDTO] = []
    @State private var mode: AccountingViewMode = .trialBalance
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(AccountingViewMode.allCases) { v in
                            FilterChip(title: v.title, isSelected: mode == v) {
                                Haptics.selection()
                                mode = v
                            }
                        }
                    }
                }

                if let error { ErrorBanner(message: error) }

                if isLoading && trialBalance == nil {
                    LoadingBlock(title: "Loading accounting data")
                } else {
                    switch mode {
                    case .trialBalance:
                        if let trialBalance {
                            TrialBalanceCard(data: trialBalance)
                        }
                    case .balanceSheet:
                        if let balanceSheet {
                            BalanceSheetCards(data: balanceSheet)
                        }
                    case .chartOfAccounts:
                        ChartOfAccountsCard(accounts: chartOfAccounts)
                    case .generalLedger:
                        GeneralLedgerCard(entries: generalLedger)
                    }
                }
            }
            .padding(16)
        }
        .task { await load() }
    }

    private func load() async {
        isLoading = trialBalance == nil
        defer { isLoading = false }
        error = nil
        do {
            async let tb: TrialBalanceDTO = session.authorized { token in
                try await APIClient.shared.get("accounting/trial-balance", token: token)
            }
            async let bs: BalanceSheetDTO = session.authorized { token in
                try await APIClient.shared.get("accounting/balance-sheet", token: token)
            }
            async let coa: [AccountDTO] = session.authorized { token in
                try await APIClient.shared.get("accounting/chart-of-accounts", token: token)
            }
            async let gl: [GLEntryDTO] = session.authorized { token in
                try await APIClient.shared.get("accounting/general-ledger", token: token, queryItems: [URLQueryItem(name: "limit", value: "100")])
            }
            let (freshTB, freshBS, freshCOA, freshGL) = try await (tb, bs, coa, gl)
            withAnimation(.easeOut(duration: 0.18)) {
                trialBalance = freshTB
                balanceSheet = freshBS
                chartOfAccounts = freshCOA
                generalLedger = freshGL
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct TrialBalanceCard: View {
    let data: TrialBalanceDTO

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Trial Balance").font(.headline).foregroundColor(.white)
                        Text("As of \(DateFormatters.apiDateOnly.string(from: data.as_of))").font(.caption2).foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Text(data.is_balanced ? "Balanced" : "Not balanced")
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background((data.is_balanced ? Brand.success : Brand.danger).opacity(0.16))
                        .foregroundColor(data.is_balanced ? Brand.success : Brand.danger)
                        .clipShape(Capsule())
                }
                if data.lines.isEmpty {
                    Text("No ledger activity yet.").font(.caption).foregroundColor(Brand.muted)
                } else {
                    ForEach(data.lines) { line in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(line.account_name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                Text("\(line.account_code) · \(line.account_type)").font(.caption2).foregroundColor(Brand.muted)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                if line.debit_minor > 0 {
                                    Text("Dr \(inr(line.debit_minor))").font(.caption.weight(.semibold)).foregroundColor(.white)
                                }
                                if line.credit_minor > 0 {
                                    Text("Cr \(inr(line.credit_minor))").font(.caption.weight(.semibold)).foregroundColor(Brand.softGold)
                                }
                            }
                        }
                        .padding(.vertical, 4)
                        Divider().background(Brand.hairline)
                    }
                    HStack {
                        Text("Totals").font(.subheadline.weight(.bold)).foregroundColor(.white)
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("Dr \(inr(data.total_debit_minor))").font(.caption.weight(.bold)).foregroundColor(.white)
                            Text("Cr \(inr(data.total_credit_minor))").font(.caption.weight(.bold)).foregroundColor(Brand.softGold)
                        }
                    }
                }
            }
        }
    }
}

private struct BalanceSheetCards: View {
    let data: BalanceSheetDTO

    var body: some View {
        VStack(spacing: 12) {
            BSSectionCard(title: "Assets", section: data.assets)
            BSSectionCard(title: "Liabilities", section: data.liabilities)
            BSSectionCard(title: "Equity", section: data.equity)
            BrandedCard {
                HStack {
                    Text("Assets = Liabilities + Equity").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                    Spacer()
                    Text(data.is_balanced ? "Balanced" : "Off by \(inr(abs(data.assets.total_minor - data.liabilities.total_minor - data.equity.total_minor)))")
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .background((data.is_balanced ? Brand.success : Brand.danger).opacity(0.16))
                        .foregroundColor(data.is_balanced ? Brand.success : Brand.danger)
                        .clipShape(Capsule())
                }
            }
        }
    }
}

private struct BSSectionCard: View {
    let title: String
    let section: BSSectionDTO

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 8) {
                Text(title.uppercased()).font(.caption.weight(.bold)).foregroundColor(Brand.muted)
                if section.lines.isEmpty {
                    Text("No entries.").font(.caption).foregroundColor(Brand.muted)
                } else {
                    ForEach(section.lines) { line in
                        HStack {
                            Text(line.name).font(.subheadline).foregroundColor(.white)
                            Spacer()
                            Text(inr(line.amount_minor))
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(line.amount_minor < 0 ? Brand.danger : .white)
                        }
                    }
                }
                Divider().background(Brand.gold.opacity(0.35))
                HStack {
                    Text("Total \(title.lowercased())").font(.subheadline.weight(.bold)).foregroundColor(.white)
                    Spacer()
                    Text(inr(section.total_minor)).font(.subheadline.weight(.bold)).foregroundColor(Brand.softGold)
                }
            }
        }
    }
}

private struct ChartOfAccountsCard: View {
    let accounts: [AccountDTO]

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("Chart of Accounts").font(.headline).foregroundColor(.white)
                if accounts.isEmpty {
                    Text("No accounts configured.").font(.caption).foregroundColor(Brand.muted)
                } else {
                    ForEach(accounts) { account in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(account.name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                Text("\(account.code) · \(account.type) · \(account.normal_side.uppercased())")
                                    .font(.caption2)
                                    .foregroundColor(Brand.muted)
                            }
                            Spacer()
                            Text(account.is_active ? "Active" : "Inactive")
                                .font(.caption2.weight(.semibold))
                                .foregroundColor(account.is_active ? Brand.success : Brand.muted)
                        }
                        .padding(.vertical, 4)
                        if account.id != accounts.last?.id {
                            Divider().background(Brand.hairline)
                        }
                    }
                }
            }
        }
    }
}

private struct GeneralLedgerCard: View {
    let entries: [GLEntryDTO]

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 8) {
                Text("General Ledger").font(.headline).foregroundColor(.white)
                Text("Most recent entries").font(.caption2).foregroundColor(Brand.muted)
                if entries.isEmpty {
                    Text("No journal entries yet.").font(.caption).foregroundColor(Brand.muted)
                } else {
                    ForEach(entries) { entry in
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 6) {
                                    Text(DateFormatters.shortDateTime.string(from: entry.occurred_at))
                                        .font(.caption2.weight(.semibold))
                                        .foregroundColor(Brand.muted)
                                    Text(entry.account_name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                }
                                if let memo = entry.memo, !memo.isEmpty {
                                    Text(memo).font(.caption2).foregroundColor(Brand.muted)
                                }
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                if entry.debit_minor > 0 {
                                    Text("Dr \(inr(entry.debit_minor))").font(.caption.weight(.semibold)).foregroundColor(.white)
                                }
                                if entry.credit_minor > 0 {
                                    Text("Cr \(inr(entry.credit_minor))").font(.caption.weight(.semibold)).foregroundColor(Brand.softGold)
                                }
                            }
                        }
                        .padding(.vertical, 4)
                        if entry.id != entries.last?.id {
                            Divider().background(Brand.hairline)
                        }
                    }
                }
            }
        }
    }
}

private struct InsightsLossesTab: View {
    @EnvironmentObject private var session: AppSession
    @State private var losses: LossesDTO?
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                if let error { ErrorBanner(message: error) }

                if let losses {
                    Text("\(DateFormatters.apiDateOnly.string(from: losses.from_date)) → \(DateFormatters.apiDateOnly.string(from: losses.to_date)) · waste + damage + negative-stock corrections")
                        .font(.caption2)
                        .foregroundColor(Brand.muted)

                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        MetricCard(title: "Waste", value: inr(losses.waste_minor), detail: "This period", icon: "trash")
                        MetricCard(title: "Damage", value: inr(losses.damage_minor), detail: "This period", icon: "exclamationmark.triangle")
                        MetricCard(title: "Negative Stock", value: inr(losses.negative_stock_minor), detail: "Corrections", icon: "arrow.down.circle")
                        MetricCard(title: "Total Losses", value: inr(losses.total_loss_minor), detail: "This period", icon: "chart.line.downtrend.xyaxis")
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("By ingredient").font(.headline).foregroundColor(.white)
                            if losses.lines.isEmpty {
                                Text("No losses in this period. Keep it up.")
                                    .font(.caption)
                                    .foregroundColor(Brand.success)
                            } else {
                                ForEach(losses.lines) { line in
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(line.name).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                            Text(line.sku).font(.caption2).foregroundColor(Brand.muted)
                                        }
                                        Spacer()
                                        VStack(alignment: .trailing, spacing: 2) {
                                            Text(inr(line.cost_lost_minor)).font(.subheadline.weight(.semibold)).foregroundColor(Brand.danger)
                                            Text("\(NumberFormatters.decimal.string(from: NSNumber(value: line.qty_lost)) ?? "") lost · \(line.movement_count) events")
                                                .font(.caption2)
                                                .foregroundColor(Brand.muted)
                                        }
                                    }
                                    .padding(.vertical, 4)
                                    if line.id != losses.lines.last?.id {
                                        Divider().background(Brand.hairline)
                                    }
                                }
                            }
                        }
                    }
                } else if isLoading {
                    LoadingBlock(title: "Loading losses")
                }
            }
            .padding(16)
        }
        .task { await load() }
    }

    private func load() async {
        isLoading = losses == nil
        defer { isLoading = false }
        error = nil
        do {
            let result: LossesDTO = try await session.authorized { token in
                try await APIClient.shared.get("insights/losses", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) { losses = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private enum FinanceTab: String, CaseIterable, Identifiable {
    case overview, expenses, partners

    var id: String { rawValue }

    var title: String {
        switch self {
        case .overview: return "Overview"
        case .expenses: return "Expenses"
        case .partners: return "Partners"
        }
    }
}

private struct FinanceNativeView: View {
    @State private var tab: FinanceTab = .overview

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(FinanceTab.allCases) { t in Text(t.title).tag(t) }
            }
            .pickerStyle(.segmented)
            .padding(16)

            Group {
                switch tab {
                case .overview: FinanceOverviewTab()
                case .expenses: FinanceExpensesTab()
                case .partners: FinancePartnersTab()
                }
            }
        }
        .navigationTitle("Finance")
        .background(Brand.background)
    }
}

private struct FinanceOverviewTab: View {
    @EnvironmentObject private var session: AppSession
    @State private var report: ReportDTO?
    @State private var metrics: BusinessMetricsDTO?
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                if let error { ErrorBanner(message: error) }

                if let report {
                    Text("Today · \(report.orders_count) orders · \(report.tickets_count) tickets · Avg \(inr(report.avg_ticket_minor))")
                        .font(.caption)
                        .foregroundColor(Brand.muted)

                    LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                        MetricCard(title: "Revenue", value: inr(report.revenue.total_minor), detail: "Gross, today", icon: "wallet.pass")
                        MetricCard(title: "GST Collected", value: inr(report.tax_collected.total_minor), detail: "CGST + SGST + IGST", icon: "doc.text")
                        MetricCard(title: "Expenses", value: inr(report.expense_total_minor), detail: "Today", icon: "receipt")
                        MetricCard(title: "Net Profit", value: inr(report.net_profit_minor), detail: "Today", icon: "chart.line.uptrend.xyaxis")
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Revenue").font(.headline).foregroundColor(.white)
                            if report.revenue.food_minor > 0 { PNLRow(title: "Food (5% GST)", value: inr(report.revenue.food_minor)) }
                            if report.revenue.gaming_minor > 0 { PNLRow(title: "Gaming (18% GST)", value: inr(report.revenue.gaming_minor)) }
                            if report.revenue.hookah_minor > 0 { PNLRow(title: "Shisha (18% GST)", value: inr(report.revenue.hookah_minor)) }
                            if report.revenue.event_tickets_minor > 0 { PNLRow(title: "Event tickets (18% GST)", value: inr(report.revenue.event_tickets_minor)) }
                            if report.revenue.delivery_aggregator_minor > 0 {
                                PNLRow(title: "Delivery aggregator (zero GST, Sec 9(5))", value: inr(report.revenue.delivery_aggregator_minor))
                            }
                            if report.revenue.other_minor > 0 { PNLRow(title: "Other", value: inr(report.revenue.other_minor)) }
                            if (report.revenue.manual_collections_minor ?? 0) > 0 {
                                PNLRow(title: "Manual collections (unitemized)", value: inr(report.revenue.manual_collections_minor ?? 0))
                                Text("Included in revenue without an order, item mix, or automatic COGS.")
                                    .font(.caption2)
                                    .foregroundColor(Brand.muted)
                            }
                            if (report.revenue.discounts_and_points_redeemed_minor ?? 0) > 0 {
                                PNLRow(title: "Less: discounts and points", value: "-\(inr(report.revenue.discounts_and_points_redeemed_minor ?? 0))")
                            }
                            Divider().background(Brand.gold.opacity(0.35))
                            PNLRow(title: "Less: Output CGST", value: "-\(inr(report.tax_collected.cgst_minor))")
                            PNLRow(title: "Less: Output SGST", value: "-\(inr(report.tax_collected.sgst_minor))")
                            if report.tax_collected.igst_minor > 0 {
                                PNLRow(title: "Less: Output IGST", value: "-\(inr(report.tax_collected.igst_minor))")
                            }
                            Divider().background(Brand.gold.opacity(0.35))
                            PNLRow(title: "Net revenue (after GST)", value: inr(report.net_revenue_minor), highlight: true)
                            PNLRow(title: "Less: cost of goods sold", value: "-\(inr(report.cogs_minor))")
                            Text("What the food, drinks, and items you sold actually cost you.")
                                .font(.caption2)
                                .foregroundColor(Brand.muted)
                            Divider().background(Brand.gold.opacity(0.35))
                            PNLRow(title: "Gross profit", value: inr(report.gross_profit_minor), highlight: true)
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Expenses by category").font(.headline).foregroundColor(.white)
                            if report.expenses.isEmpty {
                                Text("No expenses recorded today.").font(.caption).foregroundColor(Brand.muted)
                            } else {
                                ForEach(report.expenses) { e in
                                    PNLRow(title: e.category, value: inr(e.amount_minor))
                                }
                            }
                            Divider().background(Brand.gold.opacity(0.35))
                            PNLRow(title: "Total expenses", value: inr(report.expense_total_minor), highlight: true)
                        }
                    }

                    if let metrics {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Business metrics")
                                .font(.subheadline.weight(.semibold))
                                .foregroundColor(Brand.muted)

                            LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 12) {
                                MetricCard(
                                    title: "Avg order value",
                                    value: inr(metrics.aov_minor),
                                    detail: "\(metrics.orders_count) order\(metrics.orders_count == 1 ? "" : "s") this period",
                                    icon: "cart"
                                )
                                MetricCard(
                                    title: "MRR",
                                    value: inr(metrics.mrr_minor),
                                    detail: "\(metrics.active_members_count) active member\(metrics.active_members_count == 1 ? "" : "s")",
                                    icon: "arrow.triangle.2.circlepath"
                                )
                                MetricCard(title: "ARR", value: inr(metrics.arr_minor), detail: "MRR x 12, projected", icon: "calendar")
                                MetricCard(
                                    title: "Customer lifetime value",
                                    value: inr(metrics.ltv_minor),
                                    detail: "avg across \(metrics.customers_count) customer\(metrics.customers_count == 1 ? "" : "s"), all-time",
                                    icon: "person.fill.checkmark"
                                )
                                MetricCard(
                                    title: "Cost per new customer",
                                    value: metrics.cac_minor.map(inr) ?? "—",
                                    detail: metrics.cac_minor == nil
                                        ? "no new customers this period"
                                        : "\(inr(metrics.marketing_spend_minor)) marketing / \(metrics.new_customers_count) new",
                                    icon: "megaphone"
                                )
                                MetricCard(
                                    title: "Burn rate",
                                    value: inr(metrics.burn_rate_minor),
                                    detail: metrics.burn_rate_minor > 0 ? "lost money this period, after cost of goods sold" : "profitable this period",
                                    icon: metrics.burn_rate_minor > 0 ? "flame" : "checkmark.seal"
                                )
                            }

                            Text("These fit a single-location, self-funded business — not SaaS/VC fundraising metrics that don't apply here.")
                                .font(.caption2)
                                .foregroundColor(Brand.muted)
                        }
                    }

                    Text("For monthly, quarterly, or yearly P&L, open Reports.")
                        .font(.caption2)
                        .foregroundColor(Brand.muted)
                } else if isLoading {
                    LoadingBlock(title: "Loading today's P&L")
                }
            }
            .padding(16)
        }
        .task { await load() }
    }

    private func load() async {
        isLoading = report == nil
        defer { isLoading = false }
        error = nil
        do {
            async let loadedReport: ReportDTO = session.authorized { token in
                try await APIClient.shared.get("reports/daily", token: token)
            }
            async let loadedMetrics: BusinessMetricsDTO = session.authorized { token in
                try await APIClient.shared.get("finance/metrics", token: token)
            }
            let (freshReport, freshMetrics) = try await (loadedReport, loadedMetrics)
            withAnimation(.easeOut(duration: 0.18)) {
                report = freshReport
                metrics = freshMetrics
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct FinanceExpensesTab: View {
    @EnvironmentObject private var session: AppSession
    @State private var expenses: [ExpenseDTO] = []
    @State private var categories: [ExpenseCategoryDTO] = []
    @State private var branches: [BranchDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var showAdd = false

    private var total: Int { expenses.reduce(0) { $0 + $1.amount_minor } }

    private func categoryName(_ id: String) -> String {
        categories.first { $0.id == id }?.name ?? "—"
    }

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                if let error { ErrorBanner(message: error) }

                HStack {
                    Text("\(expenses.count) expense\(expenses.count == 1 ? "" : "s") · Total \(inr(total))")
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                    Spacer()
                    Button {
                        Haptics.selection()
                        showAdd = true
                    } label: {
                        Label("Add", systemImage: "plus")
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Brand.gold)
                    .disabled(categories.isEmpty || branches.isEmpty)
                }
                .buttonStyle(.plain)

                if categories.isEmpty || branches.isEmpty {
                    ErrorBanner(message: "Add at least one branch and expense category in Settings before recording expenses.")
                }

                if expenses.isEmpty {
                    if isLoading {
                        LoadingBlock(title: "Loading expenses")
                    } else {
                        InlineEmptyCard(icon: "receipt", title: "No expenses yet", subtitle: "Recorded expenses will appear here.")
                    }
                } else {
                    BrandedCard {
                        VStack(alignment: .leading, spacing: 4) {
                            ForEach(expenses) { expense in
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(categoryName(expense.category_id)).font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                        Text("\(DateFormatters.shortDateTime.string(from: expense.paid_at)) · \(expense.vendor_name?.nilIfBlank ?? "No vendor")")
                                            .font(.caption2)
                                            .foregroundColor(Brand.muted)
                                        if let invoice = expense.invoice_no?.nilIfBlank {
                                            Text("Invoice \(invoice)").font(.caption2).foregroundColor(Brand.muted)
                                        }
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 4) {
                                        Text(inr(expense.amount_minor)).font(.subheadline.weight(.semibold)).foregroundColor(Brand.softGold)
                                        Text(expense.paid_via.uppercased()).font(.caption2.weight(.semibold)).foregroundColor(Brand.muted)
                                        Button {
                                            Haptics.selection()
                                            Task { await delete(expense) }
                                        } label: {
                                            Image(systemName: "trash")
                                                .foregroundColor(Brand.danger)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                                .padding(.vertical, 6)
                                if expense.id != expenses.last?.id {
                                    Divider().background(Brand.hairline)
                                }
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .task { await load() }
        .sheet(isPresented: $showAdd) {
            AddExpenseSheet(categories: categories, branches: branches) {
                Task { await load() }
            }
        }
    }

    private func load() async {
        isLoading = expenses.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let e: [ExpenseDTO] = session.authorized { token in
                try await APIClient.shared.get("finance/expenses", token: token)
            }
            async let c: [ExpenseCategoryDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/expense-categories", token: token)
            }
            async let b: [BranchDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/branches", token: token)
            }
            let (freshExpenses, freshCategories, freshBranches) = try await (e, c, b)
            withAnimation(.easeOut(duration: 0.18)) {
                expenses = freshExpenses
                categories = freshCategories
                branches = freshBranches
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func delete(_ expense: ExpenseDTO) async {
        do {
            try await session.authorized { token in
                try await APIClient.shared.delete("finance/expenses/\(expense.id)", token: token)
            }
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct AddExpenseSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let categories: [ExpenseCategoryDTO]
    let branches: [BranchDTO]
    let onDone: () -> Void

    @State private var branchId: String
    @State private var categoryId: String
    @State private var amountText = ""
    @State private var paidVia = "cash"
    @State private var vendorName = ""
    @State private var invoiceNo = ""
    @State private var note = ""
    @State private var isBusy = false
    @State private var error: String?

    private let paidViaOptions = ["cash", "upi", "card", "bank"]

    init(categories: [ExpenseCategoryDTO], branches: [BranchDTO], onDone: @escaping () -> Void) {
        self.categories = categories
        self.branches = branches
        self.onDone = onDone
        _branchId = State(initialValue: branches.first?.id ?? "")
        _categoryId = State(initialValue: categories.first?.id ?? "")
    }

    private var amountMinor: Int? {
        guard let value = Double(amountText), value > 0 else { return nil }
        return Int((value * 100).rounded())
    }

    private var canSubmit: Bool {
        amountMinor != nil && !branchId.isEmpty && !categoryId.isEmpty
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                field("Branch") {
                                    Picker("", selection: $branchId) {
                                        ForEach(branches) { b in Text(b.name).tag(b.id) }
                                    }
                                    .pickerStyle(.menu)
                                }
                                field("Category") {
                                    Picker("", selection: $categoryId) {
                                        ForEach(categories) { c in Text(c.name).tag(c.id) }
                                    }
                                    .pickerStyle(.menu)
                                }
                                field("Amount (₹)") {
                                    TextField("0.00", text: $amountText)
                                        .keyboardType(.decimalPad)
                                        .nativeField()
                                }
                                field("Paid via") {
                                    Picker("", selection: $paidVia) {
                                        ForEach(paidViaOptions, id: \.self) { Text($0.uppercased()).tag($0) }
                                    }
                                    .pickerStyle(.segmented)
                                }
                                field("Vendor") {
                                    TextField("Optional", text: $vendorName).nativeField()
                                }
                                field("Invoice no.") {
                                    TextField("Optional", text: $invoiceNo).nativeField()
                                }
                                field("Note") {
                                    TextField("Optional", text: $note).nativeField()
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Add Expense")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await submit() } }
                        .disabled(!canSubmit || isBusy)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func submit() async {
        guard let amountMinor else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let _: ExpenseDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "finance/expenses",
                    body: ExpenseCreateRequest(
                        branch_id: branchId,
                        category_id: categoryId,
                        supplier_id: nil,
                        amount_minor: amountMinor,
                        paid_via: paidVia,
                        paid_at: DateFormatters.iso.string(from: Date()),
                        vendor_name: vendorName.trimmingCharacters(in: .whitespaces).nilIfBlank,
                        invoice_no: invoiceNo.trimmingCharacters(in: .whitespaces).nilIfBlank,
                        note: note.trimmingCharacters(in: .whitespaces).nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            onDone()
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct FinancePartnersTab: View {
    @EnvironmentObject private var session: AppSession
    @State private var partners: [PartnerDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var showAdd = false
    @State private var capitalPartner: PartnerDTO?
    @State private var ledgerPartner: PartnerDTO?

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                if let error { ErrorBanner(message: error) }

                HStack {
                    Text("\(partners.count) partner\(partners.count == 1 ? "" : "s")")
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                    Spacer()
                    Button {
                        Haptics.selection()
                        showAdd = true
                    } label: {
                        Label("Add partner", systemImage: "plus")
                    }
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Brand.gold)
                }
                .buttonStyle(.plain)

                if partners.isEmpty {
                    if isLoading {
                        LoadingBlock(title: "Loading partners")
                    } else {
                        InlineEmptyCard(icon: "person.2", title: "No partners yet", subtitle: "Add partners to track capital contributions and profit share.")
                    }
                } else {
                    ForEach(partners) { partner in
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 10) {
                                HStack(alignment: .top) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(partner.name).font(.headline).foregroundColor(.white)
                                        Text("Share \(String(format: "%.1f", partner.share_pct))% · Since \(DateFormatters.apiDateOnly.string(from: partner.joined_at))")
                                            .font(.caption2)
                                            .foregroundColor(Brand.muted)
                                    }
                                    Spacer()
                                    VStack(alignment: .trailing, spacing: 2) {
                                        Text("Capital balance").font(.caption2).foregroundColor(Brand.muted)
                                        Text(inr(partner.capital_balance_minor))
                                            .font(.subheadline.weight(.bold))
                                            .foregroundColor(partner.capital_balance_minor < 0 ? Brand.danger : .white)
                                    }
                                }
                                HStack(spacing: 14) {
                                    Button {
                                        Haptics.selection()
                                        capitalPartner = partner
                                    } label: {
                                        Label("Record movement", systemImage: "banknote")
                                    }
                                    Button {
                                        Haptics.selection()
                                        ledgerPartner = partner
                                    } label: {
                                        Label("Ledger", systemImage: "wallet.pass")
                                    }
                                }
                                .buttonStyle(.plain)
                                .font(.caption.weight(.semibold))
                                .foregroundColor(Brand.softGold)
                            }
                        }
                    }
                }
            }
            .padding(16)
        }
        .task { await load() }
        .sheet(isPresented: $showAdd) {
            AddPartnerSheet {
                Task { await load() }
            }
        }
        .sheet(item: $capitalPartner) { partner in
            CapitalMovementSheet(partner: partner) {
                Task { await load() }
            }
        }
        .sheet(item: $ledgerPartner) { partner in
            PartnerLedgerSheet(partner: partner)
        }
    }

    private func load() async {
        isLoading = partners.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            let result: [PartnerDTO] = try await session.authorized { token in
                try await APIClient.shared.get("finance/partners", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) { partners = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct AddPartnerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let onDone: () -> Void

    @State private var name = ""
    @State private var sharePctText = "50"
    @State private var notes = ""
    @State private var isBusy = false
    @State private var error: String?

    private var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && (Double(sharePctText) ?? 0) > 0
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Name").font(.caption).foregroundColor(Brand.muted)
                                TextField("Partner name", text: $name).nativeField()
                                Text("Share %").font(.caption).foregroundColor(Brand.muted)
                                TextField("50", text: $sharePctText)
                                    .keyboardType(.decimalPad)
                                    .nativeField()
                                Text("Notes").font(.caption).foregroundColor(Brand.muted)
                                TextField("Optional", text: $notes).nativeField()
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Add Partner")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await submit() } }
                        .disabled(!canSubmit || isBusy)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func submit() async {
        guard let sharePct = Double(sharePctText) else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let _: PartnerDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "finance/partners",
                    body: PartnerCreateRequest(
                        name: name.trimmingCharacters(in: .whitespaces),
                        share_pct: sharePct,
                        joined_at: DateFormatters.iso.string(from: Date()),
                        notes: notes.trimmingCharacters(in: .whitespaces).nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            onDone()
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct CapitalMovementSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let partner: PartnerDTO
    let onDone: () -> Void

    @State private var type = "invest"
    @State private var amountText = ""
    @State private var note = ""
    @State private var isBusy = false
    @State private var error: String?

    private let typeOptions: [(value: String, title: String)] = [
        (value: "invest", title: "Investment (+)"),
        (value: "withdraw", title: "Withdrawal (−)"),
        (value: "profit_share", title: "Profit share (+)")
    ]

    private var amountMinor: Int? {
        guard let value = Double(amountText), value > 0 else { return nil }
        return Int((value * 100).rounded())
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text(partner.name).font(.headline).foregroundColor(.white)
                                Text("Type").font(.caption).foregroundColor(Brand.muted)
                                Picker("", selection: $type) {
                                    ForEach(typeOptions, id: \.value) { option in
                                        Text(option.title).tag(option.value)
                                    }
                                }
                                .pickerStyle(.menu)
                                Text("Amount (₹)").font(.caption).foregroundColor(Brand.muted)
                                TextField("0.00", text: $amountText)
                                    .keyboardType(.decimalPad)
                                    .nativeField()
                                Text("Note").font(.caption).foregroundColor(Brand.muted)
                                TextField("Optional", text: $note).nativeField()
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Capital Movement")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await submit() } }
                        .disabled(amountMinor == nil || isBusy)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func submit() async {
        guard let amountMinor else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let _: CapitalEntryDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "finance/capital-entries",
                    body: CapitalEntryCreateRequest(
                        partner_id: partner.id,
                        type: type,
                        amount_minor: amountMinor,
                        effective_at: DateFormatters.iso.string(from: Date()),
                        note: note.trimmingCharacters(in: .whitespaces).nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            onDone()
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct PartnerLedgerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let partner: PartnerDTO

    @State private var entries: [CapitalEntryDTO] = []
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        if isLoading {
                            LoadingBlock(title: "Loading ledger")
                        } else if entries.isEmpty {
                            InlineEmptyCard(icon: "wallet.pass", title: "No movements yet", subtitle: "Capital movements for \(partner.name) will appear here.")
                        } else {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 4) {
                                    ForEach(entries) { entry in
                                        HStack {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(entry.type.replacingOccurrences(of: "_", with: " ").capitalized)
                                                    .font(.subheadline.weight(.semibold))
                                                    .foregroundColor(.white)
                                                Text(DateFormatters.apiDateOnly.string(from: entry.effective_at))
                                                    .font(.caption2)
                                                    .foregroundColor(Brand.muted)
                                                if let note = entry.note?.nilIfBlank {
                                                    Text(note).font(.caption2).foregroundColor(Brand.muted)
                                                }
                                            }
                                            Spacer()
                                            Text("\(entry.type == "withdraw" ? "-" : "+")\(inr(entry.amount_minor))")
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundColor(entry.type == "withdraw" ? Brand.danger : Brand.success)
                                        }
                                        .padding(.vertical, 6)
                                        if entry.id != entries.last?.id {
                                            Divider().background(Brand.hairline)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("\(partner.name) — Ledger")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
        .task { await load() }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            let result: [CapitalEntryDTO] = try await session.authorized { token in
                try await APIClient.shared.get("finance/partners/\(partner.id)/capital", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) { entries = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct EventsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var events: [EventDTO] = []
    @State private var includePast = true
    @State private var isLoading = true
    @State private var error: String?
    @State private var showAdd = false
    @State private var editingEvent: EventDTO?
    @State private var sellingFor: EventDTO?
    @State private var ticketsFor: EventDTO?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Events", subtitle: "Projector screenings, ticket sales, 18% GST", icon: "tv")

                    Toggle("Show past events", isOn: $includePast)
                        .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                        .foregroundColor(.white)
                        .font(.caption.weight(.semibold))
                        .onChange(of: includePast) { _ in Task { await load() } }

                    if let error { ErrorBanner(message: error) }

                    if events.isEmpty {
                        if isLoading {
                            LoadingBlock(title: "Loading events")
                        } else {
                            InlineEmptyCard(icon: "tv", title: "No events yet", subtitle: "Tap the + button to schedule your first screening.")
                        }
                    } else {
                        ForEach(events) { event in
                            EventCard(
                                event: event,
                                onEdit: {
                                    Haptics.selection()
                                    editingEvent = event
                                },
                                onDelete: {
                                    Task { await delete(event) }
                                },
                                onSell: {
                                    Haptics.selection()
                                    sellingFor = event
                                },
                                onViewTickets: {
                                    Haptics.selection()
                                    ticketsFor = event
                                }
                            )
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Events")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        Haptics.selection()
                        showAdd = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .background(Brand.background)
        }
        .task { await load() }
        .sheet(isPresented: $showAdd) {
            EventFormSheet(event: nil) {
                Task { await load() }
            }
        }
        .sheet(item: $editingEvent) { event in
            EventFormSheet(event: event) {
                Task { await load() }
            }
        }
        .sheet(item: $sellingFor) { event in
            SellTicketsSheet(event: event) {
                Task { await load() }
            }
        }
        .sheet(item: $ticketsFor) { event in
            EventTicketsSheet(event: event) {
                Task { await load() }
            }
        }
    }

    private func load() async {
        isLoading = events.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            let result: [EventDTO] = try await session.authorized { token in
                try await APIClient.shared.get(
                    "events/all",
                    token: token,
                    queryItems: [URLQueryItem(name: "include_past", value: includePast ? "true" : "false")]
                )
            }
            withAnimation(.easeOut(duration: 0.18)) { events = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func delete(_ event: EventDTO) async {
        do {
            try await session.authorized { token in
                try await APIClient.shared.delete("events/\(event.id)", token: token)
            }
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct EventCard: View {
    let event: EventDTO
    let onEdit: () -> Void
    let onDelete: () -> Void
    let onSell: () -> Void
    let onViewTickets: () -> Void

    private var progress: Double {
        event.capacity > 0 ? Double(event.sold) / Double(event.capacity) : 0
    }

    private var isFull: Bool { event.sold >= event.capacity }

    private var canSell: Bool {
        !isFull && (event.status == "scheduled" || event.status == "live")
    }

    private var typeLabel: String {
        switch event.event_type {
        case "football": return "Football"
        case "cricket": return "Cricket"
        case "movie": return "Movie"
        case "esports": return "E-sports"
        default: return "Other"
        }
    }

    private var statusColor: Color {
        switch event.status {
        case "live": return Brand.success
        case "ended": return Brand.muted
        case "cancelled": return Brand.danger
        default: return Brand.gold
        }
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(event.name).font(.headline).foregroundColor(.white)
                        Text(event.description?.nilIfBlank ?? event.screen).font(.caption2).foregroundColor(Brand.muted)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(typeLabel)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Brand.gold.opacity(0.16))
                            .foregroundColor(Brand.gold)
                            .clipShape(Capsule())
                        Text(event.status.capitalized)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(statusColor.opacity(0.16))
                            .foregroundColor(statusColor)
                            .clipShape(Capsule())
                    }
                }

                VStack(alignment: .leading, spacing: 4) {
                    Label(DateFormatters.shortDateTime.string(from: event.starts_at), systemImage: "calendar")
                    Label(event.screen, systemImage: "mappin.and.ellipse")
                    Label("\(event.sold) / \(event.capacity) sold · \(event.remaining) left", systemImage: "person.2")
                    Label("\(inr(event.base_ticket_price_minor)) per ticket (incl. 18% GST)", systemImage: "ticket")
                }
                .font(.caption2)
                .foregroundColor(Brand.muted)

                GeometryReader { geo in
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(Brand.surface)
                        .overlay(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 3, style: .continuous)
                                .fill(isFull ? Brand.danger : (progress > 0.75 ? Brand.gold : Brand.success))
                                .frame(width: geo.size.width * min(1, progress))
                        }
                }
                .frame(height: 6)

                HStack(spacing: 14) {
                    Button(action: onSell) {
                        Text("Sell ticket")
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(canSell ? Brand.gold : Brand.muted.opacity(0.3))
                            .foregroundColor(canSell ? .black : Brand.muted)
                            .clipShape(Capsule())
                    }
                    .disabled(!canSell)

                    Button(action: onViewTickets) {
                        Text("Tickets (\(event.sold))")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(Brand.softGold)
                    }

                    Spacer()

                    Button(action: onEdit) {
                        Image(systemName: "pencil")
                            .foregroundColor(Brand.muted)
                    }
                    Button(action: onDelete) {
                        Image(systemName: "trash")
                            .foregroundColor(Brand.danger)
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }
}

private struct EventFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let event: EventDTO?
    let onDone: () -> Void

    @State private var name: String
    @State private var description: String
    @State private var eventType: String
    @State private var screen: String
    @State private var startsAt: Date
    @State private var hasEndsAt: Bool
    @State private var endsAt: Date
    @State private var capacityText: String
    @State private var priceText: String
    @State private var posterUrl: String
    @State private var status: String
    @State private var isBusy = false
    @State private var error: String?

    private var isEdit: Bool { event != nil }

    init(event: EventDTO?, onDone: @escaping () -> Void) {
        self.event = event
        self.onDone = onDone
        _name = State(initialValue: event?.name ?? "")
        _description = State(initialValue: event?.description ?? "")
        _eventType = State(initialValue: event?.event_type ?? "football")
        _screen = State(initialValue: event?.screen ?? "Main Screen")
        _startsAt = State(initialValue: event?.starts_at ?? Date().addingTimeInterval(3600))
        _hasEndsAt = State(initialValue: event?.ends_at != nil)
        _endsAt = State(initialValue: event?.ends_at ?? Date().addingTimeInterval(7200))
        _capacityText = State(initialValue: "\(event?.capacity ?? 60)")
        _priceText = State(initialValue: String(format: "%.2f", Double(event?.base_ticket_price_minor ?? 19900) / 100))
        _posterUrl = State(initialValue: event?.poster_url ?? "")
        _status = State(initialValue: event?.status ?? "scheduled")
    }

    private var capacity: Int? { Int(capacityText) }
    private var priceMinor: Int? {
        guard let value = Double(priceText), value >= 0 else { return nil }
        return Int((value * 100).rounded())
    }

    private var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && capacity != nil && priceMinor != nil
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                field("Event name") {
                                    TextField("Name", text: $name).nativeField()
                                }
                                field("Description") {
                                    TextField("Optional", text: $description).nativeField()
                                }
                                field("Type") {
                                    Picker("", selection: $eventType) {
                                        ForEach(eventTypes, id: \.self) { Text($0.capitalized).tag($0) }
                                    }
                                    .pickerStyle(.segmented)
                                }
                                field("Screen / room") {
                                    TextField("Main Screen", text: $screen).nativeField()
                                }
                                field("Starts at") {
                                    DatePicker("", selection: $startsAt)
                                        .labelsHidden()
                                        .datePickerStyle(.compact)
                                        .colorScheme(.dark)
                                }
                                Toggle("Has end time", isOn: $hasEndsAt)
                                    .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                    .foregroundColor(.white)
                                    .font(.caption.weight(.semibold))
                                if hasEndsAt {
                                    field("Ends at") {
                                        DatePicker("", selection: $endsAt)
                                            .labelsHidden()
                                            .datePickerStyle(.compact)
                                            .colorScheme(.dark)
                                    }
                                }
                                field("Capacity") {
                                    TextField("60", text: $capacityText)
                                        .keyboardType(.numberPad)
                                        .nativeField()
                                }
                                field("Ticket price (₹, incl. 18% GST)") {
                                    TextField("199.00", text: $priceText)
                                        .keyboardType(.decimalPad)
                                        .nativeField()
                                }
                                if isEdit {
                                    field("Status") {
                                        Picker("", selection: $status) {
                                            ForEach(eventStatuses, id: \.self) { Text($0.capitalized).tag($0) }
                                        }
                                        .pickerStyle(.segmented)
                                    }
                                }
                                field("Poster image URL") {
                                    TextField("Optional", text: $posterUrl).nativeField()
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(isEdit ? "Edit Event" : "New Event")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await submit() } }
                        .disabled(!canSubmit || isBusy)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func submit() async {
        guard let capacity, let priceMinor else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            if let event {
                let _: EventDTO = try await session.authorized { token in
                    try await APIClient.shared.patch(
                        "events/\(event.id)",
                        body: EventUpdateRequest(
                            name: name.trimmingCharacters(in: .whitespaces),
                            description: description.trimmingCharacters(in: .whitespaces).nilIfBlank,
                            event_type: eventType,
                            screen: screen.trimmingCharacters(in: .whitespaces).nilIfBlank,
                            starts_at: DateFormatters.iso.string(from: startsAt),
                            ends_at: hasEndsAt ? DateFormatters.iso.string(from: endsAt) : nil,
                            capacity: capacity,
                            base_ticket_price_minor: priceMinor,
                            poster_url: posterUrl.trimmingCharacters(in: .whitespaces).nilIfBlank,
                            status: status
                        ),
                        token: token
                    )
                }
            } else {
                let _: EventDTO = try await session.authorized { token in
                    try await APIClient.shared.post(
                        "events",
                        body: EventCreateRequest(
                            name: name.trimmingCharacters(in: .whitespaces),
                            description: description.trimmingCharacters(in: .whitespaces).nilIfBlank,
                            event_type: eventType,
                            screen: screen.trimmingCharacters(in: .whitespaces).nilIfBlank,
                            starts_at: DateFormatters.iso.string(from: startsAt),
                            ends_at: hasEndsAt ? DateFormatters.iso.string(from: endsAt) : nil,
                            capacity: capacity,
                            base_ticket_price_minor: priceMinor,
                            poster_url: posterUrl.trimmingCharacters(in: .whitespaces).nilIfBlank
                        ),
                        token: token
                    )
                }
            }
            Haptics.success()
            onDone()
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct SellTicketsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let event: EventDTO
    let onDone: () -> Void

    @State private var customerName = ""
    @State private var customerPhone = ""
    @State private var seat = ""
    @State private var qtyText = "1"
    @State private var note = ""
    @State private var isBusy = false
    @State private var error: String?
    @State private var issued: [EventTicketDTO]?

    private var qty: Int? {
        guard let value = Int(qtyText), value >= 1, value <= event.remaining else { return nil }
        return value
    }

    private var canSubmit: Bool {
        !customerName.trimmingCharacters(in: .whitespaces).isEmpty && qty != nil
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                if let issued {
                    ScrollView {
                        VStack(spacing: 16) {
                            SuccessBanner(message: "\(issued.count) ticket\(issued.count == 1 ? "" : "s") issued for \(event.name)")
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 4) {
                                    ForEach(issued) { ticket in
                                        HStack {
                                            Text(ticket.ticket_no).font(.caption.monospaced()).foregroundColor(.white)
                                            Spacer()
                                            Text(inr(ticket.price_paid_minor)).font(.caption.weight(.semibold)).foregroundColor(Brand.softGold)
                                        }
                                        .padding(.vertical, 4)
                                    }
                                    Divider().background(Brand.gold.opacity(0.35))
                                    HStack {
                                        Text("Total").font(.subheadline.weight(.bold)).foregroundColor(.white)
                                        Spacer()
                                        Text(inr(issued.reduce(0) { $0 + $1.price_paid_minor }))
                                            .font(.subheadline.weight(.bold))
                                            .foregroundColor(Brand.softGold)
                                    }
                                }
                            }
                        }
                        .padding(16)
                    }
                } else {
                    ScrollView {
                        VStack(spacing: 16) {
                            if let error { ErrorBanner(message: error) }
                            Text("\(event.remaining) of \(event.capacity) seats remaining · \(inr(event.base_ticket_price_minor)) each")
                                .font(.caption)
                                .foregroundColor(Brand.muted)
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 12) {
                                    field("Customer name") {
                                        TextField("Name", text: $customerName).nativeField()
                                    }
                                    field("Phone") {
                                        TextField("Optional", text: $customerPhone)
                                            .keyboardType(.phonePad)
                                            .nativeField()
                                    }
                                    field("Seat") {
                                        TextField("Optional", text: $seat).nativeField()
                                    }
                                    field("Quantity") {
                                        TextField("1", text: $qtyText)
                                            .keyboardType(.numberPad)
                                            .nativeField()
                                    }
                                    field("Note") {
                                        TextField("Optional", text: $note).nativeField()
                                    }
                                }
                            }
                            if let qty {
                                Text("Total: \(inr(event.base_ticket_price_minor * qty))")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundColor(Brand.softGold)
                            }
                        }
                        .padding(16)
                    }
                }
            }
            .navigationTitle("Sell Tickets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // ToolbarContentBuilder's if/else only gained support in iOS 16
                // (buildEither); this app targets iOS 15, so both ToolbarItems
                // stay present and the conditional logic lives inside the
                // Button itself instead of branching the toolbar content.
                ToolbarItem(placement: .cancellationAction) {
                    Button(issued == nil ? "Cancel" : "Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(issued == nil ? "Issue" : "Done") {
                        if issued == nil {
                            Task { await submit() }
                        } else {
                            onDone()
                            dismiss()
                        }
                    }
                    .disabled(issued == nil && (!canSubmit || isBusy))
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func submit() async {
        guard let qty else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let result: [EventTicketDTO] = try await session.authorized { token in
                try await APIClient.shared.post(
                    "events/\(event.id)/tickets",
                    body: SellTicketsRequest(
                        customer_name: customerName.trimmingCharacters(in: .whitespaces),
                        customer_phone: customerPhone.trimmingCharacters(in: .whitespaces).nilIfBlank,
                        seat: seat.trimmingCharacters(in: .whitespaces).nilIfBlank,
                        qty: qty,
                        note: note.trimmingCharacters(in: .whitespaces).nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            withAnimation { issued = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct EventTicketsSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let event: EventDTO
    let onChange: () -> Void

    @State private var tickets: [EventTicketDTO] = []
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        if isLoading {
                            LoadingBlock(title: "Loading tickets")
                        } else if tickets.isEmpty {
                            InlineEmptyCard(icon: "ticket", title: "No tickets sold yet", subtitle: "Sold tickets for this event will appear here.")
                        } else {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 4) {
                                    ForEach(tickets) { ticket in
                                        HStack(alignment: .top) {
                                            VStack(alignment: .leading, spacing: 2) {
                                                Text(ticket.ticket_no).font(.caption.monospaced().weight(.semibold)).foregroundColor(.white)
                                                Text(ticket.customer_name?.nilIfBlank ?? "No name")
                                                    .font(.caption2)
                                                    .foregroundColor(Brand.muted)
                                                if let phone = ticket.customer_phone?.nilIfBlank {
                                                    Text(phone).font(.caption2).foregroundColor(Brand.muted)
                                                }
                                            }
                                            Spacer()
                                            VStack(alignment: .trailing, spacing: 4) {
                                                Text(inr(ticket.price_paid_minor)).font(.caption.weight(.semibold)).foregroundColor(Brand.softGold)
                                                Text(ticket.status == "checked_in" ? "Checked in" : ticket.status.capitalized)
                                                    .font(.caption2.weight(.semibold))
                                                    .foregroundColor(ticket.status == "checked_in" ? Brand.success : (ticket.status == "sold" ? Brand.gold : Brand.danger))
                                                if ticket.status == "sold" {
                                                    Button {
                                                        Haptics.selection()
                                                        Task { await checkIn(ticket) }
                                                    } label: {
                                                        Text("Check in")
                                                            .font(.caption2.weight(.bold))
                                                            .foregroundColor(.black)
                                                            .padding(.horizontal, 10).padding(.vertical, 5)
                                                            .background(Brand.gold)
                                                            .clipShape(Capsule())
                                                    }
                                                    .buttonStyle(.plain)
                                                }
                                            }
                                        }
                                        .padding(.vertical, 6)
                                        if ticket.id != tickets.last?.id {
                                            Divider().background(Brand.hairline)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("\(event.name) — Tickets")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
        }
        .navigationViewStyle(.stack)
        .task { await load() }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            let result: [EventTicketDTO] = try await session.authorized { token in
                try await APIClient.shared.get("events/\(event.id)/tickets", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) { tickets = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func checkIn(_ ticket: EventTicketDTO) async {
        do {
            let _: EventTicketDTO = try await session.authorized { token in
                try await APIClient.shared.post("events/\(event.id)/tickets/\(ticket.id)/check-in", body: EmptyRequest(), token: token)
            }
            await load()
            onChange()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct MembershipsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var tiers: [MembershipTierDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var editingTier: MembershipTierDTO?
    @State private var subscribingTier: MembershipTierDTO?

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "Memberships", subtitle: "D Club tiers — pricing and perks", icon: "crown")

                    if let error { ErrorBanner(message: error) }

                    if tiers.isEmpty {
                        if isLoading {
                            LoadingBlock(title: "Loading tiers")
                        } else {
                            InlineEmptyCard(icon: "crown", title: "No tiers yet", subtitle: "Membership tiers are seeded on install.")
                        }
                    } else {
                        ForEach(tiers.sorted { $0.sort_order < $1.sort_order }) { tier in
                            MembershipTierCard(
                                tier: tier,
                                canManage: session.hasProtectedOwnerAccess,
                                onEdit: {
                                    Haptics.selection()
                                    editingTier = tier
                                },
                                onSubscribe: {
                                    Haptics.selection()
                                    subscribingTier = tier
                                }
                            )
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("Memberships")
            .background(Brand.background)
        }
        .task { await load() }
        .sheet(item: $editingTier) { tier in
            MembershipTierEditSheet(tier: tier) {
                Task { await load() }
            }
        }
        .sheet(item: $subscribingTier) { tier in
            SubscribeMemberSheet(tier: tier) {
                Task { await load() }
            }
        }
    }

    private func load() async {
        isLoading = tiers.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            let result: [MembershipTierDTO] = try await session.authorized { token in
                try await APIClient.shared.get("memberships/tiers", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) { tiers = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct MembershipTierCard: View {
    let tier: MembershipTierDTO
    let canManage: Bool
    let onEdit: () -> Void
    let onSubscribe: () -> Void

    private var accent: Color {
        switch tier.code {
        case "gold": return Brand.gold
        case "platinum": return .purple
        default: return Brand.muted
        }
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    HStack(spacing: 8) {
                        Image(systemName: "crown.fill").foregroundColor(accent)
                        Text(tier.name).font(.title3.weight(.bold)).foregroundColor(.white)
                    }
                    Spacer()
                    if canManage {
                        Button(action: onEdit) {
                            Image(systemName: "pencil").foregroundColor(Brand.muted)
                        }
                        .buttonStyle(.plain)
                    }
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(inr(tier.monthly_price_minor))
                        .font(.system(size: 28, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Text("per month").font(.caption2).foregroundColor(Brand.muted)
                    if let annual = tier.annual_price_minor {
                        let savings = tier.monthly_price_minor * 12 - annual
                        Text("or \(inr(annual))/year (save \(inr(savings)))")
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(Brand.softGold)
                    }
                }

                VStack(alignment: .leading, spacing: 6) {
                    TierPerkRow(icon: "fork.knife", active: tier.food_discount_pct > 0, text: "\(Int((tier.food_discount_pct * 100).rounded()))% off food")
                    TierPerkRow(
                        icon: "gamecontroller",
                        active: tier.gaming_discount_pct > 0 || tier.free_gaming_minutes_per_week > 0,
                        text: tier.free_gaming_minutes_per_week > 0
                            ? "\(tier.free_gaming_minutes_per_week / 60)h free PS5/week"
                            : "\(Int((tier.gaming_discount_pct * 100).rounded()))% off gaming"
                    )
                    TierPerkRow(
                        icon: "flame",
                        active: tier.hookah_discount_pct > 0 || tier.free_hookah_per_month > 0,
                        text: tier.free_hookah_per_month > 0
                            ? "\(tier.free_hookah_per_month) free hookah/month"
                            : "\(Int((tier.hookah_discount_pct * 100).rounded()))% off hookah"
                    )
                    TierPerkRow(icon: "star", active: tier.point_multiplier > 1, text: "\(NumberFormatters.decimal.string(from: NSNumber(value: tier.point_multiplier)) ?? "1")× loyalty points")
                    TierPerkRow(icon: "crown", active: tier.priority_booking, text: "Priority booking")
                }

                if let description = tier.description?.nilIfBlank {
                    Divider().background(Brand.hairline)
                    Text(description).font(.caption2).foregroundColor(Brand.muted)
                }

                if canManage {
                    Button(action: onSubscribe) {
                        Text("Subscribe a customer")
                            .font(.caption.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.black)
                    .background(Brand.gold)
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
        }
    }
}

private struct TierPerkRow: View {
    let icon: String
    let active: Bool
    let text: String

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: icon).font(.caption2).frame(width: 16)
            // Text.strikethrough(_:) is a Text-only API (iOS 13+); the
            // View-level strikethrough(_:pattern:color:) modifier this app's
            // iOS 15 deployment target can't use didn't exist until iOS 16.
            Text(text).font(.caption).strikethrough(!active)
        }
        .foregroundColor(active ? .white : Brand.muted.opacity(0.5))
    }
}

private struct MembershipTierEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let tier: MembershipTierDTO
    let onDone: () -> Void

    @State private var name: String
    @State private var monthlyText: String
    @State private var annualText: String
    @State private var foodPctText: String
    @State private var gamingPctText: String
    @State private var hookahPctText: String
    @State private var pointMultiplierText: String
    @State private var freeGamingMinText: String
    @State private var freeHookahText: String
    @State private var priorityBooking: Bool
    @State private var description: String
    @State private var isBusy = false
    @State private var error: String?

    init(tier: MembershipTierDTO, onDone: @escaping () -> Void) {
        self.tier = tier
        self.onDone = onDone
        _name = State(initialValue: tier.name)
        _monthlyText = State(initialValue: String(format: "%.2f", Double(tier.monthly_price_minor) / 100))
        _annualText = State(initialValue: tier.annual_price_minor.map { String(format: "%.2f", Double($0) / 100) } ?? "")
        _foodPctText = State(initialValue: "\(Int((tier.food_discount_pct * 100).rounded()))")
        _gamingPctText = State(initialValue: "\(Int((tier.gaming_discount_pct * 100).rounded()))")
        _hookahPctText = State(initialValue: "\(Int((tier.hookah_discount_pct * 100).rounded()))")
        _pointMultiplierText = State(initialValue: String(format: "%.2f", tier.point_multiplier))
        _freeGamingMinText = State(initialValue: "\(tier.free_gaming_minutes_per_week)")
        _freeHookahText = State(initialValue: "\(tier.free_hookah_per_month)")
        _priorityBooking = State(initialValue: tier.priority_booking)
        _description = State(initialValue: tier.description ?? "")
    }

    private var monthlyMinor: Int? {
        guard let value = Double(monthlyText), value >= 0 else { return nil }
        return Int((value * 100).rounded())
    }

    private var annualMinor: Int? {
        let trimmed = annualText.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        guard let value = Double(trimmed), value >= 0 else { return nil }
        return Int((value * 100).rounded())
    }

    private var canSubmit: Bool {
        !name.trimmingCharacters(in: .whitespaces).isEmpty && monthlyMinor != nil
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                field("Name") { TextField("Name", text: $name).nativeField() }
                                field("Monthly price (₹)") {
                                    TextField("0.00", text: $monthlyText).keyboardType(.decimalPad).nativeField()
                                }
                                field("Annual price (₹, optional)") {
                                    TextField("Leave blank for none", text: $annualText).keyboardType(.decimalPad).nativeField()
                                }
                                field("Food discount %") {
                                    TextField("0", text: $foodPctText).keyboardType(.numberPad).nativeField()
                                }
                                field("Gaming discount %") {
                                    TextField("0", text: $gamingPctText).keyboardType(.numberPad).nativeField()
                                }
                                field("Hookah discount %") {
                                    TextField("0", text: $hookahPctText).keyboardType(.numberPad).nativeField()
                                }
                                field("Point multiplier (×)") {
                                    TextField("1", text: $pointMultiplierText).keyboardType(.decimalPad).nativeField()
                                }
                                field("Free PS5 min/week") {
                                    TextField("0", text: $freeGamingMinText).keyboardType(.numberPad).nativeField()
                                }
                                field("Free hookah/month") {
                                    TextField("0", text: $freeHookahText).keyboardType(.numberPad).nativeField()
                                }
                                Toggle("Priority booking", isOn: $priorityBooking)
                                    .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                    .foregroundColor(.white)
                                    .font(.caption.weight(.semibold))
                                field("Description") {
                                    TextField("Shown to customers", text: $description).nativeField()
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Edit \(tier.name)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await submit() } }
                        .disabled(!canSubmit || isBusy)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func field<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func submit() async {
        guard let monthlyMinor else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let _: MembershipTierDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "memberships/tiers/\(tier.id)",
                    body: MembershipTierUpdateRequest(
                        name: name.trimmingCharacters(in: .whitespaces),
                        monthly_price_minor: monthlyMinor,
                        annual_price_minor: annualMinor,
                        food_discount_pct: (Double(foodPctText) ?? 0) / 100,
                        gaming_discount_pct: (Double(gamingPctText) ?? 0) / 100,
                        hookah_discount_pct: (Double(hookahPctText) ?? 0) / 100,
                        point_multiplier: Double(pointMultiplierText) ?? 1,
                        free_gaming_minutes_per_week: Int(freeGamingMinText) ?? 0,
                        free_hookah_per_month: Int(freeHookahText) ?? 0,
                        priority_booking: priorityBooking,
                        description: description.trimmingCharacters(in: .whitespaces).nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            onDone()
            dismiss()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct SubscribeMemberSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let tier: MembershipTierDTO
    let onDone: () -> Void

    @State private var search = ""
    @State private var customers: [CustomerDTO] = []
    @State private var selectedCustomer: CustomerDTO?
    @State private var billingCycle = "monthly"
    @State private var isSearching = false
    @State private var isBusy = false
    @State private var error: String?
    @State private var success: SubscriptionDTO?

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        if let success {
                            SuccessBanner(message: "\(selectedCustomer?.name?.nilIfBlank ?? selectedCustomer?.phone ?? "Customer") subscribed to \(tier.name).")
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 8) {
                                    PNLRow(title: "Tier", value: success.tier_name)
                                    PNLRow(title: "Billing", value: success.billing_cycle.capitalized)
                                    PNLRow(title: "Paid", value: inr(success.amount_paid_minor))
                                    PNLRow(title: "Expires", value: DateFormatters.apiDateOnly.string(from: success.expires_at))
                                }
                            }
                        } else {
                            Text("Subscribing to \(tier.name) — \(inr(tier.monthly_price_minor))/mo")
                                .font(.caption)
                                .foregroundColor(Brand.muted)

                            if let selectedCustomer {
                                BrandedCard {
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(selectedCustomer.name?.nilIfBlank ?? "No name").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                            Text(selectedCustomer.phone).font(.caption2).foregroundColor(Brand.muted)
                                        }
                                        Spacer()
                                        Button {
                                            self.selectedCustomer = nil
                                        } label: {
                                            Text("Change")
                                                .font(.caption.weight(.semibold))
                                                .foregroundColor(Brand.softGold)
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            } else {
                                SearchBar(text: $search, placeholder: "Search name or phone")
                                    .onChange(of: search) { _ in Task { await searchCustomers() } }

                                if isSearching {
                                    LoadingBlock(title: "Searching")
                                } else if !customers.isEmpty {
                                    BrandedCard {
                                        VStack(alignment: .leading, spacing: 4) {
                                            ForEach(customers) { customer in
                                                Button {
                                                    Haptics.selection()
                                                    selectedCustomer = customer
                                                } label: {
                                                    HStack {
                                                        VStack(alignment: .leading, spacing: 2) {
                                                            Text(customer.name?.nilIfBlank ?? "No name").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                                            Text(customer.phone).font(.caption2).foregroundColor(Brand.muted)
                                                        }
                                                        Spacer()
                                                        Image(systemName: "chevron.right").foregroundColor(Brand.muted)
                                                    }
                                                    .padding(.vertical, 6)
                                                }
                                                .buttonStyle(.plain)
                                                if customer.id != customers.last?.id {
                                                    Divider().background(Brand.hairline)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if selectedCustomer != nil {
                                BrandedCard {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("Billing cycle").font(.caption).foregroundColor(Brand.muted)
                                        Picker("", selection: $billingCycle) {
                                            Text("Monthly").tag("monthly")
                                            if tier.annual_price_minor != nil {
                                                Text("Annual").tag("annual")
                                            }
                                        }
                                        .pickerStyle(.segmented)
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Subscribe Customer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(success == nil ? "Cancel" : "Close") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(success == nil ? "Subscribe" : "Done") {
                        if success == nil {
                            Task { await submit() }
                        } else {
                            onDone()
                            dismiss()
                        }
                    }
                    .disabled(success == nil && (selectedCustomer == nil || isBusy))
                }
            }
        }
        .navigationViewStyle(.stack)
        .task { await searchCustomers() }
    }

    private func searchCustomers() async {
        isSearching = true
        defer { isSearching = false }
        do {
            var queryItems: [URLQueryItem] = [URLQueryItem(name: "limit", value: "20")]
            let trimmed = search.trimmingCharacters(in: .whitespaces)
            if !trimmed.isEmpty {
                queryItems.append(URLQueryItem(name: "q", value: trimmed))
            }
            let result: [CustomerDTO] = try await session.authorized { token in
                try await APIClient.shared.get("customers", token: token, queryItems: queryItems)
            }
            customers = result
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func submit() async {
        guard let customer = selectedCustomer else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let result: SubscriptionDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "memberships/subscribe",
                    body: SubscribeRequest(customer_id: customer.id, tier_id: tier.id, billing_cycle: billingCycle, paid_via: nil),
                    token: token
                )
            }
            Haptics.success()
            withAnimation { success = result }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct PhotoLibraryPicker: UIViewControllerRepresentable {
    let onComplete: (Data) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = 1
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: PHPickerViewController, context: Context) {}

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onComplete: (Data) -> Void
        private let onCancel: () -> Void

        init(onComplete: @escaping (Data) -> Void, onCancel: @escaping () -> Void) {
            self.onComplete = onComplete
            self.onCancel = onCancel
        }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
                onCancel()
                return
            }
            provider.loadObject(ofClass: UIImage.self) { object, _ in
                DispatchQueue.main.async {
                    if let image = object as? UIImage, let data = image.jpegData(compressionQuality: 0.85) {
                        self.onComplete(data)
                    } else {
                        self.onCancel()
                    }
                }
            }
        }
    }
}

private enum OcrStatus: String {
    case parsed, needs_review, approved, duplicate, rejected

    var label: String {
        switch self {
        case .parsed: return "Parsed"
        case .needs_review: return "Needs review"
        case .approved: return "Approved"
        case .duplicate: return "Duplicate"
        case .rejected: return "Rejected"
        }
    }

    var color: Color {
        switch self {
        case .parsed: return Brand.gold
        case .needs_review: return Brand.gold
        case .approved: return Brand.success
        case .duplicate: return Brand.muted
        case .rejected: return Brand.danger
        }
    }
}

private struct OcrNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var rows: [OcrExtractionDTO] = []
    @State private var branches: [BranchDTO] = []
    @State private var isLoading = true
    @State private var isUploading = false
    @State private var error: String?
    @State private var showScanner = false
    @State private var showPhotoPicker = false

    var body: some View {
        AppNavigation {
            RefreshableScrollView(refresh: load) {
                VStack(spacing: 16) {
                    HeaderBlock(title: "OCR — Receipts", subtitle: "Upload bills, extract vendor/date/amount, approve into expenses", icon: "scanner")

                    if let error { ErrorBanner(message: error) }

                    BrandedCard {
                        VStack(spacing: 12) {
                            if isUploading {
                                ProgressView("Uploading and running OCR…")
                                    .tint(Brand.gold)
                                    .foregroundColor(.white)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 20)
                            } else {
                                Image(systemName: "doc.text.viewfinder")
                                    .font(.system(size: 32))
                                    .foregroundColor(Brand.muted)
                                Text("Scan or choose a bill image")
                                    .font(.subheadline.weight(.semibold))
                                    .foregroundColor(.white)
                                Text("JPG · PNG · auto-detects duplicates by SHA-256")
                                    .font(.caption2)
                                    .foregroundColor(Brand.muted)

                                HStack(spacing: 10) {
                                    Button {
                                        Haptics.selection()
                                        showScanner = true
                                    } label: {
                                        Label("Scan document", systemImage: "camera")
                                            .font(.caption.weight(.semibold))
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 12)
                                    }
                                    .buttonStyle(.plain)
                                    .foregroundColor(.black)
                                    .background(Brand.gold)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                                    Button {
                                        Haptics.selection()
                                        showPhotoPicker = true
                                    } label: {
                                        Label("Choose photo", systemImage: "photo")
                                            .font(.caption.weight(.semibold))
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 12)
                                    }
                                    .buttonStyle(.plain)
                                    .foregroundColor(.white)
                                    .background(Brand.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                }
                                .disabled(branches.isEmpty)

                                if branches.isEmpty && !isLoading {
                                    Text("Add a branch in Settings before uploading receipts.")
                                        .font(.caption2)
                                        .foregroundColor(Brand.danger)
                                }
                            }
                        }
                    }

                    HStack {
                        Text("Verification queue · \(rows.count) item\(rows.count == 1 ? "" : "s")")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.white)
                        Spacer()
                    }

                    if rows.isEmpty {
                        if isLoading {
                            LoadingBlock(title: "Loading queue")
                        } else {
                            InlineEmptyCard(icon: "tray", title: "No bills in the queue", subtitle: "Upload a receipt to extract vendor, date, and amount automatically.")
                        }
                    } else {
                        ForEach(rows) { row in
                            OcrExtractionRow(row: row) { decision in
                                Task { await decide(row, decision: decision) }
                            }
                        }
                    }
                }
                .padding(16)
            }
            .navigationTitle("OCR")
            .background(Brand.background)
        }
        .task { await load() }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentOCRScanner(
                onComplete: { _, imageData in
                    showScanner = false
                    if let imageData {
                        Task { await upload(data: imageData, fileName: "scan.jpg", mimeType: "image/jpeg") }
                    }
                },
                onFailure: { message in
                    error = message
                    showScanner = false
                },
                onCancel: {
                    showScanner = false
                }
            )
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showPhotoPicker) {
            PhotoLibraryPicker(
                onComplete: { data in
                    showPhotoPicker = false
                    Task { await upload(data: data, fileName: "receipt.jpg", mimeType: "image/jpeg") }
                },
                onCancel: {
                    showPhotoPicker = false
                }
            )
            .ignoresSafeArea()
        }
    }

    private func load() async {
        isLoading = rows.isEmpty
        defer { isLoading = false }
        error = nil
        do {
            async let queue: [OcrExtractionDTO] = session.authorized { token in
                try await APIClient.shared.get("ocr/queue", token: token)
            }
            async let branchList: [BranchDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/branches", token: token)
            }
            let (freshQueue, freshBranches) = try await (queue, branchList)
            withAnimation(.easeOut(duration: 0.18)) {
                rows = freshQueue
                branches = freshBranches
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func upload(data: Data, fileName: String, mimeType: String) async {
        guard let branchId = branches.first?.id else { return }
        isUploading = true
        defer { isUploading = false }
        error = nil
        do {
            let _: OcrUploadResponseDTO = try await session.authorized { token in
                try await APIClient.shared.upload(
                    "ocr/uploads",
                    fileData: data,
                    fileName: fileName,
                    mimeType: mimeType,
                    formFields: ["branch_id": branchId, "source": "manual"],
                    token: token
                )
            }
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func decide(_ row: OcrExtractionDTO, decision: String) async {
        do {
            let _: OcrVerifyResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "ocr/extractions/\(row.id)/verify",
                    body: OcrVerifyRequest(decision: decision, notes: nil),
                    token: token
                )
            }
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct OcrExtractionRow: View {
    let row: OcrExtractionDTO
    let onDecide: (String) -> Void

    private var status: OcrStatus { OcrStatus(rawValue: row.status) ?? .parsed }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .top) {
                    Image(systemName: "doc.text.fill")
                        .foregroundColor(Brand.muted)
                        .frame(width: 28)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.vendor_name?.nilIfBlank ?? "Unknown vendor")
                            .font(.subheadline.weight(.semibold))
                            .foregroundColor(.white)
                        HStack(spacing: 6) {
                            if let date = row.invoice_date {
                                Text(DateFormatters.apiDateOnly.string(from: date))
                            } else {
                                Text("No date")
                            }
                            if let invoiceNo = row.invoice_no?.nilIfBlank {
                                Text("· \(invoiceNo)")
                            }
                        }
                        .font(.caption2)
                        .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 4) {
                        Text(row.amount_minor.map { inr($0) } ?? "—")
                            .font(.subheadline.weight(.bold))
                            .foregroundColor(.white)
                        Text(status.label)
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(status.color.opacity(0.16))
                            .foregroundColor(status.color)
                            .clipShape(Capsule())
                    }
                }

                if status == .needs_review {
                    HStack(spacing: 10) {
                        Button {
                            Haptics.selection()
                            onDecide("approve")
                        } label: {
                            Label("Approve", systemImage: "checkmark")
                                .font(.caption.weight(.bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.black)
                        .background(Brand.gold)
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

                        Button {
                            Haptics.selection()
                            onDecide("reject")
                        } label: {
                            Label("Reject", systemImage: "xmark")
                                .font(.caption.weight(.bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 10)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(Brand.danger)
                        .background(Brand.danger.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    }
                }
            }
        }
    }
}

private enum OrdersShiftsTab: String, CaseIterable, Identifiable {
    case orders, shifts
    var id: String { rawValue }
    var title: String { self == .orders ? "Orders" : "Shifts" }
}

private struct OrdersNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var tab: OrdersShiftsTab = .orders
    @State private var orders: [OrderListItemDTO] = []
    @State private var shiftHistory: [ShiftDTO] = []
    @State private var search = ""
    @State private var isLoading = true
    @State private var error: String?
    @State private var closingShift: ShiftDTO?
    @State private var unsubscribeRealtime: [() -> Void] = []

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(OrdersShiftsTab.allCases) { t in
                    Text(t.title).tag(t)
                }
            }
            .pickerStyle(.segmented)
            .padding(16)

            if tab == .orders {
                ordersList
            } else {
                shiftsList
            }
        }
        .navigationTitle("Operations")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Haptics.selection()
                    Task { await load() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .background(Brand.background)
        .sheet(item: $closingShift) { shift in
            CloseShiftSheet(shift: shift) {
                Task { await load() }
            }
        }
        .task { await load() }
        .task { await pollForServerUpdates() }
        .onAppear {
            unsubscribeRealtime = ["orders", "shifts"].map { resource in
                RealtimeClient.shared.subscribe(resource) { Task { await load() } }
            }
        }
        .onDisappear {
            unsubscribeRealtime.forEach { $0() }
            unsubscribeRealtime = []
        }
    }

    // Whether a shift is open, and who has it open, is shared across every
    // device on this terminal — without this, a shift opened on one phone
    // stays invisible on everyone else's screen, which is exactly what leads
    // someone else to try opening a second one and hit a "already open"
    // error out of nowhere. Real-time push (see .onAppear above) is the
    // primary mechanism; this is only a safety net for a missed/dropped push.
    private func pollForServerUpdates() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { break }
            await load()
        }
    }

    private var ordersList: some View {
        List {
            if let error {
                ErrorBanner(message: error)
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            }

            if isLoading && orders.isEmpty {
                ForEach(0..<6, id: \.self) { _ in
                    AuditSkeletonRow()
                        .listRowBackground(Brand.background)
                        .listRowSeparator(.hidden)
                }
            } else if filteredOrders.isEmpty {
                InlineEmptyRow(icon: "receipt", title: "No orders", subtitle: "Recent orders will appear here after POS billing.")
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(filteredOrders) { order in
                    OrderHistoryRow(order: order)
                        .listRowBackground(Brand.background)
                        .listRowSeparatorTint(Brand.hairline)
                }
            }
        }
        .listStyle(.plain)
        .premiumListChrome()
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search orders")
    }

    private var shiftsList: some View {
        List {
            if let error {
                ErrorBanner(message: error)
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            }

            if isLoading && shiftHistory.isEmpty {
                ForEach(0..<4, id: \.self) { _ in
                    AuditSkeletonRow()
                        .listRowBackground(Brand.background)
                        .listRowSeparator(.hidden)
                }
            } else if shiftHistory.isEmpty {
                InlineEmptyRow(icon: "clock.badge.checkmark", title: "No shifts yet", subtitle: "Shift history appears here once a shift is opened.")
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(shiftHistory) { shift in
                    ShiftHistoryRow(shift: shift, canClose: shift.status == "open" && session.isShiftOpener(shift)) {
                        Haptics.selection()
                        closingShift = shift
                    }
                    .listRowBackground(Brand.background)
                    .listRowSeparatorTint(Brand.hairline)
                }
            }
        }
        .listStyle(.plain)
        .premiumListChrome()
    }

    private var filteredOrders: [OrderListItemDTO] {
        guard !search.isEmpty else { return orders }
        return orders.filter { order in
            (order.invoice_no ?? "").localizedCaseInsensitiveContains(search)
                || order.status.localizedCaseInsensitiveContains(search)
                || (order.customer_name ?? "").localizedCaseInsensitiveContains(search)
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            async let loadedOrders: [OrderListItemDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/orders", token: token,
                    queryItems: [URLQueryItem(name: "limit", value: "80")],
                    headers: session.terminalId.map { ["X-Terminal-Id": $0] } ?? [:]
                )
            }
            async let loadedShifts: [ShiftDTO] = session.authorized { token in
                try await APIClient.shared.get(
                    "pos/shifts", token: token,
                    queryItems: [URLQueryItem(name: "limit", value: "50")]
                )
            }
            let (freshOrders, freshShifts) = try await (loadedOrders, loadedShifts)
            orders = freshOrders
            shiftHistory = freshShifts
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct ShiftHistoryRow: View {
    let shift: ShiftDTO
    let canClose: Bool
    let onClose: () -> Void

    private var statusColor: Color {
        shift.status == "open" ? Brand.success : Brand.muted
    }

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: shift.status == "open" ? "clock.fill" : "checkmark.circle")
                .foregroundColor(statusColor)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 5) {
                HStack(spacing: 8) {
                    Text(shift.status.capitalized)
                        .font(.headline)
                        .foregroundColor(.white)
                    if let name = shift.opened_by_name, !name.isEmpty {
                        Text("by \(name)")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                }
                Text("Opened \(DateFormatters.shortDateTime.string(from: shift.opened_at))")
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
                HStack(spacing: 14) {
                    Text("Float \(inr(shift.opening_float_minor))")
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(Brand.softGold)
                    if let expected = shift.expected_minor {
                        Text("Expected \(inr(expected))")
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(Brand.softGold)
                    }
                    if let variance = shift.variance_minor {
                        Text("Variance \(inr(variance))")
                            .font(.caption2.weight(.semibold))
                            .foregroundColor(variance == 0 ? Brand.success : (abs(variance) < 5000 ? Brand.gold : Brand.danger))
                    }
                }
            }

            Spacer()

            if shift.status == "open" {
                Button(action: onClose) {
                    Text("Close")
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 12)
                        .padding(.vertical, 8)
                        .background(canClose ? Brand.gold : Brand.muted.opacity(0.3))
                        .foregroundColor(canClose ? .black : Brand.muted)
                        .clipShape(Capsule())
                }
                .buttonStyle(PressableButtonStyle())
                .disabled(!canClose)
            }
        }
        .padding(.vertical, 6)
    }
}

private enum CashCountMode: String, CaseIterable, Identifiable {
    case total, denomination
    var id: String { rawValue }
    var title: String { self == .total ? "Enter total" : "Count denominations" }
}

private struct CloseShiftSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let shift: ShiftDTO
    let onClosed: () -> Void

    // Indian note/coin denominations, largest first — used by the optional
    // denomination-breakdown entry mode below.
    private let denominations = [500, 200, 100, 50, 20, 10, 5, 2, 1]

    @State private var countedText = ""
    @State private var countMode: CashCountMode = .total
    @State private var denomCounts: [Int: String] = [500: "", 200: "", 100: "", 50: "", 20: "", 10: "", 5: "", 2: "", 1: ""]
    @State private var isSubmitting = false
    @State private var error: String?
    @State private var result: ShiftCloseResponseDTO?

    // Sum of (denomination × count), in paise — exact integer arithmetic, no
    // float round-trip through a rupee string.
    private var denomTotalMinor: Int {
        denominations.reduce(0) { sum, d in
            sum + d * 100 * (Int(denomCounts[d] ?? "") ?? 0)
        }
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                VStack(spacing: 16) {
                    if let result {
                        BrandedCard {
                            VStack(spacing: 10) {
                                Text("Variance")
                                    .font(.caption.weight(.bold))
                                    .foregroundColor(Brand.muted)
                                Text(inr(result.variance_minor))
                                    .font(.system(size: 34, weight: .bold, design: .rounded))
                                    .foregroundColor(result.variance_minor == 0 ? Brand.success : (abs(result.variance_minor) < 5000 ? Brand.gold : Brand.danger))
                                Text(
                                    result.variance_minor == 0 ? "Drawer balanced exactly"
                                        : result.variance_minor > 0 ? "Surplus — more cash than expected"
                                        : "Short — less cash than expected"
                                )
                                .font(.caption)
                                .foregroundColor(Brand.muted)
                            }
                            .frame(maxWidth: .infinity)
                        }
                        Button {
                            onClosed()
                            dismiss()
                        } label: {
                            Text("Done")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.black)
                        .background(Brand.gold)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    } else {
                        if let error {
                            ErrorBanner(message: error)
                        }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Count all the cash in the drawer (notes and coins), then enter the total. The system compares it with the expected amount (opening float plus cash sales minus cash refunds).")
                                    .font(.caption)
                                    .foregroundColor(Brand.muted)

                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Opening float")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    Text(inr(shift.opening_float_minor))
                                        .font(.headline)
                                        .foregroundColor(.white)
                                }

                                Picker("", selection: $countMode) {
                                    ForEach(CashCountMode.allCases) { m in
                                        Text(m.title).tag(m)
                                    }
                                }
                                .pickerStyle(.segmented)

                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Counted cash (what's actually in the drawer)")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    TextField("0.00", text: $countedText)
                                        .keyboardType(.decimalPad)
                                        .font(.title2.weight(.bold).monospacedDigit())
                                        .nativeField()
                                        .disabled(countMode == .denomination)
                                }
                            }
                        }

                        if countMode == .denomination {
                            BrandedCard {
                                VStack(spacing: 10) {
                                    ForEach(denominations, id: \.self) { d in
                                        HStack {
                                            Text("₹\(d)")
                                                .font(.subheadline.weight(.semibold).monospacedDigit())
                                                .foregroundColor(Brand.muted)
                                                .frame(width: 52, alignment: .leading)
                                            TextField("0", text: Binding(
                                                get: { denomCounts[d] ?? "" },
                                                set: { denomCounts[d] = $0 }
                                            ))
                                            .keyboardType(.numberPad)
                                            .multilineTextAlignment(.trailing)
                                            .font(.body.monospacedDigit())
                                            .nativeField()
                                            Text(inr(d * 100 * (Int(denomCounts[d] ?? "") ?? 0)))
                                                .font(.caption.weight(.semibold).monospacedDigit())
                                                .foregroundColor(Brand.softGold)
                                                .frame(width: 84, alignment: .trailing)
                                        }
                                    }
                                    Divider().background(Brand.hairline)
                                    HStack {
                                        Text("Total counted")
                                            .font(.subheadline.weight(.bold))
                                            .foregroundColor(.white)
                                        Spacer()
                                        Text(inr(denomTotalMinor))
                                            .font(.subheadline.weight(.bold).monospacedDigit())
                                            .foregroundColor(Brand.gold)
                                    }
                                }
                            }
                        }
                        Spacer()
                    }
                }
                .padding(16)
            }
            .navigationTitle("Close shift")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onChange(of: countMode) { newValue in
                if newValue == .denomination {
                    countedText = String(format: "%.2f", Double(denomTotalMinor) / 100)
                }
            }
            .onChange(of: denomCounts) { _ in
                if countMode == .denomination {
                    countedText = String(format: "%.2f", Double(denomTotalMinor) / 100)
                }
            }
            .safeAreaInset(edge: .bottom) {
                if result == nil {
                    Button {
                        Haptics.selection()
                        Task { await submit() }
                    } label: {
                        HStack {
                            if isSubmitting { ProgressView().tint(.black) }
                            Text("Close shift")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.black)
                    .background((Double(countedText) != nil && !isSubmitting) ? Brand.gold : Brand.muted.opacity(0.45))
                    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                    .padding(16)
                    .background(.ultraThinMaterial)
                    .disabled(Double(countedText) == nil || isSubmitting)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func submit() async {
        // Denomination mode submits the exact integer sum computed above;
        // free-text mode submits exactly as before (unchanged wire format).
        let countedMinor: Int
        if countMode == .denomination {
            countedMinor = denomTotalMinor
        } else {
            guard let counted = Double(countedText) else { return }
            countedMinor = Int((counted * 100).rounded())
        }
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let response: ShiftCloseResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "pos/shifts/\(shift.id)/close",
                    body: ShiftCloseRequest(counted_minor: countedMinor),
                    token: token
                )
            }
            result = response
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct CustomersNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var customers: [CustomerDTO] = []
    @State private var search = ""
    @State private var isLoading = true
    @State private var error: String?
    @State private var editingCustomer: CustomerDTO?
    @State private var isCreating = false

    var body: some View {
        List {
            if let error {
                ErrorBanner(message: error)
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            }

            if isLoading && customers.isEmpty {
                ForEach(0..<6, id: \.self) { _ in
                    InventorySkeletonRow()
                        .listRowBackground(Brand.background)
                        .listRowSeparator(.hidden)
                }
            } else if filteredCustomers.isEmpty {
                InlineEmptyRow(icon: "person.2", title: "No customers", subtitle: "Attach phone numbers during checkout to build customer history.")
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(filteredCustomers) { customer in
                    Button {
                        Haptics.selection()
                        editingCustomer = customer
                    } label: {
                        CustomerRow(customer: customer)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Brand.background)
                    .listRowSeparatorTint(Brand.hairline)
                }
            }
        }
        .listStyle(.plain)
        .premiumListChrome()
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search customers")
        .navigationTitle("Customers")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack {
                    Button { isCreating = true } label: { Image(systemName: "plus") }
                    Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }
                }
            }
        }
        .background(Brand.background)
        .sheet(item: $editingCustomer) { customer in
            CustomerFormSheet(existingCustomer: customer) { draft in
                Task { await save(draft) }
            }
        }
        .sheet(isPresented: $isCreating) {
            CustomerFormSheet(existingCustomer: nil) { draft in
                Task { await save(draft) }
            }
        }
        .task { await load() }
    }

    private var filteredCustomers: [CustomerDTO] {
        guard !search.isEmpty else { return customers }
        return customers.filter { customer in
            (customer.name ?? "").localizedCaseInsensitiveContains(search)
                || customer.phone.localizedCaseInsensitiveContains(search)
                || (customer.email ?? "").localizedCaseInsensitiveContains(search)
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            customers = try await session.authorized { token in
                try await APIClient.shared.get(
                    "customers",
                    token: token,
                    queryItems: [URLQueryItem(name: "limit", value: "120")]
                )
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func save(_ draft: CustomerFormDraft) async {
        error = nil
        do {
            if let id = draft.id {
                let updated: CustomerDTO = try await session.authorized { token in
                    try await APIClient.shared.patch("customers/\(id)", body: draft.updateRequest(), token: token)
                }
                if let index = customers.firstIndex(where: { $0.id == updated.id }) {
                    customers[index] = updated
                }
            } else {
                let created: CustomerDTO = try await session.authorized { token in
                    try await APIClient.shared.post("customers", body: draft.upsertRequest(), token: token)
                }
                customers.append(created)
            }
            editingCustomer = nil
            isCreating = false
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct CustomerFormDraft {
    var id: String?
    var phone: String
    var name: String
    var email: String
    var hasBirthday: Bool
    var birthday: Date
    var notes: String

    static func blank() -> CustomerFormDraft {
        CustomerFormDraft(id: nil, phone: "", name: "", email: "", hasBirthday: false, birthday: Date(), notes: "")
    }

    static func editing(_ c: CustomerDTO) -> CustomerFormDraft {
        CustomerFormDraft(
            id: c.id, phone: c.phone, name: c.name ?? "", email: c.email ?? "",
            hasBirthday: c.birthday != nil, birthday: c.birthday ?? Date(),
            notes: c.notes ?? ""
        )
    }

    private var birthdayValue: String? {
        // Date-only value (the picker only lets staff choose a calendar
        // day) — apiDateOnly avoids DateFormatters.iso's UTC conversion
        // silently shifting the day back whenever this is filled in
        // before ~05:30 IST.
        hasBirthday ? DateFormatters.apiDateOnly.string(from: birthday) : nil
    }

    func upsertRequest() -> CustomerUpsertRequest {
        CustomerUpsertRequest(phone: phone, name: name.nilIfBlank, email: email.nilIfBlank, birthday: birthdayValue, notes: notes.nilIfBlank)
    }

    func updateRequest() -> CustomerUpdateRequest {
        CustomerUpdateRequest(name: name.nilIfBlank, email: email.nilIfBlank, birthday: birthdayValue, notes: notes.nilIfBlank)
    }
}

private struct CustomerFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: CustomerFormDraft
    let isEditing: Bool
    let onSave: (CustomerFormDraft) -> Void

    init(existingCustomer: CustomerDTO?, onSave: @escaping (CustomerFormDraft) -> Void) {
        self.isEditing = existingCustomer != nil
        self._draft = State(initialValue: existingCustomer.map(CustomerFormDraft.editing) ?? .blank())
        self.onSave = onSave
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Phone").font(.caption).foregroundColor(Brand.muted)
                                TextField("", text: $draft.phone).keyboardType(.phonePad).disabled(isEditing).nativeField()
                            }
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Name").font(.caption).foregroundColor(Brand.muted)
                                TextField("", text: $draft.name).textInputAutocapitalization(.words).nativeField()
                            }
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Email").font(.caption).foregroundColor(Brand.muted)
                                TextField("", text: $draft.email).keyboardType(.emailAddress).autocorrectionDisabled().nativeField()
                            }
                            Toggle("Has birthday", isOn: $draft.hasBirthday)
                                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                                .foregroundColor(.white)
                                .font(.caption.weight(.semibold))
                            if draft.hasBirthday {
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Birthday").font(.caption).foregroundColor(Brand.muted)
                                    DatePicker("", selection: $draft.birthday, displayedComponents: [.date])
                                        .labelsHidden()
                                        .datePickerStyle(.compact)
                                        .colorScheme(.dark)
                                }
                            }
                            VStack(alignment: .leading, spacing: 6) {
                                Text("Notes").font(.caption).foregroundColor(Brand.muted)
                                TextField("", text: $draft.notes).nativeField()
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(isEditing ? "Edit customer" : "New customer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { onSave(draft) }.disabled(draft.phone.count < 4)
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

private struct StaffNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var users: [StaffUserDTO] = []
    @State private var search = ""
    @State private var isLoading = true
    @State private var error: String?
    @State private var editingUser: StaffUserDTO?
    @State private var showAddStaff = false

    @State private var onShift: [OnShiftDTO] = []
    @State private var isLoadingAttendance = true
    @State private var attendanceError: String?
    @State private var isSubmittingAttendance = false
    @State private var unsubscribeRealtime: (() -> Void)?

    private var myAttendance: OnShiftDTO? {
        onShift.first { $0.user_id == session.me?.user_id }
    }

    var body: some View {
        List {
            Section {
                AttendanceCard(
                    isClockedIn: myAttendance != nil,
                    clockInAt: myAttendance?.clock_in_at,
                    onShiftNames: onShift.map { $0.user_name ?? $0.user_email ?? "Staff" },
                    isLoading: isLoadingAttendance,
                    isSubmitting: isSubmittingAttendance,
                    error: attendanceError,
                    onClockIn: { Task { await clockIn() } },
                    onClockOut: { Task { await clockOut() } }
                )
            }
            .listRowBackground(Brand.background)
            .listRowSeparator(.hidden)
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 8, trailing: 16))

            if let error {
                ErrorBanner(message: error)
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            }

            if isLoading && users.isEmpty {
                ForEach(0..<5, id: \.self) { _ in
                    AuditSkeletonRow()
                        .listRowBackground(Brand.background)
                        .listRowSeparator(.hidden)
                }
            } else if filteredUsers.isEmpty {
                InlineEmptyRow(icon: "person.badge.key", title: "No staff", subtitle: "Staff users will appear here when access is configured.")
                    .listRowBackground(Brand.background)
                    .listRowSeparator(.hidden)
            } else {
                ForEach(filteredUsers) { user in
                    Button {
                        Haptics.selection()
                        editingUser = user
                    } label: {
                        StaffRow(user: user)
                    }
                    .buttonStyle(.plain)
                    .listRowBackground(Brand.background)
                    .listRowSeparatorTint(Brand.hairline)
                }
            }
        }
        .listStyle(.plain)
        .premiumListChrome()
        .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search staff")
        .navigationTitle("Staff")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                HStack {
                    Button { showAddStaff = true } label: { Image(systemName: "person.badge.plus") }
                    Button { Task { await load(); await loadAttendance() } } label: { Image(systemName: "arrow.clockwise") }
                }
            }
        }
        .background(Brand.background)
        .sheet(item: $editingUser) { user in
            StaffEditSheet(user: user) { updated in
                if let index = users.firstIndex(where: { $0.id == updated.id }) {
                    users[index] = updated
                }
                editingUser = nil
            } onDelete: {
                Task { await delete(user) }
            }
        }
        .sheet(isPresented: $showAddStaff) {
            StaffInviteSheet {
                showAddStaff = false
                Task { await load() }
            }
        }
        .task {
            await load()
            await loadAttendance()
        }
        .task { await pollForServerUpdates() }
        .onAppear {
            unsubscribeRealtime = RealtimeClient.shared.subscribe("attendance") { Task { await loadAttendance() } }
        }
        .onDisappear {
            unsubscribeRealtime?()
            unsubscribeRealtime = nil
        }
    }

    private var filteredUsers: [StaffUserDTO] {
        guard !search.isEmpty else { return users }
        return users.filter { user in
            user.name.localizedCaseInsensitiveContains(search)
                || user.email.localizedCaseInsensitiveContains(search)
                || user.roles.joined(separator: " ").localizedCaseInsensitiveContains(search)
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            users = try await session.authorized { token in
                try await APIClient.shared.get("staff/users", token: token)
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func delete(_ user: StaffUserDTO) async {
        error = nil
        do {
            try await session.authorized { token -> Void in
                try await APIClient.shared.delete("staff/users/\(user.id)", token: token)
            }
            users.removeAll { $0.id == user.id }
            editingUser = nil
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    // On-shift roster is shared across every device. Real-time push (see
    // .onAppear above) is the primary mechanism; this is only a safety net
    // for a missed/dropped push — same pattern as every other shared-state
    // screen in this app (Tables, Kitchen, Orders).
    private func pollForServerUpdates() async {
        while !Task.isCancelled {
            try? await Task.sleep(nanoseconds: 120_000_000_000)
            if Task.isCancelled { break }
            await loadAttendance()
        }
    }

    private func loadAttendance() async {
        isLoadingAttendance = true
        defer { isLoadingAttendance = false }
        attendanceError = nil
        do {
            onShift = try await session.authorized { token in
                try await APIClient.shared.get("staff/attendance/on-shift", token: token)
            }
        } catch is CancellationError {
        } catch {
            attendanceError = readable(error)
        }
    }

    private func clockIn() async {
        guard let branchId = session.me?.branch_id else {
            attendanceError = "No branch on this account — ask an owner to assign one before clocking in."
            return
        }
        isSubmittingAttendance = true
        defer { isSubmittingAttendance = false }
        attendanceError = nil
        do {
            let _: ClockInResponse = try await session.authorized { token in
                try await APIClient.shared.post(
                    "staff/attendance/clock-in",
                    body: ClockInRequest(branch_id: branchId, notes: nil),
                    token: token
                )
            }
            Haptics.success()
            await loadAttendance()
        } catch is CancellationError {
        } catch {
            attendanceError = readable(error)
        }
    }

    private func clockOut() async {
        isSubmittingAttendance = true
        defer { isSubmittingAttendance = false }
        attendanceError = nil
        do {
            let _: ClockOutResponse = try await session.authorized { token in
                try await APIClient.shared.post("staff/attendance/clock-out", body: EmptyRequest(), token: token)
            }
            Haptics.success()
            await loadAttendance()
        } catch is CancellationError {
        } catch {
            attendanceError = readable(error)
        }
    }
}

private struct StaffEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let user: StaffUserDTO
    let onSaved: (StaffUserDTO) -> Void
    let onDelete: () -> Void

    @State private var name: String
    @State private var phone: String
    @State private var roleCode: String
    @State private var isSuspended: Bool
    @State private var isSubmitting = false
    @State private var error: String?

    init(user: StaffUserDTO, onSaved: @escaping (StaffUserDTO) -> Void, onDelete: @escaping () -> Void) {
        self.user = user
        self.onSaved = onSaved
        self.onDelete = onDelete
        self._name = State(initialValue: user.name)
        self._phone = State(initialValue: user.phone ?? "")
        self._roleCode = State(initialValue: user.roles.first ?? "cashier")
        self._isSuspended = State(initialValue: user.status == "suspended")
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text(user.email).font(.caption).foregroundColor(Brand.muted)
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Name").font(.caption).foregroundColor(Brand.muted)
                                    TextField("", text: $name).nativeField()
                                }
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Phone").font(.caption).foregroundColor(Brand.muted)
                                    TextField("", text: $phone).keyboardType(.phonePad).nativeField()
                                }
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Role").font(.caption).foregroundColor(Brand.muted)
                                    Picker("Role", selection: $roleCode) {
                                        ForEach(staffRoleCodes, id: \.self) { role in
                                            Text(role.replacingOccurrences(of: "_", with: " ").capitalized).tag(role)
                                        }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.softGold)
                                }
                                Toggle(isOn: $isSuspended) {
                                    Text("Suspended").font(.subheadline.weight(.semibold)).foregroundColor(.white)
                                }
                                .toggleStyle(SwitchToggleStyle(tint: Brand.danger))
                            }
                        }
                        Button(role: .destructive) {
                            Haptics.selection()
                            onDelete()
                        } label: {
                            Label("Remove staff member", systemImage: "trash")
                                .font(.subheadline.weight(.bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(Brand.danger)
                        .background(Brand.danger.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Edit staff")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(isSubmitting)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private func save() async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let updated: StaffUserDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "staff/users/\(user.id)",
                    body: StaffUserUpdateRequest(
                        name: name.nilIfBlank, phone: phone.nilIfBlank,
                        status: isSuspended ? "suspended" : "active", role_code: roleCode
                    ),
                    token: token
                )
            }
            Haptics.success()
            onSaved(updated)
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

// Staff creation is deliberately OTP-gated server-side (POST /staff/users
// always rejects) — this reuses the exact same auth/register/* endpoints
// LoginView's "New login" mode does.
private struct StaffInviteSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onDone: () -> Void

    @State private var name = ""
    @State private var email = ""
    @State private var phone = ""
    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var code = ""
    @State private var challenge: PendingChallenge?
    @State private var isBusy = false
    @State private var error: String?
    @State private var successMessage: String?

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        if let successMessage {
                            SuccessBanner(message: successMessage)
                        }

                        if challenge == nil {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 12) {
                                    fieldRow("Full name") { TextField("", text: $name).textInputAutocapitalization(.words).nativeField() }
                                    fieldRow("Email") { TextField("", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().nativeField() }
                                    fieldRow("Phone (optional)") { TextField("", text: $phone).keyboardType(.phonePad).nativeField() }
                                    fieldRow("Temporary password") { SecureField("At least 10 characters", text: $password).nativeField() }
                                    fieldRow("Confirm password") { SecureField("", text: $confirmPassword).nativeField() }
                                }
                            }
                        } else {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 12) {
                                    Text("Approval code sent to \(challenge?.destination ?? "the business mailbox")")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    fieldRow("6-digit approval code") {
                                        TextField("", text: $code)
                                            .keyboardType(.numberPad)
                                            .multilineTextAlignment(.center)
                                            .font(.title2.weight(.bold).monospacedDigit())
                                            .onChange(of: code) { newValue in code = String(newValue.filter(\.isNumber).prefix(6)) }
                                            .nativeField()
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("New login")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Task { challenge == nil ? await requestCode() : await confirmCode() }
                } label: {
                    HStack {
                        if isBusy { ProgressView().tint(.black) }
                        Text(challenge == nil ? "Send approval code" : "Confirm approval code")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(canSubmit ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(!canSubmit || isBusy)
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func fieldRow<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private var canSubmit: Bool {
        if challenge == nil {
            return !name.isEmpty && !email.isEmpty && password.count >= 10 && password == confirmPassword
        }
        return code.count == 6
    }

    private func requestCode() async {
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let result: OtpChallengeDTO = try await APIClient.shared.post(
                "auth/register/request",
                body: RegisterRequestBody(email: email.trimmingCharacters(in: .whitespacesAndNewlines), name: name, phone: phone.nilIfBlank, password: password)
            )
            challenge = PendingChallenge(id: result.challenge_id, destination: result.destination, email: email)
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func confirmCode() async {
        guard let challenge else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let result: AccountActionDTO = try await APIClient.shared.post(
                "auth/register/confirm",
                body: RegisterConfirmBody(challenge_id: challenge.id, code: code.trimmingCharacters(in: .whitespacesAndNewlines))
            )
            successMessage = result.message
            Haptics.success()
            try? await Task.sleep(nanoseconds: 900_000_000)
            onDone()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct SettingsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var company: CompanyDTO?
    @State private var branches: [BranchDTO] = []
    @State private var terminals: [TerminalDTO] = []
    @State private var isLoading = true
    @State private var error: String?
    @State private var editingCompany = false
    @State private var editingBranch: BranchDTO?
    @State private var isCreatingBranch = false
    @State private var isCreatingTerminal = false
    @State private var showAccountReset = false
    @AppStorage(LocalAlarmScheduler.enabledKey) private var alarmsEnabled = true

    var body: some View {
        RefreshableScrollView(refresh: load) {
            VStack(spacing: 16) {
                HeaderBlock(title: "Company", subtitle: company?.legal_name ?? company?.name ?? "D Company", icon: "building.2")

                if let error {
                    ErrorBanner(message: error)
                }

                if isLoading && company == nil {
                    ReportsSkeletonView()
                } else {
                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Tax Setup").font(.headline).foregroundColor(.white)
                                Spacer()
                                Button { editingCompany = true } label: { Image(systemName: "pencil.circle.fill").foregroundColor(Brand.gold) }
                            }
                            SettingsFactRow(title: "GSTIN", value: company?.gstin ?? "Not set", isReady: company?.gstin?.isEmpty == false)
                            SettingsFactRow(title: "PAN", value: company?.pan ?? "Not set", isReady: company?.pan?.isEmpty == false)
                            SettingsFactRow(title: "GST Type", value: company.map { gstRegistrationTypeLabel($0.gst_registration_type) } ?? "Not set", isReady: company != nil)
                            SettingsFactRow(title: "Fiscal Year", value: "Starts month \(company?.fiscal_year_start_month ?? 4)", isReady: company != nil)
                            SettingsFactRow(title: "UPI VPA", value: company?.upi_vpa?.nilIfBlank ?? "Not set", isReady: company?.upi_vpa?.nilIfBlank != nil)
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                Text("Branches").font(.headline).foregroundColor(.white)
                                Spacer()
                                Button { isCreatingBranch = true } label: { Image(systemName: "plus.circle.fill").foregroundColor(Brand.gold) }
                            }
                            if branches.isEmpty {
                                InlineEmptyRow(icon: "mappin", title: "No branches", subtitle: "Add a branch before opening operations.")
                            } else {
                                ForEach(branches) { branch in
                                    Button { editingBranch = branch } label: {
                                        BranchRow(branch: branch)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Terminals").font(.headline).foregroundColor(.white)
                                    Text("Only registered terminals can open a shift or bill an order.")
                                        .font(.caption2)
                                        .foregroundColor(Brand.muted)
                                }
                                Spacer()
                                Button {
                                    Haptics.selection()
                                    isCreatingTerminal = true
                                } label: {
                                    Image(systemName: "plus.circle.fill").foregroundColor(Brand.gold)
                                }
                                .disabled(branches.isEmpty)
                            }
                            if terminals.isEmpty {
                                InlineEmptyRow(icon: "iphone", title: "No terminals", subtitle: "A POS terminal is required for charging orders.")
                            } else {
                                ForEach(terminals) { terminal in
                                    TerminalRow(terminal: terminal) {
                                        Task { await deleteTerminal(terminal) }
                                    }
                                }
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Account").font(.headline).foregroundColor(.white)
                            SettingsFactRow(title: "Signed in as", value: session.me?.email ?? "", isReady: true)
                            SettingsFactRow(title: "Role", value: session.displayRole, isReady: true)
                            Button {
                                Haptics.selection()
                                showAccountReset = true
                            } label: {
                                Label("Reset my password", systemImage: "key")
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 11)
                            }
                            .buttonStyle(.plain)
                            .foregroundColor(Brand.softGold)
                            .background(Brand.elevated)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Notifications & Alarms").font(.headline).foregroundColor(.white)
                            Toggle(isOn: $alarmsEnabled) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Alarm sounds & alerts")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundColor(.white)
                                    Text("Gaming session overtime and orders left waiting too long")
                                        .font(.caption2)
                                        .foregroundColor(Brand.muted)
                                }
                            }
                            .tint(Brand.gold)
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Company")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Haptics.selection()
                    Task { await load() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .background(Brand.background)
        .sheet(isPresented: $editingCompany) {
            if let company {
                CompanyEditSheet(company: company) { updated in
                    self.company = updated
                    editingCompany = false
                }
            }
        }
        .sheet(item: $editingBranch) { branch in
            BranchFormSheet(existingBranch: branch) { updated in
                if let index = branches.firstIndex(where: { $0.id == updated.id }) {
                    branches[index] = updated
                }
                editingBranch = nil
            }
        }
        .sheet(isPresented: $isCreatingBranch) {
            BranchFormSheet(existingBranch: nil) { created in
                branches.append(created)
                isCreatingBranch = false
            }
        }
        .sheet(isPresented: $isCreatingTerminal) {
            TerminalFormSheet(branches: branches) { created in
                terminals.append(created)
                isCreatingTerminal = false
            }
        }
        .sheet(isPresented: $showAccountReset) {
            AccountPasswordResetSheet(email: session.me?.email ?? "") {
                showAccountReset = false
            }
        }
        .task { await load() }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            async let loadedCompany: CompanyDTO = session.authorized { token in
                try await APIClient.shared.get("settings/company", token: token)
            }
            async let loadedBranches: [BranchDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/branches", token: token)
            }
            async let loadedTerminals: [TerminalDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/terminals", token: token)
            }

            let (freshCompany, freshBranches, freshTerminals) = try await (
                loadedCompany,
                loadedBranches,
                loadedTerminals
            )

            withAnimation(.easeOut(duration: 0.18)) {
                company = freshCompany
                branches = freshBranches
                terminals = freshTerminals
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func deleteTerminal(_ terminal: TerminalDTO) async {
        do {
            try await session.authorized { token in
                try await APIClient.shared.delete("settings/terminals/\(terminal.id)", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) {
                terminals.removeAll { $0.id == terminal.id }
            }
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct CompanyEditSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let company: CompanyDTO
    let onSaved: (CompanyDTO) -> Void

    @State private var name: String
    @State private var legalName: String
    @State private var gstin: String
    @State private var pan: String
    @State private var registrationType: String
    @State private var upiVpa: String
    @State private var isSubmitting = false
    @State private var error: String?

    private static let registrationTypes = ["regular", "composition", "unregistered", "sez"]

    init(company: CompanyDTO, onSaved: @escaping (CompanyDTO) -> Void) {
        self.company = company
        self.onSaved = onSaved
        self._name = State(initialValue: company.name)
        self._legalName = State(initialValue: company.legal_name ?? "")
        self._gstin = State(initialValue: company.gstin ?? "")
        self._pan = State(initialValue: company.pan ?? "")
        self._registrationType = State(initialValue: company.gst_registration_type)
        self._upiVpa = State(initialValue: company.upi_vpa ?? "")
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                labeled("Display name") { TextField("", text: $name).nativeField() }
                                labeled("Legal name") { TextField("", text: $legalName).nativeField() }
                                labeled("GSTIN") { TextField("", text: $gstin).autocorrectionDisabled().textInputAutocapitalization(.characters).nativeField() }
                                labeled("PAN") { TextField("", text: $pan).autocorrectionDisabled().textInputAutocapitalization(.characters).nativeField() }
                                labeled("GST registration type") {
                                    Picker("Type", selection: $registrationType) {
                                        ForEach(Self.registrationTypes, id: \.self) { type in Text(gstRegistrationTypeLabel(type)).tag(type) }
                                    }
                                    .pickerStyle(.segmented)
                                    .tint(Brand.gold)
                                }
                                labeled("UPI VPA") { TextField("name@bank", text: $upiVpa).autocorrectionDisabled().textInputAutocapitalization(.never).nativeField() }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Edit company")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(isSubmitting)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func labeled<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func save() async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let updated: CompanyDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "settings/company",
                    body: CompanyUpdateRequest(
                        name: name.nilIfBlank, legal_name: legalName.nilIfBlank, gstin: gstin.nilIfBlank,
                        pan: pan.nilIfBlank, gst_registration_type: registrationType, upi_vpa: upiVpa.nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            onSaved(updated)
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct BranchFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let isEditing: Bool
    let branchId: String?
    let onSaved: (BranchDTO) -> Void

    @State private var name: String
    @State private var address: String
    @State private var stateCode: String
    @State private var fssai: String
    @State private var tradeLicense: String
    @State private var branchGSTIN: String
    @State private var isSubmitting = false
    @State private var error: String?

    init(existingBranch: BranchDTO?, onSaved: @escaping (BranchDTO) -> Void) {
        self.isEditing = existingBranch != nil
        self.branchId = existingBranch?.id
        self.onSaved = onSaved
        self._name = State(initialValue: existingBranch?.name ?? "")
        self._address = State(initialValue: existingBranch?.address ?? "")
        self._stateCode = State(initialValue: existingBranch?.state_code ?? "32")
        self._fssai = State(initialValue: existingBranch?.fssai_license_no ?? "")
        self._tradeLicense = State(initialValue: existingBranch?.trade_license_no ?? "")
        self._branchGSTIN = State(initialValue: existingBranch?.branch_gstin ?? "")
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                labeled("Name") { TextField("", text: $name).nativeField() }
                                labeled("Address") { TextField("", text: $address).nativeField() }
                                labeled("GST state code") { TextField("32", text: $stateCode).keyboardType(.numberPad).nativeField() }
                                labeled("Branch GSTIN") { TextField("", text: $branchGSTIN).autocorrectionDisabled().textInputAutocapitalization(.characters).nativeField() }
                                labeled("FSSAI license (14 digits)") { TextField("", text: $fssai).keyboardType(.numberPad).nativeField() }
                                labeled("Trade license") { TextField("", text: $tradeLicense).nativeField() }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(isEditing ? "Edit branch" : "New branch")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(name.isEmpty || isSubmitting)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func labeled<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func save() async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        let request = BranchWriteRequest(
            name: name.nilIfBlank, code: nil, address: address.nilIfBlank, timezone: nil,
            opens_at: nil, closes_at: nil, state_code: stateCode.nilIfBlank,
            fssai_license_no: fssai.nilIfBlank, trade_license_no: tradeLicense.nilIfBlank, branch_gstin: branchGSTIN.nilIfBlank
        )
        do {
            let updated: BranchDTO
            if let branchId {
                updated = try await session.authorized { token in
                    try await APIClient.shared.patch("settings/branches/\(branchId)", body: request, token: token)
                }
            } else {
                updated = try await session.authorized { token in
                    try await APIClient.shared.post("settings/branches", body: request, token: token)
                }
            }
            Haptics.success()
            onSaved(updated)
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct TerminalFormSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var session: AppSession
    let branches: [BranchDTO]
    let onSaved: (TerminalDTO) -> Void

    @State private var branchId: String
    @State private var name = ""
    @State private var deviceId = ""
    @State private var isSubmitting = false
    @State private var error: String?

    init(branches: [BranchDTO], onSaved: @escaping (TerminalDTO) -> Void) {
        self.branches = branches
        self.onSaved = onSaved
        self._branchId = State(initialValue: branches.first?.id ?? "")
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                labeled("Name") { TextField("e.g. Gaming Terminal, Cafe Terminal", text: $name).nativeField() }
                                if branches.count > 1 {
                                    labeled("Branch") {
                                        Picker("", selection: $branchId) {
                                            ForEach(branches) { branch in Text(branch.name).tag(branch.id) }
                                        }
                                        .pickerStyle(.menu)
                                    }
                                }
                                labeled("Device ID (optional)") { TextField("", text: $deviceId).autocorrectionDisabled().nativeField() }
                            }
                        }
                        Text("Only registered terminals can open a shift or bill orders — keep this list matched to the physical registers actually in use.")
                            .font(.caption2)
                            .foregroundColor(Brand.muted)
                    }
                    .padding(16)
                }
            }
            .navigationTitle("New terminal")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { Task { await save() } }.disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || branchId.isEmpty || isSubmitting)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    @ViewBuilder
    private func labeled<Content: View>(_ label: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(Brand.muted)
            content()
        }
    }

    private func save() async {
        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        do {
            let created: TerminalDTO = try await session.authorized { token in
                try await APIClient.shared.post(
                    "settings/terminals",
                    body: TerminalCreateRequest(
                        branch_id: branchId,
                        name: name.trimmingCharacters(in: .whitespaces),
                        device_id: deviceId.trimmingCharacters(in: .whitespaces).nilIfBlank
                    ),
                    token: token
                )
            }
            Haptics.success()
            onSaved(created)
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

// Self-service password reset — like staff creation, this is deliberately
// OTP-gated server-side (POST /staff/me/password always rejects), routing
// through the same auth/password-reset/* endpoints as the sign-in screen's
// "Forgot password", just pre-filled with the current user's own email.
private struct AccountPasswordResetSheet: View {
    @Environment(\.dismiss) private var dismiss
    let email: String
    let onDone: () -> Void

    @State private var password = ""
    @State private var confirmPassword = ""
    @State private var code = ""
    @State private var challenge: PendingChallenge?
    @State private var isBusy = false
    @State private var error: String?
    @State private var successMessage: String?

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        if let error { ErrorBanner(message: error) }
                        if let successMessage { SuccessBanner(message: successMessage) }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text(email).font(.caption).foregroundColor(Brand.muted)
                                if challenge == nil {
                                    Text("An approval code will be emailed to this address.")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                } else {
                                    Text("Approval code sent to \(challenge?.destination ?? email)")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    TextField("6-digit code", text: $code)
                                        .keyboardType(.numberPad)
                                        .multilineTextAlignment(.center)
                                        .font(.title2.weight(.bold).monospacedDigit())
                                        .onChange(of: code) { newValue in code = String(newValue.filter(\.isNumber).prefix(6)) }
                                        .nativeField()
                                    TextField("New password", text: $password)
                                        .nativeField()
                                    TextField("Confirm password", text: $confirmPassword)
                                        .nativeField()
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Reset password")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Task { challenge == nil ? await requestCode() : await confirmCode() }
                } label: {
                    HStack {
                        if isBusy { ProgressView().tint(.black) }
                        Text(challenge == nil ? "Send approval code" : "Confirm and set password")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(canSubmit ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(!canSubmit || isBusy)
            }
        }
        .navigationViewStyle(.stack)
    }

    private var canSubmit: Bool {
        challenge == nil ? true : (code.count == 6 && password.count >= 10 && password == confirmPassword)
    }

    private func requestCode() async {
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let result: OtpChallengeDTO = try await APIClient.shared.post(
                "auth/password-reset/request",
                body: PasswordResetRequestBody(email: email)
            )
            challenge = PendingChallenge(id: result.challenge_id, destination: result.destination, email: email)
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func confirmCode() async {
        guard let challenge else { return }
        isBusy = true
        defer { isBusy = false }
        error = nil
        do {
            let result: AccountActionDTO = try await APIClient.shared.post(
                "auth/password-reset/confirm",
                body: PasswordResetConfirmBody(challenge_id: challenge.id, code: code, new_password: password)
            )
            successMessage = result.message
            Haptics.success()
            try? await Task.sleep(nanoseconds: 900_000_000)
            onDone()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }
}

private struct PriceDraft {
    var priceText: String
    var taxText: String
}

private struct PricingNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var cache: AppCache
    @State private var password = ""
    @State private var pricingToken: String?
    @State private var items: [MenuItemDTO] = []
    @State private var stations: [GamingStationDTO] = []
    @State private var menuDrafts: [String: PriceDraft] = [:]
    @State private var stationDrafts: [String: String] = [:]
    @State private var search = ""
    @State private var isLoading = false
    @State private var isUnlocking = false
    @State private var savingKey: String?
    @State private var error: String?
    @State private var notice: String?

    var body: some View {
        RefreshableScrollView(refresh: reloadIfUnlocked) {
            VStack(spacing: 16) {
                HeaderBlock(
                    title: "Pricing",
                    subtitle: pricingToken == nil ? "Locked owner control" : "\(filteredItems.count) items, \(filteredStations.count) stations",
                    icon: "indianrupeesign.circle"
                )

                if let error {
                    ErrorBanner(message: error)
                }

                if let notice {
                    SuccessBanner(message: notice)
                }

                if pricingToken == nil {
                    lockedPricingCard
                } else {
                    BrandedCard {
                        SearchBar(text: $search, placeholder: "Search menu, SKU, PS5, shisha, streaming")
                    }

                    menuPricingCard
                    stationPricingCard
                }
            }
            .padding(16)
        }
        .navigationTitle("Pricing")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Haptics.selection()
                    Task { await reloadIfUnlocked() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(pricingToken == nil)
            }
        }
        .background(Brand.background)
    }

    private var lockedPricingCard: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                Label("Price changes need your password", systemImage: "lock.shield")
                    .font(.headline)
                    .foregroundColor(.white)
                Text("Menu prices, GST rates, and station hourly rates are protected because they affect every bill and audit entry.")
                    .font(.subheadline)
                    .foregroundColor(Brand.muted)
                    .fixedSize(horizontal: false, vertical: true)
                SecureField("Owner password", text: $password)
                    .textContentType(.password)
                    .nativeField()
                Button {
                    Haptics.selection()
                    Task { await unlock() }
                } label: {
                    Label(isUnlocking ? "Unlocking" : "Unlock pricing", systemImage: "key.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                }
                .buttonStyle(PressableButtonStyle())
                .background(Brand.gold)
                .foregroundColor(.black)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .disabled(isUnlocking || password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private var menuPricingCard: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("Menu Prices")
                    .font(.headline)
                    .foregroundColor(.white)

                if isLoading && items.isEmpty {
                    LoadingBlock(title: "Loading menu prices")
                } else if filteredItems.isEmpty {
                    InlineEmptyRow(icon: "menucard", title: "No menu items", subtitle: "Try a different search.")
                } else {
                    ForEach(filteredItems) { item in
                        PricingMenuRow(
                            item: item,
                            draft: Binding(
                                get: { menuDrafts[item.id] ?? PriceDraft(priceText: currencyInput(item.base_price_minor), taxText: taxInput(item.tax_rate)) },
                                set: { menuDrafts[item.id] = $0 }
                            ),
                            isSaving: savingKey == "menu-\(item.id)"
                        ) {
                            Task { await saveMenuItem(item) }
                        }
                        if item.id != filteredItems.last?.id {
                            Divider().background(Brand.hairline)
                        }
                    }
                }
            }
        }
    }

    private var stationPricingCard: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("Session Rates")
                    .font(.headline)
                    .foregroundColor(.white)

                if isLoading && stations.isEmpty {
                    LoadingBlock(title: "Loading station rates")
                } else if filteredStations.isEmpty {
                    InlineEmptyRow(icon: "gamecontroller", title: "No stations", subtitle: "Add PS5, VR, simulator, shisha, or streaming stations in Stations & Services.")
                } else {
                    ForEach(filteredStations) { station in
                        PricingStationRow(
                            station: station,
                            rateText: Binding(
                                get: { stationDrafts[station.id] ?? currencyInput(station.rate_per_hour_minor) },
                                set: { stationDrafts[station.id] = $0 }
                            ),
                            isSaving: savingKey == "station-\(station.id)"
                        ) {
                            Task { await saveStation(station) }
                        }
                        if station.id != filteredStations.last?.id {
                            Divider().background(Brand.hairline)
                        }
                    }
                }
            }
        }
    }

    private var filteredItems: [MenuItemDTO] {
        let term = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let list = items.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
        guard !term.isEmpty else { return list }
        return list.filter {
            $0.name.localizedCaseInsensitiveContains(term)
            || $0.sku.localizedCaseInsensitiveContains(term)
            || $0.type.localizedCaseInsensitiveContains(term)
        }
    }

    private var filteredStations: [GamingStationDTO] {
        let term = search.trimmingCharacters(in: .whitespacesAndNewlines)
        let list = stations.sorted {
            if $0.kind.title == $1.kind.title {
                return $0.code.localizedStandardCompare($1.code) == .orderedAscending
            }
            return $0.kind.title.localizedStandardCompare($1.kind.title) == .orderedAscending
        }
        guard !term.isEmpty else { return list }
        return list.filter {
            $0.name.localizedCaseInsensitiveContains(term)
            || $0.code.localizedCaseInsensitiveContains(term)
            || $0.kind.title.localizedCaseInsensitiveContains(term)
        }
    }

    private func unlock() async {
        isUnlocking = true
        defer { isUnlocking = false }
        error = nil
        notice = nil
        do {
            let response: PricingUnlockResponse = try await session.authorized { token in
                try await APIClient.shared.post(
                    "admin/pricing/unlock",
                    body: AuditUnlockRequest(password: password),
                    token: token
                )
            }
            pricingToken = response.pricing_token
            password = ""
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func reloadIfUnlocked() async {
        guard pricingToken != nil else { return }
        await load()
    }

    private func load() async {
        if !cache.menuItems.isEmpty {
            items = cache.menuItems
        }
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            async let loadedItems: [MenuItemDTO] = session.authorized { token in
                try await APIClient.shared.get("menu/items", token: token)
            }
            async let loadedStations: [GamingStationDTO] = session.authorized { token in
                try await APIClient.shared.get("gaming/stations", token: token)
            }
            let (freshItems, freshStations) = try await (loadedItems, loadedStations)
            withAnimation(.easeOut(duration: 0.18)) {
                items = freshItems
                stations = freshStations
                menuDrafts = Dictionary(uniqueKeysWithValues: freshItems.map {
                    ($0.id, PriceDraft(priceText: currencyInput($0.base_price_minor), taxText: taxInput($0.tax_rate)))
                })
                stationDrafts = Dictionary(uniqueKeysWithValues: freshStations.map {
                    ($0.id, currencyInput($0.rate_per_hour_minor))
                })
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func saveMenuItem(_ item: MenuItemDTO) async {
        guard let pricingToken else { return }
        guard let draft = menuDrafts[item.id] else { return }
        savingKey = "menu-\(item.id)"
        defer { savingKey = nil }
        error = nil
        notice = nil
        do {
            let priceMinor = try minorFromCurrencyInput(draft.priceText)
            let taxRate = try taxRateFromInput(draft.taxText)
            let updated: MenuItemDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "menu/items/\(item.id)",
                    body: MenuItemPricingUpdateRequest(base_price_minor: priceMinor, tax_rate: taxRate),
                    token: token,
                    headers: ["X-Pricing-Token": pricingToken]
                )
            }
            replaceMenuItem(updated)
            notice = "\(updated.name) updated."
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func saveStation(_ station: GamingStationDTO) async {
        guard let pricingToken else { return }
        savingKey = "station-\(station.id)"
        defer { savingKey = nil }
        error = nil
        notice = nil
        do {
            let rateMinor = try minorFromCurrencyInput(stationDrafts[station.id] ?? currencyInput(station.rate_per_hour_minor))
            let updated: GamingStationDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "gaming/stations/\(station.id)",
                    body: GamingStationUpdateRequest(name: nil, rate_per_hour_minor: rateMinor, is_active: nil, notes: nil),
                    token: token,
                    headers: ["X-Pricing-Token": pricingToken]
                )
            }
            replaceStation(updated)
            notice = "\(updated.name) rate updated."
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func replaceMenuItem(_ updated: MenuItemDTO) {
        if let index = items.firstIndex(where: { $0.id == updated.id }) {
            items[index] = updated
        }
        menuDrafts[updated.id] = PriceDraft(priceText: currencyInput(updated.base_price_minor), taxText: taxInput(updated.tax_rate))
    }

    private func replaceStation(_ updated: GamingStationDTO) {
        if let index = stations.firstIndex(where: { $0.id == updated.id }) {
            stations[index] = updated
        }
        stationDrafts[updated.id] = currencyInput(updated.rate_per_hour_minor)
    }
}

private struct StationManagementNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var password = ""
    @State private var pricingToken: String?
    @State private var stations: [GamingStationDTO] = []
    @State private var branches: [BranchDTO] = []
    @State private var search = ""
    @State private var editorDraft: StationEditorDraft?
    @State private var isLoading = false
    @State private var isUnlocking = false
    @State private var isSaving = false
    @State private var savingStationID: String?
    @State private var error: String?
    @State private var notice: String?

    var body: some View {
        RefreshableScrollView(refresh: reloadIfUnlocked) {
            VStack(spacing: 16) {
                HeaderBlock(
                    title: "Stations & Services",
                    subtitle: pricingToken == nil ? "Locked setup" : "\(activeCount) active of \(stations.count)",
                    icon: "slider.horizontal.3"
                )

                if let error {
                    ErrorBanner(message: error)
                }

                if let notice {
                    SuccessBanner(message: notice)
                }

                if pricingToken == nil {
                    lockedStationsCard
                } else {
                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            SearchBar(text: $search, placeholder: "Search PS5, VR, simulator, shisha, streaming")
                            Text("Quick add")
                                .font(.headline)
                                .foregroundColor(.white)
                            Text("Choose a service type. Code, name, and hourly rate are prefilled and can be edited before saving.")
                                .font(.caption)
                                .foregroundColor(Brand.muted)
                                .fixedSize(horizontal: false, vertical: true)

                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 10) {
                                    ForEach(GamingStationKind.allCases) { kind in
                                        ServicePresetButton(kind: kind, configuredCount: configuredCount(for: kind)) {
                                            Haptics.selection()
                                            editorDraft = newDraft(for: kind)
                                        }
                                    }
                                }
                                .padding(.vertical, 2)
                            }

                            Button {
                                Haptics.selection()
                                editorDraft = .blank(branchID: branches.first?.id)
                            } label: {
                                Label("Add custom service", systemImage: "plus.circle.fill")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity)
                                    .padding(.vertical, 13)
                            }
                            .buttonStyle(PressableButtonStyle())
                            .background(Brand.gold)
                            .foregroundColor(.black)
                            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                    }

                    stationListCard
                }
            }
            .padding(16)
        }
        .navigationTitle("Stations")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    Haptics.selection()
                    Task { await reloadIfUnlocked() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
                .disabled(pricingToken == nil)
            }
        }
        .background(Brand.background)
        .sheet(item: $editorDraft) { draft in
            StationEditorSheet(
                draft: draft,
                branches: branches,
                isSaving: isSaving
            ) { updatedDraft in
                Task { await save(updatedDraft) }
            }
        }
    }

    private var lockedStationsCard: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                Label("Setup is protected", systemImage: "lock.shield")
                    .font(.headline)
                    .foregroundColor(.white)
                Text("Station names, active status, and hourly rates affect billing. Unlock once, then manage PS5, VR, simulator, shisha, streaming, and other services here.")
                    .font(.subheadline)
                    .foregroundColor(Brand.muted)
                    .fixedSize(horizontal: false, vertical: true)
                SecureField("Owner password", text: $password)
                    .textContentType(.password)
                    .nativeField()
                Button {
                    Haptics.selection()
                    Task { await unlock() }
                } label: {
                    Label(isUnlocking ? "Unlocking" : "Unlock setup", systemImage: "key.fill")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 13)
                }
                .buttonStyle(PressableButtonStyle())
                .background(Brand.gold)
                .foregroundColor(.black)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .disabled(isUnlocking || password.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
    }

    private var stationListCard: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                Text("Cafe Services")
                    .font(.headline)
                    .foregroundColor(.white)

                if isLoading && stations.isEmpty {
                    LoadingBlock(title: "Loading stations")
                } else if filteredStations.isEmpty {
                    InlineEmptyRow(icon: "gamecontroller", title: "No matching stations", subtitle: "Add PS5, VR, simulator, shisha, or streaming services.")
                } else {
                    ForEach(filteredStations) { station in
                        StationSetupRow(
                            station: station,
                            isSaving: savingStationID == station.id
                        ) {
                            Haptics.selection()
                            editorDraft = .editing(station)
                        } toggleActive: {
                            Task { await setActive(!station.is_active, station: station) }
                        }
                        if station.id != filteredStations.last?.id {
                            Divider().background(Brand.hairline)
                        }
                    }
                }
            }
        }
    }

    private var filteredStations: [GamingStationDTO] {
        let list = stations.sorted {
            if $0.kind.title == $1.kind.title {
                return $0.code.localizedStandardCompare($1.code) == .orderedAscending
            }
            return $0.kind.title.localizedStandardCompare($1.kind.title) == .orderedAscending
        }
        let term = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !term.isEmpty else { return list }
        return list.filter {
            $0.name.localizedCaseInsensitiveContains(term)
            || $0.code.localizedCaseInsensitiveContains(term)
            || $0.kind.title.localizedCaseInsensitiveContains(term)
        }
    }

    private var activeCount: Int {
        stations.filter(\.is_active).count
    }

    private func configuredCount(for kind: GamingStationKind) -> Int {
        stations.filter { $0.kind == kind }.count
    }

    private func newDraft(for kind: GamingStationKind) -> StationEditorDraft {
        let sequence = nextSequence(for: kind)
        let code = "\(kind.codePrefix)-\(String(format: "%02d", sequence))"
        return .blank(branchID: branches.first?.id, kind: kind, code: code, sequence: sequence)
    }

    private func nextSequence(for kind: GamingStationKind) -> Int {
        let prefix = "\(kind.codePrefix)-"
        let existingNumbers = stations.compactMap { station -> Int? in
            let code = station.code.uppercased()
            guard code.hasPrefix(prefix) else { return nil }
            return Int(code.dropFirst(prefix.count))
        }
        return (existingNumbers.max() ?? 0) + 1
    }

    private func unlock() async {
        isUnlocking = true
        defer { isUnlocking = false }
        error = nil
        notice = nil
        do {
            let response: PricingUnlockResponse = try await session.authorized { token in
                try await APIClient.shared.post(
                    "admin/pricing/unlock",
                    body: AuditUnlockRequest(password: password),
                    token: token
                )
            }
            pricingToken = response.pricing_token
            password = ""
            Haptics.success()
            await load()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func reloadIfUnlocked() async {
        guard pricingToken != nil else { return }
        await load()
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            async let loadedStations: [GamingStationDTO] = session.authorized { token in
                try await APIClient.shared.get("gaming/stations", token: token)
            }
            async let loadedBranches: [BranchDTO] = session.authorized { token in
                try await APIClient.shared.get("settings/branches", token: token)
            }
            let (freshStations, freshBranches) = try await (loadedStations, loadedBranches)
            withAnimation(.easeOut(duration: 0.18)) {
                stations = freshStations
                branches = freshBranches
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func save(_ draft: StationEditorDraft) async {
        guard let pricingToken else { return }
        isSaving = true
        defer { isSaving = false }
        error = nil
        notice = nil
        do {
            let trimmedName = draft.name.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmedName.isEmpty else {
                throw InputParseError.invalidStationName
            }
            let trimmedCode = draft.code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            guard !draft.isNew || !trimmedCode.isEmpty else {
                throw InputParseError.invalidStationCode
            }
            let rateMinor = try minorFromCurrencyInput(draft.rateText)
            if let stationID = draft.stationID {
                let updated: GamingStationDTO = try await session.authorized { token in
                    try await APIClient.shared.patch(
                        "gaming/stations/\(stationID)",
                        body: GamingStationUpdateRequest(
                            name: trimmedName,
                            rate_per_hour_minor: rateMinor,
                            is_active: draft.isActive,
                            notes: draft.notes.nilIfBlank
                        ),
                        token: token,
                        headers: ["X-Pricing-Token": pricingToken]
                    )
                }
                replaceStation(updated)
                notice = "\(updated.name) updated."
            } else {
                let created: GamingStationDTO = try await session.authorized { token in
                    try await APIClient.shared.post(
                        "gaming/stations",
                        body: GamingStationCreateRequest(
                            code: trimmedCode,
                            name: trimmedName,
                            type: draft.kind.rawValue,
                            rate_per_hour_minor: rateMinor,
                            branch_id: draft.branchID,
                            notes: draft.notes.nilIfBlank
                        ),
                        token: token,
                        headers: ["X-Pricing-Token": pricingToken]
                    )
                }
                stations.append(created)
                notice = "\(created.name) created."
            }
            editorDraft = nil
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func setActive(_ isActive: Bool, station: GamingStationDTO) async {
        guard let pricingToken else { return }
        savingStationID = station.id
        defer { savingStationID = nil }
        error = nil
        notice = nil
        do {
            let updated: GamingStationDTO = try await session.authorized { token in
                try await APIClient.shared.patch(
                    "gaming/stations/\(station.id)",
                    body: GamingStationUpdateRequest(name: nil, rate_per_hour_minor: nil, is_active: isActive, notes: nil),
                    token: token,
                    headers: ["X-Pricing-Token": pricingToken]
                )
            }
            replaceStation(updated)
            notice = updated.is_active ? "\(updated.name) enabled." : "\(updated.name) hidden from POS."
            Haptics.success()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func replaceStation(_ updated: GamingStationDTO) {
        if let index = stations.firstIndex(where: { $0.id == updated.id }) {
            stations[index] = updated
        }
    }
}

private struct PricingMenuRow: View {
    let item: MenuItemDTO
    @Binding var draft: PriceDraft
    let isSaving: Bool
    let onSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: item.type == "service" ? "timer.circle.fill" : "fork.knife.circle.fill")
                    .font(.title3)
                    .foregroundColor(Brand.gold)
                    .frame(width: 34, height: 34)
                    .background(Brand.gold.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.white)
                        .fixedSize(horizontal: false, vertical: true)
                    Text("\(item.sku) - \(item.type.capitalized)")
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                }

                Spacer(minLength: 8)

                Button(action: onSave) {
                    HStack(spacing: 6) {
                        if isSaving {
                            ProgressView()
                                .tint(.black)
                                .scaleEffect(0.78)
                        }
                        Text("Save")
                    }
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Brand.gold)
                    .foregroundColor(.black)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(isSaving)
            }

            HStack(spacing: 10) {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Price")
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(Brand.muted)
                    TextField("0.00", text: $draft.priceText)
                        .keyboardType(.decimalPad)
                        .nativeField()
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text("GST %")
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(Brand.muted)
                    TextField("18", text: $draft.taxText)
                        .keyboardType(.decimalPad)
                        .nativeField()
                }
            }
        }
        .padding(.vertical, 12)
    }
}

private struct PricingStationRow: View {
    let station: GamingStationDTO
    @Binding var rateText: String
    let isSaving: Bool
    let onSave: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 12) {
                Image(systemName: station.kind.icon)
                    .font(.title3)
                    .foregroundColor(Brand.gold)
                    .frame(width: 34, height: 34)
                    .background(Brand.gold.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

                VStack(alignment: .leading, spacing: 4) {
                    Text(station.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text("\(station.kind.title) - \(station.code)")
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                }

                Spacer(minLength: 8)

                Button(action: onSave) {
                    HStack(spacing: 6) {
                        if isSaving {
                            ProgressView()
                                .tint(.black)
                                .scaleEffect(0.78)
                        }
                        Text("Save")
                    }
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(Brand.gold)
                    .foregroundColor(.black)
                    .clipShape(Capsule())
                }
                .buttonStyle(.plain)
                .disabled(isSaving)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("Rate per hour")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.muted)
                TextField("0.00", text: $rateText)
                    .keyboardType(.decimalPad)
                    .nativeField()
            }
        }
        .padding(.vertical, 12)
    }
}

private struct StationSetupRow: View {
    let station: GamingStationDTO
    let isSaving: Bool
    let onEdit: () -> Void
    let toggleActive: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            Image(systemName: station.kind.icon)
                .font(.title3)
                .foregroundColor(station.is_active ? Brand.gold : Brand.muted)
                .frame(width: 38, height: 38)
                .background((station.is_active ? Brand.gold : Brand.muted).opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                Text(station.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text("\(station.kind.title) - \(station.code) - \(inr(station.rate_per_hour_minor))/hr")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }

            Spacer(minLength: 8)

            HStack(spacing: 8) {
                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .font(.headline)
                        .frame(width: 38, height: 38)
                        .background(Brand.elevated)
                        .foregroundColor(Brand.gold)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)

                Button(action: toggleActive) {
                    Group {
                        if isSaving {
                            ProgressView()
                                .tint(station.is_active ? Brand.danger : Brand.success)
                        } else {
                            Image(systemName: station.is_active ? "eye.fill" : "eye.slash.fill")
                        }
                    }
                    .font(.headline)
                    .frame(width: 38, height: 38)
                    .background((station.is_active ? Brand.success : Brand.danger).opacity(0.12))
                    .foregroundColor(station.is_active ? Brand.success : Brand.danger)
                    .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(isSaving)
            }
        }
        .padding(.vertical, 12)
    }
}

private struct StationEditorSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: StationEditorDraft

    let branches: [BranchDTO]
    let isSaving: Bool
    let onSave: (StationEditorDraft) -> Void

    init(
        draft: StationEditorDraft,
        branches: [BranchDTO],
        isSaving: Bool,
        onSave: @escaping (StationEditorDraft) -> Void
    ) {
        self._draft = State(initialValue: draft)
        self.branches = branches
        self.isSaving = isSaving
        self.onSave = onSave
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text(draft.isNew ? "New service" : "Edit service")
                                    .font(.headline)
                                    .foregroundColor(.white)

                                TextField("Code, e.g. PS5-01", text: $draft.code)
                                    .textInputAutocapitalization(.characters)
                                    .autocorrectionDisabled()
                                    .nativeField()
                                    .disabled(!draft.isNew)
                                    .opacity(draft.isNew ? 1 : 0.65)

                                TextField("Display name", text: $draft.name)
                                    .textInputAutocapitalization(.words)
                                    .nativeField()

                                Picker("Type", selection: $draft.kind) {
                                    ForEach(GamingStationKind.allCases) { kind in
                                        Text(kind.title).tag(kind)
                                    }
                                }
                                .pickerStyle(.menu)
                                .tint(Brand.gold)
                                .foregroundColor(.white)
                                .padding(12)
                                .background(Brand.surface)
                                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                                TextField("Rate per hour", text: $draft.rateText)
                                    .keyboardType(.decimalPad)
                                    .nativeField()

                                Toggle(isOn: $draft.isActive) {
                                    Label("Visible in POS", systemImage: "eye")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundColor(.white)
                                }
                                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))

                                if draft.isNew && !branches.isEmpty {
                                    Picker("Branch", selection: $draft.branchID) {
                                        Text("Default branch").tag(nil as String?)
                                        ForEach(branches) { branch in
                                            Text(branch.name).tag(branch.id as String?)
                                        }
                                    }
                                    .pickerStyle(.menu)
                                    .tint(Brand.gold)
                                    .foregroundColor(.white)
                                    .padding(12)
                                    .background(Brand.surface)
                                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                                }

                                TextField("Notes optional", text: $draft.notes)
                                    .nativeField()
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle(draft.isNew ? "Add Service" : "Edit Service")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        onSave(draft)
                    } label: {
                        if isSaving {
                            ProgressView()
                                .tint(Brand.gold)
                        } else {
                            Text("Save")
                        }
                    }
                    .disabled(!canSave || isSaving)
                }
            }
        }
        .navigationViewStyle(.stack)
    }

    private var canSave: Bool {
        !draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !draft.rateText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && (!draft.isNew || !draft.code.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}

private struct DeviceIntegrationsNativeView: View {
    @EnvironmentObject private var cache: AppCache
    @State private var showScanner = false
    @State private var scannedText = ""
    @State private var scanError: String?
    @State private var showSheetsConfig = false

    var body: some View {
        RefreshableScrollView(refresh: refresh) {
            VStack(spacing: 16) {
                HeaderBlock(title: "Integrations", subtitle: "Printer, payment, OCR, and offline readiness", icon: "externaldrive.connected.to.line.below")

                BrandedCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Offline Database")
                            .font(.headline)
                            .foregroundColor(.white)
                        IntegrationStatusRow(
                            title: "Local snapshot",
                            value: cache.lastSyncedAt.map(DateFormatters.shortDateTime.string(from:)) ?? "Not synced",
                            detail: "Menu: \(cache.menuItems.count) | Stock: \(cache.ingredients.count) | Daily report: \(cache.dailyReport == nil ? "No" : "Yes")",
                            isReady: cache.hasMenuData || cache.hasInventoryData || cache.dailyReport != nil,
                            icon: "internaldrive"
                        )
                        Text("This keeps the latest menu, stock, and daily P&L available after a restart. It is not yet a full offline checkout sync engine.")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }

                BrandedCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Receipt Printer")
                            .font(.headline)
                            .foregroundColor(.white)
                        IntegrationStatusRow(
                            title: "AirPrint",
                            value: "Ready",
                            detail: "Print receipts from iPhone/iPad to AirPrint printers.",
                            isReady: UIPrintInteractionController.isPrintingAvailable,
                            icon: "printer"
                        )
                        Button {
                            Haptics.selection()
                            ReceiptPrinter.printTestPage()
                        } label: {
                            Label("Print test page", systemImage: "printer.filled.and.paper")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.black)
                        .background(Brand.gold)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }

                BrandedCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Payment Terminal")
                            .font(.headline)
                            .foregroundColor(.white)
                        IntegrationStatusRow(
                            title: TerminalIntegrationStatus.current.provider,
                            value: TerminalIntegrationStatus.current.isConfigured ? "Configured" : "Provider needed",
                            detail: TerminalIntegrationStatus.current.detail,
                            isReady: TerminalIntegrationStatus.current.isConfigured,
                            icon: "creditcard.and.123"
                        )
                    }
                }

                BrandedCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("Google Sheets Sync")
                            .font(.headline)
                            .foregroundColor(.white)
                        IntegrationStatusRow(
                            title: "Sheets webhook",
                            value: GSheetsStore.readURL() != nil ? "Connected" : "Not connected",
                            detail: GSheetsStore.readURL() != nil
                                ? "Orders, tickets, events, and reports push to your Operations tab."
                                : "Connect a Google Apps Script webhook to sync every bill to a sheet.",
                            isReady: GSheetsStore.readURL() != nil,
                            icon: "tablecells"
                        )
                        Button {
                            Haptics.selection()
                            showSheetsConfig = true
                        } label: {
                            Label(GSheetsStore.readURL() != nil ? "Manage Sheets connection" : "Connect Google Sheets", systemImage: "link")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.black)
                        .background(Brand.gold)
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    }
                }

                BrandedCard {
                    VStack(alignment: .leading, spacing: 14) {
                        Text("OCR Scanner")
                            .font(.headline)
                            .foregroundColor(.white)
                        IntegrationStatusRow(
                            title: "Native document OCR",
                            value: VNDocumentCameraViewController.isSupported ? "Ready" : "Unavailable",
                            detail: VNDocumentCameraViewController.isSupported ? "Scan invoices, bills, and stock sheets with the camera." : "Document camera is unavailable on this device or simulator.",
                            isReady: VNDocumentCameraViewController.isSupported,
                            icon: "doc.viewfinder"
                        )
                        Button {
                            Haptics.selection()
                            scanError = nil
                            showScanner = true
                        } label: {
                            Label("Scan document", systemImage: "doc.text.viewfinder")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                        }
                        .buttonStyle(.plain)
                        .foregroundColor(.black)
                        .background(VNDocumentCameraViewController.isSupported ? Brand.gold : Brand.muted.opacity(0.45))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                        .disabled(!VNDocumentCameraViewController.isSupported)

                        if let scanError {
                            ErrorBanner(message: scanError)
                        }

                        if !scannedText.isEmpty {
                            Text(scannedText)
                                .font(.footnote.monospaced())
                                .foregroundColor(.white)
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .background(Brand.elevated)
                                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Integrations")
        .background(Brand.background)
        .sheet(isPresented: $showScanner) {
            DocumentOCRScanner(
                onComplete: { text, _ in
                    scannedText = text.isEmpty ? "No readable text found." : text
                    showScanner = false
                },
                onFailure: { message in
                    scanError = message
                    showScanner = false
                },
                onCancel: {
                    showScanner = false
                }
            )
            .ignoresSafeArea()
        }
        .sheet(isPresented: $showSheetsConfig) {
            GSheetsWebhookSheet()
        }
    }

    private func refresh() async {}
}

private struct IntegrationStatusRow: View {
    let title: String
    let value: String
    let detail: String
    let isReady: Bool
    let icon: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundColor(isReady ? Brand.success : Brand.danger)
                .frame(width: 34, height: 34)
                .background((isReady ? Brand.success : Brand.danger).opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 11, style: .continuous))

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(title)
                        .font(.subheadline.weight(.semibold))
                        .foregroundColor(.white)
                    Spacer()
                    Text(value)
                        .font(.caption.weight(.bold))
                        .foregroundColor(isReady ? Brand.success : Brand.danger)
                        .lineLimit(1)
                }
                Text(detail)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct DocumentOCRScanner: UIViewControllerRepresentable {
    // The second argument is the first scanned page as JPEG data — nil only
    // if the scan produced zero pages. Callers that just want local on-device
    // text (Integrations' scan test) ignore it; the real OCR upload flow
    // needs it to POST the actual receipt image to the backend.
    let onComplete: (String, Data?) -> Void
    let onFailure: (String) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onComplete: onComplete, onFailure: onFailure, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        private let onComplete: (String, Data?) -> Void
        private let onFailure: (String) -> Void
        private let onCancel: () -> Void

        init(onComplete: @escaping (String, Data?) -> Void, onFailure: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
            self.onComplete = onComplete
            self.onFailure = onFailure
            self.onCancel = onCancel
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            onFailure(error.localizedDescription)
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            DispatchQueue.global(qos: .userInitiated).async {
                let text = self.recognize(scan: scan)
                let imageData = scan.pageCount > 0 ? scan.imageOfPage(at: 0).jpegData(compressionQuality: 0.85) : nil
                DispatchQueue.main.async {
                    self.onComplete(text, imageData)
                }
            }
        }

        private func recognize(scan: VNDocumentCameraScan) -> String {
            var recognizedPages: [String] = []

            for index in 0..<scan.pageCount {
                let image = scan.imageOfPage(at: index)
                guard let cgImage = image.cgImage else { continue }

                var pageLines: [String] = []
                let request = VNRecognizeTextRequest { request, _ in
                    guard let observations = request.results as? [VNRecognizedTextObservation] else { return }
                    pageLines = observations.compactMap { observation in
                        observation.topCandidates(1).first?.string
                    }
                }
                request.recognitionLevel = .accurate
                request.usesLanguageCorrection = true

                let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
                try? handler.perform([request])

                if !pageLines.isEmpty {
                    recognizedPages.append(pageLines.joined(separator: "\n"))
                }
            }

            return recognizedPages.joined(separator: "\n\n")
        }
    }
}

private struct AuditNativeView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @State private var password = ""
    @State private var auditToken: String?
    @State private var entries: [AuditEntryDTO] = []
    @State private var search = ""
    @State private var selectedArea = "All"
    @State private var isLoading = false
    @State private var error: String?

    private let areas = ["All", "Login", "POS", "Inventory", "Staff", "Finance", "Access"]

    var body: some View {
        AppNavigation {
            VStack(spacing: 0) {
                if auditToken == nil {
                    unlockView
                } else {
                    auditList
                }
            }
            .navigationTitle("Audit")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if auditToken != nil {
                        Button {
                            Haptics.selection()
                            Task { await loadEntries() }
                        } label: {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                }
            }
            .background(Brand.background)
        }
    }

    private var unlockView: some View {
        ScrollView {
            VStack(spacing: 18) {
                Spacer(minLength: 32)
                LogoBadge(size: 74)
                Text("Protected Audit")
                    .font(.title2.weight(.bold))
                    .foregroundColor(.white)
                Text("Enter the audit password to view owner-level activity history.")
                    .multilineTextAlignment(.center)
                    .foregroundColor(Brand.muted)

                if !network.isOnline {
                    NetworkBanner(label: network.connectionLabel)
                }

                SecureField("Audit password", text: $password)
                    .nativeField()

                if let error {
                    Text(error)
                        .font(.footnote.weight(.semibold))
                        .foregroundColor(Brand.danger)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }

                Button {
                    Haptics.selection()
                    Task { await unlock() }
                } label: {
                    HStack {
                        if isLoading { ProgressView().tint(.black) }
                        Text("Unlock")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(Brand.gold)
                .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                .disabled(password.isEmpty || isLoading)
                .opacity(password.isEmpty ? 0.6 : 1)
            }
            .padding(24)
        }
    }

    private var auditList: some View {
        VStack(spacing: 0) {
            if !network.isOnline {
                NetworkBanner(label: network.connectionLabel)
                    .padding(.horizontal, 16)
                    .padding(.top, 10)
            }

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(areas, id: \.self) { area in
                        FilterChip(title: area, isSelected: selectedArea == area) {
                            Haptics.selection()
                            selectedArea = area
                            Task { await loadEntries() }
                        }
                    }
                }
                .padding(.vertical, 2)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            if let error {
                ErrorBanner(message: error)
                    .padding(.horizontal, 16)
            }

            List {
                if isLoading && entries.isEmpty {
                    ForEach(0..<7, id: \.self) { _ in
                        AuditSkeletonRow()
                            .listRowBackground(Brand.background)
                            .listRowSeparator(.hidden)
                    }
                } else if filteredEntries.isEmpty {
                    InlineEmptyRow(icon: "shield", title: "No audit entries", subtitle: "Try another filter or search.")
                        .listRowBackground(Brand.background)
                        .listRowSeparator(.hidden)
                } else {
                    ForEach(filteredEntries) { entry in
                        AuditRow(entry: entry)
                            .listRowBackground(Brand.background)
                            .listRowSeparatorTint(Brand.hairline)
                    }
                }
            }
            .listStyle(.plain)
            .premiumListChrome()
            .searchable(text: $search, placement: .navigationBarDrawer(displayMode: .automatic), prompt: "Search audit")
            .background(Brand.background)
        }
        .task { await loadEntries() }
    }

    private var filteredEntries: [AuditEntryDTO] {
        guard !search.isEmpty else { return entries }
        return entries.filter { entry in
            (entry.actor_name ?? "System").localizedCaseInsensitiveContains(search)
                || (entry.actor_email ?? "").localizedCaseInsensitiveContains(search)
                || entry.action.localizedCaseInsensitiveContains(search)
                || entry.entity_type.localizedCaseInsensitiveContains(search)
        }
    }

    private func unlock() async {
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            let response: AuditUnlockResponse = try await session.authorized { token in
                try await APIClient.shared.post("admin/audit/unlock", body: AuditUnlockRequest(password: password), token: token)
            }
            auditToken = response.audit_token
            password = ""
            await loadEntries()
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func loadEntries() async {
        guard let auditToken else { return }
        isLoading = true
        defer { isLoading = false }
        error = nil
        do {
            var query = [URLQueryItem(name: "limit", value: "80")]
            if let area = auditAreaFilter(for: selectedArea) {
                query.append(URLQueryItem(name: "area", value: area))
            }
            entries = try await session.authorized { token in
                try await APIClient.shared.get("admin/audit", token: token, queryItems: query, headers: ["X-Audit-Token": auditToken])
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func auditAreaFilter(for area: String) -> String? {
        switch area {
        case "Login": return "login"
        case "POS": return "pos"
        case "Inventory": return "inventory"
        case "Staff": return "staff"
        case "Finance": return "finance"
        case "Access": return "system"
        default:
            return nil
        }
    }
}

private struct CartLine: Identifiable {
    let item: MenuItemDTO
    let quantity: Int
    var id: String { item.id }
}

private struct POSCartEditorCard: View {
    let rows: [CartLine]
    let totalMinor: Int
    let increment: (MenuItemDTO) -> Void
    let decrement: (MenuItemDTO) -> Void
    let clear: () -> Void
    let charge: () -> Void

    private var itemCount: Int {
        rows.reduce(0) { $0 + $1.quantity }
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Current Bill")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("\(itemCount) items ready to charge")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Text(inr(totalMinor))
                        .font(.title3.weight(.bold))
                        .foregroundColor(Brand.softGold)
                }

                VStack(spacing: 8) {
                    ForEach(Array(rows.prefix(4))) { row in
                        POSCartEditorLine(row: row) {
                            decrement(row.item)
                        } increment: {
                            increment(row.item)
                        }
                    }

                    if rows.count > 4 {
                        Text("\(rows.count - 4) more bill lines")
                            .font(.caption.weight(.semibold))
                            .foregroundColor(Brand.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }

                HStack(spacing: 10) {
                    Button(role: .destructive, action: clear) {
                        Label("Clear", systemImage: "trash")
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(Brand.danger)
                    .background(Brand.danger.opacity(0.12))
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))

                    Button(action: charge) {
                        Label("Charge", systemImage: "creditcard")
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.black)
                    .background(Brand.gold)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
            }
        }
    }
}

private struct POSCartEditorLine: View {
    let row: CartLine
    let decrement: () -> Void
    let increment: () -> Void

    var body: some View {
        HStack(spacing: 10) {
            VStack(alignment: .leading, spacing: 3) {
                Text(row.item.name)
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
                Text("\(inr(row.item.base_price_minor)) each - \(inr(row.item.base_price_minor * row.quantity))")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.muted)
            }
            Spacer(minLength: 8)
            HStack(spacing: 8) {
                CartStepperButton(systemName: "minus", disabled: row.quantity == 0, action: decrement)
                Text("\(row.quantity)")
                    .font(.headline.monospacedDigit())
                    .foregroundColor(.white)
                    .frame(width: 32)
                CartStepperButton(systemName: "plus", disabled: false, action: increment)
            }
        }
        .padding(10)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

private struct CartStepperButton: View {
    let systemName: String
    let disabled: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemName)
                .font(.subheadline.weight(.heavy))
                .frame(width: 36, height: 36)
                .background(systemName == "plus" ? Brand.gold : Brand.surface)
                .foregroundColor(systemName == "plus" ? .black : Brand.softGold)
                .clipShape(Circle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.45 : 1)
    }
}

private struct CheckoutSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: CheckoutDraft

    let cartRows: [CartLine]
    let shift: ShiftDTO?
    let terminal: TerminalDTO?
    let totalMinor: Int
    let isSubmitting: Bool
    let upiVpa: String?
    let businessName: String
    let onCharge: (CheckoutDraft) -> Void

    init(
        draft: CheckoutDraft,
        cartRows: [CartLine],
        shift: ShiftDTO?,
        terminal: TerminalDTO?,
        totalMinor: Int,
        isSubmitting: Bool,
        upiVpa: String? = nil,
        businessName: String = "D Company",
        onCharge: @escaping (CheckoutDraft) -> Void
    ) {
        self._draft = State(initialValue: draft)
        self.cartRows = cartRows
        self.shift = shift
        self.terminal = terminal
        self.totalMinor = totalMinor
        self.isSubmitting = isSubmitting
        self.upiVpa = upiVpa
        self.businessName = businessName
        self.onCharge = onCharge
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Bill")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                ForEach(cartRows) { row in
                                    CartReviewLine(row: row)
                                }
                                Divider().background(Brand.gold.opacity(0.35))
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Discount (₹)")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    TextField("0", text: $draft.discountRupeesText)
                                        .keyboardType(.numberPad)
                                        .nativeField()
                                }
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Redeem points (10 pts = ₹1)")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    TextField("0", text: $draft.pointsRedeemedText)
                                        .keyboardType(.numberPad)
                                        .nativeField()
                                    if draft.pointsRedeemed > 0 && draft.customerPhone.trimmingCharacters(in: .whitespaces).isEmpty {
                                        Text("Enter the customer's phone number above to redeem points.")
                                            .font(.caption2)
                                            .foregroundColor(Brand.danger)
                                    }
                                }
                                VStack(alignment: .leading, spacing: 6) {
                                    Text("Tip (₹)")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                    TextField("0", text: $draft.tipRupeesText)
                                        .keyboardType(.numberPad)
                                        .nativeField()
                                }
                                HStack {
                                    Text("Total")
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Spacer()
                                    Text(inr(max(0, totalMinor - draft.discountMinor - draft.pointsRedeemed * 10) + draft.tipMinor))
                                        .font(.title3.weight(.bold))
                                        .foregroundColor(Brand.softGold)
                                }
                                if draft.discountMinor > 0 || draft.pointsRedeemed > 0 {
                                    Text("Exact discounted total is confirmed by the server once the bill is created.")
                                        .font(.caption2)
                                        .foregroundColor(Brand.muted)
                                }
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Service")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                Picker("Service", selection: $draft.serviceType) {
                                    ForEach(OrderServiceType.allCases) { type in
                                        Text(type.title).tag(type)
                                    }
                                }
                                .pickerStyle(.segmented)
                                .tint(Brand.gold)

                                if draft.serviceType == .delivery {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("Delivery via")
                                            .font(.caption)
                                            .foregroundColor(Brand.muted)
                                        Picker("Delivery via", selection: $draft.deliveryAggregator) {
                                            ForEach(DeliveryAggregator.allCases) { agg in
                                                Text(agg.title).tag(agg)
                                            }
                                        }
                                        .pickerStyle(.menu)
                                        .tint(Brand.softGold)

                                        if draft.deliveryAggregator.isThirdPartyAggregator {
                                            Text("Section 9(5): the platform is deemed the supplier and pays the GST on this order, not D Company.")
                                                .font(.caption2)
                                                .foregroundColor(Brand.muted)
                                        }
                                    }
                                }

                                Toggle(isOn: $draft.isInterstate) {
                                    Text("Interstate customer (IGST)")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundColor(.white)
                                }
                                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))

                                if draft.isInterstate {
                                    VStack(alignment: .leading, spacing: 8) {
                                        Text("Customer state code (e.g. 27 for Maharashtra)")
                                            .font(.caption)
                                            .foregroundColor(Brand.muted)
                                        TextField("2-digit code", text: $draft.customerStateCode)
                                            .keyboardType(.numberPad)
                                            .nativeField()
                                        Text("GSTIN (optional)")
                                            .font(.caption)
                                            .foregroundColor(Brand.muted)
                                        TextField("15-character GSTIN", text: $draft.customerGSTIN)
                                            .textInputAutocapitalization(.characters)
                                            .autocorrectionDisabled()
                                            .nativeField()
                                    }
                                }
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Payment")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                                    ForEach(PaymentMethod.allCases) { method in
                                        PaymentMethodButton(
                                            method: method,
                                            isSelected: draft.paymentMethod == method
                                        ) {
                                            Haptics.selection()
                                            draft.paymentMethod = method
                                        }
                                    }
                                }
                                PaymentTerminalNotice(method: draft.paymentMethod)
                                if draft.paymentMethod == .cash {
                                    CashTenderPad(totalMinor: max(0, totalMinor - draft.discountMinor - draft.pointsRedeemed * 10) + draft.tipMinor, tenderedMinor: $draft.cashTenderedMinor)
                                }
                                if draft.paymentMethod == .upi || draft.paymentMethod == .qr {
                                    UpiQRView(upiVpa: upiVpa, businessName: businessName, amountMinor: max(0, totalMinor - draft.discountMinor - draft.pointsRedeemed * 10) + draft.tipMinor)
                                }
                                Toggle(isOn: $draft.printReceiptAfterCharge) {
                                    Label("Print receipt after charge", systemImage: "printer")
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundColor(.white)
                                }
                                .toggleStyle(SwitchToggleStyle(tint: Brand.gold))
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Customer")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                TextField("Name optional", text: $draft.customerName)
                                    .textInputAutocapitalization(.words)
                                    .nativeField()
                                TextField("Phone optional", text: $draft.customerPhone)
                                    .keyboardType(.phonePad)
                                    .nativeField()
                                TextField("Note optional", text: $draft.note)
                                    .nativeField()
                            }
                        }

                        BrandedCard {
                            VStack(spacing: 12) {
                                CheckoutReadinessRow(
                                    title: "Shift",
                                    value: shift?.status.capitalized ?? "No open shift",
                                    isReady: shift != nil,
                                    icon: "clock.badge.checkmark"
                                )
                                CheckoutReadinessRow(
                                    title: "Terminal",
                                    value: terminal?.name ?? "No terminal",
                                    isReady: terminal != nil,
                                    icon: "iphone.and.arrow.forward"
                                )
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Review bill")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Haptics.selection()
                    onCharge(draft)
                } label: {
                    HStack {
                        if isSubmitting {
                            ProgressView()
                                .tint(.black)
                        }
                        Text(draft.isCashTenderReady(totalMinor: totalMinor) ? "Charge \(inr(totalMinor))" : "Enter cash received")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(canCharge ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(!canCharge)
            }
        }
        .navigationViewStyle(.stack)
    }

    private var canCharge: Bool {
        !isSubmitting && shift != nil && terminal != nil && !cartRows.isEmpty && draft.isCashTenderReady(totalMinor: totalMinor)
    }
}

// Bills (or voids) an order that already exists server-side — created via
// Tables "Send to POS" or Gaming "Send to POS", not built from a local
// cart. Skips POST /pos/orders entirely and goes straight to payment
// against the existing order id. Matches the web app's held-orders queue
// "resume and bill" flow (LivePOSScreen.tsx), minus "add more items" —
// that's still done by finding the order again from POS search on web too,
// this native screen just doesn't yet expose adding lines to an order
// that's already held.
private struct HeldOrderBillSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var paymentMethod: PaymentMethod = .cash
    @State private var cashTenderedMinor: Int?
    @State private var showVoidConfirm = false
    @State private var voidReason = ""
    @State private var showAddItems = false
    @State private var discountRupeesText = ""
    @State private var pointsRedeemedText = ""

    let order: OrderReadDTO
    let terminal: TerminalDTO?
    let isSubmitting: Bool
    let canVoid: Bool
    let upiVpa: String?
    let businessName: String
    let onBill: (PaymentMethod, Int?) -> Void
    let onVoid: (String) -> Void
    let onAddLines: ([OrderLineCreateRequest]) -> Void
    let onApplyDiscount: (Int) -> Void
    let onRedeemPoints: (Int) -> Void
    let menuItems: [MenuItemDTO]

    private func isCashTenderReady() -> Bool {
        paymentMethod != .cash || (cashTenderedMinor ?? order.total_minor) >= order.total_minor
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                HStack {
                                    Text(order.invoice_no ?? "Held order")
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Spacer()
                                    if let source = order.source_label {
                                        Text(source)
                                            .font(.caption.weight(.bold))
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 4)
                                            .background(Brand.gold.opacity(0.14))
                                            .foregroundColor(Brand.softGold)
                                            .clipShape(Capsule())
                                    }
                                }
                                ForEach(order.lines) { line in
                                    HStack {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(line.name)
                                                .font(.subheadline.weight(.semibold))
                                                .foregroundColor(.white)
                                            Text("Qty \(line.qty.formatted())")
                                                .font(.caption2)
                                                .foregroundColor(Brand.muted)
                                        }
                                        Spacer()
                                        Text(inr(line.line_total_minor))
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundColor(Brand.softGold)
                                    }
                                }
                                if !menuItems.isEmpty {
                                    Button {
                                        Haptics.selection()
                                        showAddItems = true
                                    } label: {
                                        Label("Add items", systemImage: "plus.circle")
                                            .font(.caption.weight(.bold))
                                    }
                                    .buttonStyle(PressableButtonStyle())
                                }
                                Divider().background(Brand.gold.opacity(0.35))
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack {
                                        Text("Discount (₹)")
                                            .font(.caption)
                                            .foregroundColor(Brand.muted)
                                        Spacer()
                                        if order.manual_discount_minor > 0 {
                                            Text("Applied: \(inr(order.manual_discount_minor))")
                                                .font(.caption2)
                                                .foregroundColor(Brand.softGold)
                                        }
                                    }
                                    HStack(spacing: 10) {
                                        TextField("0", text: $discountRupeesText)
                                            .keyboardType(.numberPad)
                                            .nativeField()
                                        Button {
                                            Haptics.selection()
                                            let minor = max(0, Int(discountRupeesText) ?? 0) * 100
                                            onApplyDiscount(minor)
                                            discountRupeesText = ""
                                        } label: {
                                            Text("Apply")
                                                .font(.subheadline.weight(.bold))
                                        }
                                        .buttonStyle(PressableButtonStyle())
                                        .padding(.horizontal, 14)
                                        .padding(.vertical, 10)
                                        .background(Brand.gold.opacity(0.16))
                                        .foregroundColor(Brand.softGold)
                                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                        .disabled(discountRupeesText.trimmingCharacters(in: .whitespaces).isEmpty || isSubmitting)
                                    }
                                }
                                Divider().background(Brand.gold.opacity(0.35))
                                VStack(alignment: .leading, spacing: 6) {
                                    HStack {
                                        Text("Redeem points (10 pts = ₹1)")
                                            .font(.caption)
                                            .foregroundColor(Brand.muted)
                                        Spacer()
                                        if order.points_redeemed_minor > 0 {
                                            Text("Applied: \(inr(order.points_redeemed_minor))")
                                                .font(.caption2)
                                                .foregroundColor(Brand.softGold)
                                        }
                                    }
                                    if order.customer_phone == nil {
                                        Text("Attach a customer to this order before redeeming points.")
                                            .font(.caption2)
                                            .foregroundColor(Brand.muted)
                                    } else {
                                        HStack(spacing: 10) {
                                            TextField("0", text: $pointsRedeemedText)
                                                .keyboardType(.numberPad)
                                                .nativeField()
                                            Button {
                                                Haptics.selection()
                                                let points = max(0, Int(pointsRedeemedText) ?? 0)
                                                onRedeemPoints(points)
                                                pointsRedeemedText = ""
                                            } label: {
                                                Text("Redeem")
                                                    .font(.subheadline.weight(.bold))
                                            }
                                            .buttonStyle(PressableButtonStyle())
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 10)
                                            .background(Brand.gold.opacity(0.16))
                                            .foregroundColor(Brand.softGold)
                                            .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                            .disabled(pointsRedeemedText.trimmingCharacters(in: .whitespaces).isEmpty || isSubmitting)
                                        }
                                    }
                                }
                                Divider().background(Brand.gold.opacity(0.35))
                                HStack {
                                    Text("Total")
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Spacer()
                                    Text(inr(order.total_minor))
                                        .font(.title3.weight(.bold))
                                        .foregroundColor(Brand.softGold)
                                }
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Payment")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                                    ForEach(PaymentMethod.allCases) { method in
                                        PaymentMethodButton(method: method, isSelected: paymentMethod == method) {
                                            Haptics.selection()
                                            paymentMethod = method
                                        }
                                    }
                                }
                                PaymentTerminalNotice(method: paymentMethod)
                                if paymentMethod == .cash {
                                    CashTenderPad(totalMinor: order.total_minor, tenderedMinor: $cashTenderedMinor)
                                }
                                if paymentMethod == .upi || paymentMethod == .qr {
                                    UpiQRView(upiVpa: upiVpa, businessName: businessName, amountMinor: order.total_minor)
                                }
                            }
                        }

                        if canVoid {
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text("Void this order")
                                        .font(.subheadline.weight(.bold))
                                        .foregroundColor(Brand.danger)
                                    TextField("Reason (required)", text: $voidReason)
                                        .nativeField()
                                    Button(role: .destructive) {
                                        onVoid(voidReason)
                                    } label: {
                                        Text("Void order")
                                            .font(.subheadline.weight(.bold))
                                            .frame(maxWidth: .infinity)
                                            .padding(.vertical, 11)
                                    }
                                    .buttonStyle(.plain)
                                    .foregroundColor(Brand.danger)
                                    .background(Brand.danger.opacity(0.12))
                                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                                    .disabled(voidReason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || isSubmitting)
                                }
                            }
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Bill order")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Close") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Haptics.selection()
                    onBill(paymentMethod, paymentMethod == .cash ? cashTenderedMinor : nil)
                } label: {
                    HStack {
                        if isSubmitting { ProgressView().tint(.black) }
                        Text(isCashTenderReady() ? "Bill \(inr(order.total_minor))" : "Enter cash received")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background((terminal != nil && isCashTenderReady() && !isSubmitting) ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(terminal == nil || !isCashTenderReady() || isSubmitting)
            }
        }
        .navigationViewStyle(.stack)
        .sheet(isPresented: $showAddItems) {
            AddItemsToOrderSheet(menuItems: menuItems) { lines in
                showAddItems = false
                onAddLines(lines)
            }
        }
    }
}

private struct AddItemsToOrderSheet: View {
    @Environment(\.dismiss) private var dismiss
    let menuItems: [MenuItemDTO]
    let onAdd: ([OrderLineCreateRequest]) -> Void

    @State private var cart: [String: Int] = [:]
    @State private var search = ""

    private var filtered: [MenuItemDTO] {
        menuItems.filter { search.isEmpty || $0.name.localizedCaseInsensitiveContains(search) }
    }

    var body: some View {
        NavigationView {
            List {
                ForEach(filtered) { item in
                    MenuItemRow(item: item, quantity: cart[item.id] ?? 0) {
                        cart[item.id, default: 0] += 1
                    } decrement: {
                        let next = max((cart[item.id] ?? 0) - 1, 0)
                        cart[item.id] = next == 0 ? nil : next
                    }
                    .listRowBackground(Brand.background)
                    .listRowSeparatorTint(Brand.hairline)
                }
            }
            .listStyle(.plain)
            .searchable(text: $search)
            .navigationTitle("Add items")
            .navigationBarTitleDisplayMode(.inline)
            .background(Brand.background)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        let lines = cart.compactMap { itemID, qty -> OrderLineCreateRequest? in
                            guard qty > 0 else { return nil }
                            return OrderLineCreateRequest(menu_item_id: itemID, variant_id: nil, qty: Double(qty), modifiers: nil, note: nil)
                        }
                        onAdd(lines)
                    }
                    .disabled(cart.isEmpty)
                }
            }
        }
        .navigationViewStyle(.stack)
    }
}

private struct CashTenderPad: View {
    let totalMinor: Int
    @Binding var tenderedMinor: Int?

    private let keys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", "Back"]

    private var receivedMinor: Int {
        tenderedMinor ?? totalMinor
    }

    private var changeMinor: Int {
        max(receivedMinor - totalMinor, 0)
    }

    private var isShort: Bool {
        receivedMinor < totalMinor
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                TenderSummaryTile(title: "Received", value: inr(receivedMinor), isAlert: isShort)
                TenderSummaryTile(title: "Change", value: inr(changeMinor), isAlert: false)
            }

            LazyVGrid(columns: keypadColumns, spacing: 8) {
                ForEach(keys, id: \.self) { key in
                    Button {
                        Haptics.selection()
                        tap(key)
                    } label: {
                        Text(key == "Back" ? "⌫" : key)
                            .font(.headline.weight(.bold))
                            .foregroundColor(key == "Back" ? Brand.gold : .white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .background(Brand.elevated)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .buttonStyle(PressableButtonStyle())
                }
            }

            HStack(spacing: 10) {
                Button {
                    Haptics.selection()
                    tenderedMinor = totalMinor
                } label: {
                    Label("Exact", systemImage: "checkmark.circle")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(Brand.gold)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                Button {
                    Haptics.selection()
                    tenderedMinor = 0
                } label: {
                    Label("Clear", systemImage: "xmark.circle")
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 11)
                }
                .buttonStyle(.plain)
                .foregroundColor(.white)
                .background(Brand.elevated)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }

            if isShort {
                Text("Cash received is short by \(inr(totalMinor - receivedMinor)).")
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Brand.danger)
            }
        }
    }

    private var keypadColumns: [GridItem] {
        [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)]
    }

    private func tap(_ key: String) {
        if key == "Back" {
            var digits = String(max((tenderedMinor ?? 0) / 100, 0))
            _ = digits.popLast()
            tenderedMinor = Int(digits).map { $0 * 100 } ?? 0
            return
        }

        var digits = tenderedMinor == nil ? "" : String(max((tenderedMinor ?? 0) / 100, 0))
        digits += key
        let value = min(Int(digits) ?? 0, 999_999)
        tenderedMinor = value * 100
    }
}

private struct TenderSummaryTile: View {
    let title: String
    let value: String
    let isAlert: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption.weight(.bold))
                .foregroundColor(Brand.muted)
            Text(value)
                .font(.headline.weight(.bold))
                .foregroundColor(isAlert ? Brand.danger : Brand.softGold)
                .lineLimit(1)
                .minimumScaleFactor(0.78)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background((isAlert ? Brand.danger : Brand.gold).opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct CartReviewLine: View {
    let row: CartLine

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.item.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                Text("\(row.quantity) x \(inr(row.item.base_price_minor))")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
            }
            Spacer()
            Text(inr(row.item.base_price_minor * row.quantity))
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Brand.softGold)
        }
    }
}

private struct PaymentMethodButton: View {
    let method: PaymentMethod
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: method.icon)
                    .foregroundColor(isSelected ? .black : Brand.gold)
                Text(method.title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(isSelected ? .black : .white)
                Spacer()
            }
            .padding(12)
            .background(isSelected ? Brand.gold : Brand.elevated)
            .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .buttonStyle(PressableButtonStyle())
    }
}

private struct PaymentTerminalNotice: View {
    let method: PaymentMethod

    var body: some View {
        let status = TerminalIntegrationStatus.current
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: status.isConfigured ? "creditcard.and.123" : "exclamationmark.triangle.fill")
                .foregroundColor(status.isConfigured ? Brand.success : Brand.danger)
            VStack(alignment: .leading, spacing: 4) {
                Text(method == .cash ? "Cash payment" : "\(method.title) is manual-record mode")
                    .font(.caption.weight(.bold))
                    .foregroundColor(.white)
                Text(method == .cash ? "No payment terminal is needed for cash." : status.detail)
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding(11)
        .background((method == .cash ? Brand.success : Brand.danger).opacity(0.10))
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct CheckoutReadinessRow: View {
    let title: String
    let value: String
    let isReady: Bool
    let icon: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .foregroundColor(isReady ? Brand.success : Brand.danger)
                .frame(width: 26)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                Text(value)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
            }
            Spacer()
            Image(systemName: isReady ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                .foregroundColor(isReady ? Brand.success : Brand.danger)
        }
    }
}

private struct LogoBadge: View {
    let size: CGFloat

    var body: some View {
        Image("BrandLogo")
            .resizable()
            .interpolation(.high)
            .antialiased(true)
            .scaledToFit()
        .frame(width: size, height: size)
        .shadow(color: Brand.gold.opacity(0.22), radius: 18, x: 0, y: 10)
    }
}

private struct HeaderBlock: View {
    let title: String
    let subtitle: String
    let icon: String

    var body: some View {
        HStack(spacing: 14) {
            LogoBadge(size: 52)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.title2.weight(.bold))
                    .foregroundColor(.white)
                Text(subtitle)
                    .font(.subheadline)
                    .foregroundColor(Brand.muted)
            }
            Spacer()
            Image(systemName: icon)
                .font(.title2)
                .foregroundColor(Brand.gold)
        }
        .padding(16)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Brand.cardGradient)
                .shadow(color: .black.opacity(0.30), radius: 18, x: 0, y: 10)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Brand.gold.opacity(0.20), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

private struct BrandedCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(Brand.cardGradient)
                    .shadow(color: .black.opacity(0.24), radius: 16, x: 0, y: 9)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Brand.gold.opacity(0.22), lineWidth: 1)
            )
    }
}

private struct NetworkBanner: View {
    let label: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "wifi.slash")
                .font(.headline)
                .foregroundColor(Brand.danger)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text("Server connection is offline")
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.white)
                Text("D Company ERP needs the live DigitalOcean backend for billing, audit, stock, and reports.")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            Text(label)
                .font(.caption2.weight(.bold))
                .padding(.horizontal, 8)
                .padding(.vertical, 5)
                .background(Brand.danger.opacity(0.16))
                .foregroundColor(Brand.danger)
                .clipShape(Capsule())
        }
        .padding(12)
        .background(Brand.danger.opacity(0.10))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Brand.danger.opacity(0.26), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct OwnerCommandCenter: View {
    let report: ReportDTO?
    let lowStockCount: Int
    let menuCount: Int
    let canSeeInventory: Bool
    let isOnline: Bool
    let connectionLabel: String

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Owner Command Center")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Today’s live operating posture")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Image(systemName: "gauge.with.dots.needle.67percent")
                        .foregroundColor(Brand.gold)
                }

                VStack(spacing: 10) {
                    OperationalSignalRow(
                        title: "Backend",
                        value: connectionLabel,
                        detail: isOnline ? "Live sync is available" : "Billing requires internet",
                        isReady: isOnline,
                        icon: isOnline ? "checkmark.icloud.fill" : "xmark.icloud.fill"
                    )
                    OperationalSignalRow(
                        title: "Sales",
                        value: report.map { "\($0.orders_count) orders" } ?? "Loading",
                        detail: report.map { "Net \(inr($0.net_revenue_minor)) today" } ?? "Waiting for daily report",
                        isReady: report != nil,
                        icon: "chart.line.uptrend.xyaxis"
                    )
                    if canSeeInventory {
                        OperationalSignalRow(
                            title: "Inventory",
                            value: lowStockCount == 0 ? "No low stock" : "\(lowStockCount) low",
                            detail: lowStockCount == 0 ? "Stock risk looks clear" : "Review reorder list before service",
                            isReady: lowStockCount == 0,
                            icon: lowStockCount == 0 ? "cube.box.fill" : "exclamationmark.triangle.fill"
                        )
                    }
                    OperationalSignalRow(
                        title: "Menu",
                        value: "\(menuCount) items",
                        detail: menuCount > 0 ? "POS catalogue loaded" : "Menu needs data",
                        isReady: menuCount > 0,
                        icon: "menucard.fill"
                    )
                }
            }
        }
    }
}

private struct ERPWorkflowCard: View {
    let openTab: (NativeTab) -> Void
    let canUseProtectedControls: Bool

    private var columns: [GridItem] {
        [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)]
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Operate Cafe")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Billing, sessions, prices, stock, and control.")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Image(systemName: "bolt.fill")
                        .foregroundColor(Brand.gold)
                }

                LazyVGrid(columns: columns, spacing: 10) {
                    Button {
                        Haptics.selection()
                        openTab(.pos)
                    } label: {
                        OperationCommandTile(title: "New Bill", subtitle: "Food, drinks, services", icon: "cart.badge.plus", isPrimary: true)
                    }
                    .buttonStyle(.plain)

                    Button {
                        Haptics.selection()
                        openTab(.gaming)
                    } label: {
                        OperationCommandTile(title: "Sessions", subtitle: "PS5, VR, shisha", icon: "gamecontroller.fill")
                    }
                    .buttonStyle(.plain)

                    if canUseProtectedControls {
                        NavigationLink {
                            PricingNativeView()
                        } label: {
                            OperationCommandTile(title: "Prices", subtitle: "Owner locked", icon: "indianrupeesign.circle")
                        }
                        .buttonStyle(.plain)

                        NavigationLink {
                            StationManagementNativeView()
                        } label: {
                            OperationCommandTile(title: "Services", subtitle: "Add station", icon: "plus.circle")
                        }
                        .buttonStyle(.plain)

                        NavigationLink {
                            InventoryNativeView()
                        } label: {
                            OperationCommandTile(title: "Stock", subtitle: "Reorder risk", icon: "cube.box")
                        }
                        .buttonStyle(.plain)

                        NavigationLink {
                            SettingsNativeView()
                        } label: {
                            OperationCommandTile(title: "Settings", subtitle: "GST, terminal", icon: "gearshape")
                        }
                        .buttonStyle(.plain)

                        NavigationLink {
                            AuditNativeView()
                        } label: {
                            OperationCommandTile(title: "Audit", subtitle: "Owner trail", icon: "shield.checkered")
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

private struct OperationCommandTile: View {
    let title: String
    let subtitle: String
    let icon: String
    var isPrimary = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Image(systemName: icon)
                    .font(.title3.weight(.bold))
                    .foregroundColor(isPrimary ? .black : Brand.gold)
                    .frame(width: 36, height: 36)
                    .background(isPrimary ? Brand.gold : Brand.gold.opacity(0.14))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.caption.weight(.bold))
                    .foregroundColor(isPrimary ? .black.opacity(0.55) : Brand.muted)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(isPrimary ? .black : .white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.78)
                Text(subtitle)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(isPrimary ? .black.opacity(0.65) : Brand.muted)
                    .lineLimit(1)
            }
        }
        .frame(maxWidth: .infinity, minHeight: 104, alignment: .leading)
        .padding(14)
        .background(isPrimary ? Brand.gold : Brand.elevated)
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .stroke(Brand.gold.opacity(isPrimary ? 0 : 0.18), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct ServiceControlCard: View {
    let canUseProtectedControls: Bool
    let openPOS: () -> Void

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Service Control")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Set up billable cafe services once, then use them from sessions and POS.")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Image(systemName: "slider.horizontal.3")
                        .foregroundColor(Brand.gold)
                }

                VStack(spacing: 8) {
                    if canUseProtectedControls {
                        NavigationLink {
                            StationManagementNativeView()
                        } label: {
                            ERPWorkflowRow(number: "1", title: "Add PS5, VR, simulator, shisha, streaming", subtitle: "Create station/service records and hourly rates", icon: "plus.circle")
                        }

                        NavigationLink {
                            PricingNativeView()
                        } label: {
                            ERPWorkflowRow(number: "2", title: "Update rates", subtitle: "Change session and menu pricing under owner lock", icon: "indianrupeesign.circle")
                        }
                    }

                    Button {
                        Haptics.selection()
                        openPOS()
                    } label: {
                        ERPWorkflowRow(
                            number: canUseProtectedControls ? "3" : "1",
                            title: "Bill in POS",
                            subtitle: "Charge session, service, food, and drink items",
                            icon: "receipt"
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

private struct POSShiftCommandCard: View {
    let shift: ShiftDTO?
    let terminal: TerminalDTO?
    let cartItems: Int
    let cartTotalMinor: Int
    let activeSessionCount: Int
    let serviceCount: Int
    let isSubmitting: Bool
    let openSessions: () -> Void
    let openShift: () -> Void
    let closeShift: () -> Void
    let openMenu: () -> Void
    let reviewBill: () -> Void

    private var isReady: Bool {
        shift != nil && terminal != nil
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("POS Command")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text(isReady ? "Ready for billing and receipt printing." : "Open a shift and terminal before charging.")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Text(isReady ? "Ready" : "Setup")
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background((isReady ? Brand.success : Brand.danger).opacity(0.16))
                        .foregroundColor(isReady ? Brand.success : Brand.danger)
                        .clipShape(Capsule())
                }

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    POSCommandMetric(title: "Shift", value: shift == nil ? "Closed" : "Open", icon: "clock.badge.checkmark", isReady: shift != nil)
                    POSCommandMetric(title: "Terminal", value: terminal?.name ?? "Missing", icon: "printer", isReady: terminal != nil)
                    POSCommandMetric(title: "Cart", value: "\(cartItems) items", icon: "cart.fill", isReady: cartItems > 0)
                    POSCommandMetric(title: "Total", value: inr(cartTotalMinor), icon: "indianrupeesign.circle", isReady: cartTotalMinor > 0)
                }

                HStack(spacing: 10) {
                    POSCommandButton(title: "Menu", icon: "menucard", action: openMenu)
                    POSCommandButton(title: "Sessions", icon: "timer", action: openSessions)
                    POSCommandButton(title: "Bill", icon: "receipt", action: reviewBill)
                        .opacity(cartItems == 0 ? 0.48 : 1)
                        .disabled(cartItems == 0)
                }

                if shift == nil {
                    Button(action: openShift) {
                        Label(isSubmitting ? "Opening shift..." : "Open shift", systemImage: "clock.badge.plus")
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.black)
                    .background((terminal == nil || isSubmitting) ? Brand.muted.opacity(0.45) : Brand.gold)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(terminal == nil || isSubmitting)
                } else {
                    Button(action: closeShift) {
                        Label("Close shift", systemImage: "clock.badge.xmark")
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(Brand.softGold)
                    .background(Brand.elevated)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Brand.gold.opacity(0.35), lineWidth: 1)
                    )
                }

                Text("\(serviceCount) services configured - \(activeSessionCount) running now")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
            }
        }
    }
}

private struct POSCommandMetric: View {
    let title: String
    let value: String
    let icon: String
    let isReady: Bool

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(isReady ? Brand.gold : Brand.muted)
                .frame(width: 30, height: 30)
                .background((isReady ? Brand.gold : Brand.muted).opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.muted)
                Text(value)
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
            }
            Spacer(minLength: 0)
        }
        .padding(10)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
    }
}

private struct POSCommandButton: View {
    let title: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Image(systemName: icon)
                    .font(.headline)
                Text(title)
                    .font(.caption.weight(.bold))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 11)
            .background(Brand.gold.opacity(0.16))
            .foregroundColor(Brand.softGold)
            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
        }
        .buttonStyle(.plain)
    }
}

private struct ServiceKindSummaryStrip: View {
    let stations: [GamingStationDTO]
    let activeSessions: [GamingSessionDTO]
    let canManageServices: Bool
    let openPOS: () -> Void

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Billable Services")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("Configured service types available for timed billing.")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Text("\(stations.filter(\.is_active).count) active")
                        .font(.caption.weight(.bold))
                        .foregroundColor(Brand.softGold)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(Brand.gold.opacity(0.13))
                        .clipShape(Capsule())
                }

                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(GamingStationKind.allCases) { kind in
                            ServiceKindPill(
                                kind: kind,
                                configuredCount: configuredCount(for: kind),
                                runningCount: runningCount(for: kind)
                            )
                        }
                    }
                    .padding(.vertical, 1)
                }

                HStack(spacing: 10) {
                    if canManageServices {
                        NavigationLink {
                            StationManagementNativeView()
                        } label: {
                            Label("Manage services", systemImage: "slider.horizontal.3")
                                .font(.caption.weight(.bold))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 11)
                                .background(Brand.gold)
                                .foregroundColor(.black)
                                .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                        }
                    }

                    Button {
                        Haptics.selection()
                        openPOS()
                    } label: {
                        Label("Open POS", systemImage: "receipt")
                            .font(.caption.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 11)
                            .background(Brand.gold.opacity(0.16))
                            .foregroundColor(Brand.softGold)
                            .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private func configuredCount(for kind: GamingStationKind) -> Int {
        stations.filter { $0.is_active && $0.kind == kind }.count
    }

    private func runningCount(for kind: GamingStationKind) -> Int {
        let stationIDs = Set(stations.filter { $0.kind == kind }.map(\.id))
        return activeSessions.filter { $0.status == "active" && stationIDs.contains($0.station_id) }.count
    }
}

private struct ServiceKindPill: View {
    let kind: GamingStationKind
    let configuredCount: Int
    let runningCount: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Image(systemName: kind.icon)
                .font(.headline)
                .foregroundColor(configuredCount == 0 ? Brand.muted : Brand.gold)
            Text(kind.title)
                .font(.caption.weight(.bold))
                .foregroundColor(.white)
                .lineLimit(1)
            Text(configuredCount == 0 ? "Add" : "\(configuredCount) setup")
                .font(.caption2.weight(.semibold))
                .foregroundColor(configuredCount == 0 ? Brand.danger : Brand.softGold)
            if runningCount > 0 {
                Text("\(runningCount) running")
                    .font(.caption2.weight(.bold))
                    .foregroundColor(Brand.success)
            }
        }
        .frame(width: 96, alignment: .leading)
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
    }
}

private struct ServicePresetButton: View {
    let kind: GamingStationKind
    let configuredCount: Int
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: kind.icon)
                        .font(.headline)
                        .foregroundColor(Brand.gold)
                    Spacer()
                    Text("\(configuredCount)")
                        .font(.caption2.weight(.bold))
                        .foregroundColor(configuredCount == 0 ? Brand.muted : Brand.success)
                }

                Text(kind.title)
                    .font(.caption.weight(.bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)

                Text("\(inr(kind.defaultRateMinor))/hr")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.softGold)
            }
            .frame(width: 112, alignment: .leading)
            .padding(12)
            .background(Brand.elevated)
            .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: 15, style: .continuous)
                    .stroke(Brand.gold.opacity(0.16), lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

private struct ERPWorkflowRow: View {
    let number: String
    let title: String
    let subtitle: String
    let icon: String

    var body: some View {
        HStack(spacing: 12) {
            Text(number)
                .font(.caption.weight(.bold))
                .foregroundColor(.black)
                .frame(width: 28, height: 28)
                .background(Brand.gold)
                .clipShape(Circle())

            Image(systemName: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(Brand.gold)
                .frame(width: 30, height: 30)
                .background(Brand.gold.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(2)
            }

            Spacer(minLength: 8)

            Image(systemName: "chevron.right")
                .font(.caption.weight(.bold))
                .foregroundColor(Brand.muted)
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct OperationalSignalRow: View {
    let title: String
    let value: String
    let detail: String
    let isReady: Bool
    let icon: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundColor(isReady ? Brand.success : Brand.danger)
                .frame(width: 30, height: 30)
                .background((isReady ? Brand.success : Brand.danger).opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption.weight(.semibold))
                    .foregroundColor(Brand.muted)
                Text(detail)
                    .font(.caption2)
                    .foregroundColor(Brand.muted.opacity(0.78))
                    .lineLimit(1)
            }

            Spacer()

            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundColor(isReady ? Brand.softGold : Brand.danger)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(11)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct InventorySnapshotHeader: View {
    let ingredients: [IngredientDTO]

    private var lowStockCount: Int {
        ingredients.filter(\.isLowStock).count
    }

    private var zeroStockCount: Int {
        ingredients.filter { $0.current_qty <= 0 }.count
    }

    private var stockValueMinor: Int {
        ingredients.reduce(0) { total, ingredient in
            total + lineValueMinor(qty: ingredient.current_qty, avgCostMinor: ingredient.avg_cost_minor)
        }
    }

    private var reorderValueMinor: Int {
        ingredients
            .filter(\.isLowStock)
            .reduce(0) { total, ingredient in
                total + lineValueMinor(qty: ingredient.reorder_qty, avgCostMinor: ingredient.avg_cost_minor)
            }
    }

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
            InventoryMetricPill(title: "Low stock", value: "\(lowStockCount)", icon: "exclamationmark.triangle", isWarning: lowStockCount > 0)
            InventoryMetricPill(title: "Zero stock", value: "\(zeroStockCount)", icon: "minus.circle", isWarning: zeroStockCount > 0)
            InventoryMetricPill(title: "Stock value", value: inr(stockValueMinor), icon: "indianrupeesign.circle", isWarning: false)
            InventoryMetricPill(title: "Reorder value", value: inr(reorderValueMinor), icon: "cart.badge.plus", isWarning: reorderValueMinor > 0)
        }
    }
}

private struct InventoryMetricPill: View {
    let title: String
    let value: String
    let icon: String
    let isWarning: Bool

    var body: some View {
        HStack(spacing: 9) {
            Image(systemName: icon)
                .foregroundColor(isWarning ? Brand.danger : Brand.gold)
                .frame(width: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(value)
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.72)
                Text(title)
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.muted)
            }
            Spacer(minLength: 0)
        }
        .padding(11)
        .background(Brand.surface)
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke((isWarning ? Brand.danger : Brand.gold).opacity(0.22), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct GSTComplianceCard: View {
    let compliance: TaxComplianceDTO

    private var statusColor: Color {
        if compliance.critical_count > 0 { return Brand.danger }
        if compliance.warning_count > 0 { return Brand.softGold }
        return Brand.success
    }

    private var statusTitle: String {
        if compliance.critical_count > 0 { return "GST needs urgent review" }
        if compliance.warning_count > 0 { return "GST has warnings" }
        return "GST checks clean"
    }

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(spacing: 12) {
                    Image(systemName: compliance.critical_count > 0 ? "exclamationmark.octagon.fill" : "checkmark.seal.fill")
                        .foregroundColor(statusColor)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(statusTitle)
                            .font(.headline)
                            .foregroundColor(.white)
                        Text("GSTIN \(compliance.gstin?.isEmpty == false ? compliance.gstin! : "not set")")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                }

                LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], spacing: 10) {
                    MiniStat(title: "Taxable", value: inr(compliance.taxable_minor))
                    MiniStat(title: "GST collected", value: inr(compliance.gst_collected_minor))
                    MiniStat(title: "Orders checked", value: "\(compliance.checked_orders)")
                    MiniStat(title: "Issues", value: "\(compliance.critical_count + compliance.warning_count)")
                }

                if let issue = compliance.issues.first(where: { $0.severity != "info" }) ?? compliance.issues.first {
                    VStack(alignment: .leading, spacing: 5) {
                        Text("\(issue.area): \(issue.title)")
                            .font(.caption.weight(.bold))
                            .foregroundColor(statusColor)
                        Text(issue.action)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(10)
                    .background(statusColor.opacity(0.10))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                }
            }
        }
    }
}

private struct MiniStat: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(value)
                .font(.subheadline.weight(.bold))
                .foregroundColor(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
            Text(title)
                .font(.caption2.weight(.semibold))
                .foregroundColor(Brand.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

private struct MetricCard: View {
    let title: String
    let value: String
    let detail: String
    let icon: String

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Image(systemName: icon)
                        .foregroundColor(Brand.gold)
                    Spacer()
                }
                Text(value)
                    .font(.title3.weight(.bold))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .font(.caption.weight(.semibold))
                        .foregroundColor(Brand.muted)
                    Text(detail)
                        .font(.caption2)
                        .foregroundColor(Brand.muted.opacity(0.75))
                }
            }
        }
    }
}

private struct StatusPill: View {
    let title: String
    let value: String
    let icon: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .foregroundColor(Brand.gold)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                Text(value)
                    .font(.headline)
                    .foregroundColor(.white)
            }
            Spacer()
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct SearchBar: View {
    @Binding var text: String
    let placeholder: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundColor(Brand.muted)
            TextField(placeholder, text: $text)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .foregroundColor(.white)
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundColor(Brand.muted)
                }
            }
        }
        .padding(12)
        .background(Brand.surface)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct FilterChip: View {
    let title: String
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .padding(.horizontal, 14)
                .padding(.vertical, 9)
                .background(isSelected ? Brand.gold : Brand.surface)
                .foregroundColor(isSelected ? .black : Brand.softGold)
                .clipShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct POSServiceStationRow: View {
    let station: GamingStationDTO
    let activeSession: GamingSessionDTO?
    let isSubmitting: Bool
    let onStart: () -> Void
    let onStop: (GamingSessionDTO) -> Void

    private var isRunning: Bool {
        activeSession != nil
    }

    private var elapsedMinutes: Int {
        guard let activeSession else { return 0 }
        return max(1, Int(Date().timeIntervalSince(activeSession.start_at) / 60))
    }

    private var estimateMinor: Int {
        guard let activeSession else { return station.rate_per_hour_minor }
        let rate = activeSession.rate_per_hour_minor ?? station.rate_per_hour_minor
        return Int((Double(rate) * Double(elapsedMinutes) / 60.0).rounded(.up))
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: station.kind.icon)
                .font(.title3)
                .foregroundColor(Brand.gold)
                .frame(width: 38, height: 38)
                .background(Brand.gold.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            VStack(alignment: .leading, spacing: 5) {
                Text(station.name)
                    .font(.headline)
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .minimumScaleFactor(0.82)
                Text(isRunning ? "\(station.kind.title) running - \(elapsedMinutes) min - \(inr(estimateMinor)) est." : "\(station.kind.title) - \(inr(station.rate_per_hour_minor))/hr")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
            }

            Spacer(minLength: 8)

            if let activeSession {
                Button {
                    onStop(activeSession)
                } label: {
                    Label("Stop", systemImage: "stop.fill")
                        .labelStyle(.iconOnly)
                        .font(.headline)
                        .frame(width: 44, height: 44)
                        .background(Brand.danger.opacity(0.18))
                        .foregroundColor(Brand.danger)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(isSubmitting)
            } else {
                Button(action: onStart) {
                    Label("Start", systemImage: "play.fill")
                        .labelStyle(.iconOnly)
                        .font(.headline)
                        .frame(width: 44, height: 44)
                        .background(Brand.gold)
                        .foregroundColor(.black)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(isSubmitting)
            }
        }
        .padding(.vertical, 10)
    }
}

private struct MenuItemRow: View {
    let item: MenuItemDTO
    let quantity: Int
    let increment: () -> Void
    let decrement: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 5) {
                Text(item.name)
                    .font(.headline)
                    .foregroundColor(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text("\(item.type.capitalized) - \(inr(item.base_price_minor))")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
            }
            Spacer()
            HStack(spacing: 8) {
                Button(action: decrement) {
                    Image(systemName: "minus")
                        .font(.headline.weight(.bold))
                        .frame(width: 42, height: 42)
                        .background(quantity > 0 ? Brand.elevated : Brand.surface.opacity(0.45))
                        .foregroundColor(quantity > 0 ? Brand.gold : Brand.muted.opacity(0.45))
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(quantity == 0)
                .contentShape(Circle())

                Text("\(quantity)")
                    .font(.headline.monospacedDigit())
                    .foregroundColor(.white)
                    .frame(width: 30)

                Button(action: increment) {
                    Image(systemName: "plus")
                        .font(.headline.weight(.bold))
                        .frame(width: 42, height: 42)
                        .background(Brand.gold)
                        .foregroundColor(.black)
                        .clipShape(Circle())
                }
                .buttonStyle(.plain)
                .contentShape(Circle())
            }
        }
        .padding(.vertical, 10)
        .contentShape(Rectangle())
    }
}

private struct InventoryRow: View {
    let ingredient: IngredientDTO

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: ingredient.isLowStock ? "exclamationmark.triangle.fill" : "cube.box")
                .foregroundColor(ingredient.isLowStock ? Brand.danger : Brand.gold)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(ingredient.name)
                    .font(.headline)
                    .foregroundColor(.white)
                Text("\(ingredient.sku) - \(ingredient.base_unit)")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 4) {
                Text(decimalString(ingredient.current_qty))
                    .font(.headline)
                    .foregroundColor(.white)
                Text("Min \(decimalString(ingredient.reorder_threshold))")
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
            }
        }
        .padding(.vertical, 8)
    }
}

private struct AuditRow: View {
    let entry: AuditEntryDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.actorDisplayName)
                        .font(.headline)
                        .foregroundColor(.white)
                    if let email = entry.actor_email, !email.isEmpty {
                        Text(email)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                }
                Spacer()
                Text(DateFormatters.shortDateTime.string(from: entry.created_at))
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
            }

            HStack {
                Text(readableAction(entry.action))
                    .font(.caption.weight(.semibold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Brand.gold.opacity(0.16))
                    .foregroundColor(Brand.softGold)
                    .clipShape(Capsule())
                Text(entry.entity_type)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                Spacer()
            }

            AuditChangeSummary(before: entry.before?.summary, after: entry.after?.summary)

            HStack(spacing: 8) {
                if let entityID = entry.entity_id, !entityID.isEmpty {
                    Text("ID \(entityID.prefix(10))")
                }
                if let ip = entry.ip, !ip.isEmpty {
                    Text("IP \(ip)")
                }
                if let agent = entry.user_agent, !agent.isEmpty {
                    Text(agent.contains("iOSNative") ? "iOS app" : "Client recorded")
                }
            }
            .font(.caption2)
            .foregroundColor(Brand.muted.opacity(0.75))
        }
        .padding(.vertical, 8)
    }
}

private struct AuditChangeSummary: View {
    let before: String?
    let after: String?

    private var hasChange: Bool {
        let beforeValue = before ?? "empty"
        let afterValue = after ?? "empty"
        return beforeValue != "empty" || afterValue != "empty"
    }

    var body: some View {
        if hasChange {
            VStack(alignment: .leading, spacing: 6) {
                if let before, before != "empty" {
                    AuditValueLine(title: "Before", value: before)
                }
                if let after, after != "empty" {
                    AuditValueLine(title: "After", value: after)
                }
            }
            .padding(10)
            .background(Brand.elevated)
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
    }
}

private struct AuditValueLine: View {
    let title: String
    let value: String

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(title)
                .font(.caption2.weight(.bold))
                .foregroundColor(Brand.gold)
                .frame(width: 48, alignment: .leading)
            Text(value)
                .font(.caption)
                .foregroundColor(Brand.muted)
                .lineLimit(2)
            Spacer(minLength: 0)
        }
    }
}

private struct PNLRow: View {
    let title: String
    let value: String
    var highlight = false

    var body: some View {
        HStack {
            Text(title)
                .foregroundColor(highlight ? .white : Brand.muted)
            Spacer()
            Text(value)
                .fontWeight(highlight ? .bold : .semibold)
                .foregroundColor(highlight ? Brand.softGold : .white)
        }
    }
}

private struct ErrorBanner: View {
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
                .foregroundColor(Brand.danger)
            Text(message)
                .font(.footnote.weight(.semibold))
                .foregroundColor(.white)
            Spacer()
        }
        .padding(12)
        .background(Brand.danger.opacity(0.16))
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct SuccessBanner: View {
    let message: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(Brand.success)
            Text(message)
                .font(.footnote.weight(.semibold))
                .foregroundColor(.white)
            Spacer()
        }
        .padding(12)
        .background(Brand.success.opacity(0.14))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .stroke(Brand.success.opacity(0.25), lineWidth: 1)
        )
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct WorkspaceLinkRow: View {
    let title: String
    let subtitle: String
    let icon: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.headline)
                .foregroundColor(Brand.gold)
                .frame(width: 32, height: 32)
                .background(Brand.gold.opacity(0.12))
                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)
            }

            Spacer()

            Image(systemName: "chevron.right")
                .font(.caption.weight(.bold))
                .foregroundColor(Brand.muted)
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct WorkspaceSectionHeader: View {
    let title: String
    var isFirst: Bool = false

    var body: some View {
        Text(title.uppercased())
            .font(.caption2.weight(.bold))
            .tracking(0.6)
            .foregroundColor(Brand.muted)
            .padding(.top, isFirst ? 0 : 10)
    }
}

private struct MenuCatalogRow: View {
    let item: MenuItemDTO
    let categoryName: String

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: item.is_available ? "fork.knife.circle.fill" : "pause.circle.fill")
                .font(.title3)
                .foregroundColor(item.is_available ? Brand.gold : Brand.muted)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 5) {
                Text(item.name)
                    .font(.subheadline.weight(.semibold))
                    .foregroundColor(.white)
                    .fixedSize(horizontal: false, vertical: true)
                Text("\(categoryName) - \(item.sku)")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)
                if let description = item.description?.nilIfBlank {
                    Text(description)
                        .font(.caption2)
                        .foregroundColor(Brand.muted.opacity(0.85))
                        .lineLimit(2)
                }
            }

            Spacer(minLength: 10)

            VStack(alignment: .trailing, spacing: 5) {
                Text(inr(item.base_price_minor))
                    .font(.headline.weight(.bold))
                    .foregroundColor(Brand.softGold)
                    .lineLimit(1)
                    .minimumScaleFactor(0.80)
                Text("GST \(taxInput(item.tax_rate))%")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.muted)
            }
        }
        .padding(.vertical, 12)
    }
}

private struct GamingStationCard: View {
    let station: GamingStationDTO
    let activeSession: GamingSessionDTO?
    let action: () -> Void

    private var isRunning: Bool {
        activeSession != nil
    }

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 10) {
                    Image(systemName: station.kind.icon)
                        .font(.title3)
                        .foregroundColor(Brand.gold)
                        .frame(width: 34, height: 34)
                        .background(Brand.gold.opacity(0.12))
                        .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                    Spacer()
                    Text(isRunning ? "Running" : "Free")
                        .font(.caption.weight(.bold))
                        .foregroundColor(isRunning ? Brand.danger : Brand.success)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 5)
                        .background((isRunning ? Brand.danger : Brand.success).opacity(0.12))
                        .clipShape(Capsule())
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(station.name)
                        .font(.headline)
                        .foregroundColor(.white)
                        .lineLimit(1)
                        .minimumScaleFactor(0.82)
                    Text("\(station.kind.title) - \(station.code)")
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                        .lineLimit(1)
                }

                HStack {
                    Text("\(inr(station.rate_per_hour_minor))/hr")
                        .font(.subheadline.weight(.bold))
                        .foregroundColor(Brand.softGold)
                    Spacer()
                    Image(systemName: isRunning ? "lock.fill" : "plus.circle.fill")
                        .foregroundColor(isRunning ? Brand.muted : Brand.gold)
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 150, alignment: .leading)
            .background(Brand.cardGradient)
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(isRunning ? Brand.danger.opacity(0.25) : Brand.gold.opacity(0.22), lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(PressableButtonStyle())
        .disabled(isRunning)
        .opacity(isRunning ? 0.74 : 1)
    }
}

private struct GamingSessionCard: View {
    let session: GamingSessionDTO
    let stationName: String
    let isSubmitting: Bool
    let onStop: () -> Void
    let onExtend: () -> Void
    let onClearTimer: () -> Void

    private var elapsedMinutes: Int {
        max(1, Int(Date().timeIntervalSince(session.start_at) / 60))
    }

    private var estimateMinor: Int {
        if let amount = session.amount_minor {
            return amount
        }
        guard let rate = session.rate_per_hour_minor else { return 0 }
        return Int((Double(rate) * Double(elapsedMinutes) / 60.0).rounded(.up))
    }

    private var customerLabel: String {
        let trimmed = session.customer_name?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return trimmed.isEmpty ? "Walk-in" : trimmed
    }

    var isOvertime: Bool {
        guard let endsAt = session.timer_ends_at else { return false }
        return Date() >= endsAt
    }

    private func timerLabel(now: Date) -> (text: String, color: Color)? {
        guard let endsAt = session.timer_ends_at else { return nil }
        let remaining = endsAt.timeIntervalSince(now)
        if remaining <= 0 {
            let over = Int((-remaining / 60).rounded(.up))
            return ("+\(over)m over", Brand.danger)
        }
        let mins = Int((remaining / 60).rounded(.up))
        let color: Color = remaining < 5 * 60 ? Brand.danger : (remaining < 15 * 60 ? .orange : Brand.success)
        return ("\(mins)m left", color)
    }

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .top, spacing: 12) {
                    Image(systemName: isOvertime ? "exclamationmark.triangle.fill" : "timer.circle.fill")
                        .font(.title3)
                        .foregroundColor(isOvertime ? Brand.danger : Brand.gold)
                        .frame(width: 34)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(stationName)
                            .font(.headline)
                            .foregroundColor(.white)
                        Text(customerLabel)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                            .lineLimit(1)
                    }

                    Spacer()

                    Button(action: onStop) {
                        Text("Stop")
                            .font(.caption.weight(.bold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 8)
                            .background(Brand.danger.opacity(0.18))
                            .foregroundColor(Brand.danger)
                            .clipShape(Capsule())
                    }
                    .buttonStyle(PressableButtonStyle())
                    .disabled(isSubmitting)
                }

                HStack(spacing: 10) {
                    TenderSummaryTile(title: "Elapsed", value: "\(elapsedMinutes)m", isAlert: false)
                    TenderSummaryTile(title: "Estimate", value: inr(estimateMinor), isAlert: false)
                    if let label = timerLabel(now: context.date) {
                        TenderSummaryTile(title: "Timer", value: label.text, isAlert: isOvertime)
                    }
                }

                HStack(spacing: 8) {
                    if session.timer_minutes != nil {
                        Button(action: onExtend) {
                            Label("+15m", systemImage: "plus.circle")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(PressableButtonStyle())
                        .disabled(isSubmitting)

                        Button(action: onClearTimer) {
                            Label("Clear timer", systemImage: "timer.square")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(PressableButtonStyle())
                        .disabled(isSubmitting)
                    } else {
                        Button(action: onExtend) {
                            Label("Set 30m timer", systemImage: "timer")
                                .font(.caption.weight(.semibold))
                        }
                        .buttonStyle(PressableButtonStyle())
                        .disabled(isSubmitting)
                    }
                }
                .foregroundColor(Brand.softGold)
            }
            .padding(12)
            .background(Brand.elevated)
            .overlay(
                RoundedRectangle(cornerRadius: 15, style: .continuous)
                    .stroke(isOvertime ? Brand.danger.opacity(0.6) : Color.clear, lineWidth: 1.5)
            )
            .clipShape(RoundedRectangle(cornerRadius: 15, style: .continuous))
        }
    }
}

private struct GamingSessionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: GamingSessionDraft

    let activeShift: ShiftDTO?
    let isSubmitting: Bool
    let onStart: (GamingSessionDraft) -> Void

    init(
        draft: GamingSessionDraft,
        activeShift: ShiftDTO?,
        isSubmitting: Bool,
        onStart: @escaping (GamingSessionDraft) -> Void
    ) {
        self._draft = State(initialValue: draft)
        self.activeShift = activeShift
        self.isSubmitting = isSubmitting
        self.onStart = onStart
    }

    var body: some View {
        NavigationView {
            ZStack {
                Brand.background.ignoresSafeArea()
                ScrollView {
                    VStack(spacing: 16) {
                        BrandedCard {
                            HStack(spacing: 14) {
                                Image(systemName: draft.station.kind.icon)
                                    .font(.title2)
                                    .foregroundColor(Brand.gold)
                                    .frame(width: 44, height: 44)
                                    .background(Brand.gold.opacity(0.12))
                                    .clipShape(RoundedRectangle(cornerRadius: 13, style: .continuous))
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(draft.station.name)
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Text("\(draft.station.kind.title) - \(inr(draft.station.rate_per_hour_minor))/hr")
                                        .font(.caption)
                                        .foregroundColor(Brand.muted)
                                }
                                Spacer()
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 14) {
                                Text("Session")
                                    .font(.headline)
                                    .foregroundColor(.white)

                                Stepper(value: $draft.partySize, in: 1...12) {
                                    HStack {
                                        Label(draft.station.kind.participantLabel, systemImage: "person.2")
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundColor(.white)
                                        Spacer()
                                        Text("\(draft.partySize)")
                                            .font(.headline.weight(.bold))
                                            .foregroundColor(Brand.softGold)
                                    }
                                }
                                .tint(Brand.gold)

                                TextField("Customer name optional", text: $draft.customerName)
                                    .textInputAutocapitalization(.words)
                                    .nativeField()

                                TextField("Phone optional", text: $draft.customerPhone)
                                    .keyboardType(.phonePad)
                                    .nativeField()
                            }
                        }

                        BrandedCard {
                            VStack(alignment: .leading, spacing: 12) {
                                Text("Timer")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                HStack(spacing: 8) {
                                    ForEach(TimerPreset.allCases) { preset in
                                        let isSelected = draft.timerMinutes == preset.minutes
                                        Button {
                                            Haptics.selection()
                                            draft.timerMinutes = preset.minutes
                                        } label: {
                                            Text(preset.title)
                                                .font(.caption.weight(.bold))
                                                .frame(maxWidth: .infinity)
                                                .padding(.vertical, 10)
                                                .background(isSelected ? Brand.gold : Brand.surface)
                                                .foregroundColor(isSelected ? .black : Brand.muted)
                                                .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                                        }
                                        .buttonStyle(.plain)
                                    }
                                }
                            }
                        }

                        BrandedCard {
                            CheckoutReadinessRow(
                                title: "POS shift",
                                value: activeShift == nil ? "Open a shift before starting" : "Ready",
                                isReady: activeShift != nil,
                                icon: "clock.badge.checkmark"
                            )
                        }
                    }
                    .padding(16)
                }
            }
            .navigationTitle("Start session")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                Button {
                    Haptics.selection()
                    onStart(draft)
                } label: {
                    HStack {
                        if isSubmitting {
                            ProgressView()
                                .tint(.black)
                        }
                        Text(activeShift == nil ? "Open shift first" : "Start session")
                            .font(.headline)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 15)
                }
                .buttonStyle(.plain)
                .foregroundColor(.black)
                .background(canStart ? Brand.gold : Brand.muted.opacity(0.45))
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .padding(16)
                .background(.ultraThinMaterial)
                .disabled(!canStart)
            }
        }
        .navigationViewStyle(.stack)
    }

    private var canStart: Bool {
        activeShift != nil && !isSubmitting
    }
}

private struct OrderHistoryRow: View {
    let order: OrderListItemDTO

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "receipt")
                .foregroundColor(Brand.gold)
                .frame(width: 28)

            VStack(alignment: .leading, spacing: 7) {
                HStack(spacing: 8) {
                    Text(order.invoice_no ?? "Order \(order.id.prefix(8))")
                        .font(.headline)
                        .foregroundColor(.white)
                        .lineLimit(1)
                    Text(readableAction(order.status))
                        .font(.caption2.weight(.bold))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(Brand.gold.opacity(0.14))
                        .foregroundColor(Brand.softGold)
                        .clipShape(Capsule())
                }

                if let customer = order.customer_name, !customer.isEmpty {
                    Text(customer)
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                        .lineLimit(1)
                }

                Text(DateFormatters.shortDateTime.string(from: order.created_at))
                    .font(.caption2)
                    .foregroundColor(Brand.muted.opacity(0.78))
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 5) {
                Text(inr(order.total_minor))
                    .font(.headline)
                    .foregroundColor(Brand.softGold)
                Text("\(order.items_count) items")
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
            }
        }
        .padding(.vertical, 8)
    }
}

private struct CustomerRow: View {
    let customer: CustomerDTO

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "person.crop.circle.fill")
                .font(.title3)
                .foregroundColor(Brand.gold)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 5) {
                Text(customer.name?.isEmpty == false ? customer.name! : customer.phone)
                    .font(.headline)
                    .foregroundColor(.white)
                    .lineLimit(1)

                Text([customer.phone, customer.email].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " - "))
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)

                if let lastVisit = customer.last_visit_at {
                    Text("Last visit \(DateFormatters.shortDateTime.string(from: lastVisit))")
                        .font(.caption2)
                        .foregroundColor(Brand.muted.opacity(0.78))
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 5) {
                Text(inr(customer.total_spent_minor))
                    .font(.subheadline.weight(.bold))
                    .foregroundColor(Brand.softGold)
                Text("\(customer.visit_count) visits")
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
                Text("\(customer.loyalty_points) pts")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.success)
            }
        }
        .padding(.vertical, 8)
    }
}

private struct StaffRow: View {
    let user: StaffUserDTO

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: user.status == "active" ? "person.badge.key.fill" : "person.crop.circle.badge.exclamationmark")
                .font(.title3)
                .foregroundColor(user.status == "active" ? Brand.gold : Brand.danger)
                .frame(width: 30)

            VStack(alignment: .leading, spacing: 6) {
                Text(user.name)
                    .font(.headline)
                    .foregroundColor(.white)
                    .lineLimit(1)
                Text(user.email)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)

                HStack(spacing: 6) {
                    ForEach(user.roles.prefix(3), id: \.self) { role in
                        Text(readableAction(role))
                            .font(.caption2.weight(.semibold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Brand.gold.opacity(0.14))
                            .foregroundColor(Brand.softGold)
                            .clipShape(Capsule())
                    }
                }
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 5) {
                Text(readableAction(user.status))
                    .font(.caption.weight(.bold))
                    .foregroundColor(user.status == "active" ? Brand.success : Brand.danger)
                if let lastLogin = user.last_login_at {
                    Text(DateFormatters.shortDateTime.string(from: lastLogin))
                        .font(.caption2)
                        .foregroundColor(Brand.muted)
                        .multilineTextAlignment(.trailing)
                }
            }
        }
        .padding(.vertical, 8)
    }
}

private struct AttendanceCard: View {
    let isClockedIn: Bool
    let clockInAt: Date?
    let onShiftNames: [String]
    let isLoading: Bool
    let isSubmitting: Bool
    let error: String?
    let onClockIn: () -> Void
    let onClockOut: () -> Void

    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Attendance")
                            .font(.headline)
                            .foregroundColor(.white)
                        Text(statusSubtitle)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                    }
                    Spacer()
                    Text(isClockedIn ? "On shift" : "Off shift")
                        .font(.caption.weight(.bold))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background((isClockedIn ? Brand.success : Brand.muted).opacity(0.16))
                        .foregroundColor(isClockedIn ? Brand.success : Brand.muted)
                        .clipShape(Capsule())
                }

                if let error {
                    ErrorBanner(message: error)
                }

                if isClockedIn {
                    Button(action: onClockOut) {
                        Label(isSubmitting ? "Please wait..." : "Clock out", systemImage: "clock.badge.xmark")
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(Brand.softGold)
                    .background(Brand.elevated)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 14, style: .continuous)
                            .stroke(Brand.gold.opacity(0.35), lineWidth: 1)
                    )
                    .disabled(isSubmitting)
                } else {
                    Button(action: onClockIn) {
                        Label(isSubmitting ? "Please wait..." : "Clock in", systemImage: "clock.badge.checkmark")
                            .font(.subheadline.weight(.bold))
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 12)
                    }
                    .buttonStyle(.plain)
                    .foregroundColor(.black)
                    .background(isSubmitting ? Brand.muted.opacity(0.45) : Brand.gold)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(isSubmitting)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text(rosterTitle)
                        .font(.caption2.weight(.semibold))
                        .foregroundColor(Brand.muted)

                    if !isLoading {
                        if onShiftNames.isEmpty {
                            Text("Nobody is clocked in right now.")
                                .font(.caption)
                                .foregroundColor(Brand.muted)
                        } else {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 8) {
                                    ForEach(Array(onShiftNames.enumerated()), id: \.offset) { _, name in
                                        Text(name)
                                            .font(.caption2.weight(.semibold))
                                            .padding(.horizontal, 10)
                                            .padding(.vertical, 6)
                                            .background(Brand.gold.opacity(0.14))
                                            .foregroundColor(Brand.softGold)
                                            .clipShape(Capsule())
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private var statusSubtitle: String {
        guard isClockedIn else { return "You are not clocked in right now" }
        guard let clockInAt else { return "You are clocked in" }
        return "You clocked in at \(DateFormatters.timeOnly.string(from: clockInAt))"
    }

    private var rosterTitle: String {
        isLoading ? "Loading on-shift roster..." : "On shift now (\(onShiftNames.count))"
    }
}

private struct SettingsFactRow: View {
    let title: String
    let value: String
    let isReady: Bool

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: isReady ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                .foregroundColor(isReady ? Brand.success : Brand.danger)
                .frame(width: 26)
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundColor(.white)
            Spacer()
            Text(value.isEmpty ? "Not set" : value)
                .font(.caption.weight(.semibold))
                .foregroundColor(isReady ? Brand.muted : Brand.danger)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct BranchRow: View {
    let branch: BranchDTO

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "mappin.and.ellipse")
                    .foregroundColor(Brand.gold)
                VStack(alignment: .leading, spacing: 2) {
                    Text(branch.name)
                        .font(.headline)
                        .foregroundColor(.white)
                    Text([branch.code, branch.state_code].compactMap { $0?.isEmpty == false ? $0 : nil }.joined(separator: " - "))
                        .font(.caption)
                        .foregroundColor(Brand.muted)
                }
                Spacer()
            }

            if let address = branch.address, !address.isEmpty {
                Text(address)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(2)
            }

            HStack(spacing: 8) {
                MiniReadinessPill(title: "FSSAI", value: branch.fssai_license_no)
                MiniReadinessPill(title: "Trade", value: branch.trade_license_no)
                MiniReadinessPill(title: "GST", value: branch.branch_gstin)
            }
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct TerminalRow: View {
    let terminal: TerminalDTO
    let onDelete: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "iphone.gen3")
                .foregroundColor(Brand.gold)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 4) {
                Text(terminal.name)
                    .font(.headline)
                    .foregroundColor(.white)
                Text(terminal.device_id?.isEmpty == false ? terminal.device_id! : "No device id")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
                    .lineLimit(1)
            }
            Spacer()
            if let lastSeen = terminal.last_seen_at {
                Text(DateFormatters.shortDateTime.string(from: lastSeen))
                    .font(.caption2)
                    .foregroundColor(Brand.muted)
                    .multilineTextAlignment(.trailing)
            } else {
                Text("Not seen")
                    .font(.caption2.weight(.semibold))
                    .foregroundColor(Brand.danger)
            }
            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundColor(Brand.danger)
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .background(Brand.elevated)
        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct MiniReadinessPill: View {
    let title: String
    let value: String?

    private var isReady: Bool {
        value?.isEmpty == false
    }

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: isReady ? "checkmark.circle.fill" : "xmark.circle.fill")
                .font(.caption2)
            Text(title)
                .font(.caption2.weight(.bold))
        }
        .foregroundColor(isReady ? Brand.success : Brand.danger)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background((isReady ? Brand.success : Brand.danger).opacity(0.12))
        .clipShape(Capsule())
    }
}

private struct LoadingBlock: View {
    let title: String

    var body: some View {
        BrandedCard {
            HStack(spacing: 12) {
                ProgressView()
                    .tint(Brand.gold)
                Text(title)
                    .foregroundColor(Brand.muted)
                Spacer()
            }
        }
    }
}

private struct AppNavigation<Content: View>: View {
    private let content: () -> Content

    init(@ViewBuilder content: @escaping () -> Content) {
        self.content = content
    }

    var body: some View {
        if #available(iOS 16.0, *) {
            NavigationStack {
                content()
            }
            .premiumNavigationChrome()
        } else {
            NavigationView {
                content()
            }
            .navigationViewStyle(.stack)
        }
    }
}

private enum Haptics {
    static func selection() {
        UISelectionFeedbackGenerator().selectionChanged()
    }

    static func impact() {
        UIImpactFeedbackGenerator(style: .light).impactOccurred()
    }

    static func success() {
        UINotificationFeedbackGenerator().notificationOccurred(.success)
    }
}

private struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.965 : 1)
            .brightness(configuration.isPressed ? -0.025 : 0)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.interactiveSpring(response: 0.18, dampingFraction: 0.82), value: configuration.isPressed)
    }
}

private struct QuickActionButton: View {
    let title: String
    let subtitle: String
    let icon: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            BrandedCard {
                VStack(alignment: .leading, spacing: 12) {
                    Image(systemName: icon)
                        .font(.title3)
                        .foregroundColor(Brand.gold)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(title)
                            .font(.headline)
                            .foregroundColor(.white)
                            .lineLimit(1)
                            .minimumScaleFactor(0.82)
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                            .lineLimit(1)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .buttonStyle(PressableButtonStyle())
    }
}

private struct MetricsSkeletonGrid: View {
    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        LazyVGrid(columns: columns, spacing: 12) {
            ForEach(0..<4, id: \.self) { _ in
                MetricSkeletonCard()
            }
        }
    }
}

private struct MetricSkeletonCard: View {
    var body: some View {
        BrandedCard {
            VStack(alignment: .leading, spacing: 12) {
                SkeletonLine(width: 28, height: 20)
                SkeletonLine(height: 24)
                SkeletonLine(width: 86, height: 10)
                SkeletonLine(width: 64, height: 8)
            }
        }
    }
}

private struct ReportsSkeletonView: View {
    var body: some View {
        VStack(spacing: 12) {
            BrandedCard {
                VStack(alignment: .leading, spacing: 14) {
                    SkeletonLine(width: 110, height: 16)
                    ForEach(0..<4, id: \.self) { _ in
                        HStack {
                            SkeletonLine(width: 112, height: 12)
                            Spacer()
                            SkeletonLine(width: 78, height: 12)
                        }
                    }
                }
            }
            MetricsSkeletonGrid()
        }
    }
}

private struct MenuItemSkeletonRow: View {
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 8) {
                SkeletonLine(width: 136, height: 14)
                SkeletonLine(width: 92, height: 10)
            }
            Spacer()
            SkeletonLine(width: 74, height: 24)
        }
        .padding(.vertical, 10)
    }
}

private struct InventorySkeletonRow: View {
    var body: some View {
        HStack(spacing: 12) {
            SkeletonLine(width: 28, height: 28)
            VStack(alignment: .leading, spacing: 8) {
                SkeletonLine(width: 132, height: 14)
                SkeletonLine(width: 96, height: 10)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 8) {
                SkeletonLine(width: 48, height: 14)
                SkeletonLine(width: 68, height: 9)
            }
        }
        .padding(.vertical, 10)
    }
}

private struct AuditSkeletonRow: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                VStack(alignment: .leading, spacing: 8) {
                    SkeletonLine(width: 118, height: 14)
                    SkeletonLine(width: 156, height: 9)
                }
                Spacer()
                SkeletonLine(width: 82, height: 9)
            }
            HStack {
                SkeletonLine(width: 82, height: 22)
                SkeletonLine(width: 68, height: 10)
                Spacer()
            }
        }
        .padding(.vertical, 10)
    }
}

private struct SkeletonLine: View {
    var width: CGFloat?
    let height: CGFloat

    var body: some View {
        RoundedRectangle(cornerRadius: height / 2, style: .continuous)
            .fill(Brand.elevated)
            .overlay(
                RoundedRectangle(cornerRadius: height / 2, style: .continuous)
                    .fill(Brand.gold.opacity(0.08))
            )
            .frame(width: width, height: height)
            .redacted(reason: .placeholder)
    }
}

private struct InlineEmptyRow: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundColor(Brand.gold)
                .frame(width: 32)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.headline)
                    .foregroundColor(.white)
                Text(subtitle)
                    .font(.caption)
                    .foregroundColor(Brand.muted)
            }
            Spacer()
        }
        .padding(.vertical, 14)
    }
}

private struct InlineEmptyCard: View {
    let icon: String
    let title: String
    let subtitle: String

    var body: some View {
        BrandedCard {
            InlineEmptyRow(icon: icon, title: title, subtitle: subtitle)
        }
    }
}

private struct RefreshableScrollView<Content: View>: View {
    let refresh: () async -> Void
    @ViewBuilder let content: Content

    var body: some View {
        if #available(iOS 15.0, *) {
            ScrollView {
                content
            }
            .background(Brand.appGradient)
            .refreshable {
                await refresh()
            }
        } else {
            ScrollView {
                content
            }
            .background(Brand.background)
        }
    }
}

private struct NativeFieldModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(14)
            .foregroundColor(.white)
            .background(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Brand.elevated)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .stroke(Brand.gold.opacity(0.22), lineWidth: 1)
            )
    }
}

private extension View {
    func nativeField() -> some View {
        modifier(NativeFieldModifier())
    }

    @ViewBuilder
    func premiumTabChrome() -> some View {
        if #available(iOS 16.0, *) {
            self
                .tint(Brand.gold)
                .toolbarBackground(Brand.background.opacity(0.96), for: .tabBar)
                .toolbarBackground(.visible, for: .tabBar)
                .toolbarColorScheme(.dark, for: .tabBar)
        } else {
            self
                .accentColor(Brand.gold)
        }
    }

    @ViewBuilder
    func premiumNavigationChrome() -> some View {
        if #available(iOS 16.0, *) {
            self
                .toolbarBackground(Brand.background.opacity(0.96), for: .navigationBar)
                .toolbarBackground(.visible, for: .navigationBar)
                .toolbarColorScheme(.dark, for: .navigationBar)
        } else {
            self
        }
    }

    @ViewBuilder
    func premiumListChrome() -> some View {
        if #available(iOS 16.0, *) {
            self
                .scrollContentBackground(.hidden)
                .background(Brand.appGradient)
        } else {
            self
                .background(Brand.background)
        }
    }
}

private func sectionHeader(_ title: String) -> some View {
    Text(title)
        .font(.caption.weight(.bold))
        .foregroundColor(Brand.gold)
        .textCase(nil)
}

private func inr(_ minor: Int) -> String {
    let rupees = Double(minor) / 100
    return NumberFormatters.inr.string(from: NSNumber(value: rupees)) ?? "INR \(String(format: "%.2f", rupees))"
}

private func currencyInput(_ minor: Int) -> String {
    let rupees = Double(minor) / 100
    if rupees.rounded() == rupees {
        return String(format: "%.0f", rupees)
    }
    return String(format: "%.2f", rupees)
}

private func taxInput(_ rate: Double) -> String {
    let percent = rate <= 1 ? rate * 100 : rate
    if percent.rounded() == percent {
        return String(format: "%.0f", percent)
    }
    return String(format: "%.2f", percent)
}

private func minorFromCurrencyInput(_ value: String) throws -> Int {
    let cleaned = value
        .replacingOccurrences(of: "₹", with: "")
        .replacingOccurrences(of: ",", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !cleaned.isEmpty, let amount = Double(cleaned), amount >= 0 else {
        throw InputParseError.invalidMoney
    }
    return Int((amount * 100).rounded())
}

private func taxRateFromInput(_ value: String) throws -> Double {
    let cleaned = value
        .replacingOccurrences(of: "%", with: "")
        .trimmingCharacters(in: .whitespacesAndNewlines)
    guard !cleaned.isEmpty, let percent = Double(cleaned), percent >= 0, percent <= 100 else {
        throw InputParseError.invalidTax
    }
    return percent / 100
}

private func decimalString(_ value: Double) -> String {
    return NumberFormatters.decimal.string(from: NSNumber(value: value)) ?? String(format: "%.2f", value)
}

private func lineValueMinor(qty: Double, avgCostMinor: Int) -> Int {
    max(0, Int((qty * Double(avgCostMinor)).rounded()))
}

private func readable(_ error: Error) -> String {
    if let error = error as? LocalizedError, let message = error.errorDescription {
        return message
    }
    return error.localizedDescription
}

private func readableAction(_ value: String) -> String {
    value.replacingOccurrences(of: "_", with: " ").capitalized
}

private enum InputParseError: LocalizedError {
    case invalidMoney
    case invalidTax
    case invalidStationName
    case invalidStationCode

    var errorDescription: String? {
        switch self {
        case .invalidMoney:
            return "Enter a valid rupee amount, for example 180 or 180.50."
        case .invalidTax:
            return "Enter GST as a percentage from 0 to 100, for example 5, 12, or 18."
        case .invalidStationName:
            return "Station name is required."
        case .invalidStationCode:
            return "Station code is required."
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
