"""Seed Kerala / India reference data.

Populates the in_* tables that the tax engine reads from:
  - in_state_codes        — 36 Indian state/UT codes
  - in_gst_rate_slabs     — canonical rate slabs
  - in_hsn_codes          — café-relevant HSN subset
  - in_sac_codes          — café + gaming SAC subset
  - in_kerala_pt_slabs    — Kerala professional-tax half-yearly slabs (FY 2026-27)

Plus extends the existing CoA seed with India-specific accounts (output
CGST/SGST/IGST, input ITC, RCM, TDS, EPF, ESI, PT, MDR, tips payable, etc).

Run from the backend/ directory:
    python -m scripts.seed_india
"""

from __future__ import annotations

import asyncio
from uuid import uuid4

from sqlalchemy import select

from app.core.db import AsyncSessionLocal
from app.models import (
    Account,
    Company,
    GstRateSlab,
    HsnCode,
    KeralaPTSlab,
    SacCode,
    StateCode,
)

# ---------------------------------------------------------------------------
# State / UT codes used in GSTIN and place-of-supply.
# Code = first two chars of every GSTIN issued in that state.
# Special category states get the lower (₹10 lakh) GST registration threshold.
# ---------------------------------------------------------------------------
STATES: list[tuple[str, str, bool, bool]] = [
    # (code, name, is_ut, is_special_category)
    ("01", "Jammu and Kashmir", False, False),
    ("02", "Himachal Pradesh", False, True),
    ("03", "Punjab", False, False),
    ("04", "Chandigarh", True, False),
    ("05", "Uttarakhand", False, True),
    ("06", "Haryana", False, False),
    ("07", "Delhi", True, False),
    ("08", "Rajasthan", False, False),
    ("09", "Uttar Pradesh", False, False),
    ("10", "Bihar", False, False),
    ("11", "Sikkim", False, True),
    ("12", "Arunachal Pradesh", False, True),
    ("13", "Nagaland", False, True),
    ("14", "Manipur", False, True),
    ("15", "Mizoram", False, True),
    ("16", "Tripura", False, True),
    ("17", "Meghalaya", False, True),
    ("18", "Assam", False, True),
    ("19", "West Bengal", False, False),
    ("20", "Jharkhand", False, False),
    ("21", "Odisha", False, False),
    ("22", "Chhattisgarh", False, False),
    ("23", "Madhya Pradesh", False, False),
    ("24", "Gujarat", False, False),
    ("25", "Daman and Diu", True, False),  # merged into 26 in 2020 but old GSTINs persist
    ("26", "Dadra and Nagar Haveli and Daman and Diu", True, False),
    ("27", "Maharashtra", False, False),
    ("29", "Karnataka", False, False),
    ("30", "Goa", False, False),
    ("31", "Lakshadweep", True, False),
    ("32", "Kerala", False, False),  # ← D Company home state
    ("33", "Tamil Nadu", False, False),
    ("34", "Puducherry", True, False),
    ("35", "Andaman and Nicobar Islands", True, False),
    ("36", "Telangana", False, False),
    ("37", "Andhra Pradesh", False, False),
    ("38", "Ladakh", True, False),
    ("97", "Other Territory", True, False),
    ("99", "Centre (for inter-state ITC)", True, False),
]

# ---------------------------------------------------------------------------
# Canonical GST headline rates. CGST + SGST = rate ÷ 2.
# ---------------------------------------------------------------------------
GST_SLABS: list[tuple[float, str, str]] = [
    (0.0000, "Exempt", "Goods exempt from GST (some fresh produce, books, healthcare)"),
    (0.0025, "0.25%", "Cut & semi-precious stones"),
    (0.0300, "3%", "Gold, silver, jewellery"),
    (0.0500, "5%", "Standalone restaurants, packaged food essentials, transport"),
    (0.1200, "12%", "Processed food, computers, some textiles"),
    (0.1800, "18%", "Most services, electronics, AC restaurants in hotels, gaming"),
    (0.2800, "28%", "Luxury, demerit goods (tobacco, large cars, aerated drinks)"),
]

