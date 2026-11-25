import os
from dataclasses import dataclass, field


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
