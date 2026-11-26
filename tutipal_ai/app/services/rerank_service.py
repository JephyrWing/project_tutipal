"""PT-86: Spring이 넘긴 검색 후보를 cross-encoder로 점수화한다 (요청 단위, 무상태).

- 모델은 앱 시작(lifespan) 때 한 번 읽는다. 모델이 없거나 RERANK_ENABLED가 true가 아니면 앱은 뜨고 /v1/rerank만 503.
- onnxruntime·tokenizers·numpy는 실제 모델을 읽을 때만 import한다 (테스트·모델 없는 환경에서 앱이 떠야 함).
- 추론은 CPU를 오래 쓰므로 이벤트 루프 밖 스레드에서, 한 번에 하나씩(Semaphore 1) 실행한다.
- 로그에는 모델 이름·후보 수·소요 ms·예외 종류만 남긴다. 질문과 조각 원문은 남기지 않는다.
"""
import asyncio
import logging
import math
import time
from collections.abc import Awaitable, Callable
from pathlib import Path
from typing import Protocol

import anyio
from fastapi import HTTPException

from app.core.settings import RerankSettings, load_rerank_settings
from app.models.rerank import RerankRequest
from app.views.rerank_view import RerankView

log = logging.getLogger(__name__)

MODEL_FILE = "model.onnx"
TOKENIZER_FILE = "tokenizer.json"


class RerankScorer(Protocol):
    """(질문, 조각) 쌍마다 관련도 점수. 동기 함수이며 워커 스레드에서 호출된다."""

    model_name: str

    def score(self, query: str, documents: list[str]) -> list[float]: ...


_scorer: RerankScorer | None = None
# anyio.Semaphore는 처음 쓴 이벤트 루프에 묶인다. 운영에서는 루프가 하나지만 테스트는 루프를 새로 만들므로 루프마다 만든다.
_gate: anyio.Semaphore | None = None
_gate_loop: asyncio.AbstractEventLoop | None = None


def set_scorer(scorer: RerankScorer | None) -> None:
    global _scorer
    _scorer = scorer


def get_scorer() -> RerankScorer | None:
    return _scorer


def _gate_for_running_loop() -> anyio.Semaphore:
    global _gate, _gate_loop
    loop = asyncio.get_running_loop()
    if _gate is None or _gate_loop is not loop:
        _gate = anyio.Semaphore(1)
        _gate_loop = loop
    return _gate


class OnnxCrossEncoder:
    """ONNX cross-encoder (예: cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 양자화본). 출력 logit 하나를 점수로 쓴다."""

    def __init__(self, model_dir: Path, model_name: str, max_length: int, threads: int) -> None:
        import numpy as np
        import onnxruntime as ort
        from tokenizers import Tokenizer

        options = ort.SessionOptions()
        options.intra_op_num_threads = threads
        options.inter_op_num_threads = 1
        options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        # 2 vCPU(t3.large) 를 Spring·DB와 같이 쓰므로 연산 사이 대기 중 스핀으로 CPU를 태우지 않는다.
        options.add_session_config_entry("session.intra_op.allow_spinning", "0")
        self._np = np
        self._session = ort.InferenceSession(
            str(model_dir / MODEL_FILE), sess_options=options, providers=["CPUExecutionProvider"])
        self._inputs = {item.name for item in self._session.get_inputs()}
        tokenizer = Tokenizer.from_file(str(model_dir / TOKENIZER_FILE))
        # (질문, 조각) 쌍을 함께 잘라 max_length 토큰 안에 넣는다 (긴 쪽부터 자름).
        tokenizer.enable_truncation(max_length=max_length)
        if tokenizer.padding is None:
            pad_token = next((t for t in ("<pad>", "[PAD]") if tokenizer.token_to_id(t) is not None), None)
            if pad_token is None:
                raise ValueError("tokenizer has no pad token")
            tokenizer.enable_padding(pad_id=tokenizer.token_to_id(pad_token), pad_token=pad_token)
        self._tokenizer = tokenizer
        self.model_name = model_name

    def score(self, query: str, documents: list[str]) -> list[float]:
        np = self._np
        encodings = self._tokenizer.encode_batch([(query, document) for document in documents])
        feed = {
            "input_ids": np.array([e.ids for e in encodings], dtype=np.int64),
            "attention_mask": np.array([e.attention_mask for e in encodings], dtype=np.int64),
        }
        if "token_type_ids" in self._inputs:
            feed["token_type_ids"] = np.array([e.type_ids for e in encodings], dtype=np.int64)
        logits = np.asarray(self._session.run(None, feed)[0], dtype=np.float32)
        # 출력이 [n, 1] 또는 [n] 인 모델만 받는다. 순서만 쓰므로 sigmoid는 하지 않는다.
        if logits.ndim == 2 and logits.shape[1] == 1:
            logits = logits[:, 0]
        if logits.ndim != 1 or logits.shape[0] != len(documents):
            raise ValueError("unexpected reranker output shape")
        return [float(value) for value in logits]


