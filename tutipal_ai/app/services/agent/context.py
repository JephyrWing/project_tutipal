"""Token estimate and context budget for one agent turn (contract 6.3, 6.4).

Nothing is dropped or shortened while the whole prompt fits BUDGET. Past the budget, parts are
removed or shortened in the fixed contract order, one item at a time, until it fits again.
"""
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from functools import lru_cache

from app.services.agent.classify import PROTECTED_WINDOW
from app.services.agent.prompts import build_system, build_system_parts

BUDGET = 3000
QUESTION_LIMIT = 500
QUESTION_HEAD = 300
PROTECTED_MESSAGE_LIMIT = 120
SUMMARY_LIMIT = 150
PIN_LIMIT = 150
SCREEN_SUMMARY_LIMIT = 200
MATERIAL_LIMIT = 200
# The answer call reuses inference ChatRequest (max 40 messages) and the question is appended last.
MAX_HISTORY = 39
ELLIPSIS = "…"
QUESTION_JOIN = f" {ELLIPSIS} "

# Hangul syllables and jamo, CJK unified ideographs, kana (contract 6.3).
_CJK_CLASS = (
    "[ᄀ-ᇿ㄰-㆏ꥠ-꥿가-힣ힰ-퟿"
    "぀-ゟ゠-ヿㇰ-ㇿ㐀-䶿一-鿿豈-﫿]"
)
_CJK = re.compile(_CJK_CLASS)
# Matching whole runs is much faster than per-character work on long Korean text.
_CJK_RUN = re.compile(_CJK_CLASS + "+")


def _thirds(text: str) -> int:
    """3 * CJK characters + other characters. Additive over concatenation, unlike the rounded estimate."""
    cjk = sum(map(len, _CJK_RUN.findall(text)))
    return 2 * cjk + len(text)


@lru_cache(maxsize=256)
def _piece_thirds(piece: str) -> int:
    return _thirds(piece)


def estimate_tokens(text: str) -> int:
    """ceil(CJK characters + other characters / 3)."""
    return (_thirds(text) + 2) // 3


def _parts_tokens(parts: Sequence[str]) -> int:
    """estimate_tokens("".join(parts)), counting each distinct piece once across re-renders."""
    return (sum(_piece_thirds(piece) for piece in parts) + 2) // 3


def _fit_length(chars: Sequence[str], limit: int) -> int:
    """How many leading characters of `chars` fit in `limit` estimated tokens."""
    thirds = 0
    for count, char in enumerate(chars):
        thirds += 3 if _CJK.match(char) else 1
        if thirds > 3 * limit:
            return count
    return len(chars)


def _head(text: str, limit: int) -> str:
    return text[:_fit_length(text, limit)]


def _tail(text: str, limit: int) -> str:
    length = _fit_length(text[::-1], limit)
    return text[len(text) - length:] if length else ""


def shorten(text: str, limit: int) -> tuple[str, bool]:
    """Head of `text` plus an ellipsis, at most `limit` estimated tokens. Returns (text, changed)."""
    if estimate_tokens(text) <= limit:
        return text, False
    return _head(text, limit - 1) + ELLIPSIS, True


def shorten_question(question: str) -> tuple[str, bool]:
    """Keep the head (300) and tail (200) of a long question (contract 6.3)."""
    if estimate_tokens(question) <= QUESTION_LIMIT:
        return question, False
    tail_limit = QUESTION_LIMIT - QUESTION_HEAD - estimate_tokens(QUESTION_JOIN)
    return _head(question, QUESTION_HEAD) + QUESTION_JOIN + _tail(question, tail_limit), True


@dataclass
class FittedContext:
    question: str
    messages: list[dict]
    summary: dict | None
    pin: str | None
    memories: list[dict]
    materials: list[dict]
    screen_summary: str | None
    estimated_input_tokens: int = 0
    dropped: dict[str, int] = field(default_factory=dict)
    truncated: list[str] = field(default_factory=list)

    def render_system(self, instructions: str) -> str:
        return build_system(instructions, summary=self.summary, pin=self.pin, memories=self.memories,
                            materials=self.materials, screen_summary=self.screen_summary)

    def system_parts(self, instructions: str) -> list[str]:
        return build_system_parts(instructions, summary=self.summary, pin=self.pin, memories=self.memories,
                                  materials=self.materials, screen_summary=self.screen_summary)


def prompt_tokens(system: str, messages: Sequence[Mapping[str, str]], question: str) -> int:
    """Estimate of everything the model reads: system, history and the current question."""
    return estimate_tokens("\n".join([system, *(m["content"] for m in messages), question]))


