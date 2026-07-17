"""Audited source data for the July 2026 D Company reconciliation.

This module is deliberately data-only.  Values come from the owner's Google
Sheet and from a read-only production snapshot taken on 2026-07-16.  Keeping
the payload separate from the mutating script makes it possible to test every
count, total, identifier, and precondition without connecting to production.

Amounts are integer paise.  Sheet dates are preserved literally; in
particular rows 18 and 19 are 2026-04-02 and 2026-05-02 respectively.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal
from uuid import UUID

EXPECTED_COMPANY_ID = UUID("8f323fba-4358-45fe-9d3b-a8e0fae52993")
EXPECTED_COMPANY_NAME = "D Company"
RECONCILIATION_VERSION = "d-company-historical-reconciliation-v1"
TRANSACTIONS_SOURCE_REF = "historical-setup-reconciliation:2026-07-16"
TRANSACTIONS_SHEET_ROW_COUNT = 503
TRANSACTIONS_TOTAL_MINOR = 202_210_274
TRANSACTIONS_MATCHED_MINOR = 114_684_205


@dataclass(frozen=True, slots=True)
class PartnerTarget:
    id: UUID
    name: str
    ownership_share_pct: Decimal
    joined_at: datetime
    notes: str
    expected_rows: int
    expected_capital_minor: int


PARTNER_TARGETS = (
    PartnerTarget(
        id=UUID("42f76e81-db22-44c9-9d3e-3872f3a2d685"),
        name="Nasih",
        ownership_share_pct=Decimal("33.3334"),
        joined_at=datetime.fromisoformat("2025-12-06T00:00:00+00:00"),
        notes="Opening capital balance imported from Partner Investments sheet",
        expected_rows=14,
        expected_capital_minor=60_798_490,
    ),
    PartnerTarget(
        id=UUID("85689a3b-5946-4170-a16a-9154129a7afb"),
        name="Shemeer",
        ownership_share_pct=Decimal("33.3333"),
        joined_at=datetime.fromisoformat("2025-12-05T00:00:00+00:00"),
        notes="Opening capital balance imported from Partner Investments sheet",
        expected_rows=21,
        expected_capital_minor=71_000_000,
    ),
    PartnerTarget(
        id=UUID("392ae6dc-e902-4967-9df2-0ce61b17e324"),
        name="Rafi",
        ownership_share_pct=Decimal("33.3333"),
        joined_at=datetime.fromisoformat("2025-11-30T00:00:00+00:00"),
        notes="Opening capital balance imported from Partner Investments sheet",
        expected_rows=29,
        expected_capital_minor=75_144_300,
    ),
)


@dataclass(frozen=True, slots=True)
class CapitalImportRow:
    sheet_row: int
    business_date: date
    partner_name: str
    amount_minor: int
    original_note: str | None = None

    @property
    def source_ref(self) -> str:
        return f"dcompany:partner-investments:row:{self.sheet_row}"


CAPITAL_IMPORT_ROWS = (
    CapitalImportRow(2, date(2025, 11, 30), "Rafi", 500_000),
    CapitalImportRow(3, date(2025, 12, 5), "Shemeer", 1_820_000),
    CapitalImportRow(4, date(2025, 12, 6), "Nasih", 200_000),
    CapitalImportRow(5, date(2025, 12, 8), "Rafi", 400_000),
    CapitalImportRow(6, date(2025, 12, 10), "Rafi", 500_000),
    CapitalImportRow(7, date(2025, 12, 11), "Rafi", 50_000),
    CapitalImportRow(8, date(2025, 12, 18), "Rafi", 1_000_000),
    CapitalImportRow(9, date(2025, 12, 18), "Nasih", 3_000_000),
    CapitalImportRow(10, date(2026, 1, 6), "Rafi", 5_000_000),
    CapitalImportRow(11, date(2026, 1, 6), "Rafi", 3_000_000),
    CapitalImportRow(12, date(2026, 1, 6), "Nasih", 400_000),
    CapitalImportRow(13, date(2026, 1, 10), "Shemeer", 74_000),
    CapitalImportRow(14, date(2026, 1, 25), "Nasih", 3_000_000),
    CapitalImportRow(15, date(2026, 1, 30), "Rafi", 5_000_000),
    CapitalImportRow(16, date(2026, 2, 1), "Nasih", 2_000_000),
    CapitalImportRow(17, date(2026, 2, 1), "Rafi", 8_000_000),
    CapitalImportRow(18, date(2026, 4, 2), "Shemeer", 5_000_000),
    CapitalImportRow(19, date(2026, 5, 2), "Shemeer", 2_000_000),
    CapitalImportRow(20, date(2026, 2, 6), "Nasih", 1_000_000),
    CapitalImportRow(21, date(2026, 2, 10), "Shemeer", 5_000_000),
    CapitalImportRow(22, date(2026, 2, 10), "Rafi", 2_000_000),
    CapitalImportRow(
        23,
        date(2026, 2, 20),
        "Shemeer",
        5_000_000,
        "Direct transfer to chellan",
    ),
    CapitalImportRow(24, date(2026, 2, 23), "Nasih", 10_000_000),
    CapitalImportRow(
        25,
        date(2026, 3, 7),
        "Nasih",
        5_000_000,
        "Direct transfer to chellan",
    ),
    CapitalImportRow(
        26,
        date(2026, 3, 10),
        "Shemeer",
        5_000_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(
        27,
        date(2026, 3, 11),
        "Shemeer",
        5_000_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(28, date(2026, 3, 18), "Rafi", 1_000_000),
    CapitalImportRow(29, date(2026, 3, 30), "Rafi", 2_000_000),
    CapitalImportRow(30, date(2026, 3, 30), "Rafi", 4_000_000),
    CapitalImportRow(
        31,
        date(2026, 3, 30),
        "Rafi",
        4_450_000,
        "paid to My G - VR2",
    ),
    CapitalImportRow(
        32,
        date(2026, 3, 31),
        "Shemeer",
        5_000_000,
        "Transfer to rafi",
    ),
    CapitalImportRow(
        33,
        date(2026, 3, 31),
        "Rafi",
        2_000_000,
        "transfer to chellan",
    ),
    CapitalImportRow(
        34,
        date(2026, 3, 31),
        "Rafi",
        2_000_000,
        "transfer to sonu ramdan",
    ),
    CapitalImportRow(
        35,
        date(2026, 4, 1),
        "Nasih",
        5_000_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(
        36,
        date(2026, 4, 1),
        "Nasih",
        4_000_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(
        37,
        date(2026, 4, 3),
        "Nasih",
        10_000_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(38, date(2026, 4, 4), "Rafi", 333_200, "transfer to jio"),
    CapitalImportRow(
        39,
        date(2026, 4, 10),
        "Shemeer",
        1_000_000,
        "Transfer to chellan(salary)",
    ),
    CapitalImportRow(
        40,
        date(2026, 4, 10),
        "Rafi",
        3_729_300,
        "Tv amount paid Amazon",
    ),
    CapitalImportRow(41, date(2026, 4, 16), "Rafi", 3_000_000),
    CapitalImportRow(
        42,
        date(2026, 4, 16),
        "Shemeer",
        5_000_000,
        "Transfer to rafi",
    ),
    CapitalImportRow(43, date(2026, 4, 18), "Nasih", 5_000_000),
    CapitalImportRow(
        44,
        date(2026, 4, 23),
        "Shemeer",
        5_000_000,
        "Transfer to rafi",
    ),
    CapitalImportRow(45, date(2026, 4, 29), "Nasih", 7_698_490, "Bought TV"),
    CapitalImportRow(46, date(2026, 4, 30), "Rafi", 3_929_400, "Bought TV"),
    CapitalImportRow(
        47,
        date(2026, 5, 5),
        "Shemeer",
        1_000_000,
        "Chellan salary",
    ),
    CapitalImportRow(48, date(2026, 5, 9), "Shemeer", 4_000_000, "Sent to Rafi"),
    CapitalImportRow(49, date(2026, 5, 17), "Shemeer", 600_000, "Brand logo"),
    CapitalImportRow(
        50,
        date(2026, 5, 20),
        "Shemeer",
        4_500_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(51, date(2026, 5, 23), "Rafi", 6_853_400, "bought AC"),
    CapitalImportRow(
        52,
        date(2026, 5, 26),
        "Shemeer",
        5_000_000,
        "Transfer to chellan",
    ),
    CapitalImportRow(
        53,
        date(2026, 6, 1),
        "Rafi",
        3_345_400,
        "Bought Thrustmaster SIM",
    ),
    CapitalImportRow(
        54,
        date(2026, 6, 3),
        "Rafi",
        750_400,
        "Bought Racing seat",
    ),
    CapitalImportRow(
        55,
        date(2026, 6, 5),
        "Rafi",
        117_000,
        "Painting advance, gum, scooty petrol",
    ),
    CapitalImportRow(
        56,
        date(2026, 6, 6),
        "Shemeer",
        5_000_000,
        "Transfer to rafi",
    ),
    CapitalImportRow(57, date(2026, 6, 8), "Rafi", 3_450_000, "projector"),
    CapitalImportRow(58, date(2026, 6, 8), "Rafi", 953_000),
    CapitalImportRow(
        59,
        date(2026, 6, 10),
        "Rafi",
        3_800_000,
        "chaliyar steel bill",
    ),
    CapitalImportRow(
        60,
        date(2026, 6, 12),
        "Shemeer",
        1_000_000,
        "Chellan salary",
    ),
    CapitalImportRow(
        61,
        date(2026, 6, 12),
        "Rafi",
        2_584_800,
        "Land leveling fan exhaust curtain tube bush tube pipe utensils",
    ),
    CapitalImportRow(
        62,
        date(2026, 6, 12),
        "Shemeer",
        4_006_000,
        "Transfer to rafi",
    ),
    CapitalImportRow(
        63,
        date(2026, 6, 13),
        "Rafi",
        1_398_400,
        "Speaker projector screen",
    ),
    CapitalImportRow(
        64,
        date(2026, 6, 25),
        "Nasih",
        4_500_000,
        "transferred to rafi",
    ),
    CapitalImportRow(
        65,
        date(2026, 7, 14),
        "Shemeer",
        1_000_000,
        "Chellan salary",
    ),
)


@dataclass(frozen=True, slots=True)
class BadCapitalEntry:
    id: UUID
    partner_name: str
    amount_minor: int
    effective_at: datetime
    note: str


def _monthly_note(month: str) -> str:
    return f"Construction/setup cost, {month} (from owner's transactions sheet, 1/3 share)."


_TOP_UP_NOTE = (
    "Capital top-up to true up to actual total invested (₹20,69,427.90), equal split. "
    "Supersedes the earlier rough opening-balance estimate."
)


BAD_CAPITAL_ENTRIES = (
    BadCapitalEntry(
        UUID("09d11198-a084-492b-8aa8-1d9553ef2023"),
        "Rafi",
        166_667,
        datetime.fromisoformat("2025-11-01T06:30:00+00:00"),
        _monthly_note("Nov 2025"),
    ),
    BadCapitalEntry(
        UUID("624ceeaa-4efe-46a4-95c9-b27f725f71ed"),
        "Shemeer",
        166_666,
        datetime.fromisoformat("2025-11-01T06:30:00+00:00"),
        _monthly_note("Nov 2025"),
    ),
    BadCapitalEntry(
        UUID("b4d56b88-1d23-4f84-bee2-1d5ee84eefb3"),
        "Nasih",
        166_667,
        datetime.fromisoformat("2025-11-01T06:30:00+00:00"),
        _monthly_note("Nov 2025"),
    ),
    BadCapitalEntry(
        UUID("408788e3-c7c4-4d0d-9c22-5f1a2e8d24df"),
        "Rafi",
        2_048_933,
        datetime.fromisoformat("2025-12-01T06:30:00+00:00"),
        _monthly_note("Dec 2025"),
    ),
    BadCapitalEntry(
        UUID("6460fb3b-13a8-47a0-b6c6-9557c03eae40"),
        "Nasih",
        2_048_934,
        datetime.fromisoformat("2025-12-01T06:30:00+00:00"),
        _monthly_note("Dec 2025"),
    ),
    BadCapitalEntry(
        UUID("97dc2da5-8065-4b76-83e2-d4b135b7cd1a"),
        "Shemeer",
        2_048_933,
        datetime.fromisoformat("2025-12-01T06:30:00+00:00"),
        _monthly_note("Dec 2025"),
    ),
    BadCapitalEntry(
        UUID("95a4ab36-90d0-4806-aa23-aff6b9581143"),
        "Rafi",
        5_235_500,
        datetime.fromisoformat("2026-01-01T06:30:00+00:00"),
        _monthly_note("Jan 2026"),
    ),
    BadCapitalEntry(
        UUID("f4d65712-b3f3-471b-af8c-e97b406ec2f8"),
        "Shemeer",
        5_235_500,
        datetime.fromisoformat("2026-01-01T06:30:00+00:00"),
        _monthly_note("Jan 2026"),
    ),
    BadCapitalEntry(
        UUID("fb679d91-7a06-4f8b-a041-33ade97fd12d"),
        "Nasih",
        5_235_500,
        datetime.fromisoformat("2026-01-01T06:30:00+00:00"),
        _monthly_note("Jan 2026"),
    ),
    BadCapitalEntry(
        UUID("3e6d0390-52fe-4b60-9021-904b14d32cff"),
        "Nasih",
        11_336_900,
        datetime.fromisoformat("2026-02-01T06:30:00+00:00"),
        _monthly_note("Feb 2026"),
    ),
    BadCapitalEntry(
        UUID("be215980-6b3a-4299-b232-72bee2b67168"),
        "Shemeer",
        11_336_900,
        datetime.fromisoformat("2026-02-01T06:30:00+00:00"),
        _monthly_note("Feb 2026"),
    ),
    BadCapitalEntry(
        UUID("da775a7b-227c-4cbf-aa6c-01bdcedac9b0"),
        "Rafi",
        11_336_900,
        datetime.fromisoformat("2026-02-01T06:30:00+00:00"),
        _monthly_note("Feb 2026"),
    ),
    BadCapitalEntry(
        UUID("45e4a0ee-4445-4704-88aa-bd0b0b6153ca"),
        "Shemeer",
        11_869_043,
        datetime.fromisoformat("2026-03-01T06:30:00+00:00"),
        _monthly_note("Mar 2026"),
    ),
    BadCapitalEntry(
        UUID("49f7e574-a725-4c7c-865e-a4056ae1098f"),
        "Rafi",
        11_869_043,
        datetime.fromisoformat("2026-03-01T06:30:00+00:00"),
        _monthly_note("Mar 2026"),
    ),
    BadCapitalEntry(
        UUID("8eb7ec5f-f79f-405d-b5ac-1f461340c029"),
        "Nasih",
        11_869_044,
        datetime.fromisoformat("2026-03-01T06:30:00+00:00"),
        _monthly_note("Mar 2026"),
    ),
    BadCapitalEntry(
        UUID("61d95f07-c4ec-4077-992f-a473f22fb83c"),
        "Shemeer",
        18_008_700,
        datetime.fromisoformat("2026-04-01T06:30:00+00:00"),
        _monthly_note("Apr 2026"),
    ),
    BadCapitalEntry(
        UUID("a02546a7-aec8-44bf-8477-d3998cafff4e"),
        "Nasih",
        18_008_700,
        datetime.fromisoformat("2026-04-01T06:30:00+00:00"),
        _monthly_note("Apr 2026"),
    ),
    BadCapitalEntry(
        UUID("bc5f976c-4f64-407b-8f2f-f2a40e6f8b3c"),
        "Rafi",
        18_008_700,
        datetime.fromisoformat("2026-04-01T06:30:00+00:00"),
        _monthly_note("Apr 2026"),
    ),
    BadCapitalEntry(
        UUID("10a225ac-04d6-4dcc-83d1-cc2a5352bc14"),
        "Shemeer",
        8_773_348,
        datetime.fromisoformat("2026-05-01T06:30:00+00:00"),
        _monthly_note("May 2026"),
    ),
    BadCapitalEntry(
        UUID("a03191b9-0682-4c0f-a59b-2000027913be"),
        "Nasih",
        8_773_348,
        datetime.fromisoformat("2026-05-01T06:30:00+00:00"),
        _monthly_note("May 2026"),
    ),
    BadCapitalEntry(
        UUID("e8b227c0-7aef-4455-b294-ce2e903199cc"),
        "Rafi",
        8_773_348,
        datetime.fromisoformat("2026-05-01T06:30:00+00:00"),
        _monthly_note("May 2026"),
    ),
    BadCapitalEntry(
        UUID("93bb1919-c34e-48f8-a759-293d10333e3b"),
        "Rafi",
        9_964_333,
        datetime.fromisoformat("2026-06-01T06:30:00+00:00"),
        _monthly_note("Jun 2026"),
    ),
    BadCapitalEntry(
        UUID("a690b751-9981-4958-9f28-0f88bb178b1f"),
        "Nasih",
        9_964_334,
        datetime.fromisoformat("2026-06-01T06:30:00+00:00"),
        _monthly_note("Jun 2026"),
    ),
    BadCapitalEntry(
        UUID("d7c0ad7b-7a06-4952-b4a3-1204aad827aa"),
        "Shemeer",
        9_964_333,
        datetime.fromisoformat("2026-06-01T06:30:00+00:00"),
        _monthly_note("Jun 2026"),
    ),
    BadCapitalEntry(
        UUID("024dd600-0b57-42b9-b3af-83c1f8f0f4a1"),
        "Rafi",
        1_577_506,
        datetime.fromisoformat("2026-07-16T16:36:51.177724+00:00"),
        _TOP_UP_NOTE,
    ),
    BadCapitalEntry(
        UUID("aa53f033-caf7-40f8-8721-ac42437dada7"),
        "Shemeer",
        1_577_507,
        datetime.fromisoformat("2026-07-16T16:36:51.177724+00:00"),
        _TOP_UP_NOTE,
    ),
    BadCapitalEntry(
        UUID("fa5cb605-d3b1-47a4-bc81-aa35f511f41b"),
        "Nasih",
        1_577_503,
        datetime.fromisoformat("2026-07-16T16:36:51.177724+00:00"),
        _TOP_UP_NOTE,
    ),
)


@dataclass(frozen=True, slots=True)
class SyntheticOrder:
    order_id: UUID
    session_id: UUID
    business_date: date
    invoice_no: str
    amount_minor: int
    payment_method: str


_INVOICE_PREFIX = "D/MN/26-27/"
IMPORT_CREATED_BY_ID = UUID("7016c42c-11c9-48fa-a30a-5d66a8c970b7")

SYNTHETIC_ORDERS = (
    SyntheticOrder(
        UUID("8a3aabdd-a700-46f7-8a30-167ea396e40f"),
        UUID("a47cb93c-45d8-4bfb-9d9b-bcefa4413cb2"),
        date(2026, 7, 11),
        f"{_INVOICE_PREFIX}00002",
        89_100,
        "upi",
    ),
    SyntheticOrder(
        UUID("ab991a22-bb46-4b1e-9188-da2d3c6831c2"),
        UUID("b5c037ab-f114-47c3-8d0a-29d4669877e6"),
        date(2026, 7, 11),
        f"{_INVOICE_PREFIX}00003",
        65_000,
        "cash",
    ),
    SyntheticOrder(
        UUID("3c21fd96-0357-41e9-91c6-4360d3afe62a"),
        UUID("acd2371a-4253-416f-ac42-4afe0b23718c"),
        date(2026, 7, 12),
        f"{_INVOICE_PREFIX}00004",
        114_000,
        "upi",
    ),
    SyntheticOrder(
        UUID("dc7bc894-6de3-4d7c-a087-15de2a54ac9f"),
        UUID("09aa17ca-612d-423e-8f2b-cb7dfc1c3103"),
        date(2026, 7, 12),
        f"{_INVOICE_PREFIX}00005",
        45_000,
        "cash",
    ),
    SyntheticOrder(
        UUID("8615b105-6982-4b4e-8502-cdf724a80c53"),
        UUID("891df03d-a5cd-4350-aeb0-9766b99c5db6"),
        date(2026, 7, 13),
        f"{_INVOICE_PREFIX}00006",
        182_000,
        "upi",
    ),
    SyntheticOrder(
        UUID("1db1b1de-39d8-4e66-bea6-a2d9c9d50ba1"),
        UUID("2b0d9aa8-12c4-4e32-8447-c88b712c8a58"),
        date(2026, 7, 13),
        f"{_INVOICE_PREFIX}00007",
        35_000,
        "cash",
    ),
    SyntheticOrder(
        UUID("87138f80-719a-424e-bde8-1697d1cce481"),
        UUID("2fca8c5a-f5c5-41ae-9ef9-a06d7390569b"),
        date(2026, 7, 14),
        f"{_INVOICE_PREFIX}00008",
        90_000,
        "upi",
    ),
    SyntheticOrder(
        UUID("18d58869-2277-4cab-af36-aa648874b911"),
        UUID("1decf109-3282-4025-ba1e-3e1962937dd0"),
        date(2026, 7, 14),
        f"{_INVOICE_PREFIX}00009",
        50_000,
        "cash",
    ),
    SyntheticOrder(
        UUID("3e73284a-74c2-451a-836c-89b0339f41b2"),
        UUID("dbe9c8a0-6c72-40d0-97a9-437e27143048"),
        date(2026, 7, 15),
        f"{_INVOICE_PREFIX}00010",
        126_000,
        "upi",
    ),
    SyntheticOrder(
        UUID("31dbf41c-bf8d-483e-8c4a-2ff1e979d822"),
        UUID("d6f269ac-d492-42c5-932c-7425669d5113"),
        date(2026, 7, 15),
        f"{_INVOICE_PREFIX}00011",
        108_000,
        "cash",
    ),
)


@dataclass(frozen=True, slots=True)
class CollectionImportRow:
    business_date: date
    method: str
    amount_minor: int
    source_kind: str

    @property
    def source_ref(self) -> str:
        return f"daily-collection:{self.business_date.isoformat()}:{self.method}"

    @property
    def idempotency_key(self) -> str:
        return f"{RECONCILIATION_VERSION}:collection:{self.business_date}:{self.method}"


COLLECTION_IMPORT_ROWS = (
    CollectionImportRow(date(2026, 7, 11), "upi", 89_100, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 11), "cash", 65_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 12), "upi", 114_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 12), "cash", 45_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 13), "upi", 182_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 13), "cash", 35_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 14), "upi", 90_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 14), "cash", 50_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 15), "upi", 126_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 15), "cash", 108_000, "legacy_daily"),
    CollectionImportRow(date(2026, 7, 16), "upi", 162_000, "manual_daily"),
    CollectionImportRow(date(2026, 7, 16), "cash", 21_000, "manual_daily"),
)


def validate_payload() -> None:
    """Fail fast if a future edit corrupts the audited constants."""
    if len(CAPITAL_IMPORT_ROWS) != 64:
        raise ValueError("expected exactly 64 partner investment rows")
    if len({row.source_ref for row in CAPITAL_IMPORT_ROWS}) != 64:
        raise ValueError("partner investment source refs must be unique")
    if {row.sheet_row for row in CAPITAL_IMPORT_ROWS} != set(range(2, 66)):
        raise ValueError("partner investment sheet rows must be exactly 2..65")

    for target in PARTNER_TARGETS:
        rows = [row for row in CAPITAL_IMPORT_ROWS if row.partner_name == target.name]
        if len(rows) != target.expected_rows:
            raise ValueError(f"unexpected investment row count for {target.name}")
        if sum(row.amount_minor for row in rows) != target.expected_capital_minor:
            raise ValueError(f"unexpected investment total for {target.name}")

    if sum(target.ownership_share_pct for target in PARTNER_TARGETS) != Decimal("100"):
        raise ValueError("partner ownership shares must total exactly 100%")
    if sum(row.amount_minor for row in CAPITAL_IMPORT_ROWS) != 206_942_790:
        raise ValueError("partner investments must total INR 2,069,427.90")

    if len(BAD_CAPITAL_ENTRIES) != 27:
        raise ValueError("expected exactly 27 bad production capital entries")
    if len({row.id for row in BAD_CAPITAL_ENTRIES}) != 27:
        raise ValueError("bad capital entry IDs must be unique")
    if sum(row.amount_minor for row in BAD_CAPITAL_ENTRIES) != 206_942_790:
        raise ValueError("bad capital entries must net to the audited capital total")

    if len(SYNTHETIC_ORDERS) != 10:
        raise ValueError("expected exactly 10 synthetic orders")
    if len({row.order_id for row in SYNTHETIC_ORDERS}) != 10:
        raise ValueError("synthetic order IDs must be unique")
    if len({row.session_id for row in SYNTHETIC_ORDERS}) != 10:
        raise ValueError("synthetic session IDs must be unique")
    if sum(row.amount_minor for row in SYNTHETIC_ORDERS) != 904_100:
        raise ValueError("synthetic orders must equal the Jul 11-15 collections")

    if len(COLLECTION_IMPORT_ROWS) != 12:
        raise ValueError("expected cash and UPI rows for six business dates")
    if len({row.idempotency_key for row in COLLECTION_IMPORT_ROWS}) != 12:
        raise ValueError("manual collection idempotency keys must be unique")
    if sum(row.amount_minor for row in COLLECTION_IMPORT_ROWS) != 1_087_100:
        raise ValueError("Jul 11-16 collections must total INR 10,871.00")
    historical = [row for row in COLLECTION_IMPORT_ROWS if row.business_date < date(2026, 7, 16)]
    if sum(row.amount_minor for row in historical) != 904_100:
        raise ValueError("Jul 11-15 collections must total INR 9,041.00")
    synthetic_signatures = {
        (row.business_date, row.payment_method, row.amount_minor) for row in SYNTHETIC_ORDERS
    }
    historical_signatures = {
        (row.business_date, row.method, row.amount_minor) for row in historical
    }
    if synthetic_signatures != historical_signatures:
        raise ValueError("synthetic orders must exactly match Jul 11-15 collection rows")


validate_payload()
