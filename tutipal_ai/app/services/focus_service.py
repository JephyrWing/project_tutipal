"""PT-90: reference focus state from the trained LightGBM model (docs/AI_WORKFLOW_CONTRACT.md 8).

- The model is read once at startup (lifespan) from FOCUS_MODEL_PATH, a LightGBM text file: its bytes are read once,
  their SHA-256 must match FOCUS_MODEL_SHA256, and exactly those bytes (UTF-8) go to lightgbm.Booster(model_str=...)
  (text format, no object deserialization, no second open of the path between the check and the load, and no path
  in LightGBM's own native error output). The sidecar .json next to it and the booster itself must list the same 28 features and 3
  classes as the code. Any failure leaves the app running; /v1/focus/predict answers 503 with one fixed text.
- lightgbm is imported only when a model is loaded (tests and model-less environments still start).
- Fewer than MIN_ROWS minutes in the 15-minute window: no model call, status insufficient_data.
- Logs carry the status, elapsed ms and exception type only: no feature values, probabilities or paths.
"""
import hashlib
import json
import logging
import math
import time
from pathlib import Path
from typing import Protocol

import anyio
from fastapi import HTTPException

from app.core.settings import FocusSettings, load_focus_settings
from app.models.focus import FocusPredictRequest
from app.services.focus_features import CLASSES, FEATURE_COLUMNS, build_vector
from app.views.focus_view import FocusView

log = logging.getLogger(__name__)

MIN_ROWS = 13
WINDOW_MINUTES = 15
UNAVAILABLE_DETAIL = "focus model unavailable"
# far above a 3-class LightGBM text model; a bigger file is refused before it is read into memory
MAX_MODEL_BYTES = 64 * 1024 * 1024


class FocusPredictor(Protocol):
    """Probabilities in CLASSES order for one 28-float vector. Synchronous; runs in a worker thread."""

    model_version: str

    def predict_proba(self, vector: list[float]) -> list[float]: ...


class ModelRejected(Exception):
    """The configured model failed a check. The reason is a short fixed code (never a path or value)."""


_predictor: FocusPredictor | None = None


def set_predictor(predictor: FocusPredictor | None) -> None:
    global _predictor
    _predictor = predictor


def get_predictor() -> FocusPredictor | None:
    return _predictor


class LightGbmPredictor:
    def __init__(self, booster, model_version: str) -> None:
        import numpy as np

        self._np = np
        self._booster = booster
        self.model_version = model_version

    def predict_proba(self, vector: list[float]) -> list[float]:
        np = self._np
        features = np.asarray([vector], dtype=np.float64)
        # one row per call: a single thread avoids OpenMP spawning a thread per core on every request
        output = np.asarray(self._booster.predict(features, num_threads=1), dtype=np.float64)
        if output.shape != (1, len(CLASSES)):
            raise ValueError("unexpected model output shape")
        return [float(value) for value in output[0]]


def _read_model(path: Path) -> bytes:
    with path.open("rb") as handle:
        data = handle.read(MAX_MODEL_BYTES + 1)
    if len(data) > MAX_MODEL_BYTES:
        raise ModelRejected("model too large")
    return data


def _check_sidecar(model_path: Path) -> None:
    sidecar = model_path.with_suffix(".json")
    if not sidecar.is_file():
        raise ModelRejected("sidecar missing")
    try:
        meta = json.loads(sidecar.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        raise ModelRejected("sidecar unreadable") from None
    if not isinstance(meta, dict):
        raise ModelRejected("sidecar shape")
    if meta.get("features") != list(FEATURE_COLUMNS):
        raise ModelRejected("feature order")
    if meta.get("classes") != list(CLASSES):
        raise ModelRejected("classes")


def load_predictor(settings: FocusSettings) -> FocusPredictor | None:
    """Once at startup. Returns None (and logs a fixed reason) when no model is configured or a check fails."""
    if not settings.model_path:
        log.info("focus model not configured; /v1/focus/predict returns 503")
        return None
    started = time.perf_counter()
    try:
        path = Path(settings.model_path)
        if not path.is_file():
            raise ModelRejected("model missing")
        expected = settings.model_sha256.strip().lower()
        if len(expected) != 64:
            raise ModelRejected("hash missing")
        data = _read_model(path)
        actual = hashlib.sha256(data).hexdigest()
        if actual != expected:
            raise ModelRejected("hash mismatch")
        try:
            text = data.decode("utf-8")
        except UnicodeDecodeError:
            raise ModelRejected("model encoding") from None
        _check_sidecar(path)
        import lightgbm

        # the checked bytes themselves: no re-open of the path (TOCTOU) and no path in native error messages
        booster = lightgbm.Booster(model_str=text)
        if list(booster.feature_name()) != list(FEATURE_COLUMNS):
            raise ModelRejected("booster features")
        if booster.num_model_per_iteration() != len(CLASSES):
            raise ModelRejected("booster classes")
        predictor = LightGbmPredictor(booster, f"focus-lgbm-{actual[:12]}")
    except ModelRejected as rejected:
        log.warning("focus model rejected: %s", rejected)
        return None
    except Exception as error:  # noqa: BLE001 - any load failure only disables this endpoint
        log.warning("focus model load failed: %s", type(error).__name__)
        return None
    log.info("focus model loaded: version=%s ms=%d", predictor.model_version, (time.perf_counter() - started) * 1000)
    return predictor


async def startup() -> None:
    set_predictor(await anyio.to_thread.run_sync(load_predictor, load_focus_settings()))


def shutdown() -> None:
    set_predictor(None)


async def predict(request: FocusPredictRequest) -> FocusView:
    started = time.perf_counter()
    rows = [row.model_dump() for row in request.rows if 1 <= row.minutes_ago <= WINDOW_MINUTES]
    if len(rows) < MIN_ROWS:
        log.info("focus predict status=insufficient_data rows=%d ms=%d", len(rows),
                 (time.perf_counter() - started) * 1000)
        return FocusView(status="insufficient_data")
    predictor = _predictor
    if predictor is None:
        log.info("focus predict status=unavailable")
        raise HTTPException(status_code=503, detail=UNAVAILABLE_DETAIL)
    vector = build_vector(rows, request.hour, request.weekday)
    try:
        raw = await anyio.to_thread.run_sync(predictor.predict_proba, vector)
        probabilities = [float(value) for value in raw]
    except Exception as error:  # noqa: BLE001 - the message may contain input; log the type only
        log.warning("focus predict failed: %s ms=%d", type(error).__name__, (time.perf_counter() - started) * 1000)
        raise HTTPException(status_code=500, detail="focus prediction failed") from None
    if (len(probabilities) != len(CLASSES) or not all(math.isfinite(p) and p >= 0 for p in probabilities)
            or not math.isclose(sum(probabilities), 1.0, abs_tol=1e-3)):
        log.warning("focus predict failed: invalid output ms=%d", (time.perf_counter() - started) * 1000)
        raise HTTPException(status_code=500, detail="focus prediction failed")
    best = max(range(len(CLASSES)), key=lambda i: probabilities[i])
    log.info("focus predict status=ok ms=%d", (time.perf_counter() - started) * 1000)
    return FocusView(status="ok", state=CLASSES[best], probabilities=dict(zip(CLASSES, probabilities)),
                     model_version=predictor.model_version)
