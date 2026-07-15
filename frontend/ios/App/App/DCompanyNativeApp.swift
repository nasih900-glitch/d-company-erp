import SwiftUI
import Security
import UIKit
import Network
import Vision
import VisionKit
import AudioToolbox
import CoreImage

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

    private let baseURL = URL(string: "https://dcompany.duckdns.org/api/v1/")!

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

private struct MenuItemDTO: Codable, Identifiable, Hashable {
    let id: String
    let category_id: String?
    let sku: String
    let name: String
    let type: String
    let base_price_minor: Int
    let tax_rate: Double
    let is_available: Bool
    let description: String?
}

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

private struct MoneyBucketDTO: Codable {
    let total_minor: Int
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
    let revenue: MoneyBucketDTO
    let tax_collected: MoneyBucketDTO
    let payments_received: MoneyBucketDTO
    let expense_total_minor: Int
    let gross_revenue_minor: Int
    let net_revenue_minor: Int
    let net_profit_minor: Int
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

private struct CheckoutDraft: Identifiable {
    let id = UUID()
    var serviceType: OrderServiceType = .dineIn
    var paymentMethod: PaymentMethod = .cash
    var customerName = ""
    var customerPhone = ""
    var note = ""
    var cashTenderedMinor: Int?
    var printReceiptAfterCharge = true

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

private struct EmptyRequest: Encodable {}

private struct ShiftOpenRequest: Encodable {
    let opening_float_minor: Int
}

private struct ShiftOpenResponse: Decodable {
    let id: String
    let status: String
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

private struct PaymentCreateRequest: Encodable {
    let method: String
    let amount_minor: Int
    let tendered_minor: Int?
    let ref_external: String?
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
    let birthday: String?
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

private enum ReceiptPrinter {
    @MainActor
    static func print(order: OrderReadDTO) {
        present(text: receiptText(for: order), jobName: "D Company \(order.invoice_no ?? order.id.prefix(8).description)")
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

    private static func receiptText(for order: OrderReadDTO) -> String {
        var lines: [String] = [
            "D Company",
            "Cafe | Games | Lounge | After Dark",
            "Invoice: \(order.invoice_no ?? order.id)",
            "Type: \(order.type.replacingOccurrences(of: "_", with: " ").capitalized)",
            String(repeating: "-", count: 32)
        ]

        for item in order.lines {
            let quantity = NumberFormatters.decimal.string(from: NSNumber(value: item.qty)) ?? "\(item.qty)"
            lines.append("\(item.name) x \(quantity)")
            lines.append("  \(inr(item.line_total_minor))")
        }

        lines.append(String(repeating: "-", count: 32))
        lines.append("Subtotal: \(inr(order.subtotal_minor))")
        if order.discount_minor > 0 {
            lines.append("Discount: -\(inr(order.discount_minor))")
        }
        lines.append("CGST: \(inr(order.cgst_minor))")
        lines.append("SGST: \(inr(order.sgst_minor))")
        if order.igst_minor > 0 {
            lines.append("IGST: \(inr(order.igst_minor))")
        }
        lines.append("Round off: \(inr(order.round_off_minor))")
        lines.append("Total: \(inr(order.total_minor))")
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
            me = try await authorized { token in
                try await APIClient.shared.get("auth/me", token: token)
            }
            status = .signedIn
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
            me = try await APIClient.shared.get("auth/me", token: token.access_token)
            status = .signedIn
        } catch {
            status = .signedOut
            lastError = readable(error)
        }
    }

