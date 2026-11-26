"""PT-90 AC-13: the focus model is loaded only from a LightGBM text file whose SHA-256 matches FOCUS_MODEL_SHA256,
and only when the sidecar .json and the booster agree with the code constants (28 features, 3 classes).

Any failure leaves the app running: /v1/focus/predict answers 503 with a fixed text, other endpoints still work.

lightgbm is pinned in requirements.lock.txt / requirements-test.lock.txt (PT-90), so CI runs the training-based
tests; a local environment without lightgbm skips them. The real model smoke test runs only when
FOCUS_REAL_MODEL_PATH points at focus_lgbm.txt.
"""
import hashlib
import json
import os
import re
from pathlib import Path

import httpx
import pytest

from app.main import app
from app.services import focus_service, rerank_service
from app.services.focus_features import CLASSES, FEATURE_COLUMNS

pytestmark = pytest.mark.anyio
AUTH = {"X-Internal-Key": "pt26-test-key"}
APP_DIR = Path(__file__).resolve().parents[1] / "app"


@pytest.fixture(autouse=True)
def focus_env(monkeypatch):
    for name in ("FOCUS_MODEL_PATH", "FOCUS_MODEL_SHA256"):
        monkeypatch.delenv(name, raising=False)
    focus_service.set_predictor(None)
    yield
    focus_service.set_predictor(None)


@pytest.fixture
def lightgbm():
    return pytest.importorskip("lightgbm")


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def train(lightgbm, path: Path, feature_names=None, num_class=3):
    """Small multiclass model on random data, saved as a LightGBM text file."""
    import numpy as np

    rng = np.random.default_rng(90)
    names = list(feature_names or FEATURE_COLUMNS)
    x = rng.random((120, len(names)))
    y = np.arange(120) % num_class
    params = {"objective": "multiclass", "num_class": num_class, "num_leaves": 4, "min_data_in_leaf": 1,
              "verbose": -1, "seed": 90}
    booster = lightgbm.train(params, lightgbm.Dataset(x, label=y, feature_name=names), num_boost_round=3)
    booster.save_model(str(path))
    return path


def sidecar(model_path: Path, features=None, classes=None):
    meta = {"features": list(features or FEATURE_COLUMNS), "classes": list(classes or CLASSES)}
    model_path.with_suffix(".json").write_text(json.dumps(meta), encoding="utf-8")


def use_model(monkeypatch, path: Path, digest=None):
    monkeypatch.setenv("FOCUS_MODEL_PATH", str(path))
    monkeypatch.setenv("FOCUS_MODEL_SHA256", digest if digest is not None else sha256(path))


def rows(count=15):
    return [{"minutes_ago": m, "category": "ide", "idle_sec": 5, "switch_count": 1} for m in range(1, count + 1)]


async def post_predict():
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post("/v1/focus/predict", json={"rows": rows(), "hour": 10.0, "weekday": 1},
                                 headers=AUTH)


# ---- source rules ----

def test_focus_code_never_uses_pickle_or_joblib():
    for name in ("services/focus_service.py", "services/focus_features.py", "controllers/focus_controller.py"):
        source = (APP_DIR / name).read_text(encoding="utf-8")
        assert not re.search(r"\b(pickle|joblib|cloudpickle|dill)\b", source), name


def test_focus_service_loads_the_booster_from_the_checked_bytes():
    # leader ruling (Round 1, L3 + Z5): the hashed bytes go to Booster(model_str=), the path is never reopened
    source = (APP_DIR / "services/focus_service.py").read_text(encoding="utf-8")
    assert "model_str=" in source
    assert "model_file" not in source


# ---- success ----

