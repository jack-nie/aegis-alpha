"""Shared dependency instances."""

import os

from .config import settings
from .core.llm_client import LLMClient
from .core.market_data import MarketDataService
from .core.memory_store import MemoryStoreManager
from .core.node_executor import NodeExecutor
from .core.tools import create_tools
from .core.workflow_engine import WorkflowEngine
from .core.intent_classifier import IntentClassifier

os.makedirs(settings.data_dir, exist_ok=True)

llm_client = LLMClient(settings)
market_data = MarketDataService(settings)
node_executor = NodeExecutor(settings, llm_client, market_data)
memory_store_manager = MemoryStoreManager(settings)
tools = create_tools(settings)

try:
    from langgraph.checkpoint.sqlite import SqliteSaver
    _checkpointer = SqliteSaver.from_conn_string(f"{settings.data_dir}/checkpoints.db")
except ImportError:
    from langgraph.checkpoint.memory import MemorySaver
    _checkpointer = MemorySaver()

workflow_engine = WorkflowEngine(
    node_executor,
    tools=tools,
    store=memory_store_manager.store,
    checkpointer=_checkpointer,
)
intent_classifier = IntentClassifier(llm_client)