    func signOut() {
        TokenStore.delete("access_token")
        TokenStore.delete("refresh_token")
        accessToken = nil
        refreshToken = nil
        me = nil
        status = .signedOut
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

private struct LoginView: View {
    @EnvironmentObject private var session: AppSession
    @EnvironmentObject private var network: NetworkMonitor
    @State private var email = ""
    @State private var password = ""
    @FocusState private var focusedField: Field?

    private enum Field {
        case email
        case password
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Spacer(minLength: 36)
                LogoBadge(size: 92)
                VStack(spacing: 8) {
                    Text("D Company ERP")
                        .font(.system(size: 32, weight: .bold, design: .rounded))
                        .foregroundColor(.white)
                    Text("Cafe, lounge, games, and finance")
                        .font(.subheadline)
                        .foregroundColor(Brand.muted)
                }

                if !network.isOnline {
                    NetworkBanner(label: network.connectionLabel)
                }

                VStack(spacing: 16) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Email")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                        TextField("name@dcompany.local", text: $email)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .focused($focusedField, equals: .email)
                            .submitLabel(.next)
                            .onSubmit { focusedField = .password }
                            .nativeField()
                    }

                    VStack(alignment: .leading, spacing: 8) {
                        Text("Password")
                            .font(.caption)
                            .foregroundColor(Brand.muted)
                        SecureField("Password", text: $password)
                            .focused($focusedField, equals: .password)
                            .submitLabel(.go)
                            .onSubmit { Task { await signIn() } }
                            .nativeField()
                    }

                    if let error = session.lastError {
                        Text(error)
                            .font(.footnote.weight(.semibold))
                            .foregroundColor(Brand.danger)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button {
                        Task { await signIn() }
                    } label: {
                        HStack {
                            if session.isAuthenticating {
                                ProgressView()
                                    .tint(.black)
                            }
                            Text("Sign in")
                                .font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 15)
                    }
                    .buttonStyle(.plain)
                    .background(Brand.gold)
                    .foregroundColor(.black)
                    .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .disabled(email.isEmpty || password.isEmpty || session.isAuthenticating)
                    .opacity(email.isEmpty || password.isEmpty ? 0.6 : 1)
                }
                .padding(20)
                .background(Brand.surface)
                .overlay(
                    RoundedRectangle(cornerRadius: 24, style: .continuous)
                        .stroke(Brand.gold.opacity(0.35), lineWidth: 1)
                )
                .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            }
            .padding(24)
        }
        .background(Brand.appGradient)
    }

