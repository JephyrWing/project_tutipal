from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send


# PT-90: small fixed-shape bodies get their own, lower limit (exact path match).
DEFAULT_PATH_LIMITS = {"/v1/focus/predict": 8192}


class InferenceBodyLimit:
    """입력 스키마 검증 전에 추론 요청의 실제 수신량을 제한한다."""

    def __init__(self, app: ASGIApp, max_bytes: int = 4_500_000,
                 path_limits: dict[str, int] | None = None) -> None:
        self.app = app
        self.max_bytes = max_bytes
        self.path_limits = dict(DEFAULT_PATH_LIMITS if path_limits is None else path_limits)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http" or not scope["path"].startswith("/v1/"):
            await self.app(scope, receive, send)
            return
        limit = self.path_limits.get(scope["path"].rstrip("/"), self.max_bytes)
        data = bytearray()
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            data.extend(message.get("body", b""))
            if len(data) > limit:
                response = JSONResponse(status_code=413, content={"detail": "inference request too large"})
                await response(scope, receive, send)
                return
            if not message.get("more_body", False):
                break
        body = bytes(data)
        delivered = False

        async def replay() -> dict:
            nonlocal delivered
            if not delivered:
                delivered = True
                return {"type": "http.request", "body": body, "more_body": False}
            return await receive()

        await self.app(scope, replay, send)
