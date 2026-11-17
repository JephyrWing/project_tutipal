import hmac

from fastapi import Header, HTTPException

from app.core.settings import load_model_settings


def internal_only(x_internal_key: str = Header(default="")) -> None:
    """추론 API는 Spring과 같은 내부 키를 보낸 경우에만 허용한다."""
    expected = load_model_settings().internal_key
    if not expected or not hmac.compare_digest(x_internal_key.encode(), expected.encode()):
        raise HTTPException(status_code=401, detail="internal authentication required")
