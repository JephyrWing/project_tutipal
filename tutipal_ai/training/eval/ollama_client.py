"""Ollama /api/chat 호출(표준 라이브러리만 사용)."""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass


class OllamaError(RuntimeError):
    pass


@dataclass(frozen=True)
class ChatResult:
    content: str
    wall_seconds: float
    eval_tokens: int
    tokens_per_second: float | None
    done_reason: str | None


class OllamaClient:
    def __init__(self, base_url: str = "http://127.0.0.1:11434", timeout: float = 300):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def _post(self, path: str, payload: dict) -> dict:
        req = urllib.request.Request(
            self.base_url + path, data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}, method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", errors="replace")
            raise OllamaError(f"HTTP {e.code}: {body[:500]}") from e
        except (urllib.error.URLError, TimeoutError) as e:
            raise OllamaError(f"Ollama 연결 실패({self.base_url}): {e}") from e

    def installed_models(self) -> set[str]:
        try:
            with urllib.request.urlopen(self.base_url + "/api/tags", timeout=10) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError) as e:
            raise OllamaError(f"Ollama 연결 실패({self.base_url}): {e}") from e
        names = set()
        for m in data.get("models", []):
            name = m.get("name") or m.get("model") or ""
            names.add(name)
            if name.endswith(":latest"):
                names.add(name[: -len(":latest")])
        return names

    def chat(self, model: str, messages: list[dict], *, think: bool | None = False,
             temperature: float = 0.0, seed: int = 0, num_ctx: int = 4096,
             num_predict: int = 1024) -> ChatResult:
        payload: dict = {
            "model": model, "messages": messages, "stream": False,
            "options": {"temperature": temperature, "seed": seed, "num_ctx": num_ctx,
                        "num_predict": num_predict},
        }
        if think is not None:
            payload["think"] = think
        start = time.perf_counter()
        try:
            data = self._post("/api/chat", payload)
        except OllamaError as e:
            # 생각 모드를 지원하지 않는 모델은 think 필드 없이 다시 보낸다
            if think is not None and "think" in str(e).lower():
                payload.pop("think")
                start = time.perf_counter()
                data = self._post("/api/chat", payload)
            else:
                raise
        wall = time.perf_counter() - start
        eval_tokens = int(data.get("eval_count") or 0)
        eval_ns = data.get("eval_duration") or 0
        tps = eval_tokens / (eval_ns / 1e9) if eval_tokens and eval_ns else None
        return ChatResult(
            content=(data.get("message") or {}).get("content", ""),
            wall_seconds=wall, eval_tokens=eval_tokens, tokens_per_second=tps,
            done_reason=data.get("done_reason"),
        )
