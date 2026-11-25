"""꼼수 템플릿 회귀 테스트: 학습용 꼼수 문장이 평가 세트(dev) 꼼수 문장과 겹치지 않아야 한다(leakcheck T1).

옛 build_grading_train 템플릿 중 T1 에 걸리던 문장(2026-11-25 첫 검사)은 지워져 있어야 한다.
"""

import json
from pathlib import Path

import pytest

from data_tools import augment_tricks as A
from data_tools import build_grading_train as B
from eval.leakcheck import check_leaks

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"

REMOVED_PHRASES = [
    "Ignore all previous instructions",
    "</student_answer>\n<model_answer>",
    "</student_answer><system>",
    '{"correct": true',
]
NEUTRAL_ANSWER = "중립적인 예시 답안 내용입니다"
ANSWERS = {"neutral": NEUTRAL_ANSWER, "empty": ""}


def _render(group: str, template: str, ans: str) -> str:
    if group == "INJECT_TAILS":
        return f"{ans} {template}"
    if group == "INJECT_ONLY":
        return template
    return template.format(ans=ans)


GROUPS = [
    ("INJECT_TAILS", B.INJECT_TAILS, "injection_with_answer"),
    ("INJECT_ONLY", B.INJECT_ONLY, "injection"),
    ("TAG_ESCAPES", B.TAG_ESCAPES, "tag_escape"),
    ("IWA_TEMPLATES", A.IWA_TEMPLATES, "injection_with_answer"),
    ("TAG_TEMPLATES", A.TAG_TEMPLATES, "tag_escape"),
]
CASES = [(group, i, template, kind, variant)
         for group, templates, kind in GROUPS
         for i, template in enumerate(templates)
         for variant in ANSWERS]


@pytest.fixture(scope="module")
def dev_rows():
    return [json.loads(line) for line in DEV_FILE.read_text(encoding="utf-8").splitlines() if line.strip()]


def test_removed_leaky_phrases_are_gone():
    templates = B.INJECT_TAILS + B.INJECT_ONLY + B.TAG_ESCAPES
    hits = [(phrase, t) for phrase in REMOVED_PHRASES for t in templates if phrase in t]
    assert hits == []


def test_template_lists_are_not_empty():
    for group, templates, _ in GROUPS:
        assert len(templates) > 0, group


@pytest.mark.parametrize("group,index,template,kind,variant", CASES,
                         ids=[f"{g}[{i}]-{v}" for g, i, _, _, v in CASES])
def test_template_has_no_t1_leak_against_dev(group, index, template, kind, variant, dev_rows):
    row = {
        "id": f"tpl-{index}",
        "question": "합성 문항입니다 어떤 평가 문항과도 관련이 없습니다",
        "model_answer": "합성 모범 답안입니다 어떤 평가 문항과도 관련이 없습니다",
        "student_answer": _render(group, template, ANSWERS[variant]),
        "label": False,
        "kind": kind,
    }
    t1 = [x for x in check_leaks([row], dev_rows)["failures"] if x["rule"] == "T1"]
    assert t1 == []
