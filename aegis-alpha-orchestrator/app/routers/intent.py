"""Intent classification router."""

from fastapi import APIRouter

from ..models.requests import IntentRequest
from ..models.responses import IntentResult
from ..dependencies import intent_classifier

router = APIRouter(tags=["intent"])


@router.post("/classify-intent", response_model=IntentResult)
async def classify_intent(body: IntentRequest) -> IntentResult:
    """Classify user intent to match workflow."""
    try:
        return await intent_classifier.classify(
            message=body.message,
            workflows=body.workflows,
        )
    except Exception as e:
        return IntentResult(
            workflow_key=None,
            ticker=None,
            confidence=0,
            error=str(e),
        )
