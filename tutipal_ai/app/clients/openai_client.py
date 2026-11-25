"""OpenAI Chat Completions/Embeddings를 기존 내부 응답 형식으로 바꾼다."""

import asyncio
import base64
import json
import math
from collections.abc import AsyncIterator
from urllib.parse import urlsplit

import httpx

from app.clients.ollama_client import EmbeddingTemporarilyUnavailable, ModelUnavailable, TIMEOUT
from app.core.settings import ModelSettings


def _base_url(settings: ModelSettings) -> str:
    parsed = urlsplit(settings.openai_base_url)
    loopback = parsed.hostname in {"127.0.0.1", "localhost", "::1"}
    if (parsed.scheme != "https" and not (parsed.scheme == "http" and loopback)) or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ModelUnavailable("invalid OpenAI URL")
    if not settings.openai_api_key:
        raise ModelUnavailable("OpenAI key not configured")
    return settings.openai_base_url


def _messages(messages: list[dict], json_mode: bool) -> list[dict]:
    result = []
    for message in messages:
        role = "developer" if message["role"] == "system" else message["role"]
        content = message["content"]
        if json_mode and role == "developer":
            content += "\nReturn one valid JSON object only."
        images = message.get("images") or []
        if images:
            parts = [{"type": "text", "text": content}]
            for image in images:
                raw = base64.b64decode(image, validate=True)
                mime = "image/png" if raw.startswith(b"\x89PNG") else "image/jpeg"
                parts.append({"type": "image_url", "image_url": {"url": f"data:{mime};base64,{image}"}})
            content = parts
        result.append({"role": role, "content": content})
    return result


async def chat_stream(settings: ModelSettings, messages: list[dict], json_mode: bool) -> AsyncIterator[str]:
    payload = {"model": settings.openai_model, "messages": _messages(messages, json_mode), "stream": True,
               "max_completion_tokens": settings.openai_max_completion_tokens, "store": False}
    if json_mode:
        payload["response_format"] = {"type": "json_object"}
    headers = {"Authorization": f"Bearer {settings.openai_api_key}"}
    output_size = 0
    complete = False
    async with asyncio.timeout(300), httpx.AsyncClient(timeout=TIMEOUT, trust_env=False) as client:
        async with client.stream("POST", f"{_base_url(settings)}/chat/completions", headers=headers, json=payload) as response:
            if response.status_code != 200:
                raise ModelUnavailable("OpenAI rejected request")
            async for line in response.aiter_lines():
                if len(line) > 1_000_000:
                    raise ModelUnavailable("OpenAI response too large")
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    if not complete:
                        raise ModelUnavailable("OpenAI stream not completed")
                    return
                try:
                    event = json.loads(data)
                    if not isinstance(event, dict) or event.get("error"):
                        raise ValueError("invalid OpenAI event")
                    choices = event.get("choices") or []
                    if not choices:
                        continue
                    choice = choices[0]
                    delta = choice.get("delta") or {}
                    if delta.get("refusal"):
                        raise ModelUnavailable("OpenAI refusal")
                    content = delta.get("content") or ""
                    if not isinstance(content, str):
                        raise ValueError("invalid OpenAI delta")
                    output_size += len(content)
                    if output_size > 1_000_000:
                        raise ModelUnavailable("OpenAI response too large")
                    if content:
                        yield content
                    finish = choice.get("finish_reason")
                    if finish:
                        if finish != "stop":
                            raise ModelUnavailable("OpenAI incomplete output")
                        complete = True
                except (ValueError, TypeError, KeyError, AttributeError, IndexError) as error:
                    raise ModelUnavailable("invalid OpenAI stream") from error
            raise ModelUnavailable("OpenAI stream ended before completion")


async def embed_texts(settings: ModelSettings, texts: list[str]) -> tuple[str, list[list[float]]]:
    payload = {"model": settings.openai_embedding_model, "input": texts,
               "dimensions": settings.embedding_dimensions, "encoding_format": "float"}
    headers = {"Authorization": f"Bearer {settings.openai_api_key}"}
    async with asyncio.timeout(125), httpx.AsyncClient(timeout=httpx.Timeout(120.0, connect=5.0), trust_env=False) as client:
        async with client.stream("POST", f"{_base_url(settings)}/embeddings", headers=headers, json=payload) as response:
            if response.status_code != 200:
                if response.status_code in {502, 504}:
                    raise EmbeddingTemporarilyUnavailable("embedding gateway unavailable")
                raise ModelUnavailable("OpenAI embedding unavailable")
            raw = bytearray()
            async for part in response.aiter_bytes():
                raw.extend(part)
                if len(raw) > 12_000_000:
                    raise ModelUnavailable("OpenAI embedding response too large")
    try:
        result = json.loads(raw)
        rows = result["data"]
        if not isinstance(rows, list) or len(rows) != len(texts):
            raise ValueError("vector count mismatch")
        rows = sorted(rows, key=lambda row: row["index"])
        if [row["index"] for row in rows] != list(range(len(texts))):
            raise ValueError("vector index mismatch")
        vectors = [row["embedding"] for row in rows]
        for vector in vectors:
            if not isinstance(vector, list) or len(vector) != settings.embedding_dimensions:
                raise ValueError("vector dimension mismatch")
            if any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) for value in vector):
                raise ValueError("invalid vector value")
        model = result.get("model") or settings.openai_embedding_model
        if not isinstance(model, str) or len(model) > 180:
            raise ValueError("invalid embedding model")
        return model, vectors
    except (ValueError, TypeError, OverflowError, KeyError, AttributeError) as error:
        raise ModelUnavailable("invalid OpenAI embedding response") from error
