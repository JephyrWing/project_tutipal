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
