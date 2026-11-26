"""PT-86: POST /v1/rerank scores the candidates Spring sends (stateless, internal key only, no provider dependency).

No model file is loaded in tests: a fake scorer is injected through rerank_service.set_scorer().
"""
import asyncio
import logging
import threading
import time

import httpx
import pytest

from app.main import app
from app.services import rerank_service

pytestmark = pytest.mark.anyio
AUTH = {"X-Internal-Key": "pt26-test-key"}
PRIVATE_QUERY = "PRIVATE-QUERY 버블 정렬의 시간 복잡도"
PRIVATE_DOC = "PRIVATE-DOC 버블 정렬은 인접한 두 원소를 비교한다"


class FakeScorer:
    """Deterministic scorer: score = document length, so order/length are easy to check."""

    model_name = "fake-reranker"

    def __init__(self, scores=None, error=None, delay=0.0):
        self.calls = []
        self.threads = []
        self.active = 0
        self.max_active = 0
        self._lock = threading.Lock()
        self._scores = scores
        self._error = error
        self._delay = delay

    def score(self, query, documents):
        with self._lock:
            self.active += 1
            self.max_active = max(self.max_active, self.active)
        try:
            self.calls.append((query, list(documents)))
            self.threads.append(threading.get_ident())
            if self._delay:
                time.sleep(self._delay)  # simulated CPU work inside the worker thread
            if self._error is not None:
                raise self._error
            if self._scores is not None:
                return list(self._scores)
            return [float(len(document)) for document in documents]
        finally:
            with self._lock:
                self.active -= 1