class _Fitter:
    def __init__(self, system: str, question: str, messages: list[dict], summary: dict | None,
                 memories: list[dict], materials: list[dict], screen_summary: str | None) -> None:
        self.system = system
        self.fitted = FittedContext(
            question=question, messages=[], summary=summary, pin=None, memories=memories,
            materials=materials, screen_summary=screen_summary,
            dropped={"messages": 0, "memories": 0, "materials": 0, "screenSummary": 0}, truncated=[],
        )
        split = max(0, len(messages) - PROTECTED_WINDOW)
        self.first_user = next((i for i, m in enumerate(messages) if m["role"] == "user"), None)
        # (original index, message, tokens) for messages outside the protected window, oldest first.
        self.older = [(i, m, estimate_tokens(m["content"])) for i, m in enumerate(messages[:split])]
        self.protected = list(messages[split:])
        self.protected_tokens = [estimate_tokens(m["content"]) for m in self.protected]
        self.question_tokens = estimate_tokens(question)
        self.refresh_system()

    # Upper bound of prompt_tokens(): each part rounded up plus one token per joining newline.
    def total(self) -> int:
        history = sum(t for _, _, t in self.older) + sum(self.protected_tokens)
        parts = len(self.older) + len(self.protected) + 1
        return self.system_tokens + history + self.question_tokens + parts

    def over(self) -> bool:
        return self.total() > BUDGET

    def refresh_system(self) -> None:
        # Pieces of items that did not change hit the cache, so a drop costs one small count, not a full re-count.
        self.system_tokens = _parts_tokens(self.fitted.system_parts(self.system))

    def mark(self, name: str) -> None:
        if name not in self.fitted.truncated:
            self.fitted.truncated.append(name)

    def drop_history(self) -> None:
        index, message, _ = self.older.pop(0)
        self.fitted.dropped["messages"] += 1
        if index == self.first_user and self.fitted.summary is None:
            # Creating the pin from a dropped message is part of the drop, not a reported truncation.
            self.fitted.pin = shorten(message["content"], PIN_LIMIT)[0]
            self.refresh_system()

    def run(self) -> FittedContext:
        fitted = self.fitted
        while len(self.older) + len(self.protected) > MAX_HISTORY:
            self.drop_history()
        if self.exact() > BUDGET:
            self.reduce()
        fitted.messages = [m for _, m, _ in self.older] + self.protected
        fitted.estimated_input_tokens = self.exact()
        return fitted

    def exact(self) -> int:
        messages = [m for _, m, _ in self.older] + self.protected
        return prompt_tokens(self.fitted.render_system(self.system), messages, self.fitted.question)

    def reduce(self) -> None:
        fitted = self.fitted
        # 1) messages outside the protected window, oldest first
        while self.over() and self.older:
            self.drop_history()
        # 2) memories from the back (least relevant)
        while self.over() and fitted.memories:
            fitted.memories = fitted.memories[:-1]
            fitted.dropped["memories"] += 1
            self.refresh_system()
        # 3) screen summary: shorten to 200, then remove
        if self.over() and fitted.screen_summary is not None:
            fitted.screen_summary, changed = shorten(fitted.screen_summary, SCREEN_SUMMARY_LIMIT)
            if changed:
                self.mark("screenSummary")
            self.refresh_system()
            if self.over():
                fitted.screen_summary = None
                fitted.dropped["screenSummary"] = 1
                if "screenSummary" in fitted.truncated:
                    fitted.truncated.remove("screenSummary")
                self.refresh_system()
        # 4) materials, lowest rank first, keep at least one
        while self.over() and len(fitted.materials) > 1:
            fitted.materials = fitted.materials[:-1]
            fitted.dropped["materials"] += 1
            self.refresh_system()
        # 5) summary down to 150
        if self.over() and fitted.summary is not None:
            text, changed = shorten(str(fitted.summary["text"]), SUMMARY_LIMIT)
            if changed:
                fitted.summary = {**fitted.summary, "text": text}
                self.mark("summary")
                self.refresh_system()
        # Current question: head 300 + tail 200. Placed before the protected window so recent turns survive longer.
        if self.over():
            fitted.question, changed = shorten_question(fitted.question)
            if changed:
                self.question_tokens = estimate_tokens(fitted.question)
                self.mark("question")
        # 6) protected messages, oldest first, each down to 120 (never removed)
        for index, message in enumerate(self.protected):
            if not self.over():
                break
            content, changed = shorten(message["content"], PROTECTED_MESSAGE_LIMIT)
            if changed:
                self.protected[index] = {**message, "content": content}
                self.protected_tokens[index] = estimate_tokens(content)
                self.mark("protected")
        # Last resort: shorten the remaining materials to their 200 share, lowest rank first.
        for index in reversed(range(len(fitted.materials))):
            if not self.over():
                break
            material = fitted.materials[index]
            content, changed = shorten(str(material["content"]), MATERIAL_LIMIT)
            if changed:
                fitted.materials = [*fitted.materials[:index], {**material, "content": content},
                                    *fitted.materials[index + 1:]]
                self.mark("materials")
                self.refresh_system()


def fit_context(*, system: str, question: str, messages: Sequence[Mapping[str, str]],
                summary: Mapping[str, object] | None, memories: Sequence[Mapping[str, object]],
                materials: Sequence[Mapping[str, object]], screen_summary: str | None) -> FittedContext:
    """Fit one turn into BUDGET. Inputs are not modified; shortened items are copies."""
    return _Fitter(
        system=system,
        question=question,
        messages=[dict(m) for m in messages],
        summary=dict(summary) if summary is not None else None,
        memories=[dict(m) for m in memories],
        materials=[dict(m) for m in materials],
        screen_summary=screen_summary,
    ).run()
