"""Health check router."""

from fastapi import APIRouter

from ..config import settings
from ..models.responses import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    """Health check endpoint."""
    return HealthResponse(
        ok=True,
        engine="langgraph",
        port=settings.port,
        provider=settings.provider,
        model=settings.model,
        has_api_key=bool(settings.effective_api_key),
        base_url_configured=bool(settings.effective_base_url),
        mock=settings.is_mock_mode,
    )
