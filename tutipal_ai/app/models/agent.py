"""PT-85 POST /v1/agent/chat request (docs/AI_WORKFLOW_CONTRACT.md 6-7).

Every level forbids unknown fields. Validation errors must never echo input text, so
cross-field checks raise short fixed messages and the search state is re-validated
without exposing its keys in the error location.
"""
import json
from typing import Annotated, Any, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StringConstraints,
    ValidationError,
    model_validator,
)

from app.models.question_variant import OriginalQuestion

SEARCH_STATE_MAX_BYTES = 65536
SEARCH_STATE_VERSION = 1
MAX_SEARCH_ROUND = 2
QUERY_MAX_CHARS = 500

OpaqueId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,64}$")]
ToolName = Literal["answer_question", "material_search", "question_variant"]
AgentKind = Literal["student", "personal"]


class _Strict(BaseModel):
    model_config = ConfigDict(extra="forbid")


class SessionScope(_Strict):
    kind: Literal["session"]
    sessionId: int = Field(ge=1, strict=True)


class PersonalScope(_Strict):
    kind: Literal["personal"]


Scope = Annotated[SessionScope | PersonalScope, Field(discriminator="kind")]


class Policy(_Strict):
    topic: str = Field(min_length=1, max_length=30)
    difficulty: int = Field(ge=1, le=5, strict=True)
    # Strict: "false" or 0 must not quietly become a policy value (Round 1 P6).
    hideAnswers: StrictBool
    preferMaterials: StrictBool


class AgentMessage(_Strict):
    role: Literal["user", "assistant"]
    content: str = Field(min_length=1, max_length=8000)


class Summary(_Strict):
    id: OpaqueId
    scope: Scope
    text: str = Field(min_length=1, max_length=4000)


class Memory(_Strict):
    id: OpaqueId
    scope: Scope
    text: str = Field(min_length=1, max_length=1000)


class AgentContext(_Strict):
    scope: Scope
    messages: list[AgentMessage] = Field(default_factory=list, max_length=40)
    summary: Summary | None = None
    memories: list[Memory] = Field(default_factory=list, max_length=20)
    screenSummary: str | None = Field(default=None, min_length=1, max_length=4000)


class Material(_Strict):
    id: OpaqueId
    source: str = Field(min_length=1, max_length=120)
    page: int | None = Field(default=None, ge=1, strict=True)
    content: str = Field(min_length=1, max_length=1500)


class Search(_Strict):
    round: int = Field(ge=0, le=MAX_SEARCH_ROUND, strict=True)
    state: dict[str, Any] | None = None


class SearchState(BaseModel):
    """Versioned internal shape of need_search state. Holds only previous queries (contract 6.8)."""

    model_config = ConfigDict(extra="forbid", strict=True)

    # StrictInt with ge=le=1: Literal[1] alone also accepted true and 1.0 (Round 1 P6).
    v: StrictInt = Field(ge=SEARCH_STATE_VERSION, le=SEARCH_STATE_VERSION)
    queries: list[Annotated[str, StringConstraints(min_length=1, max_length=QUERY_MAX_CHARS)]] = Field(
        max_length=MAX_SEARCH_ROUND)


def state_size(state: dict[str, Any]) -> int:
    return len(json.dumps(state, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))


class AgentChatRequest(_Strict):
    agent: AgentKind
    question: str = Field(min_length=1, max_length=8000)
    policy: Policy
    allowedTools: list[ToolName] = Field(max_length=3)
    context: AgentContext
    materials: list[Material] = Field(default_factory=list, max_length=3)
    variantSource: OriginalQuestion | None = None
    search: Search

    @model_validator(mode="after")
    def check_contract(self) -> "AgentChatRequest":
        self._check_tools()
        self._check_personal()
        self._check_scope()
        self._check_search()
        return self

    def _check_tools(self) -> None:
        tools = self.allowedTools
        if len(set(tools)) != len(tools):
            raise ValueError("allowedTools must not repeat")
        if "answer_question" not in tools:
            raise ValueError("allowedTools must include answer_question")
        if ("question_variant" in tools) != (self.variantSource is not None):
            raise ValueError("question_variant and variantSource must come together")

    def _check_personal(self) -> None:
        if self.agent != "personal":
            return
        if (self.allowedTools != ["answer_question"] or self.materials or self.variantSource is not None
                or self.policy.hideAnswers or self.policy.preferMaterials or self.search.round != 0):
            raise ValueError("personal agent only allows answer_question without materials or search")

    def _check_scope(self) -> None:
        scope = self.context.scope
        expected = "session" if self.agent == "student" else "personal"
        if scope.kind != expected:
            raise ValueError("context.scope does not match agent")
        items = ([self.context.summary] if self.context.summary else []) + list(self.context.memories)
        if any(item.scope != scope for item in items):
            raise ValueError("summary and memories must share context.scope")

    def _check_search(self) -> None:
        state = self.search.state
        if self.search.round == 0:
            if state is not None:
                raise ValueError("search.state must be null on round 0")
            return
        if state is None:
            raise ValueError("search.state is required after round 0")
        if state_size(state) > SEARCH_STATE_MAX_BYTES:
            raise ValueError("search.state is too large")
        try:
            parsed = SearchState.model_validate(state)
        except ValidationError:
            # Re-raise without the nested error: its location would contain client-supplied keys.
            raise ValueError("search.state is not a valid agent state") from None
        if len(parsed.queries) != self.search.round:
            raise ValueError("search.state does not match search.round")
