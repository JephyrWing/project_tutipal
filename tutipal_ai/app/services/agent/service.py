"""Run one agent turn and stream NDJSON lines (contract 7.5-7.8).

The graph decides (rule based, no LLM). This module then performs the decision: the PT-84
variant tool, a need_search line, or the single answer call through the existing stream_chat,
so cancellation and provider fallback stay exactly as in /v1/chat.

`stream_chat` and `generate_question_variant` are module globals looked up at call time so
tests can replace them. Logs carry node names, counts and outcomes only, never request text.
"""
import asyncio
import json
import logging
import math
import os
from collections.abc import AsyncIterator
from contextlib import aclosing
from typing import Any

from app.models.agent import AgentChatRequest
from app.models.inference import ChatRequest, Message
from app.models.question_variant import VariantFailure, VariantResult
from app.services.agent import prompts
from app.services.agent.classify import PROTECTED_WINDOW
from app.services.agent.context import BUDGET, FittedContext, fit_context, shorten
from app.services.agent.graph import shared_graph
from app.services.agent.search_gate import next_state, previous_queries
from app.services.agent.tools import ANSWER_QUESTION, ToolNotAllowed, require_tool
from app.services.inference_service import stream_chat
from app.services.question_variant_service import generate_question_variant

log = logging.getLogger(__name__)

PING_INTERVAL_SECONDS = 10
FALLBACK_MIN_REMAINING_SECONDS = 30
DEFAULT_DEADLINE_SECONDS = 120.0
# Share of the policy part for the original quiz text in a variant fallback answer (Round 1 P10).
ORIGINAL_QUESTION_LIMIT = 200
# Failure reasons that never get the general-answer fallback (D3).
NO_FALLBACK_REASONS = {"model_unavailable", "invalid_original"}

_PING = json.dumps({"ping": True}) + "\n"
_END = object()
# Strong references so cancelled helper tasks are not garbage collected before they finish cleaning up.
_background: set[asyncio.Task] = set()


class _DeadlineReached(Exception):
    pass


class _ProducerFailed(Exception):
    def __init__(self, error: BaseException) -> None:
        super().__init__(type(error).__name__)
        self.error = error


def deadline_seconds() -> float:
    """AGENT_DEADLINE_SECONDS (decimals allowed) limits everything before the first delta. Default 120."""
    raw = os.getenv("AGENT_DEADLINE_SECONDS", "").strip()
    if not raw:
        return DEFAULT_DEADLINE_SECONDS
    try:
        value = float(raw)
    except ValueError:
        value = math.nan
    if not math.isfinite(value) or value <= 0:
        log.warning("invalid AGENT_DEADLINE_SECONDS, using %s", DEFAULT_DEADLINE_SECONDS)
        return DEFAULT_DEADLINE_SECONDS
    return value


def _line(payload: dict[str, Any]) -> str:
    return json.dumps(payload, ensure_ascii=False) + "\n"


def _now() -> float:
    return asyncio.get_running_loop().time()


async def _tick(waitable: asyncio.Future, deadline: float) -> bool:
    """Wait until `waitable` is done (True) or a ping is due (False). Raises _DeadlineReached past the deadline."""
    remaining = deadline - _now()
    if remaining <= 0:
        raise _DeadlineReached
    done, _ = await asyncio.wait({waitable}, timeout=min(PING_INTERVAL_SECONDS, remaining))
    if done:
        return True
    if deadline - _now() <= 0:
        raise _DeadlineReached
    return False


async def _stop(task: asyncio.Task | None) -> None:
    """Cancel a helper task and wait for its cleanup unless this coroutine is itself being cancelled."""
    if task is None or task.done():
        return
    task.cancel()
    _background.add(task)
    task.add_done_callback(_background.discard)
    await asyncio.gather(task, return_exceptions=True)


def _initial_state(request: AgentChatRequest) -> dict:
    window = [m.model_dump() for m in request.context.messages[-PROTECTED_WINDOW:]]
    return {
        "agent": request.agent,
        "question": request.question,
        "window": window,
        "topic": request.policy.topic,
        "allowed_tools": list(request.allowedTools),
        "variant_turn": request.variantSource is not None,
        "search_round": request.search.round,
        "material_count": len(request.materials),
        "prefer_materials": request.policy.preferMaterials,
        "previous_queries": previous_queries(request.search.state),
        "nodes": [],
    }


def _empty_context() -> dict:
    return {"estimatedInputTokens": 0, "budget": BUDGET,
            "dropped": {"messages": 0, "memories": 0, "materials": 0, "screenSummary": 0}, "truncated": []}


