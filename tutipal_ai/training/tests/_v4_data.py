"""PT-88 v4 테스트 도우미: v3 사람 검수 회귀 fixture 읽기, 용어집을 따르는 가짜 번역·작성 모델.

fixture(tests/fixtures/pt88_v3_review_regression.csv)는 2026-11-26 v3 사람 검수 시트에서 OK 가 아닌 14행과
OK 4행을 옮긴 것이다(검수자의 note·점수 열은 뺐다). 원문은 공개 데이터셋(Mohler ASAG, SAF) 문장과 v3 생성 결과다.
"""

from __future__ import annotations

import csv
import json
import re
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools import quality as Q

FIXTURE = Path(__file__).resolve().parent / "fixtures" / "pt88_v3_review_regression.csv"
MOH = "MohlerASAG"
SAF = "SAF-communication-networks"


def review_rows() -> list[dict]:
    with open(FIXTURE, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def by_short_id() -> dict[str, dict]:
    """'E04.Q05.A07' 또는 SAF 는 앞 8자리('b610b9c1')로 찾는다."""
    out = {}
    for r in review_rows():
        sid = r["id"].split("-")[-1]
        out[sid[:8] if r["source"] == SAF else sid] = r
    return out


def label_of(r: dict) -> bool:
    return {"TRUE": True, "FALSE": False}[r["label"]]


def x_of(r: dict) -> dict:
    return {"q": r["q_en"], "ref": r["ref_en"], "ans": r["ans_en"]}


def tr_of(r: dict) -> dict:
    return {"question": r["q_ko"], "model_answer": r["ref_ko"], "student_answer": r["ans_ko"]}


def o_of(r: dict, **override) -> dict:
    return {**tr_of(r), "reason": r["reason"], "feedback": r["feedback"], **override}


def en_of(r: dict) -> str:
    return "\n".join((r["q_en"], r["ref_en"], r["ans_en"]))


# ---------- 가짜 LLM (용어집 허용형을 넣어 v4 번역 검사를 통과시킨다) ----------

def fake_ko(text: str, drop_terms: frozenset[str] = frozenset()) -> str:
    """영어의 숫자·대문자 약어 토큰을 옮기고, 원문에 든 용어집 항목마다 첫 허용형을 붙인다.

    drop_terms 에 든 영어 용어(항목 en)는 허용형을 붙이지 않아 glossary 규칙에 걸리게 한다.
    """
    keep = [w for w in text.split() if re.search(r"\d|[A-Z]{2}", w)]
    terms = [e["ko"][0].strip() for e in Q.glossary_for(text) if e["en"] not in drop_terms]
    return " ".join(["번역 결과 문장입니다", *keep, *terms, "않음 오직 최대 최소 초과 미만"])


TRUE_REASON = "모범 답안과 같은 뜻을 담고 있다."
TRUE_FEEDBACK = "핵심을 잘 담았어요. 계속 이렇게 해 보세요."
FALSE_FEEDBACK = "모범 답안의 핵심을 다시 확인해 보세요."


def _tag(prompt: str, name: str) -> str:
    m = re.search(rf"<{name}>(.*?)</{name}>", prompt, re.DOTALL)
    return m.group(1) if m else ""


class FakeLLM:
    """H.translate / H.generate / H.unload 대신 쓴다. 호출을 기록한다.

    오답 행의 reason 에는 모범 답안 번역을 그대로 넣어 expl:vague_missing 에 걸리지 않게 한다.
    """

    def __init__(self, drop: dict[str, frozenset[str]] | None = None):
        self.translated: list[str] = []
        self.prompts: list[str] = []
        self.systems: list[str | None] = []
        self.drop = drop or {}

    def translate(self, model, text, seed=None, **kw):
        self.translated.append(text)
        return fake_ko(text, self.drop.get(text, frozenset()))

    def generate(self, model, prompt, system=None, fmt=None, seed=None):
        self.prompts.append(prompt)
        self.systems.append(system)
        if B.KIND_DESC["paraphrase"] in prompt:
            return json.dumps({"reason": TRUE_REASON, "feedback": TRUE_FEEDBACK}, ensure_ascii=False)
        ref = _tag(prompt, "model_answer")
        return json.dumps({"reason": f"모범 답안의 핵심 내용을 답하지 못했다: {ref}", "feedback": FALSE_FEEDBACK},
                          ensure_ascii=False)

    def unload(self, model):
        pass
