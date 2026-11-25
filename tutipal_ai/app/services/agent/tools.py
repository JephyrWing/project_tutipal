"""Tool names and the re-check that runs right before a node uses a tool (contract 7.2, 7.3)."""
from collections.abc import Collection
from typing import get_args

from app.models.agent import ToolName

# The request model's Literal is the single list of tool names (Round 1 P15); unpacking fails loudly if it changes.
TOOL_NAMES: tuple[str, ...] = get_args(ToolName)
ANSWER_QUESTION, MATERIAL_SEARCH, QUESTION_VARIANT = TOOL_NAMES


class ToolNotAllowed(Exception):
    """A node tried to use a tool outside the request's allowedTools."""

    def __init__(self, tool: str) -> None:
        super().__init__(f"tool not allowed: {tool}")
        self.tool = tool


def require_tool(allowed: Collection[str], tool: str) -> None:
    if tool not in allowed:
        raise ToolNotAllowed(tool)
