"""PT-90: POST /v1/focus/predict (AC-11 contract, AC-12 decision, AC-15 logs).

No model file is loaded here: a fake predictor is injected through focus_service.set_predictor().
Model loading is covered by test_focus_model.py.
"""
import json
import logging
import math

import httpx
import pytest

from app.main import app
from app.services import focus_service, rerank_service
from app.services.focus_features import CLASSES

pytestmark = pytest.mark.anyio
AUTH = {"X-Internal-Key": "pt26-test-key"}
PATH = "/v1/focus/predict"
# Distinctive values: if any of them shows up in a log line or an error body, request data leaked.
SECRET_HOUR = 13.7654321
SECRET_PROBS = [0.1234567891, 0.2345678912, 0.6419753197]


class FakePredictor:
    """Returns fixed probabilities in CLASSES order and records every vector it was given."""

    model_version = "fake-focus-1"

    def __init__(self, probabilities=None, error=None):
        self.calls = []
        self._probabilities = probabilities if probabilities is not None else [0.1, 0.2, 0.7]
        self._error = error

    def predict_proba(self, vector):
        self.calls.append(list(vector))
        if self._error is not None:
            raise self._error
        return list(self._probabilities)


@pytest.fixture(autouse=True)
def focus_env(monkeypatch):
    for name in ("FOCUS_MODEL_PATH", "FOCUS_MODEL_SHA256"):
        monkeypatch.delenv(name, raising=False)
    focus_service.set_predictor(None)
    yield
    focus_service.set_predictor(None)


@pytest.fixture
def fake():
    predictor = FakePredictor()
    focus_service.set_predictor(predictor)
    return predictor


def rows(count, category="ide", idle=5, switches=1):
    return [{"minutes_ago": m, "category": category, "idle_sec": idle, "switch_count": switches}
            for m in range(1, count + 1)]


def body(count=15, hour=10.5, weekday=2, **row_args):
    return {"rows": rows(count, **row_args), "hour": hour, "weekday": weekday}


