from pydantic import BaseModel


class HealthView(BaseModel):
    status: str
    model_ready: bool
    local_chat_ready: bool = False
    local_embedding_ready: bool = False
    fallback_configured: bool = False
