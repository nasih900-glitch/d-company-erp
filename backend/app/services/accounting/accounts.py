"""Canonical chart-of-accounts definitions used by seed and derived ledger.

A code must have exactly one meaning.  Keeping operational account metadata in
one registry prevents the ledger, chart API, and bootstrap seed from silently
assigning different names or types to the same number.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

AccountType = Literal["asset", "liability", "equity", "revenue", "expense"]
NormalSide = Literal["dr", "cr"]


@dataclass(frozen=True, slots=True)
class AccountDefinition:
    code: str
    name: str
    type: AccountType
    normal_side: NormalSide

    @property
    def ledger_tuple(self) -> tuple[str, str, str]:
        return self.code, self.name, self.type

    def seed_dict(self) -> dict[str, str]:
        return {
            "code": self.code,
            "name": self.name,
            "type": self.type,
            "normal_side": self.normal_side,
        }


CASH = AccountDefinition("1000", "Cash", "asset", "dr")
BANK = AccountDefinition("1010", "Bank", "asset", "dr")
CARD_CLEARING = AccountDefinition("1100", "Card Clearing", "asset", "dr")
UPI_CLEARING = AccountDefinition("1110", "UPI / QR Clearing", "asset", "dr")
WALLET_CLEARING = AccountDefinition("1120", "Wallet Clearing", "asset", "dr")
HISTORICAL_FUNDS = AccountDefinition(
    "1185", "Historical Funds Pending Reconciliation", "asset", "dr"
)
UNRECONCILED_SETTLEMENT = AccountDefinition("1190", "Unreconciled Settlement", "asset", "dr")
INVENTORY = AccountDefinition("1200", "Inventory", "asset", "dr")
POS_SETTLEMENT_CLEARING = AccountDefinition("1210", "POS Settlement Clearing", "asset", "dr")
HISTORICAL_SETUP_COSTS = AccountDefinition(
    "1490", "Historical Setup Costs Pending Classification", "asset", "dr"
)
FIXED_ASSETS = AccountDefinition("1500", "Fixed Assets", "asset", "dr")

ACCOUNTS_PAYABLE = AccountDefinition("2000", "Accounts Payable", "liability", "cr")
TAX_PAYABLE = AccountDefinition("2100", "Tax Payable", "liability", "cr")
CGST_PAYABLE = AccountDefinition("2110", "CGST Payable", "liability", "cr")
SGST_PAYABLE = AccountDefinition("2120", "SGST Payable", "liability", "cr")
IGST_PAYABLE = AccountDefinition("2130", "IGST Payable", "liability", "cr")
CESS_PAYABLE = AccountDefinition("2140", "Cess Payable", "liability", "cr")
STORE_CREDIT_LIABILITY = AccountDefinition("2160", "Store Credit Liability", "liability", "cr")
TIPS_PAYABLE = AccountDefinition("2400", "Tips Payable", "liability", "cr")

PARTNER_CAPITAL = AccountDefinition("3000", "Partner Capital", "equity", "cr")

SALES_REVENUE = AccountDefinition("4000", "POS Sales Revenue", "revenue", "cr")
COFFEE_REVENUE = AccountDefinition("4010", "Revenue — Coffee", "revenue", "cr")
DESSERT_REVENUE = AccountDefinition("4020", "Revenue — Desserts", "revenue", "cr")
SALES_RETURNS = AccountDefinition("4090", "Sales Returns", "revenue", "dr")
GAMING_REVENUE = AccountDefinition("4100", "Revenue — Gaming", "revenue", "cr")
HOOKAH_REVENUE = AccountDefinition("4150", "Revenue — Hookah", "revenue", "cr")
STREAMING_REVENUE = AccountDefinition("4160", "Revenue — Streaming", "revenue", "cr")
EVENT_REVENUE = AccountDefinition("4200", "Revenue — Events", "revenue", "cr")
MANUAL_COLLECTION_REVENUE = AccountDefinition("4300", "Manual Collection Revenue", "revenue", "cr")
ROUNDING_INCOME = AccountDefinition("4900", "Rounding Income", "revenue", "cr")

COST_OF_GOODS_SOLD = AccountDefinition("5000", "Cost of Goods Sold", "expense", "dr")
WAGES = AccountDefinition("5100", "Wages", "expense", "dr")
RENT = AccountDefinition("5200", "Rent", "expense", "dr")
DISCOUNTS_GIVEN = AccountDefinition("5210", "Discounts Given", "expense", "dr")
LOYALTY_POINTS_REDEEMED = AccountDefinition("5220", "Loyalty Points Redeemed", "expense", "dr")
UTILITIES = AccountDefinition("5300", "Utilities", "expense", "dr")
MARKETING = AccountDefinition("5400", "Marketing", "expense", "dr")
REPAIRS_MAINTENANCE = AccountDefinition("5500", "Repairs & Maintenance", "expense", "dr")
ROUNDING_EXPENSE = AccountDefinition("5800", "Rounding Expense", "expense", "dr")
OTHER_EXPENSES = AccountDefinition("5900", "Other Expenses", "expense", "dr")
FURNITURES = AccountDefinition("5901", "Furnitures", "expense", "dr")
DESIGN_ARCHITECTURE = AccountDefinition("5902", "Design & Architecture", "expense", "dr")
LABOUR_CHARGES = AccountDefinition("5903", "Labour Charges", "expense", "dr")
BUILDING_MATERIALS = AccountDefinition("5904", "Building Materials", "expense", "dr")
PAPERWORK_CHARGES = AccountDefinition("5905", "PaperWork Charges", "expense", "dr")
FOOD_WATER = AccountDefinition("5906", "Food & Water", "expense", "dr")
GAMING_EQUIPMENTS = AccountDefinition("5907", "Gaming Equipments", "expense", "dr")
KITCHEN_EQUIPMENTS = AccountDefinition("5908", "Kitchen Equipments", "expense", "dr")
TRANSPORTATION_COST = AccountDefinition("5909", "Transportation Cost", "expense", "dr")
CAFE_EQUIPMENTS = AccountDefinition("5910", "Cafe Equipments", "expense", "dr")
MISCELLANEOUS_EXPENSES = AccountDefinition("5911", "Others", "expense", "dr")


# The existing D Company categories were seeded without codes.  This mapping
# gives every category a stable, unique ledger meaning while preserving the
# names already shown in the UI.  It is also consumed by the reconciliation
# script so existing production rows and newly seeded companies converge on
# the same chart.
DEFAULT_EXPENSE_CATEGORY_ACCOUNTS: dict[str, AccountDefinition] = {
    account.name: account
    for account in (
        FURNITURES,
        DESIGN_ARCHITECTURE,
        UTILITIES,
        LABOUR_CHARGES,
        BUILDING_MATERIALS,
        RENT,
        PAPERWORK_CHARGES,
        FOOD_WATER,
        GAMING_EQUIPMENTS,
        KITCHEN_EQUIPMENTS,
        MARKETING,
        TRANSPORTATION_COST,
        WAGES,
        CAFE_EQUIPMENTS,
        MISCELLANEOUS_EXPENSES,
    )
}


DEFAULT_CHART_OF_ACCOUNTS: tuple[AccountDefinition, ...] = (
    CASH,
    BANK,
    CARD_CLEARING,
    UPI_CLEARING,
    WALLET_CLEARING,
    HISTORICAL_FUNDS,
    UNRECONCILED_SETTLEMENT,
    INVENTORY,
    POS_SETTLEMENT_CLEARING,
    HISTORICAL_SETUP_COSTS,
    FIXED_ASSETS,
    ACCOUNTS_PAYABLE,
    TAX_PAYABLE,
    CGST_PAYABLE,
    SGST_PAYABLE,
    IGST_PAYABLE,
    CESS_PAYABLE,
    STORE_CREDIT_LIABILITY,
    TIPS_PAYABLE,
    PARTNER_CAPITAL,
    SALES_REVENUE,
    COFFEE_REVENUE,
    DESSERT_REVENUE,
    SALES_RETURNS,
    GAMING_REVENUE,
    HOOKAH_REVENUE,
    STREAMING_REVENUE,
    EVENT_REVENUE,
    MANUAL_COLLECTION_REVENUE,
    ROUNDING_INCOME,
    COST_OF_GOODS_SOLD,
    WAGES,
    RENT,
    DISCOUNTS_GIVEN,
    LOYALTY_POINTS_REDEEMED,
    UTILITIES,
    MARKETING,
    REPAIRS_MAINTENANCE,
    ROUNDING_EXPENSE,
    OTHER_EXPENSES,
    FURNITURES,
    DESIGN_ARCHITECTURE,
    LABOUR_CHARGES,
    BUILDING_MATERIALS,
    PAPERWORK_CHARGES,
    FOOD_WATER,
    GAMING_EQUIPMENTS,
    KITCHEN_EQUIPMENTS,
    TRANSPORTATION_COST,
    CAFE_EQUIPMENTS,
    MISCELLANEOUS_EXPENSES,
)

ACCOUNT_BY_CODE: dict[str, AccountDefinition] = {
    account.code: account for account in DEFAULT_CHART_OF_ACCOUNTS
}
if len(ACCOUNT_BY_CODE) != len(DEFAULT_CHART_OF_ACCOUNTS):
    raise RuntimeError("duplicate account codes in canonical chart of accounts")


__all__ = [
    "ACCOUNT_BY_CODE",
    "DEFAULT_CHART_OF_ACCOUNTS",
    "DEFAULT_EXPENSE_CATEGORY_ACCOUNTS",
    "AccountDefinition",
]
