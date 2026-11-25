"""Pure decision nodes (dict -> dict) for the agent graph. Testable without LangGraph.

The graph only decides what to do; service.run_agent performs the action (variant tool,
need_search line or the single streamed answer). The graph state carries the question and
the protected window only, never memories, summary or material text.
"""
import operator
from typing import Annotated, Literal, TypedDict

from app.services.agent.classify import decide
from app.services.agent.search_gate import build_query, need_search
from app.services.agent.tools import ANSWER_QUESTION, MATERIAL_SEARCH, QUESTION_VARIANT, ToolNotAllowed, require_tool

Action = Literal["answer", "variant", "need_search", "error"]
TOOL_NOT_ALLOWED = "tool_not_allowed"


class AgentState(TypedDict, total=False):
    # inputs
    agent: str
    question: str
    window: list[dict]
    topic: str
    allowed_tools: list[str]
    variant_turn: bool
    search_round: int
    material_count: int
    prefer_materials: bool
    previous_queries: list[str]  # earlier need_search queries from search.state (no material text)
    # outputs
    route: str
    hint_level: int | None
    suggest_help: bool
    action: Action
    error: str | None
    query: str | None
    nodes: Annotated[list[str], operator.add]


def _guarded(node: str, tool: str, state: AgentState, action: Action) -> dict:
    """Re-check the tool right before the node acts (contract 7.3); a miss ends the run with tool_not_allowed."""
    try:
        require_tool(state["allowed_tools"], tool)
    except ToolNotAllowed:
        return {"nodes": [node], "action": "error", "error": TOOL_NOT_ALLOWED}
    return {"nodes": [node], "action": action}


def classify_node(state: AgentState) -> dict:
    decision = decide(state["agent"], state["question"], state["window"])
    return {"route": decision.route, "hint_level": decision.hint_level,
            "suggest_help": decision.suggest_help, "nodes": ["classify"]}


def _search_query(state: AgentState) -> str | None:
    previous_user = next((m["content"] for m in reversed(state["window"]) if m["role"] == "user"), None)
    return build_query(state["question"], state["topic"], state["search_round"], previous_user=previous_user,
                       previous_queries=state.get("previous_queries", []))


def next_after_classify(state: AgentState) -> str:
    if state["variant_turn"]:
        return "variant"
    # A query that would only repeat an earlier search cannot find anything new: answer instead (Round 1 P3).
    if need_search(allowed_tools=state["allowed_tools"], search_round=state["search_round"],
                   material_count=state["material_count"], prefer_materials=state["prefer_materials"],
                   variant_turn=state["variant_turn"]) and _search_query(state) is not None:
        return "search"
    return "hint" if state["agent"] == "student" else "compose"


def variant_node(state: AgentState) -> dict:
    return _guarded("variant", QUESTION_VARIANT, state, "variant")


def search_node(state: AgentState) -> dict:
    result = _guarded("search", MATERIAL_SEARCH, state, "need_search")
    if result["action"] == "need_search":
        result["query"] = _search_query(state)
    return result


def hint_node(state: AgentState) -> dict:
    # hint_level and suggest_help were fixed by classify from the protected window; this marks the step.
    return {"nodes": ["hint"]}


def compose_node(state: AgentState) -> dict:
    return _guarded("compose", ANSWER_QUESTION, state, "answer")