def load_scorer(settings: RerankSettings) -> RerankScorer | None:
    """시작할 때 한 번. 꺼져 있거나 모델 파일이 없거나 읽기에 실패하면 None (앱은 계속 뜬다)."""
    if not settings.enabled:
        log.info("rerank disabled")
        return None
    model_dir = Path(settings.model_dir)
    if not (model_dir / MODEL_FILE).is_file() or not (model_dir / TOKENIZER_FILE).is_file():
        log.warning("rerank model missing; /v1/rerank returns 503")
        return None
    started = time.perf_counter()
    try:
        scorer = OnnxCrossEncoder(model_dir, settings.model_name, settings.max_length, settings.threads)
    except Exception as error:  # noqa: BLE001 - 어떤 실패든 재순위화만 끄고 앱은 계속 뜬다
        log.warning("rerank model load failed: %s", type(error).__name__)
        return None
    log.info("rerank model loaded: model=%s ms=%d", settings.model_name, (time.perf_counter() - started) * 1000)
    return scorer


async def startup() -> None:
    set_scorer(await anyio.to_thread.run_sync(load_scorer, load_rerank_settings()))


def shutdown() -> None:
    set_scorer(None)


def _unavailable() -> HTTPException:
    return HTTPException(status_code=503, detail="rerank unavailable")


async def rerank(request: RerankRequest,
                 is_disconnected: Callable[[], Awaitable[bool]] | None = None) -> RerankView:
    settings = load_rerank_settings()
    if not settings.enabled:
        raise _unavailable()
    scorer = _scorer
    if scorer is None:
        raise _unavailable()
    documents = list(request.documents)
    started = time.perf_counter()
    gate = _gate_for_running_loop()
    # 앞 요청을 기다리는 시간을 묶는다. Spring은 2s 뒤 포기하고 코사인으로 돌아가므로 그 뒤의 추론은 CPU 낭비다.
    try:
        with anyio.fail_after(settings.queue_timeout_ms / 1000):
            await gate.acquire()
    except TimeoutError:
        log.warning("rerank busy: queue wait over %d ms count=%d", settings.queue_timeout_ms, len(documents))
        raise HTTPException(status_code=503, detail="rerank busy") from None
    try:
        if is_disconnected is not None and await is_disconnected():
            log.info("rerank skipped: client disconnected count=%d", len(documents))
            raise HTTPException(status_code=503, detail="rerank unavailable")
        try:
            raw = await anyio.to_thread.run_sync(scorer.score, request.query, documents)
            scores = [float(value) for value in raw]
        except Exception as error:  # noqa: BLE001 - 예외 메시지에 입력이 들어 있을 수 있어 종류만 남긴다
            log.warning("rerank failed: %s", type(error).__name__)
            raise HTTPException(status_code=500, detail="rerank failed") from None
    finally:
        gate.release()
    if len(scores) != len(documents) or not all(math.isfinite(value) for value in scores):
        log.warning("rerank failed: invalid scores count=%d", len(documents))
        raise HTTPException(status_code=500, detail="rerank failed")
    log.info("rerank model=%s count=%d ms=%d", scorer.model_name, len(documents),
             (time.perf_counter() - started) * 1000)
    return RerankView(model=scorer.model_name, scores=scores)
