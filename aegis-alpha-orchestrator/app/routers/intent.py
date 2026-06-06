"""Intent classification router."""

from fastapi import APIRouter

from ..models.requests import IntentRequest
from ..models.responses import IntentResult
from ..core.intent_classifier import IntentClassifier
from ..core.llm_client import LLMClient
from ..config import settings

router = APIRouter(tags=["intent"])

# Dependencies
_llm_client = LLMClient(settings)
_intent_classifier = IntentClassifier(_llm_client)


@router.post("/classify-intent", response_model=IntentResult)
async def classify_intent(body: IntentRequest) -> IntentResult:
    """Classify user intent to match workflow."""
    try:
        return await _intent_classifier.classify(
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