# ---------------------------------------------------------------------------
# HSN catalogue subset relevant to a café.
# Pulled from the Customs Tariff. 6-digit subheading.
# ---------------------------------------------------------------------------
HSN_CODES: list[tuple[str, str, float]] = [
    # Coffee, tea (chapter 09)
    ("090111", "Coffee, not roasted, not decaffeinated", 0.05),
    ("090121", "Coffee, roasted, not decaffeinated", 0.05),
    ("090122", "Coffee, roasted, decaffeinated", 0.05),
    ("090210", "Tea, green, not fermented", 0.05),
    ("090230", "Tea, black, fermented (in packs ≤3kg)", 0.05),
    # Sugar, confectionery (chapter 17, 18)
    ("170199", "Refined sugar, in solid form", 0.05),
    ("180310", "Cocoa paste, not defatted", 0.05),
    ("180620", "Chocolate, in blocks/slabs > 2kg", 0.18),
    ("180690", "Chocolate confectionery (boxed, etc.)", 0.18),
    # Bakery (chapter 19)
    ("190120", "Mixes & doughs for bread/pastry preparation", 0.18),
    ("190531", "Sweet biscuits", 0.18),
    ("190540", "Rusks, toasted bread", 0.05),
    ("190590", "Other pastries, cakes, croissants", 0.18),
    # Prepared/preserved (chapter 21)
    ("210690", "Food preparations not elsewhere specified (sauces, etc.)", 0.18),
    ("210112", "Coffee extracts/concentrates with sugar", 0.18),
    # Beverages (chapter 22)
    ("220110", "Mineral & aerated waters, not sweetened", 0.18),
    ("220210", "Aerated drinks (Coke/Pepsi)", 0.28),
    ("220290", "Other non-alcoholic beverages (juice, smoothies)", 0.12),
    # Ice cream (chapter 21)
    ("210500", "Ice cream and other edible ice", 0.18),
    # Dairy used as ingredient (chapter 04)
    ("040120", "Milk, fat ≤6%, not concentrated", 0.05),
    ("040221", "Milk powder, unsweetened", 0.05),
]

# ---------------------------------------------------------------------------
# SAC catalogue subset.
# ---------------------------------------------------------------------------
SAC_CODES: list[tuple[str, str, float]] = [
    ("996331", "Restaurant service (food & beverage at premises)", 0.05),
    ("996334", "Outdoor catering", 0.05),
    ("996337", "Other contract food services", 0.18),
    ("999692", "Amusement, recreation & entertainment services", 0.18),
    ("997319", "Renting of equipment without operator", 0.18),
    ("998596", "Events / convention / trade-show services", 0.18),
    ("998599", "Other support services n.e.c.", 0.18),
    ("997212", "Renting of immovable property (commercial)", 0.18),
]

# ---------------------------------------------------------------------------
# Kerala Professional Tax — half-yearly slabs (FY 2026-27 baseline).
# Effective from 2025-04-01. Source: Kerala Municipality Amended Act, 2015.
# Salary range refers to GROSS for the half-year (Apr-Sep / Oct-Mar).
# ---------------------------------------------------------------------------
PT_SLABS: list[tuple[int, int | None, int]] = [
    # (min_half_year_salary, max_half_year_salary, half_year_pt)
    (0, 11_999, 0),
    (12_000, 17_999, 120),
    (18_000, 29_999, 180),
    (30_000, 44_999, 300),
    (45_000, 59_999, 450),
    (60_000, 74_999, 600),
    (75_000, 99_999, 750),
    (100_000, 124_999, 1_000),
    (125_000, None, 1_250),
]

