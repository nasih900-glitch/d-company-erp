import SwiftUI
import Security
import UIKit
import Network
import Vision
import VisionKit

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

        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let detail = json["detail"] {
            if let text = detail as? String {
                return text
            }
            if let dict = detail as? [String: Any],
               let message = dict["message"] as? String {
                return message
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
}

private struct ShiftDTO: Decodable, Identifiable {
    let id: String
    let terminal_id: String?
    let status: String
    let opened_at: Date
    let closed_at: Date?
    let opening_float_minor: Int
    let expected_minor: Int?
    let counted_minor: Int?
    let variance_minor: Int?
}

private struct TerminalDTO: Decodable, Identifiable {
    let id: String
    let branch_id: String
    let name: String
    let device_id: String?
    let last_seen_at: Date?
}

private struct OrderListItemDTO: Decodable, Identifiable {
    let id: String
    let invoice_no: String?
    let status: String
    let total_minor: Int
    let items_count: Int
    let customer_name: String?
    let created_at: Date
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
    let lines: [OrderLineReadDTO]
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

    private var accessToken: String?
    private var refreshToken: String?

    var displayRole: String {
        me?.roles.first?.replacingOccurrences(of: "_", with: " ").capitalized ?? "Owner"
    }

    var canSeeAudit: Bool {
        me?.protected_access == true
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
        status = .restoring
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
                            if session.status == .restoring {
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
                    .disabled(email.isEmpty || password.isEmpty || session.status == .restoring)
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
    case inventory
    case reports
    case audit

    var title: String {
        switch self {
        case .dashboard: return "Home"
        case .pos: return "POS"
        case .inventory: return "Stock"
        case .reports: return "Reports"
        case .audit: return "Audit"
        }
    }

    var icon: String {
        switch self {
        case .dashboard: return "house"
        case .pos: return "cart"
        case .inventory: return "cube.box"
        case .reports: return "chart.bar"
        case .audit: return "shield"
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

            InventoryNativeView()
                .tabItem { Label(NativeTab.inventory.title, systemImage: NativeTab.inventory.icon) }
                .tag(NativeTab.inventory)

            ReportsNativeView()
                .tabItem { Label(NativeTab.reports.title, systemImage: NativeTab.reports.icon) }
                .tag(NativeTab.reports)

            if session.canSeeAudit {
                AuditNativeView()
                    .tabItem { Label(NativeTab.audit.title, systemImage: NativeTab.audit.icon) }
                    .tag(NativeTab.audit)
            }
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
                        isOnline: network.isOnline,
                        connectionLabel: network.connectionLabel
                    )

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 14) {
                            Text("Operations")
                                .font(.headline)
                                .foregroundColor(.white)
                            HStack(spacing: 12) {
                                StatusPill(title: "Menu", value: "\(menuCount)", icon: "menucard")
                                StatusPill(title: "Low Stock", value: "\(lowStockCount)", icon: "exclamationmark.triangle")
                            }
                        }
                    }

                    LazyVGrid(columns: twoColumns, spacing: 12) {
                        QuickActionButton(title: "New bill", subtitle: "Open POS", icon: "cart.badge.plus") {
                            openTab(.pos)
                        }
                        QuickActionButton(title: "Stock check", subtitle: "Inventory", icon: "cube.box") {
                            openTab(.inventory)
                        }
                        QuickActionButton(title: "P&L", subtitle: "Reports", icon: "chart.line.uptrend.xyaxis") {
                            openTab(.reports)
                        }
                        if session.canSeeAudit {
                            QuickActionButton(title: "Activity", subtitle: "Audit", icon: "shield.checkered") {
                                openTab(.audit)
                            }
                        }
                    }

                    BrandedCard {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Workspace")
                                .font(.headline)
                                .foregroundColor(.white)
                                .padding(.bottom, 4)

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
        report != nil || menuCount > 0 || lowStockCount > 0 || cache.dailyReport != nil || cache.hasMenuData || cache.hasInventoryData
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
            async let loadedIngredients: [IngredientDTO] = session.authorized { token in
                try await APIClient.shared.get("inventory/ingredients", token: token)
            }

            let (freshReport, freshItems, freshIngredients) = try await (loadedReport, loadedItems, loadedIngredients)
            withAnimation(.easeOut(duration: 0.18)) {
                report = freshReport
                menuCount = freshItems.count
                lowStockCount = freshIngredients.filter(\.isLowStock).count
                cache.dailyReport = freshReport
                cache.menuItems = freshItems
                cache.ingredients = freshIngredients
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
        if cache.hasInventoryData {
            lowStockCount = cache.ingredients.filter(\.isLowStock).count
        }
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
    @State private var selectedCategory: String?
    @State private var search = ""
    @State private var cart: [String: Int] = [:]
    @State private var checkoutDraft: CheckoutDraft?
    @State private var createdInvoice: String?
    @State private var lastChargedOrder: OrderReadDTO?
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

                categoryScroller
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 8)
                .background(Brand.background)

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

                List {
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
                                    Haptics.impact()
                                    withAnimation(.spring(response: 0.22, dampingFraction: 0.85)) {
                                        cart[item.id, default: 0] += 1
                                    }
                                } decrement: {
                                    Haptics.selection()
                                    withAnimation(.spring(response: 0.22, dampingFraction: 0.85)) {
                                        let next = max((cart[item.id] ?? 0) - 1, 0)
                                        cart[item.id] = next == 0 ? nil : next
                                    }
                                }
                                .listRowBackground(Brand.background)
                                .listRowSeparatorTint(Brand.hairline)
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
                    isSubmitting: isSubmitting
                ) { updatedDraft in
                    Task { await submitCheckout(updatedDraft) }
                }
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

    private var activeShift: ShiftDTO? {
        shifts.first { $0.status == "open" } ?? shifts.first
    }

    private var activeTerminal: TerminalDTO? {
        guard let activeShift else { return terminals.first }
        if let terminalID = activeShift.terminal_id {
            return terminals.first { $0.id == terminalID } ?? terminals.first
        }
        return terminals.first
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

            let (freshCategories, freshItems, freshShifts, freshTerminals) = try await (
                loadedCategories,
                loadedItems,
                loadedShifts,
                loadedTerminals
            )

            withAnimation(.easeOut(duration: 0.18)) {
                cache.categories = freshCategories
                cache.menuItems = freshItems
                categories = freshCategories
                items = freshItems.filter(\.is_available)
                shifts = freshShifts
                terminals = freshTerminals
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
            items = cache.menuItems.filter(\.is_available)
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
                "X-Idempotency-Key": UUID().uuidString,
                "X-Terminal-Id": terminal.id
            ]
            let order: OrderReadDTO = try await session.authorized { token in
                try await APIClient.shared.post("pos/orders", body: orderRequest, token: token, headers: orderHeaders)
            }

            let paymentRequest = PaymentCreateRequest(
                method: draft.paymentMethod.rawValue,
                amount_minor: order.total_minor,
                tendered_minor: draft.paymentMethod == .cash ? order.total_minor : nil,
                ref_external: nil
            )
            let paymentHeaders = [
                "X-Idempotency-Key": UUID().uuidString,
                "X-Terminal-Id": terminal.id
            ]
            let _: PaymentResponseDTO = try await session.authorized { token in
                try await APIClient.shared.post("pos/orders/\(order.id)/payments", body: paymentRequest, token: token, headers: paymentHeaders)
            }

            checkoutDraft = nil
            cart.removeAll()
            createdInvoice = order.invoice_no ?? "Order \(order.id.prefix(8))"
            lastChargedOrder = order
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

private struct CheckoutSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var draft: CheckoutDraft

    let cartRows: [CartLine]
    let shift: ShiftDTO?
    let terminal: TerminalDTO?
    let totalMinor: Int
    let isSubmitting: Bool
    let onCharge: (CheckoutDraft) -> Void

    init(
        draft: CheckoutDraft,
        cartRows: [CartLine],
        shift: ShiftDTO?,
        terminal: TerminalDTO?,
        totalMinor: Int,
        isSubmitting: Bool,
        onCharge: @escaping (CheckoutDraft) -> Void
    ) {
        self._draft = State(initialValue: draft)
        self.cartRows = cartRows
        self.shift = shift
        self.terminal = terminal
        self.totalMinor = totalMinor
        self.isSubmitting = isSubmitting
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
                        Text("Charge \(inr(totalMinor))")
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
        !isSubmitting && shift != nil && terminal != nil && !cartRows.isEmpty
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
        ZStack {
            Circle()
                .fill(Color.black)
            Circle()
                .stroke(Brand.gold, lineWidth: size * 0.055)
            Circle()
                .stroke(Brand.gold.opacity(0.45), lineWidth: 1)
                .padding(size * 0.12)
            Text("D")
                .font(.system(size: size * 0.48, weight: .black, design: .rounded))
                .foregroundColor(Brand.gold)
        }
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
                    OperationalSignalRow(
                        title: "Inventory",
                        value: lowStockCount == 0 ? "No low stock" : "\(lowStockCount) low",
                        detail: lowStockCount == 0 ? "Stock risk looks clear" : "Review reorder list before service",
                        isReady: lowStockCount == 0,
                        icon: lowStockCount == 0 ? "cube.box.fill" : "exclamationmark.triangle.fill"
                    )
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

private struct MenuItemRow: View {
    let item: MenuItemDTO
    let quantity: Int
    let increment: () -> Void
    let decrement: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.headline)
                    .foregroundColor(.white)
                Text("\(item.type.capitalized) - \(inr(item.base_price_minor))")
                    .font(.caption)
                    .foregroundColor(Brand.muted)
            }
            Spacer()
            HStack(spacing: 10) {
                if quantity > 0 {
                    Button(action: decrement) {
                        Image(systemName: "minus.circle.fill")
                    }
                    Text("\(quantity)")
                        .font(.headline)
                        .foregroundColor(.white)
                        .frame(minWidth: 24)
                }
                Button(action: increment) {
                    Image(systemName: "plus.circle.fill")
                        .font(.title3)
                }
            }
            .foregroundColor(Brand.gold)
        }
        .padding(.vertical, 8)
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

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }
}
