import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import type { CostingCoverageDTO, DashboardKPIsDTO } from '@/lib/erp-api';
import { OwnerCommandCenter } from './AnalyticsScreen';

const data: DashboardKPIsDTO = {
  date: '2026-09-05', revenue_food_minor: 9000, revenue_gaming_minor: 14000,
  revenue_hookah_minor: 0, revenue_events_minor: 0, revenue_memberships_minor: 0,
  revenue_manual_collections_minor: 0, discounts_and_points_redeemed_minor: 300,
  revenue_total_minor: 22700, orders_count: 2, tickets_count: 0,
  avg_ticket_minor: 11350, inventory_value_minor: 0, low_stock_items: 0,
  open_sessions: 0, net_profit_minor: 22700,
};
const complete: CostingCoverageDTO = {
  inventory_item_count: 0, fully_costed_item_count: 0, incomplete_item_count: 0,
  missing_recipe_count: 0, empty_recipe_count: 0, missing_ingredient_cost_count: 0,
  is_complete: true, issues: [],
};

function render(costing: CostingCoverageDTO | null) {
  return renderToStaticMarkup(<StaticRouter location="/"><OwnerCommandCenter data={data} costing={costing} canSeeProtected={false}/></StaticRouter>);
}

describe('dashboard profit confidence', () => {
  it('never presents incomplete product costs as a verified 100 percent margin', () => {
    const html = render({...complete, is_complete:false, incomplete_item_count:2});
    expect(html).toContain('Provisional');
    expect(html).toContain('Needs attention');
    expect(html).not.toContain('100.0%');
    expect(html).not.toContain('Stable');
  });
  it('marks failed costing verification as unknown', () => {
    const html = render(null);
    expect(html).toContain('Cost check needed');
    expect(html).not.toContain('100.0%');
  });
  it('distinguishes explicitly verified zero costs from missing costs', () => {
    const html = render(complete);
    expect(html).toContain('100.0%');
    expect(html).toContain('Stable');
  });
});
