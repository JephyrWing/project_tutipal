import logging
import os
from dataclasses import dataclass, field

_log = logging.getLogger(__name__)


@dataclass(frozen=True)
class ModelSettings:
    provider: str = "auto"
    name: str = "gemma4-ally:latest"
    base_url: str = "http://host.docker.internal:11434"
    embedding_model: str = "bge-m3"
    embedding_dimensions: int = 1024
    openai_model: str = "gpt-5.4-mini"
    openai_embedding_model: str = "text-embedding-3-small"
    openai_base_url: str = "https://api.openai.com/v1"
    openai_max_completion_tokens: int = 4096
    api_key: str = field(default="", repr=False)
    openai_api_key: str = field(default="", repr=False)
    internal_key: str = field(default="", repr=False)


def _setting(*names: str, default: str = "") -> str:
    for name in names:
        value = os.getenv(name, "").strip()
        if value:
            return value
    return default


def load_model_settings() -> ModelSettings:
    dimensions = int(_setting("EMBEDDING_DIMENSIONS", default="1024"))
    if dimensions != 1024:
        raise ValueError("EMBEDDING_DIMENSIONS must match the Spring vector(1024) schema")
    output_limit = int(_setting("OPENAI_MAX_COMPLETION_TOKENS", default="4096"))
    if not 64 <= output_limit <= 16384:
        raise ValueError("OPENAI_MAX_COMPLETION_TOKENS must be between 64 and 16384")
    return ModelSettings(
        provider=_setting("MODEL_PROVIDER", "LLM_PROVIDER", default="auto").lower(),
        name=_setting("MODEL_NAME", "OLLAMA_MODEL", default="gemma4-ally:latest"),
        base_url=_setting("MODEL_BASE_URL", "OLLAMA_BASE_URL", default="http://host.docker.internal:11434").rstrip("/"),
        embedding_model=_setting("EMBEDDING_MODEL", default="bge-m3"),
        embedding_dimensions=dimensions,
        openai_model=_setting("OPENAI_MODEL", default="gpt-5.4-mini"),
        openai_embedding_model=_setting("OPENAI_EMBEDDING_MODEL", default="text-embedding-3-small"),
        openai_base_url=_setting("OPENAI_BASE_URL", default="https://api.openai.com/v1").rstrip("/"),
        openai_max_completion_tokens=output_limit,
        api_key=_setting("MODEL_API_KEY"),
        openai_api_key=_setting("OPENAI_API_KEY"),
        internal_key=_setting("AI_INTERNAL_KEY"),
    )


DEFAULT_RERANK_MODEL = "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"


@dataclass(frozen=True)
class RerankSettings:
    """PT-86 /v1/rerank. 모델이 없거나 꺼져 있어도 앱은 뜨고 /v1/rerank 만 503을 돌려준다."""

    enabled: bool = False
    model_dir: str = "/models/reranker"
    model_name: str = DEFAULT_RERANK_MODEL
    max_length: int = 256
    threads: int = 2
    # Spring 재순위화 timeout(2s)보다 짧게: 앞 요청을 기다리다 Spring이 이미 포기한 요청까지 추론하지 않는다.
    queue_timeout_ms: int = 1000


def _bounded_int(name: str, default: int, low: int, high: int) -> int:
    try:
        value = int(_setting(name, default=str(default)))
    except ValueError:
        raise ValueError(f"{name} must be an integer") from None
    if not low <= value <= high:
        raise ValueError(f"{name} must be between {low} and {high}")
    return value


def load_rerank_settings() -> RerankSettings:
    """재순위화 설정. 꺼져 있으면 숫자 설정을 검사하지 않고, 켜져 있는데 값이 잘못되면 재순위화만 끈다.

    어느 경우에도 예외를 올리지 않는다: 부가 기능 설정 때문에 채팅·임베딩 앱 기동이 막히지 않게 한다.
    """
    model_dir = _setting("RERANK_MODEL_DIR", default="/models/reranker")
    model_name = _setting("RERANK_MODEL_NAME", default=DEFAULT_RERANK_MODEL)
    if _setting("RERANK_ENABLED", default="false").lower() != "true":
        return RerankSettings(enabled=False, model_dir=model_dir, model_name=model_name)
    try:
        return RerankSettings(
            enabled=True,
            model_dir=model_dir,
            model_name=model_name,
            max_length=_bounded_int("RERANK_MAX_LENGTH", 256, 16, 8192),
            threads=_bounded_int("RERANK_THREADS", 2, 1, 64),
            queue_timeout_ms=_bounded_int("RERANK_QUEUE_TIMEOUT_MS", 1000, 0, 60000),
        )
    except ValueError as error:
        # 값 자체는 남기지 않고 어떤 설정이 잘못됐는지만 남긴다.
        _log.warning("rerank disabled: invalid setting (%s)", str(error).split(" must ")[0])
        return RerankSettings(enabled=False, model_dir=model_dir, model_name=model_name)

@dataclass(frozen=True)
class FocusSettings:
    """PT-90 /v1/focus/predict. Without a model (or with a failed check) the app starts and only this path is 503."""

    model_path: str = ""
    model_sha256: str = field(default="", repr=False)


def load_focus_settings() -> FocusSettings:
    """FOCUS_MODEL_PATH: LightGBM text file. FOCUS_MODEL_SHA256: its hex digest (letter case ignored)."""
    return FocusSettings(model_path=_setting("FOCUS_MODEL_PATH"), model_sha256=_setting("FOCUS_MODEL_SHA256"))


# langsmith.utils.tracing_is_enabled reads TRACING_V2, then TRACING, in both the LANGSMITH_ and
# LANGCHAIN_ namespaces, so all four names must be checked (PT-85 AC-25).
TRACING_FLAGS = ("LANGSMITH_TRACING", "LANGCHAIN_TRACING_V2", "LANGSMITH_TRACING_V2", "LANGCHAIN_TRACING")
_TRUE_VALUES = {"true", "1", "yes", "on"}


def _langsmith_tracing_enabled() -> bool:
    """Ask the installed langsmith itself, so a future variable name or fallback is also caught."""
    try:
        from langsmith import utils as langsmith_utils
    except ImportError:
        return False
    # get_env_var is lru_cached; clear it so the check sees the current environment.
    cache_clear = getattr(langsmith_utils.get_env_var, "cache_clear", None)
    if cache_clear is not None:
        cache_clear()
    return bool(langsmith_utils.tracing_is_enabled())


def require_tracing_disabled() -> None:
    """Fail closed: LangSmith/LangChain tracing would send request bodies off the server (PT-85 AC-25)."""
    for name in TRACING_FLAGS:
        if os.getenv(name, "").strip().lower() in _TRUE_VALUES:
            raise RuntimeError(f"{name} must be false: tracing would export request bodies")
    if _langsmith_tracing_enabled():
        raise RuntimeError("langsmith reports tracing enabled: tracing would export request bodies")