async def test_valid_model_with_matching_hash_and_sidecar_is_loaded(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    sidecar(model)
    use_model(monkeypatch, model)

    await focus_service.startup()

    assert focus_service.get_predictor() is not None
    response = await post_predict()
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "ok"
    assert data["state"] in CLASSES
    assert sum(data["probabilities"].values()) == pytest.approx(1.0, abs=1e-6)
    assert data["model_version"]


async def test_hash_comparison_ignores_letter_case(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    sidecar(model)
    use_model(monkeypatch, model, sha256(model).upper())

    await focus_service.startup()

    assert focus_service.get_predictor() is not None


# ---- failures: 503, fixed text, no path or exception in the body ----

async def assert_unavailable(tmp_path):
    assert focus_service.get_predictor() is None
    response = await post_predict()
    assert response.status_code == 503
    assert str(tmp_path) not in response.text
    assert "focus_lgbm" not in response.text
    assert "Error" not in response.text
    return response.json()


async def test_no_model_path_configured_is_unavailable(tmp_path):
    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_missing_model_file_is_unavailable(monkeypatch, tmp_path):
    monkeypatch.setenv("FOCUS_MODEL_PATH", str(tmp_path / "focus_lgbm.txt"))
    monkeypatch.setenv("FOCUS_MODEL_SHA256", "0" * 64)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_hash_mismatch_is_unavailable(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    sidecar(model)
    use_model(monkeypatch, model, "0" * 64)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_missing_hash_is_unavailable(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    sidecar(model)
    use_model(monkeypatch, model, "")

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_missing_sidecar_json_is_unavailable(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    use_model(monkeypatch, model)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_sidecar_feature_order_mismatch_is_unavailable(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    swapped = list(FEATURE_COLUMNS)
    swapped[0], swapped[1] = swapped[1], swapped[0]
    sidecar(model, features=swapped)
    use_model(monkeypatch, model)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_sidecar_class_mismatch_is_unavailable(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt")
    sidecar(model, classes=["focus", "distracted", "break"])
    use_model(monkeypatch, model)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_booster_feature_names_mismatch_is_unavailable(monkeypatch, tmp_path, lightgbm):
    names = list(FEATURE_COLUMNS)
    names[-1], names[-2] = names[-2], names[-1]
    model = train(lightgbm, tmp_path / "focus_lgbm.txt", feature_names=names)
    sidecar(model)
    use_model(monkeypatch, model)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_booster_with_two_classes_is_unavailable(monkeypatch, tmp_path, lightgbm):
    model = train(lightgbm, tmp_path / "focus_lgbm.txt", num_class=2)
    sidecar(model)
    use_model(monkeypatch, model)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_not_a_lightgbm_file_is_unavailable(monkeypatch, tmp_path):
    model = tmp_path / "focus_lgbm.txt"
    model.write_text("this is not a model", encoding="utf-8")
    sidecar(model)
    use_model(monkeypatch, model)

    await focus_service.startup()

    await assert_unavailable(tmp_path)


async def test_every_failure_returns_the_same_body(monkeypatch, tmp_path):
    await focus_service.startup()
    first = await assert_unavailable(tmp_path)

    monkeypatch.setenv("FOCUS_MODEL_PATH", str(tmp_path / "missing.txt"))
    monkeypatch.setenv("FOCUS_MODEL_SHA256", "0" * 64)
    await focus_service.startup()
    second = await assert_unavailable(tmp_path)

    assert first == second


# ---- the app keeps running ----

async def test_app_starts_with_a_bad_model_and_other_endpoints_still_work(monkeypatch, tmp_path):
    monkeypatch.setenv("FOCUS_MODEL_PATH", str(tmp_path / "missing.txt"))
    monkeypatch.setenv("FOCUS_MODEL_SHA256", "0" * 64)
    monkeypatch.setenv("RERANK_ENABLED", "true")
    monkeypatch.setenv("RERANK_MODEL_DIR", str(tmp_path / "missing-rerank"))

    class Scorer:
        model_name = "fake-reranker"

        def score(self, query, documents):
            return [1.0 for _ in documents]

    async with app.router.lifespan_context(app):
        assert focus_service.get_predictor() is None
        assert (await post_predict()).status_code == 503
        rerank_service.set_scorer(Scorer())
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
                response = await client.post("/v1/rerank", json={"query": "q", "documents": ["d"]}, headers=AUTH)
        finally:
            rerank_service.set_scorer(None)
    assert response.status_code == 200


# ---- real model smoke (optional) ----

async def test_real_model_smoke(monkeypatch, lightgbm):
    real = os.environ.get("FOCUS_REAL_MODEL_PATH")
    if not real:
        pytest.skip("FOCUS_REAL_MODEL_PATH not set")
    path = Path(real)
    use_model(monkeypatch, path)

    await focus_service.startup()

    assert focus_service.get_predictor() is not None
    response = await post_predict()
    assert response.status_code == 200
    assert response.json()["state"] in CLASSES


def test_lightgbm_predictor_asks_for_a_single_thread_per_call():
    # Round 1 review (T2): one row per request, so no OpenMP thread pool per call
    import numpy as np

    class RecordingBooster:
        def __init__(self):
            self.kwargs = []

        def predict(self, features, **kwargs):
            self.kwargs.append(kwargs)
            return np.asarray([[0.2, 0.3, 0.5]])

    booster = RecordingBooster()
    predictor = focus_service.LightGbmPredictor(booster, "focus-lgbm-test")

    assert predictor.predict_proba([0.0] * 28) == pytest.approx([0.2, 0.3, 0.5])
    assert booster.kwargs == [{"num_threads": 1}]


# ---- leader ruling (Round 1, L3 + Z5): checked bytes only ----

async def test_corrupt_model_with_matching_hash_is_unavailable_without_path_in_native_stderr(
        monkeypatch, tmp_path, lightgbm, capfd):
    model = tmp_path / "focus_lgbm.txt"
    # with Booster(model_file=) this content made LightGBM print "... in model file <full path>" to native stderr
    model.write_text("garbage model\n", encoding="utf-8")
    sidecar(model)
    use_model(monkeypatch, model)
    capfd.readouterr()

    await focus_service.startup()

    body = await assert_unavailable(tmp_path)
    assert body == {"detail": focus_service.UNAVAILABLE_DETAIL}
    captured = capfd.readouterr()
    for text in (captured.err, captured.out):
        assert str(tmp_path) not in text
        assert "focus_lgbm" not in text


async def test_model_bytes_that_are_not_utf8_are_unavailable(monkeypatch, tmp_path):
    model = tmp_path / "focus_lgbm.txt"
    model.write_bytes(b"tree\n\xff\xfe\xfa not utf-8\n")
    sidecar(model)
    use_model(monkeypatch, model)

    await focus_service.startup()

    body = await assert_unavailable(tmp_path)
    assert body == {"detail": focus_service.UNAVAILABLE_DETAIL}
