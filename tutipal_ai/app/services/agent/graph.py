"""The one shared agent graph (PT-85 D1). The only module that imports LangGraph.

Compiled without a checkpointer: FastAPI keeps no state between requests (contract 1).
"""
from functools import lru_cache

from langgraph.graph import END, START, StateGraph

from app.services.agent.nodes import (
    AgentState,
    classify_node,
    compose_node,
    hint_node,
    next_after_classify,
    search_node,
    variant_node,
)


def build_graph():
    graph = StateGraph(AgentState)
    graph.add_node("classify", classify_node)
    graph.add_node("variant", variant_node)
    graph.add_node("search", search_node)
    graph.add_node("hint", hint_node)
    graph.add_node("compose", compose_node)
    graph.add_edge(START, "classify")
    graph.add_conditional_edges("classify", next_after_classify, ["variant", "search", "hint", "compose"])
    graph.add_edge("hint", "compose")
    graph.add_edge("variant", END)
    graph.add_edge("search", END)
    graph.add_edge("compose", END)
    return graph.compile(checkpointer=None)


@lru_cache(maxsize=1)
def shared_graph():
    """Compiled once per process; safe to share because it holds no per-request state."""
    return build_graph()
