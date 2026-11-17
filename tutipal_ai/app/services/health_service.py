from app.models.health import HealthState
from app.clients.ollama_client import available_models, model_present
from app.core.settings import load_model_settings


async def get_health() -> HealthState:
    # HTTP 서버의 생존과 실제 모델 파일 준비를 구분한다.
    settings = load_model_settings()
    names = await available_models(settings) if settings.provider in {"auto", "ollama"} else set()
    chat = model_present(names, settings.name)
    embed = model_present(names, settings.embedding_model)
    fallback = bool(settings.openai_api_key) and settings.provider in {"auto", "openai"}
    return HealthState(model_ready=(chat or fallback) and (embed or fallback), local_chat_ready=chat,
                       local_embedding_ready=embed, fallback_configured=fallback)