# ---------------------------------------------------------------------------
# India-specific accounts to add to the Chart of Accounts. Codes follow
# the convention seeded in scripts/seed.py (1xxx assets, 2xxx liabilities, etc).
# ---------------------------------------------------------------------------
INDIA_ACCOUNTS: list[dict] = [
    # Liabilities
    {"code": "2110", "name": "Output CGST Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2120", "name": "Output SGST Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2130", "name": "Output IGST Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2140", "name": "Output Cess Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2150", "name": "RCM GST Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2200", "name": "TDS Payable (192 Salary)", "type": "liability", "normal_side": "cr"},
    {"code": "2210", "name": "TDS Payable (194C Contractor)", "type": "liability", "normal_side": "cr"},
    {"code": "2220", "name": "TDS Payable (194I Rent)", "type": "liability", "normal_side": "cr"},
    {"code": "2230", "name": "TDS Payable (194J Professional)", "type": "liability", "normal_side": "cr"},
    {"code": "2300", "name": "EPF Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2310", "name": "ESI Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2320", "name": "Professional Tax Payable", "type": "liability", "normal_side": "cr"},
    {"code": "2400", "name": "Tips Payable to Staff", "type": "liability", "normal_side": "cr"},
    {"code": "2500", "name": "Gratuity Provision", "type": "liability", "normal_side": "cr"},
    # Assets — Input Tax Credit
    {"code": "1310", "name": "Input CGST Credit (ITC)", "type": "asset", "normal_side": "dr"},
    {"code": "1320", "name": "Input SGST Credit (ITC)", "type": "asset", "normal_side": "dr"},
    {"code": "1330", "name": "Input IGST Credit (ITC)", "type": "asset", "normal_side": "dr"},
    {"code": "1340", "name": "TCS Receivable (Aggregator)", "type": "asset", "normal_side": "dr"},
    # Expense — payment processing & compliance
    {"code": "5600", "name": "MDR (Card Charges)", "type": "expense", "normal_side": "dr"},
    {"code": "5610", "name": "UPI Charges", "type": "expense", "normal_side": "dr"},
    {"code": "5620", "name": "Aggregator Commission (Zomato/Swiggy)", "type": "expense", "normal_side": "dr"},
    {"code": "5700", "name": "FSSAI Licence Fee", "type": "expense", "normal_side": "dr"},
    {"code": "5710", "name": "Trade Licence Fee", "type": "expense", "normal_side": "dr"},
    {"code": "5800", "name": "Round-off (Income/Expense)", "type": "expense", "normal_side": "dr"},
]


async def seed_india() -> None:
    async with AsyncSessionLocal() as s:
        # ---- state codes ----
        existing_states = (await s.execute(select(StateCode.code))).scalars().all()
        if not existing_states:
            for code, name, is_ut, is_special in STATES:
                s.add(
                    StateCode(
                        code=code,
                        name=name,
                        is_union_territory=is_ut,
                        is_special_category=is_special,
                    )
                )
            print(f"  seeded {len(STATES)} state codes (Kerala = 32)")
        else:
            print(f"  state codes already present ({len(existing_states)}); skipping")

        # ---- GST rate slabs ----
        existing_slabs = (await s.execute(select(GstRateSlab.rate))).scalars().all()
        if not existing_slabs:
            for rate, label, desc in GST_SLABS:
                s.add(GstRateSlab(id=uuid4(), rate=rate, label=label, description=desc))
            print(f"  seeded {len(GST_SLABS)} GST rate slabs")

        # ---- HSN codes ----
        existing_hsn = (await s.execute(select(HsnCode.code))).scalars().all()
        if not existing_hsn:
            for code, desc, rate in HSN_CODES:
                s.add(
                    HsnCode(
                        code=code,
                        description=desc,
                        default_gst_rate=rate,
                        chapter=code[:2],
                    )
                )
            print(f"  seeded {len(HSN_CODES)} HSN codes (café-relevant subset)")

        # ---- SAC codes ----
        existing_sac = (await s.execute(select(SacCode.code))).scalars().all()
        if not existing_sac:
            for code, desc, rate in SAC_CODES:
                s.add(SacCode(code=code, description=desc, default_gst_rate=rate))
            print(f"  seeded {len(SAC_CODES)} SAC codes")

        # ---- Kerala PT slabs ----
        existing_pt = (await s.execute(select(KeralaPTSlab.id))).scalars().all()
        if not existing_pt:
            for lo, hi, pt in PT_SLABS:
                s.add(
                    KeralaPTSlab(
                        id=uuid4(),
                        min_half_year_salary=lo,
                        max_half_year_salary=hi,
                        half_year_pt=pt,
                        effective_from="2025-04-01",
                        effective_to=None,
                    )
                )
            print(f"  seeded {len(PT_SLABS)} Kerala PT slabs")

        # ---- India-specific accounts ----
        company = (await s.execute(select(Company).limit(1))).scalar_one_or_none()
        if company is None:
            print("  ⚠ no company found — run scripts/seed.py first, then re-run seed_india")
        else:
            existing_codes = {
                c
                for (c,) in (
                    await s.execute(
                        select(Account.code).where(Account.company_id == company.id)
                    )
                ).all()
            }
            added = 0
            for a in INDIA_ACCOUNTS:
                if a["code"] not in existing_codes:
                    s.add(Account(id=uuid4(), company_id=company.id, **a))
                    added += 1
            if added:
                print(f"  added {added} India-specific accounts to Chart of Accounts")
            else:
                print("  India accounts already present in CoA; skipping")

        await s.commit()
        print("seed_india: done.")


if __name__ == "__main__":
    asyncio.run(seed_india())
