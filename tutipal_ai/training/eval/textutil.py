"""모델 응답 텍스트 처리: 생각 블록 제거, 코드 추출, 한국어 비율, 문항 해시."""

from __future__ import annotations

import hashlib
import re

_THINK_BLOCK = re.compile(r"<think>.*?</think>", re.DOTALL | re.IGNORECASE)
_THINK_OPEN = re.compile(r"<think>.*", re.DOTALL | re.IGNORECASE)
_FENCE = re.compile(r"```[ \t]*([A-Za-z0-9_+-]*)[^\n]*\n(.*?)```", re.DOTALL)
_HANGUL = re.compile(r"[가-힣]")
_LATIN = re.compile(r"[A-Za-z]")
_PY_LANGS = {"", "python", "py", "python3"}


def strip_think(text: str) -> str:
    """<think>...</think> 블록을 지운다. 닫히지 않은 블록은 끝까지 지운다."""
    text = _THINK_BLOCK.sub("", text)
    return _THINK_OPEN.sub("", text).strip()


def code_blocks(text: str) -> list[tuple[str, str]]:
    """(언어, 본문) 목록. 언어는 소문자."""
    return [(lang.lower(), body) for lang, body in _FENCE.findall(text)]


def extract_code(text: str, function_names: list[str] | None = None) -> str:
    """응답에서 실행할 Python 코드를 고른다.

    우선순위: 요구 함수를 모두 정의한 Python 블록 → 가장 긴 Python 블록 → 코드 블록이 없으면 응답 전체.
    """
    text = strip_think(text)
    blocks = [body for lang, body in code_blocks(text) if lang in _PY_LANGS]
    if not blocks:
        return text.strip()
    if function_names:
        defining = [b for b in blocks if all(_defines(b, n) for n in function_names)]
        if defining:
            return max(defining, key=len).strip()
    return max(blocks, key=len).strip()


def _defines(code: str, name: str) -> bool:
    return re.search(rf"^\s*(?:async\s+)?def\s+{re.escape(name)}\s*\(|^\s*class\s+{re.escape(name)}\b",
                     code, re.MULTILINE) is not None


def hangul_ratio(text: str) -> float:
    """코드 블록을 뺀 본문에서 한글 음절 / (한글 음절 + 로마자) 비율. 글자가 없으면 0."""
    prose = _FENCE.sub("", strip_think(text))
    prose = re.sub(r"`[^`\n]*`", "", prose)  # 인라인 코드도 제외
    ko = len(_HANGUL.findall(prose))
    en = len(_LATIN.findall(prose))
    return ko / (ko + en) if ko + en else 0.0


def question_hash(text: str) -> str:
    """공백·대소문자 차이를 무시한 문항 지문. 학습 데이터에서 평가 문항을 빼는 데 쓴다."""
    normalized = " ".join(text.split()).lower()
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()
