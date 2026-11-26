"""PT-106 POST /v1/agent/summarize request / response (docs/AI_WORKFLOW_CONTRACT.md 6.5).

Spring calls it outside the chat turn with stored messages of one conversation. Every level forbids unknown
fields and the scope follows the same strict rules as the agent chat request.
"""
from pydantic import BaseModel, ConfigDict, Field

from app.models.agent import AgentMessage, Scope

SUMMARY_MAX_CHARS = 4000


class SummarizeRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scope: Scope
    messages: list[AgentMessage] = Field(min_length=1, max_length=40)
    previousSummary: str | None = Field(default=None, min_length=1, max_length=SUMMARY_MAX_CHARS)


class SummarizeResponse(BaseModel):
    summary: str = Field(min_length=1, max_length=SUMMARY_MAX_CHARS)
