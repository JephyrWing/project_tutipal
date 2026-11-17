from dataclasses import dataclass


@dataclass(frozen=True)
class HealthState:
    status: str = "ok"
    model_ready: bool = False
    local_chat_ready: bool = False
    local_embedding_ready: bool = False
    fallback_configured: bool = False
