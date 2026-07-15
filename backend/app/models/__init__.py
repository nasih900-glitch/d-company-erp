"""SQLAlchemy models — re-exported so Alembic autogenerate picks them up."""

from app.models.base import Base, TimestampMixin, SoftDeleteMixin, TenantMixin
from app.models.tenant import Company, Branch, Terminal
from app.models.user import User, Role, UserRole, Attendance, PayrollEntry
from app.models.menu import MenuCategory, MenuItem, MenuVariant, MenuModifier
from app.models.inventory import (
    Ingredient,
    Recipe,
    RecipeLine,
    Batch,
    StockMovement,
    Supplier,
    PurchaseOrder,
    PurchaseOrderLine,
    GRN,
    GRNLine,
)
from app.models.pos import Order, OrderLine, Payment, Refund, Shift
from app.models.tables import Floor, Table, Reservation
from app.models.gaming import Station, GamingSession, GamingBooking, Tournament
from app.models.finance import (
    Account,
    JournalEntry,
    JournalLine,
    Partner,
    CapitalEntry,
    Expense,
    ExpenseCategory,
    Asset,
)
from app.models.ocr import OcrUpload, OcrExtraction, OcrVerification
from app.models.audit import AuditLog
from app.models.auth_challenge import AuthOtpChallenge
from app.models.customer import Customer
from app.models.membership import (
    CustomerMembership,
    MembershipBenefitReservation,
    MembershipTier,
)
from app.models.events import Event, EventTicket
from app.models.idempotency_key import IdempotencyKey
from app.models.india import (
    GstRateSlab,
    HsnCode,
    InvoiceCounter,
    KeralaPTSlab,
    SacCode,
    StateCode,
)
from app.models.access_control import RolePermissionOverride

__all__ = [
    "Account",
    "Asset",
    "Attendance",
    "AuditLog",
    "AuthOtpChallenge",
    "Base",
    "Batch",
    "Branch",
    "CapitalEntry",
    "Company",
    "Customer",
    "CustomerMembership",
    "Event",
    "EventTicket",
    "Expense",
    "ExpenseCategory",
    "Floor",
    "GamingBooking",
    "GamingSession",
    "GRN",
    "GRNLine",
    "GstRateSlab",
    "HsnCode",
    "IdempotencyKey",
    "Ingredient",
    "InvoiceCounter",
    "KeralaPTSlab",
    "JournalEntry",
    "JournalLine",
    "MembershipTier",
    "MembershipBenefitReservation",
    "MenuCategory",
    "MenuItem",
    "MenuModifier",
    "MenuVariant",
    "OcrExtraction",
    "OcrUpload",
    "OcrVerification",
    "Order",
    "OrderLine",
    "Partner",
    "Payment",
    "PayrollEntry",
    "PurchaseOrder",
    "PurchaseOrderLine",
    "Recipe",
    "RecipeLine",
    "Refund",
    "Reservation",
    "Role",
    "RolePermissionOverride",
    "SacCode",
    "Shift",
    "SoftDeleteMixin",
    "StateCode",
    "Station",
    "StockMovement",
    "Supplier",
    "Table",
    "TenantMixin",
    "Terminal",
    "TimestampMixin",
    "Tournament",
    "User",
    "UserRole",
]
