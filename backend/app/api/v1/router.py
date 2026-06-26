"""v1 API router — composes one APIRouter per module."""

from __future__ import annotations

from fastapi import APIRouter

from app.api.v1.analytics.router import router as analytics_router
from app.api.v1.auth.router import router as auth_router
from app.api.v1.events.router import router as events_router
from app.api.v1.finance.router import router as finance_router
from app.api.v1.gaming.router import router as gaming_router
from app.api.v1.inventory.router import router as inventory_router
from app.api.v1.menu.router import router as menu_router
from app.api.v1.ocr.router import router as ocr_router
from app.api.v1.pos.router import router as pos_router
from app.api.v1.reports.router import router as reports_router
from app.api.v1.staff.router import router as staff_router
from app.api.v1.tables.router import router as tables_router
from app.api.v1.admin.router import router as admin_router
from app.api.v1.settings.router import router as settings_router
from app.api.v1.customers.router import router as customers_router
from app.api.v1.public.router import router as public_router
from app.api.v1.memberships.router import router as memberships_router
from app.api.v1.kitchen.router import router as kitchen_router
from app.api.v1.accounting.router import router as accounting_router
from app.api.v1.insights.router import router as insights_router

api_router = APIRouter()
api_router.include_router(auth_router, prefix="/auth", tags=["auth"])
api_router.include_router(pos_router, prefix="/pos", tags=["pos"])
api_router.include_router(tables_router, prefix="/tables", tags=["tables"])
api_router.include_router(menu_router, prefix="/menu", tags=["menu"])
api_router.include_router(inventory_router, prefix="/inventory", tags=["inventory"])
api_router.include_router(gaming_router, prefix="/gaming", tags=["gaming"])
api_router.include_router(events_router, prefix="/events", tags=["events"])
api_router.include_router(finance_router, prefix="/finance", tags=["finance"])
api_router.include_router(ocr_router, prefix="/ocr", tags=["ocr"])
api_router.include_router(staff_router, prefix="/staff", tags=["staff"])
api_router.include_router(analytics_router, prefix="/analytics", tags=["analytics"])
api_router.include_router(reports_router, prefix="/reports", tags=["reports"])
api_router.include_router(admin_router, prefix="/admin", tags=["admin"])
api_router.include_router(settings_router, prefix="/settings", tags=["settings"])
api_router.include_router(customers_router, prefix="/customers", tags=["customers"])
api_router.include_router(public_router, prefix="/public", tags=["public"])
api_router.include_router(memberships_router, prefix="/memberships", tags=["memberships"])
api_router.include_router(kitchen_router, prefix="/kitchen", tags=["kitchen"])
api_router.include_router(accounting_router, prefix="/accounting", tags=["accounting"])
api_router.include_router(insights_router, prefix="/insights", tags=["insights"])
