"""PT-86 review round 1: bounded queue wait, disconnected clients, settings that never block startup."""
import asyncio
import logging
import threading
import time

import httpx
import pytest

from app.core.settings import load_rerank_settings
from app.main import app
from app.models.rerank import RerankRequest
from app.services import rerank_service

pytestmark = pytest.mark.anyio
AUTH = {"X-Internal-Key": "pt26-test-key"}


class SlowScorer:
    model_name = "slow-fake"

    def __init__(self, delay: float) -> None:
        self.delay = delay
        self.calls = 0
        self._lock = threading.Lock()

    def score(self, query, documents):
        with self._lock:
            self.calls += 1
        time.sleep(self.delay)
        return [1.0 for _ in documents]


@pytest.fixture(autouse=True)
def rerank_env(monkeypatch):
    for name in ("RERANK_ENABLED", "RERANK_MODEL_DIR", "RERANK_MODEL_NAME", "RERANK_MAX_LENGTH", "RERANK_THREADS",
                 "RERANK_QUEUE_TIMEOUT_MS"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("RERANK_ENABLED", "true")
    rerank_service.set_scorer(None)
    yield
    rerank_service.set_scorer(None)


async def post(body):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post("/v1/rerank", json=body, headers=AUTH)


BODY = {"query": "정렬", "documents": ["버블 정렬", "선택 정렬"]}


async def test_queued_request_gives_up_instead_of_piling_up(monkeypatch):
    monkeypatch.setenv("RERANK_QUEUE_TIMEOUT_MS", "100")
    scorer = SlowScorer(delay=0.5)
    rerank_service.set_scorer(scorer)

    responses = await asyncio.gather(post(BODY), post(BODY), post(BODY))

    assert sorted(r.status_code for r in responses) == [200, 503, 503]
    assert scorer.calls == 1
    assert all(r.json() == {"detail": "rerank busy"} for r in responses if r.status_code == 503)


async def test_default_queue_wait_is_below_spring_timeout():
    assert load_rerank_settings().queue_timeout_ms < 2000


@pytest.mark.parametrize("enabled", [None, "true"])
async def test_defaults_are_the_measured_config(monkeypatch, enabled):
    """Leader ruling: code defaults = measured/frozen values (256 tokens, 1000 ms queue wait)."""
    if enabled is None:
        monkeypatch.delenv("RERANK_ENABLED", raising=False)
    settings = load_rerank_settings()

    assert settings.max_length == 256
    assert settings.queue_timeout_ms == 1000
    assert settings.threads == 2


async def test_disconnected_client_is_not_scored():
    scorer = SlowScorer(delay=0)
    rerank_service.set_scorer(scorer)

    async def gone() -> bool:
        return True

    with pytest.raises(Exception) as error:
        await rerank_service.rerank(RerankRequest(**BODY), gone)

    assert getattr(error.value, "status_code", None) == 503
    assert scorer.calls == 0


async def test_connected_client_is_scored():
    scorer = SlowScorer(delay=0)
    rerank_service.set_scorer(scorer)

    async def here() -> bool:
        return False

    view = await rerank_service.rerank(RerankRequest(**BODY), here)

    assert view.scores == [1.0, 1.0]
    assert scorer.calls == 1


@pytest.mark.parametrize("name,value", [("RERANK_MAX_LENGTH", "abc"), ("RERANK_MAX_LENGTH", "1"),
                                        ("RERANK_THREADS", "0"), ("RERANK_QUEUE_TIMEOUT_MS", "-1")])
async def test_bad_rerank_env_does_not_block_startup_when_disabled(monkeypatch, name, value):
    monkeypatch.setenv("RERANK_ENABLED", "false")
    monkeypatch.setenv(name, value)

    async with app.router.lifespan_context(app):
        response = await post(BODY)

    assert response.status_code == 503


@pytest.mark.parametrize("name,value", [("RERANK_MAX_LENGTH", "abc"), ("RERANK_THREADS", "999")])
async def test_bad_rerank_env_when_enabled_disables_rerank_only(monkeypatch, caplog, tmp_path, name, value):
    monkeypatch.setenv("RERANK_MODEL_DIR", str(tmp_path))
    monkeypatch.setenv(name, value)
    caplog.set_level(logging.WARNING)

    async with app.router.lifespan_context(app):
        assert rerank_service.get_scorer() is None
        response = await post(BODY)

    assert response.status_code == 503
    assert name in caplog.text
    assert value not in caplog.text.replace(name, "")


@pytest.mark.parametrize("blank", ["\u200b", "\ufeff", " \u200b\u200d "])
async def test_invisible_only_strings_are_rejected(blank):
    rerank_service.set_scorer(SlowScorer(delay=0))

    assert (await post({"query": blank, "documents": ["문서"]})).status_code == 422
    assert (await post({"query": "질문", "documents": [blank]})).status_code == 422
