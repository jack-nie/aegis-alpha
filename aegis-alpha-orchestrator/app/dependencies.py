"""Shared dependency instances."""

import os

from .config import settings
from .core.llm_client import LLMClient
from .core.market_data import MarketDataService
from .core.memory_store import MemoryStoreManager
from .core.node_executor import NodeExecutor
from .core.tool_registry import create_tool_registry
from .core.workflow_engine import WorkflowEngine, GraphGuardrails
from .core.intent_classifier import IntentClassifier

os.makedirs(settings.data_dir, exist_ok=True)

llm_client = LLMClient(settings)
market_data = MarketDataService(settings)
node_executor = NodeExecutor(settings, llm_client, market_data)
memory_store_manager = MemoryStoreManager(settings)
# Full registry + flat list (list form keeps WorkflowEngine / ToolNode callers unchanged).
tool_registry = create_tool_registry(settings)
tools = list(tool_registry.values())

# P2-16: the durable AsyncSqliteSaver needs the running event loop, so the lifespan in main.py
# opens it and attaches it to the engine. Until then (and after shutdown) an in-memory saver is
# used, e.g. by tests that drive the routers without the app lifespan.
from langgraph.checkpoint.memory import MemorySaver

CHECKPOINT_DB_PATH = f"{settings.data_dir}/checkpoints.db"
_checkpointer = MemorySaver()

workflow_engine = WorkflowEngine(
    node_executor,
    tools=tools,
    tool_registry=tool_registry,
    store=memory_store_manager.store,
    checkpointer=_checkpointer,
    guardrails=GraphGuardrails.from_settings(settings),
)
intent_classifier = IntentClassifier(llm_client)