    private func signIn() async {
        await session.login(email: email.trimmingCharacters(in: .whitespacesAndNewlines), password: password)
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

                            if session.canSeeTables {
                                NavigationLink {
                                    TablesNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Tables", subtitle: "Build dine-in orders, send to kitchen and POS", icon: "table.furniture")
                                }
                            }
                            NavigationLink {
                                KitchenNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Kitchen", subtitle: "Live order queue, received to served", icon: "flame")
                            }
                            NavigationLink {
                                MenuCatalogNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Menu", subtitle: "Catalog, GST rate, and sale price view", icon: "menucard")
                            }
                            if session.canSeeInventory {
                                NavigationLink {
                                    InventoryNativeView()
                                } label: {
                                    WorkspaceLinkRow(title: "Inventory", subtitle: "Stock, reorder alerts, and ingredients", icon: "cube.box")
                                }
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
                            NavigationLink {
                                KitchenNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Kitchen", subtitle: "Live order queue, received to served", icon: "flame")
                            }
                            NavigationLink {
                                MenuCatalogNativeView()
                            } label: {
                                WorkspaceLinkRow(title: "Menu", subtitle: "Catalog, GST rate, and sale price view", icon: "menucard")
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
        terminals.first
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
                    queryItems: [URLQueryItem(name: "table_id", value: table.id), URLQueryItem(name: "limit", value: "5")]
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

// Forward-only KDS: received -> preparing -> ready -> served. Auto-polls
// every 3s like the web KitchenScreen, since this is a kiosk-style display
// meant to stay open, not a screen someone actively refreshes.
private struct KitchenNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var orders: [KitchenOrderDTO] = []
    @State private var isLoading = true
    @State private var error: String?

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
            try? await Task.sleep(nanoseconds: 3_000_000_000)
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
                    } else if let data {
                        ForEach(data.modules, id: \.self) { module in
                            BrandedCard {
                                VStack(alignment: .leading, spacing: 10) {
                                    Text(module.replacingOccurrences(of: "_", with: " ").capitalized)
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
                                    MenuCatalogRow(item: item, categoryName: categoryName(for: item.category_id))
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
            .background(Brand.background)
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
                AudioServicesPlaySystemSound(1005)
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
    @State private var selectedCategory: String?
    @State private var search = ""
    @State private var cart: [String: Int] = [:]
    @State private var checkoutDraft: CheckoutDraft?
    @State private var sessionDraft: GamingSessionDraft?
    @State private var posMode: POSMode = .all
    @State private var createdInvoice: String?
    @State private var lastChargedOrder: OrderReadDTO?
    @State private var finishedSession: GamingSessionDTO?
    @State private var sessionBillNote: String?
    @State private var isLoading = true
    @State private var isSubmitting = false
    @State private var error: String?

    var body: some View {
        AppNavigation {
            VStack(spacing: 0) {
                if !network.isOnline {
                    NetworkBanner(label: network.connectionLabel)
                        .padding(.horizontal, 16)
                        .padding(.top, 10)
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
                                ReceiptPrinter.print(order: lastChargedOrder)
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
                    }
                )
            }
        }
        .task { await load() }
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
        guard let activeShift else { return terminals.first }
        if let terminalID = activeShift.terminal_id {
            return terminals.first { $0.id == terminalID } ?? terminals.first
        }
        return terminals.first
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
                    ]
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
            ref_external: nil
        )
        let headers = ["Idempotency-Key": UUID().uuidString, "X-Terminal-Id": terminal.id]
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
        guard let terminal = activeTerminal ?? terminals.first else {
            error = "No POS terminal is registered. Add a terminal in Settings before opening a shift."
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
        guard draft.isCashTenderReady(totalMinor: cartTotal) else {
            error = "Cash received is below the bill total."
            return
        }

        isSubmitting = true
        defer { isSubmitting = false }
        error = nil
        createdInvoice = nil
        lastChargedOrder = nil

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
            delivery_via: draft.serviceType == .delivery ? "inhouse" : nil,
            customer_name: draft.customerName.nilIfBlank,
            customer_phone: draft.customerPhone.nilIfBlank,
            customer_gstin: nil,
            customer_address: nil,
            customer_state_code: nil,
            place_of_supply_state_code: nil,
            notes: draft.note.nilIfBlank
        )

        do {
            let orderHeaders = [
                "Idempotency-Key": UUID().uuidString,
                "X-Terminal-Id": terminal.id
            ]
            let order: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.post("pos/orders", body: orderRequest, token: token, headers: orderHeaders)
            }

            let paymentRequest = PaymentCreateRequest(
                method: draft.paymentMethod.rawValue,
                amount_minor: order.total_minor,
                tendered_minor: draft.tenderedMinor(totalMinor: order.total_minor),
                ref_external: nil
            )
            let paymentHeaders = [
                "Idempotency-Key": UUID().uuidString,
                "X-Terminal-Id": terminal.id
            ]
            let _: PaymentResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post("pos/orders/\(order.id)/payments", body: paymentRequest, token: token, headers: paymentHeaders)
            }

            checkoutDraft = nil
            cart.removeAll()
            createdInvoice = order.invoice_no ?? "Order \(order.id.prefix(8))"
            lastChargedOrder = order
            if draft.printReceiptAfterCharge {
                await MainActor.run {
                    ReceiptPrinter.print(order: order)
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
    @State private var search = ""
    @State private var showLowOnly = false
    @State private var isLoading = true
    @State private var error: String?

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
                            InventoryRow(ingredient: ingredient)
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
            let loadedIngredients: [IngredientDTO] = try await session.authorized { token in
                try await APIClient.shared.get("inventory/ingredients", token: token)
            }
            withAnimation(.easeOut(duration: 0.18)) {
                ingredients = loadedIngredients
                cache.ingredients = loadedIngredients
            }
            await cache.markSynced()
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

                    if let report {
                        BrandedCard {
                            VStack(alignment: .leading, spacing: 16) {
                                Text("P&L Summary")
                                    .font(.headline)
                                    .foregroundColor(.white)
                                PNLRow(title: "Gross revenue", value: inr(report.gross_revenue_minor))
                                PNLRow(title: "Net revenue", value: inr(report.net_revenue_minor))
                                PNLRow(title: "Expenses", value: inr(report.expense_total_minor))
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
            }
            .background(Brand.background)
        }
        .task { await load() }
        .onChange(of: period) { _ in
            Task { await load() }
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

private struct OrdersNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var orders: [OrderListItemDTO] = []
    @State private var search = ""
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
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
        .navigationTitle("Orders")
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
        .task { await load() }
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
            orders = try await session.authorized { token in
                try await APIClient.shared.get(
                    "pos/orders",
                    token: token,
                    queryItems: [URLQueryItem(name: "limit", value: "80")]
                )
            }
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
                    CustomerRow(customer: customer)
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
                Button {
                    Haptics.selection()
                    Task { await load() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .background(Brand.background)
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
}

private struct StaffNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var users: [StaffUserDTO] = []
    @State private var search = ""
    @State private var isLoading = true
    @State private var error: String?

    var body: some View {
        List {
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
                    StaffRow(user: user)
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
                Button {
                    Haptics.selection()
                    Task { await load() }
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .background(Brand.background)
        .task { await load() }
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
}

private struct SettingsNativeView: View {
    @EnvironmentObject private var session: AppSession
    @State private var company: CompanyDTO?
    @State private var branches: [BranchDTO] = []
    @State private var terminals: [TerminalDTO] = []
    @State private var isLoading = true
    @State private var error: String?

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
                            Text("Tax Setup")
                                .font(.headline)
                                .foregroundColor(.white)
                            SettingsFactRow(title: "GSTIN", value: company?.gstin ?? "Not set", isReady: company?.gstin?.isEmpty == false)
                            SettingsFactRow(title: "PAN", value: company?.pan ?? "Not set", isReady: company?.pan?.isEmpty == false)
                            SettingsFactRow(title: "GST Type", value: company?.gst_registration_type.replacingOccurrences(of: "_", with: " ").capitalized ?? "Not set", isReady: company != nil)
                            SettingsFactRow(title: "Fiscal Year", value: "Starts month \(company?.fiscal_year_start_month ?? 4)", isReady: company != nil)
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Branches")
                                .font(.headline)
                                .foregroundColor(.white)
                            if branches.isEmpty {
                                InlineEmptyRow(icon: "mappin", title: "No branches", subtitle: "Add a branch before opening operations.")
                            } else {
                                ForEach(branches) { branch in
                                    BranchRow(branch: branch)
                                }
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 12) {
                            Text("Terminals")
                                .font(.headline)
                                .foregroundColor(.white)
                            if terminals.isEmpty {
                                InlineEmptyRow(icon: "iphone", title: "No terminals", subtitle: "A POS terminal is required for charging orders.")
                            } else {
                                ForEach(terminals) { terminal in
                                    TerminalRow(terminal: terminal)
                                }
                            }
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
                onComplete: { text in
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
    let onComplete: (String) -> Void
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
        private let onComplete: (String) -> Void
        private let onFailure: (String) -> Void
        private let onCancel: () -> Void

        init(onComplete: @escaping (String) -> Void, onFailure: @escaping (String) -> Void, onCancel: @escaping () -> Void) {
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
                DispatchQueue.main.async {
                    self.onComplete(text)
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
            if let entity = entityFilter(for: selectedArea) {
                query.append(URLQueryItem(name: "entity_type", value: entity))
            }
            entries = try await session.authorized { token in
                try await APIClient.shared.get("admin/audit", token: token, queryItems: query, headers: ["X-Audit-Token": auditToken])
            }
        } catch is CancellationError {
        } catch {
            self.error = readable(error)
        }
    }

    private func entityFilter(for area: String) -> String? {
        switch area {
        case "Login":
            return "User"
        case "POS":
            return "Order"
        case "Inventory":
            return "Inventory"
        case "Staff":
            return "Staff"
        case "Finance":
            return "Finance"
        case "Access":
            return "AuditAccess"
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
                                HStack {
                                    Text("Total")
                                        .font(.headline)
                                        .foregroundColor(.white)
                                    Spacer()
                                    Text(inr(totalMinor))
                                        .font(.title3.weight(.bold))
                                        .foregroundColor(Brand.softGold)
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
                                    CashTenderPad(totalMinor: totalMinor, tenderedMinor: $draft.cashTenderedMinor)
                                }
                                if draft.paymentMethod == .upi || draft.paymentMethod == .qr {
                                    UpiQRView(upiVpa: upiVpa, businessName: businessName, amountMinor: totalMinor)
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

    let order: OrderReadDTO
    let terminal: TerminalDTO?
    let isSubmitting: Bool
    let canVoid: Bool
    let upiVpa: String?
    let businessName: String
    let onBill: (PaymentMethod, Int?) -> Void
    let onVoid: (String) -> Void

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