async def post(payload, headers=AUTH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(PATH, json=payload, headers=headers)


async def post_raw(content: bytes, headers=AUTH, path=PATH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(path, content=content, headers={**headers, "Content-Type": "application/json"})


# ---- AC-12 decision ----

async def test_thirteen_rows_return_ok_with_state_probabilities_and_model_version(fake):
    response = await post(body(count=13))

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["state"] == "focus"
    assert set(data["probabilities"]) == set(CLASSES)
    assert math.isclose(sum(data["probabilities"].values()), 1.0, abs_tol=1e-6)
    assert data["probabilities"]["focus"] == pytest.approx(0.7)
    assert data["model_version"] == "fake-focus-1"
    assert len(fake.calls) == 1
    assert len(fake.calls[0]) == 28


async def test_state_is_the_class_with_the_highest_probability():
    focus_service.set_predictor(FakePredictor(probabilities=[0.6, 0.3, 0.1]))

    response = await post(body(count=15))

    assert response.status_code == 200
    assert response.json()["state"] == "break"


async def test_distracted_is_reported():
    focus_service.set_predictor(FakePredictor(probabilities=[0.2, 0.5, 0.3]))

    response = await post(body(count=14))

    assert response.json()["state"] == "distracted"


@pytest.mark.parametrize("count", [0, 1, 12])
async def test_fewer_than_13_rows_is_insufficient_data_without_calling_the_model(fake, count):
    response = await post(body(count=count))

    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "insufficient_data"
    assert data.get("state") is None
    assert data.get("probabilities") is None
    assert fake.calls == []


async def test_rows_in_any_order_count_by_minutes_ago(fake):
    payload = body(count=13)
    payload["rows"].reverse()

    response = await post(payload)

    assert response.json()["status"] == "ok"


async def test_feature_vector_uses_hour_and_weekday(fake):
    await post(body(count=13, hour=6.0, weekday=5))

    vector = fake.calls[0]
    assert vector[25] == pytest.approx(1.0)  # hour_sin
    assert vector[26] == pytest.approx(0.0, abs=1e-9)  # hour_cos
    assert vector[27] == 5.0  # weekday


async def test_503_with_fixed_text_when_no_model_is_loaded():
    response = await post(body(count=15))

    assert response.status_code == 503
    assert "detail" in response.json()


async def test_predictor_failure_is_server_error_without_details():
    focus_service.set_predictor(FakePredictor(error=RuntimeError("C:/models/secret-path.txt boom")))

    response = await post(body(count=15))

    assert response.status_code >= 500
    assert "secret-path" not in response.text
    assert "RuntimeError" not in response.text


@pytest.mark.parametrize("bad", [[0.5, 0.5], [math.nan, 0.5, 0.5], [0.1, 0.2, math.inf]])
async def test_predictor_returning_invalid_probabilities_is_server_error(bad):
    focus_service.set_predictor(FakePredictor(probabilities=bad))

    response = await post(body(count=15))

    assert response.status_code >= 500


# ---- AC-11 contract: auth ----

@pytest.mark.parametrize("key", [None, "wrong", ""])
async def test_internal_key_required(fake, key):
    response = await post(body(), {} if key is None else {"X-Internal-Key": key})

    assert response.status_code == 401
    assert fake.calls == []


async def test_auth_is_checked_before_validation(fake):
    response = await post({"rows": "nonsense"}, {})

    assert response.status_code == 401


# ---- AC-11 contract: body size ----

async def test_body_over_8kb_is_413(fake):
    raw = json.dumps(body()).encode()
    padded = raw[:-1] + b" " * (8192 - len(raw) + 10) + b"}"
    assert len(padded) > 8192

    response = await post_raw(padded)

    assert response.status_code == 413
    assert fake.calls == []


async def test_body_just_under_8kb_is_accepted(fake):
    raw = json.dumps(body()).encode()
    padded = raw[:-1] + b" " * (8192 - len(raw)) + b"}"
    assert len(padded) <= 8192

    response = await post_raw(padded)

    assert response.status_code == 200


async def test_8kb_limit_applies_only_to_the_focus_path(monkeypatch):
    monkeypatch.setenv("RERANK_ENABLED", "true")
    rerank_service.set_scorer(None)
    raw = json.dumps({"query": "q", "documents": ["d"]}).encode()
    padded = raw[:-1] + b" " * 9000 + b"}"

    response = await post_raw(padded, path="/v1/rerank")

    assert response.status_code != 413


# ---- AC-11 contract: schema ----

@pytest.mark.parametrize("change", [
    {"extra": "PRIVATE-EXTRA"},
    {"rows": rows(16)},
    {"rows": "PRIVATE-ROWS"},
    {"hour": 24},
    {"hour": -0.1},
    {"hour": "PRIVATE-HOUR"},
    {"weekday": 7},
    {"weekday": -1},
    {"weekday": 2.5},
])
async def test_top_level_violations_are_422_without_echoing_input(fake, change):
    payload = body()
    payload.update(change)

    response = await post(payload)

    assert response.status_code == 422
    assert "PRIVATE-" not in response.text
    assert fake.calls == []


@pytest.mark.parametrize("change", [
    {"minutes_ago": 0},
    {"minutes_ago": 16},
    {"category": "PRIVATE-CATEGORY"},
    {"idle_sec": 61},
    {"idle_sec": -1},
    {"switch_count": 121},
    {"switch_count": -1},
    {"exe_name": "PRIVATE-chrome.exe"},
    {"user_id": "PRIVATE-USER"},
])
async def test_row_violations_are_422_without_echoing_input(fake, change):
    payload = body()
    payload["rows"][0].update(change)

    response = await post(payload)

    assert response.status_code == 422
    assert "PRIVATE-" not in response.text
    assert fake.calls == []


@pytest.mark.parametrize("missing", ["rows", "hour", "weekday"])
async def test_missing_top_level_field_is_422(fake, missing):
    payload = body()
    del payload[missing]

    assert (await post(payload)).status_code == 422


async def test_duplicate_minutes_ago_is_422(fake):
    payload = body(count=13)
    payload["rows"][1]["minutes_ago"] = payload["rows"][0]["minutes_ago"]

    response = await post(payload)

    assert response.status_code == 422
    assert fake.calls == []


@pytest.mark.parametrize("literal", [b"NaN", b"Infinity", b"-Infinity"])
async def test_nan_and_infinity_hour_are_422(fake, literal):
    raw = json.dumps(body()).replace('"hour": 10.5', '"hour": __X__').encode().replace(b"__X__", literal)

    response = await post_raw(raw)

    assert response.status_code == 422
    assert fake.calls == []


async def test_all_six_categories_are_accepted(fake):
    payload = body(count=13)
    for index, category in enumerate(["ide", "browser", "terminal", "game", "chat", "other"]):
        payload["rows"][index]["category"] = category

    assert (await post(payload)).status_code == 200


async def test_boundary_values_are_accepted(fake):
    payload = body(count=15, hour=23.99, weekday=6)
    payload["rows"][0].update({"idle_sec": 60, "switch_count": 120})
    payload["rows"][1].update({"idle_sec": 0, "switch_count": 0})

    assert (await post(payload)).status_code == 200


# ---- AC-15 logs ----

async def test_logs_contain_no_feature_values_or_probabilities(caplog):
    focus_service.set_predictor(FakePredictor(probabilities=SECRET_PROBS))
    caplog.set_level(logging.DEBUG)

    response = await post(body(count=15, hour=SECRET_HOUR, switches=117))

    assert response.status_code == 200
    for secret in ("13.765", "0.12345", "0.23456", "0.64197"):
        assert secret not in caplog.text
    assert "focus" in caplog.text.lower()  # a status line is written


async def test_insufficient_data_logs_contain_no_input(caplog, fake):
    caplog.set_level(logging.DEBUG)

    await post(body(count=3, hour=SECRET_HOUR))

    assert "13.765" not in caplog.text


async def test_failure_logs_only_the_exception_type(caplog):
    focus_service.set_predictor(FakePredictor(error=ValueError("PRIVATE-MESSAGE 13.7654321")))
    caplog.set_level(logging.DEBUG)

    await post(body(count=15, hour=SECRET_HOUR))

    assert "PRIVATE-MESSAGE" not in caplog.text
    assert "13.765" not in caplog.text


# ---- Round 1 review: hour is strict like the other fields ----

@pytest.mark.parametrize("hour", ["10.5", True, False, None])
async def test_hour_as_string_or_bool_is_422(fake, hour):
    payload = body()
    payload["hour"] = hour

    response = await post(payload)

    assert response.status_code == 422
    assert fake.calls == []


async def test_integer_hour_is_still_accepted(fake):
    response = await post(body(count=13, hour=10))

    assert response.status_code == 200
    assert len(fake.calls) == 1