@pytest.fixture(autouse=True)
def rerank_env(monkeypatch):
    for name in ("RERANK_ENABLED", "RERANK_MODEL_DIR", "RERANK_MODEL_NAME", "RERANK_MAX_LENGTH", "RERANK_THREADS"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("RERANK_ENABLED", "true")
    rerank_service.set_scorer(None)
    yield
    rerank_service.set_scorer(None)


@pytest.fixture
def fake():
    scorer = FakeScorer()
    rerank_service.set_scorer(scorer)
    return scorer


async def post(body, headers=AUTH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post("/v1/rerank", json=body, headers=headers)


def body(query="정렬이 뭐야?", documents=("짧은 조각", "조금 더 긴 조각입니다", "가")):
    return {"query": query, "documents": list(documents)}


# ---- happy path ----

async def test_returns_model_and_scores_in_input_order(fake):
    documents = ["짧은 조각", "조금 더 긴 조각입니다", "가"]
    response = await post(body(documents=documents))

    assert response.status_code == 200
    assert response.json() == {"model": "fake-reranker", "scores": [float(len(d)) for d in documents]}
    assert fake.calls == [("정렬이 뭐야?", documents)]


async def test_scores_have_same_length_as_documents_for_max_batch(fake):
    documents = [f"조각 {i} " + "가" * i for i in range(32)]
    response = await post(body(documents=documents))

    assert response.status_code == 200
    assert response.json()["scores"] == [float(len(d)) for d in documents]


async def test_works_when_model_provider_is_unsupported(monkeypatch, fake):
    monkeypatch.setenv("MODEL_PROVIDER", "unknown")
    response = await post(body())

    assert response.status_code == 200
    assert len(response.json()["scores"]) == 3


async def test_scoring_runs_off_the_event_loop_thread(fake):
    loop_thread = threading.get_ident()
    response = await post(body())

    assert response.status_code == 200
    assert fake.threads and all(thread != loop_thread for thread in fake.threads)


async def test_concurrent_requests_are_scored_one_at_a_time():
    scorer = FakeScorer(delay=0.05)
    rerank_service.set_scorer(scorer)

    responses = await asyncio.gather(post(body()), post(body()), post(body()))

    assert [r.status_code for r in responses] == [200, 200, 200]
    assert scorer.max_active == 1


# ---- auth ----

@pytest.mark.parametrize("key", [None, "wrong", ""])
async def test_internal_key_required(fake, key):
    response = await post(body(), {} if key is None else {"X-Internal-Key": key})

    assert response.status_code == 401
    assert response.json() == {"detail": "internal authentication required"}
    assert fake.calls == []


async def test_empty_server_key_rejected(monkeypatch, fake):
    monkeypatch.delenv("AI_INTERNAL_KEY")

    assert (await post(body())).status_code == 401
    assert fake.calls == []


async def test_auth_checked_before_model_availability():
    assert (await post(body(), {})).status_code == 401


# ---- schema limits ----

@pytest.mark.parametrize("change", [
    {"query": ""},
    {"query": "   "},
    {"query": "\n\t "},
    {"query": "x" * 1001},
    {"documents": []},
    {"documents": ["x"] * 33},
    {"documents": [""]},
    {"documents": ["정상 조각", "   "]},
    {"documents": ["x" * 2001]},
    {"documents": "not a list"},
    {"documents": [1, 2]},
    {"query": None},
    {"extra": "PRIVATE-QUERY"},
])
async def test_schema_violations_are_422_without_echoing_input(fake, change):
    request = body(query=PRIVATE_QUERY, documents=[PRIVATE_DOC])
    request.update(change)
    response = await post(request)

    assert response.status_code == 422
    assert "PRIVATE-" not in response.text
    assert fake.calls == []


@pytest.mark.parametrize("missing", ["query", "documents"])
async def test_missing_field_is_422(fake, missing):
    request = body()
    del request[missing]

    assert (await post(request)).status_code == 422


async def test_upper_bounds_are_inclusive(fake):
    response = await post(body(query="q" * 1000, documents=["d" * 2000] * 32))

    assert response.status_code == 200
    assert response.json()["scores"] == [2000.0] * 32


async def test_single_char_query_and_document_are_accepted(fake):
    response = await post(body(query="q", documents=["d"]))

    assert response.status_code == 200
    assert response.json()["scores"] == [1.0]


# ---- unavailable model ----

async def test_503_when_no_model_loaded():
    response = await post(body(query=PRIVATE_QUERY, documents=[PRIVATE_DOC]))

    assert response.status_code == 503
    assert "PRIVATE-" not in response.text


async def test_503_when_rerank_disabled_even_with_scorer(monkeypatch, fake):
    monkeypatch.setenv("RERANK_ENABLED", "false")
    response = await post(body())

    assert response.status_code == 503
    assert fake.calls == []


async def test_app_starts_with_missing_model_and_returns_503(monkeypatch, tmp_path):
    monkeypatch.setenv("RERANK_MODEL_DIR", str(tmp_path / "missing-model"))
    async with app.router.lifespan_context(app):
        assert rerank_service.get_scorer() is None
        response = await post(body())
    assert response.status_code == 503


async def test_app_starts_when_rerank_disabled(monkeypatch, tmp_path):
    monkeypatch.setenv("RERANK_ENABLED", "false")
    monkeypatch.setenv("RERANK_MODEL_DIR", str(tmp_path / "missing-model"))
    async with app.router.lifespan_context(app):
        response = await post(body())
    assert response.status_code == 503


async def test_scorer_failure_is_server_error_without_echoing_input():
    rerank_service.set_scorer(FakeScorer(error=RuntimeError(PRIVATE_DOC)))
    response = await post(body(query=PRIVATE_QUERY, documents=[PRIVATE_DOC]))

    assert response.status_code >= 500
    assert "PRIVATE-" not in response.text


async def test_scorer_returning_wrong_length_is_server_error():
    rerank_service.set_scorer(FakeScorer(scores=[0.1]))
    response = await post(body(documents=["a", "b", "c"]))

    assert response.status_code >= 500


# ---- logging ----

async def test_logs_never_contain_request_text(caplog, fake):
    caplog.set_level(logging.DEBUG)
    response = await post(body(query=PRIVATE_QUERY, documents=[PRIVATE_DOC, PRIVATE_DOC + " 2"]))

    assert response.status_code == 200
    assert "PRIVATE-" not in caplog.text


async def test_failure_logs_never_contain_request_text(caplog):
    caplog.set_level(logging.DEBUG)
    rerank_service.set_scorer(FakeScorer(error=RuntimeError(PRIVATE_DOC)))
    await post(body(query=PRIVATE_QUERY, documents=[PRIVATE_DOC]))

    assert "PRIVATE-" not in caplog.text
