/** Structural regression guards for the active monolith; these are not iOS runtime/UI proof. */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(process.cwd(), 'ios/App/App/DCompanyNativeApp.swift'), 'utf8');
function section(start: string, end: string): string {
  const from = source.indexOf(start);
  const to = source.indexOf(end, from + start.length);
  expect(from).toBeGreaterThanOrEqual(0);
  expect(to).toBeGreaterThan(from);
  return source.slice(from, to);
}

describe('active iOS financial admission wiring', () => {
  it('uses explicit effective permissions instead of owner/module inference for finance reads', () => {
    const session = section('private final class AppSession:', 'struct NativeERPAppView:');
    expect(source).toContain('let effective_permissions: [String]?');
    expect(session).toContain('me?.effective_permissions?.contains(permission) == true');
    expect(session).toContain('hasEffectivePermission("analytics.read") && canReadFinanceReports');
    expect(session).toContain('var canReadFinanceReports: Bool { hasEffectivePermission("finance.read") }');
    expect(session).toContain('generation: sessionGeneration, companyId: me.company_id');
  });
  it('rotates before login publication and fences stale refresh failure before signout', () => {
    const login = section('    func login(email:', '    func signOut()');
    expect(login.indexOf('sessionGeneration = UUID()')).toBeLessThan(login.indexOf('save(token)'));
    expect(login).toContain('guard sessionGeneration == generation else { return }');
    const refresh = section('    private func refresh() async throws', '    private func save(');
    expect(refresh).not.toContain('sessionGeneration = UUID()');
    expect(refresh).toContain('guard sessionGeneration == generation else { throw CancellationError() }');
    const signout = section('    func signOut()', '    func resolveTerminalClaim(');
    expect(signout).toContain('sessionGeneration = UUID()');
  });
  it('serializes persisted financial invalidation and rejects older snapshot writes', () => {
    const store = section('private actor OfflineSnapshotStore', 'private enum');
    expect(store).toContain('guard revision >= latestRevision else { return }');
    expect(store).toContain('dailyReportScope: FinanceReadScope?');
    expect(store).not.toContain('Task.detached');
    const cache = section('private final class AppCache:', 'private final class AppSession:');
    expect(cache).toContain('if dailyReportScope != scope');
    expect(cache).toContain('financeRevision += 1');
    expect(cache).toContain('dailyReport = nil');
    expect(cache).toContain('await markSynced()');
  });
  it('wires current Analytics denial to shared finance invalidation before any old response can be admitted', () => {
    const analytics = section('private struct AnalyticsNativeView:', 'private enum InsightsTab:');
    expect(analytics).toContain('session.financeReadScope == scope && requestID == request && revision == cache.financeRevision');
    const failure = analytics.slice(analytics.lastIndexOf('} catch {'));
    expect(failure).toContain('guard current() else { return }');
    expect(failure).toContain('if (error as? DCompanyAPIError)?.isAccessDenied == true');
    expect(failure).toContain('storedData = nil');
    expect(failure).toContain('dataScope = nil');
    expect(failure).toContain('dataRevision = nil');
    expect(failure).toContain('await cache.invalidateFinance(scope: scope)');
    expect(failure.indexOf('guard current()')).toBeLessThan(failure.indexOf('await cache.invalidateFinance'));
    expect(failure.indexOf('isLoading = false')).toBeLessThan(failure.indexOf('await cache.invalidateFinance'));
  });
  it('keeps finance-only P&L and printing admitted while omitting the analytics-only tax supplement', () => {
    const reports = section('private struct ReportsNativeView:', 'private struct SimpleBarChart:');
    const taxGate = reports.indexOf('guard session.hasEffectivePermission("analytics.read") else {');
    const taxRequest = reports.indexOf('let loadedTax: TaxComplianceDTO');
    expect(taxGate).toBeGreaterThan(reports.indexOf('storedReport = loadedReport'));
    expect(taxRequest).toBeGreaterThan(taxGate);
    const omittedTax = reports.slice(taxGate, taxRequest);
    expect(omittedTax).toContain('taxCompliance = nil');
    expect(omittedTax).toContain('return');
    expect(omittedTax).not.toContain('clearReport');
    expect(omittedTax).not.toContain('invalidateFinance');
    expect(reports).toContain('if session.hasEffectivePermission("analytics.read"), let taxCompliance');
    expect(reports).toContain('if let report { ReportPrinter.print(report: report, periodTitle: period.title) }');
    expect(reports).toContain('.disabled(report == nil)');
  });
  it('binds report/cache/print admission and invalidation to the current session and request', () => {
    const reports = section('private struct ReportsNativeView:', 'private struct SimpleBarChart:');
    expect(reports).toContain('reportScope == scope, reportPeriod == period, reportRevision == cache.financeRevision');
    expect(reports).toContain('cache.deniedFinanceScope != scope');
    expect(reports).toContain('reportRequestID == requestID && period == requestedPeriod');
    expect(reports).toContain('guard current(), revision == cache.financeRevision else { return }');
    expect(reports).toContain('await cache.invalidateFinance(scope: scope)');
    expect(reports).toContain('taxCompliance = nil');
    expect(reports).toContain('shareFile = nil');
    expect(reports).toContain('.disabled(report == nil)');
    expect(reports).not.toContain('taxCompliance = try?');
    const dashboard = section('private struct DashboardNativeView:', 'private struct WorkspaceNativeView:');
    expect(dashboard).toContain('cache.dailyReportScope == scope');
    expect(dashboard).toContain('await cache.invalidateFinance(scope: scope)');
    expect(dashboard).toContain('guard current(), revision == cache.financeRevision else { return }');
  });
});