class _Turn:
    """Per-request bookkeeping for the final meta line and the one log line."""

    def __init__(self, request: AgentChatRequest, decided: dict) -> None:
        self.request = request
        self.nodes = list(decided["nodes"])
        self.route = decided["route"]
        self.hint_level = decided["hint_level"]
        self.suggest_help = decided["suggest_help"]
        self.llm_calls = 0
        self.variant: str | None = None
        self.retryable = False
        self.context = _empty_context()
        self.used_memory_ids: list[str] = []
        self.used_summary_id: str | None = None
        self.material_ids: list[str] = []
        self.failed = False
        self.answered = False

    def use_context(self, fitted: FittedContext) -> None:
        self.context = {"estimatedInputTokens": fitted.estimated_input_tokens, "budget": BUDGET,
                        "dropped": dict(fitted.dropped), "truncated": list(fitted.truncated)}
        self.used_memory_ids = [str(m["id"]) for m in fitted.memories]
        self.used_summary_id = str(fitted.summary["id"]) if fitted.summary else None

    def meta(self) -> str:
        return _line({"meta": {
            "agent": self.request.agent,
            "route": self.route,
            "nodes": [*self.nodes, "respond"],
            "llmCalls": self.llm_calls,
            "hintLevel": self.hint_level,
            "suggestHelp": self.suggest_help,
            "searchRound": self.request.search.round,
            "variant": self.variant,
            "retryable": self.retryable,
            "context": self.context,
            "usedMemoryIds": self.used_memory_ids,
            "usedSummaryId": self.used_summary_id,
        }})


async def run_agent(request: AgentChatRequest) -> AsyncIterator[str]:
    started = _now()
    deadline = started + deadline_seconds()
    decided = await shared_graph().ainvoke(_initial_state(request))
    turn = _Turn(request, decided)
    outcome = "error"
    try:
        log.info("agent decided agent=%s nodes=%s route=%s hint=%s help=%s action=%s", request.agent,
                 ",".join(turn.nodes), turn.route, turn.hint_level, turn.suggest_help, decided["action"])
        if decided["action"] == "error":
            yield _line({"error": decided["error"]})
            return
        if decided["action"] == "need_search":
            state = next_state(request.search.state, decided["query"])
            outcome = "need_search"
            yield _line({"status": "need_search", "query": decided["query"], "state": state})
            return

        variant_fallback = False
        if decided["action"] == "variant":
            turn.route, turn.hint_level, turn.suggest_help = "variant", None, False
            result: VariantResult | None = None
            async with aclosing(_run_variant(request, deadline)) as lines:
                async for line in lines:
                    if isinstance(line, str):
                        yield line
                    else:
                        result = line
            if result is None:
                raise RuntimeError("variant tool produced no result")
            yield _line({"variant": result.model_dump(mode="json", exclude_none=True)})
            turn.variant = result.status
            if result.status == "ok":
                outcome = "variant_ok"
                yield turn.meta()
                return
            reasons = set(result.failure_reasons)
            turn.retryable = "invalid_original" not in reasons
            if reasons & NO_FALLBACK_REASONS or deadline - _now() < FALLBACK_MIN_REMAINING_SECONDS:
                # Spring sends the fixed notice; no LLM call (D3).
                outcome = "variant_failed"
                yield turn.meta()
                return
            try:
                require_tool(request.allowedTools, ANSWER_QUESTION)
            except ToolNotAllowed:
                yield _line({"error": "tool_not_allowed"})
                return
            turn.nodes.append("compose")
            turn.hint_level = decided["hint_level"]
            variant_fallback = True

        async with aclosing(_answer(request, turn, decided, deadline, variant_fallback)) as lines:
            async for line in lines:
                yield line
        if turn.failed:
            return
        # An empty fallback answer after a failed variant ends like the fixed-notice path (contract 7.6).
        outcome = "answered" if turn.answered else "variant_failed"
        yield turn.meta()
    finally:
        log.info("agent finished agent=%s nodes=%s outcome=%s llmCalls=%d variant=%s elapsedMs=%d",
                 request.agent, ",".join(turn.nodes), outcome, turn.llm_calls, turn.variant,
                 int((_now() - started) * 1000))


