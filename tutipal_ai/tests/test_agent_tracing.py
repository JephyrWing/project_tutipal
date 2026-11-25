"""PT-85 AC-25: LangSmith/LangChain tracing is fail-closed (request bodies must never leave the server)."""
import re
from pathlib import Path

import pytest

from app.core.settings import require_tracing_disabled
from app.main import app

ROOT = Path(__file__).resolve().parents[1]
# langsmith.utils.tracing_is_enabled reads TRACING_V2 then TRACING in both namespaces (Round 1 P1).
FLAGS = ["LANGSMITH_TRACING", "LANGCHAIN_TRACING_V2", "LANGSMITH_TRACING_V2", "LANGCHAIN_TRACING"]


@pytest.mark.parametrize("name", FLAGS)
@pytest.mark.parametrize("value", ["true", "True", "TRUE", "1", "yes", "on", " true "])
def test_tracing_flag_on_is_refused(monkeypatch, name, value):
    monkeypatch.setenv(name, value)
    with pytest.raises(RuntimeError):
        require_tracing_disabled()


@pytest.mark.parametrize("value", [None, "", "false", "False", "0", "no", "off"])
def test_tracing_flag_off_or_missing_is_allowed(monkeypatch, value):
    for name in FLAGS:
        if value is None:
            monkeypatch.delenv(name, raising=False)
        else:
            monkeypatch.setenv(name, value)
    require_tracing_disabled()


@pytest.mark.anyio
@pytest.mark.parametrize("name", FLAGS)
async def test_app_startup_fails_when_tracing_is_on(monkeypatch, name):
    monkeypatch.setenv(name, "true")
    with pytest.raises(RuntimeError):
        async with app.router.lifespan_context(app):
            pass


@pytest.mark.anyio
async def test_app_starts_when_tracing_is_off(monkeypatch):
    monkeypatch.setenv("LANGSMITH_TRACING", "false")
    async with app.router.lifespan_context(app):
        pass


@pytest.mark.parametrize("name", FLAGS)
def test_dockerfile_pins_tracing_off(name):
    dockerfile = (ROOT / "Dockerfile").read_text(encoding="utf-8")
    assert re.search(rf"^ENV\b.*\b{name}=false\b", dockerfile, re.MULTILINE), name


@pytest.mark.parametrize("name", FLAGS)
def test_guard_agrees_with_langsmith(monkeypatch, name):
    """Every name that turns langsmith tracing on must also stop startup (Round 1 P1 regression)."""
    from langsmith import utils as langsmith_utils

    monkeypatch.setenv(name, "true")
    langsmith_utils.get_env_var.cache_clear()
    assert langsmith_utils.tracing_is_enabled()
    with pytest.raises(RuntimeError):
        require_tracing_disabled()


def test_langsmith_reported_tracing_is_refused(monkeypatch):
    """A name we do not know about yet is still caught through langsmith's own answer."""
    from langsmith import utils as langsmith_utils

    monkeypatch.setattr(langsmith_utils, "tracing_is_enabled", lambda: True)
    with pytest.raises(RuntimeError):
        require_tracing_disabled()


def test_langsmith_check_sees_current_environment(monkeypatch):
    """get_env_var is lru_cached; a stale cached 'true' must not linger after the variable is cleared."""
    from langsmith import utils as langsmith_utils

    monkeypatch.setenv("LANGCHAIN_TRACING", "true")
    langsmith_utils.get_env_var.cache_clear()
    assert langsmith_utils.tracing_is_enabled()
    monkeypatch.delenv("LANGCHAIN_TRACING")
    require_tracing_disabled()
