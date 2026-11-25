"""When the agent asks Spring to search again (contract 3, 7.5 need_search).

FastAPI never searches. It only returns a query and a small versioned state; Spring checks
permissions, searches and calls again with round + 1 (at most MAX_SEARCH_ROUND times).
"""
import re
from collections.abc import Collection, Sequence

from app.models.agent import MAX_SEARCH_ROUND, QUERY_MAX_CHARS, SEARCH_STATE_VERSION, SearchState
from app.services.agent.tools import MATERIAL_SEARCH

_SPACES = re.compile(r"\s+")


def need_search(*, allowed_tools: Collection[str], search_round: int, material_count: int,
                prefer_materials: bool, variant_turn: bool) -> bool:
    return (MATERIAL_SEARCH in allowed_tools and search_round < MAX_SEARCH_ROUND and material_count == 0
            and prefer_materials and not variant_turn)


def _normal(text: str) -> str:
    return _SPACES.sub(" ", text).strip()


def _same(a: str, b: str) -> bool:
    return _normal(a).casefold() == _normal(b).casefold()


def _candidates(question: str, topic: str, previous_user: str | None, search_round: int) -> list[str]:
    """Query candidates for this round, best first (contract 7.5 need_search query rule).

    Spring's first search already used the question itself and found nothing, so no round asks
    for the bare question again. Round 0 adds the session topic to the question. Round 1 searches
    the topic with the previous user turn (follow-up questions often lean on it), then the topic alone.
    """
    topic = _normal(topic)
    question = _normal(question)
    if search_round == 0:
        return [f"{topic} {question}"]
    found = [f"{topic} {_normal(previous_user)}"] if previous_user and _normal(previous_user) else []
    return [*found, topic]


def build_query(question: str, topic: str, search_round: int, *, previous_user: str | None = None,
                previous_queries: Sequence[str] = ()) -> str | None:
    """The re-search query, or None when every candidate repeats the question or an earlier query."""
    for candidate in _candidates(question, topic, previous_user, search_round):
        query = candidate.strip()[:QUERY_MAX_CHARS].strip()
        if not query or _same(query, question) or any(_same(query, old) for old in previous_queries):
            continue
        return query
    return None


def previous_queries(state: dict | None) -> list[str]:
    return list(SearchState.model_validate(state).queries) if state else []


def next_state(previous: dict | None, query: str) -> dict:
    """State for the next round: previous queries only, never memories, summary or material text (6.8)."""
    return SearchState(v=SEARCH_STATE_VERSION, queries=[*previous_queries(previous), query]).model_dump()