async def _run_variant(request: AgentChatRequest, deadline: float) -> AsyncIterator[str | VariantResult]:
    """Run the PT-84 tool inside the remaining deadline, pinging meanwhile. Out of time -> model_unavailable."""
    source = request.variantSource.model_dump(exclude_none=True)
    task = asyncio.create_task(generate_question_variant(source))
    try:
        try:
            while not await _tick(task, deadline):
                yield _PING
            result = task.result()
        except _DeadlineReached:
            await _stop(task)
            log.warning("agent variant out of time")
            result = VariantFailure(attempts=1, failure_reasons=["model_unavailable"])
        except Exception as error:  # the tool must not break the stream; report it as unavailable
            log.warning("agent variant failed: %s", type(error).__name__)
            result = VariantFailure(attempts=1, failure_reasons=["model_unavailable"])
        log.info("agent variant status=%s attempts=%d", result.status, result.attempts)
        yield result
    finally:
        if not task.done():
            task.cancel()
            _background.add(task)
            task.add_done_callback(_background.discard)


def _chat_request(request: AgentChatRequest, turn: _Turn, decided: dict,
                  variant_fallback: bool) -> ChatRequest:
    policy = request.policy
    original = None
    if variant_fallback and request.variantSource is not None:
        source = request.variantSource.model_dump(exclude_none=True)
        original = shorten(prompts.original_question_text(source), ORIGINAL_QUESTION_LIMIT)[0]
    instructions = prompts.build_instructions(
        agent=request.agent, topic=policy.topic, difficulty=policy.difficulty, hide_answers=policy.hideAnswers,
        route=decided["route"], hint_level=turn.hint_level, stuck=decided["stuck"], variant_fallback=variant_fallback,
        original_question=original,
    )
    context = request.context
    fitted = fit_context(
        system=instructions,
        question=request.question,
        messages=[m.model_dump() for m in context.messages],
        summary=context.summary.model_dump() if context.summary else None,
        memories=[m.model_dump() for m in context.memories],
        materials=[m.model_dump() for m in request.materials],
        screen_summary=context.screenSummary,
    )
    turn.use_context(fitted)
    turn.material_ids = [str(m["id"]) for m in fitted.materials]
    messages = [Message(role=m["role"], content=m["content"]) for m in fitted.messages]
    messages.append(Message(role="user", content=fitted.question))
    return ChatRequest(system=fitted.render_system(instructions), messages=messages)


async def _pump(lines: AsyncIterator[str], queue: asyncio.Queue) -> None:
    """Drain stream_chat in one task so its timeouts and HTTP stream stay bound to a single task."""
    try:
        async for line in lines:
            await queue.put(line)
    except Exception as error:
        await queue.put(_ProducerFailed(error))
        return
    finally:
        await lines.aclose()
    await queue.put(_END)


async def _answer(request: AgentChatRequest, turn: _Turn, decided: dict, deadline: float,
                  variant_fallback: bool) -> AsyncIterator[str]:
    """The one answer LLM call. The deadline applies until the first delta only (contract 7.8)."""
    chat = _chat_request(request, turn, decided, variant_fallback)
    queue: asyncio.Queue = asyncio.Queue(maxsize=16)
    producer = asyncio.create_task(_pump(stream_chat(chat), queue))
    turn.llm_calls = 1
    first = True
    get: asyncio.Future | None = None
    try:
        while True:
            if first:
                get = asyncio.ensure_future(queue.get())
                try:
                    while not await _tick(get, deadline):
                        yield _PING
                except _DeadlineReached:
                    log.warning("agent first delta deadline reached")
                    await _stop(producer)
                    turn.failed = True
                    yield _line({"error": "agent_timeout"})
                    return
                item = await get
                get = None
            else:
                # After the first delta there is no deadline or ping: await the queue directly (Round 1 P13).
                item = await queue.get()
            if item is _END:
                if first and not variant_fallback:
                    # The model ended without any text: report it instead of a successful empty answer (7.5).
                    log.warning("agent answer stream ended without text")
                    turn.failed = True
                    yield _line({"error": "llm_unavailable"})
                elif first:
                    # Variant line already sent: end with meta and no delta so Spring sends the fixed notice (7.6).
                    log.warning("agent fallback answer ended without text")
                return
            if isinstance(item, _ProducerFailed):
                raise item.error
            if first:
                payload = json.loads(item)
                if "delta" in payload and not payload["delta"]:
                    continue  # an empty piece is not output; start waits for real text
                if "delta" in payload:
                    first = False
                    turn.answered = True
                    yield _line({"start": {"materialIds": turn.material_ids}})
                failed = "error" in payload
            else:
                # Inside a JSON string every quote is escaped, so an unescaped "error" token is a key or a value
                # equal to "error"; only such a line is parsed to tell which (no json.loads per delta).
                failed = '"error"' in item and "error" in json.loads(item)
            yield item
            if failed:
                turn.failed = True
                return
    finally:
        if get is not None and not get.done():
            get.cancel()
        if not producer.done():
            producer.cancel()
            _background.add(producer)
            producer.add_done_callback(_background.discard)
