"""Rule-based route, hint level and help suggestion (PT-85 AC-1, AC-2, AC-4, AC-22).

No LLM call. Decisions depend only on the current question and the raw text of the last
PROTECTED_WINDOW messages, so the same window always gives the same result regardless of
conversation length, summary or memories.
"""
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Literal

Route = Literal["concept", "answer_request", "answer_check", "code_debug"]

PROTECTED_WINDOW = 6
MAX_HINT_LEVEL = 3
CODE_DEBUG_HELP_COUNT = 3
# Spring appends this exact text when meta.suggestHelp is true, so it reappears in the next turn's history.
HELP_SUGGESTION_MARKER = "선생님께 도움 요청할래요?"

# Questions are up to 8000 characters and run on the event loop, so every pattern must stay linear
# (Round 1 P2): `\s*+`/`\d++` are possessive (Python 3.11+) wherever the next token cannot be
# whitespace/a digit, a digit run is matched only from its first digit, and the free word after
# "코드" is capped at CODE_WORD_MAX characters.
CODE_WORD_MAX = 10
_CODE_DEBUG = re.compile(
    r"```"
    r"|(?i:traceback)"
    r"|에러|오류"
    r"|\b[A-Z][A-Za-z]*(?:Error|Exception)\b"
    r"|\bline\s++\d"
    r"|(?<!\d)\d++\s*+번\s*+째?\s*+줄"
)
_ANSWER_CHECK = re.compile(
    r"(?<![가-힣])(?:제|내)\s*+답"
    r"|맞나요|맞아요\s*+\?|맞는지|맞을까요|맞는\s*+거"
    r"|이렇게\s*+(?:했|풀었|짰|작성했)"
    r"|제가\s*+(?:푼|쓴|짠|작성한)"
)
_ANSWER_REQUEST = re.compile(
    r"답\s*+(?:좀\s*+)?(?:알려|가르쳐|말해)"
    r"|풀어\s*+(?:줘|주세요|주실|봐\s*+줘)"
    r"|코드\S{0," + str(CODE_WORD_MAX) + r"}\s*+(?:좀\s*+)?(?:짜|작성해|만들어)\s*+(?:줘|주세요|주실)"
    r"|(?:정답|완성)\s*+코드"
    r"|답만"
)
_STUCK = re.compile(
    r"모르겠|몰라요|막혔|막혀|막힘|헷갈|이해가?\s*+안|못\s*+하겠|감이\s*+안|안\s*+풀려|어려워요"
)


@dataclass(frozen=True)
class Decision:
    route: Route
    hint_level: int | None
    suggest_help: bool
    # The current question says it is stuck or asks for the answer; prompts uses it to add the
    # hint-level rule to a stuck turn that classify() still routes to concept.
    stuck: bool = False


def classify(question: str) -> Route:
    """Priority: code_debug > answer_check > answer_request > concept."""
    if _CODE_DEBUG.search(question):
        return "code_debug"
    if _ANSWER_CHECK.search(question):
        return "answer_check"
    if _ANSWER_REQUEST.search(question):
        return "answer_request"
    return "concept"


def is_stuck(text: str, route: Route | None = None) -> bool:
    """A user turn that says it is stuck or asks for the answer. Pass `route` to reuse a classify() result."""
    if _STUCK.search(text):
        return True
    return (route if route is not None else classify(text)) == "answer_request"


def _content(message: Mapping[str, str]) -> str:
    return message["content"]


def decide(agent: str, question: str, messages: Sequence[Mapping[str, str]]) -> Decision:
    route = classify(question)
    if agent != "student":
        return Decision(route=route, hint_level=None, suggest_help=False)

    window = list(messages[-PROTECTED_WINDOW:])
    # classify() runs once per text; streak and the code_debug count both reuse it.
    user_turns = [(text, classify(text)) for text in (_content(m) for m in window if m["role"] == "user")]
    streak = 0
    for text, text_route in reversed(user_turns):
        if not is_stuck(text, text_route):
            break
        streak += 1
    hint_level = min(MAX_HINT_LEVEL, 1 + streak)
    stuck = is_stuck(question, route)

    last_assistant = next((_content(m) for m in reversed(window) if m["role"] == "assistant"), "")
    if HELP_SUGGESTION_MARKER in last_assistant:
        return Decision(route=route, hint_level=hint_level, suggest_help=False, stuck=stuck)

    still_stuck = hint_level == MAX_HINT_LEVEL and stuck
    debug_count = sum(1 for _, text_route in user_turns if text_route == "code_debug")
    repeated_debug = route == "code_debug" and debug_count + 1 >= CODE_DEBUG_HELP_COUNT
    return Decision(route=route, hint_level=hint_level, suggest_help=still_stuck or repeated_debug, stuck=stuck)
