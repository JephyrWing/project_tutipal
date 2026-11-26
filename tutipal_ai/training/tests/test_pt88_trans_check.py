"""PT-88 번역 검사(data_tools.quality.trans_check)와 번역 프롬프트 용어집 테스트.

수용 기준 5(용어집), 6(약어), 7(숫자), 8(비교·부정), 10(사람 검수에서 찾은 번역 오류 회귀).
LLM·네트워크를 쓰지 않는다. 함수는 순수 함수다(영어 원문, 한국어 번역) → 실패 규칙 이름 또는 None.

규칙 이름: 'glossary:<영어 용어>', 'acronym:<약어>', 'number', 'cmp:<at_most|at_least|more_than|less_than>',
'only', 'neg'. 아래 실패 예시는 한 규칙만 어기도록 골랐다.
"""

import pytest

from data_tools import compare_hybrid as H
from data_tools import quality as Q
from data_tools.common import DEFAULT_SEED

TRANSLATOR = "translategemma:4b"


# ---------- 수용 기준 5: 용어집 ----------

def _entry(term):
    found = [e for e in Q.GLOSSARY if e["en"] == term]
    assert len(found) == 1, f"GLOSSARY 에 {term!r} 항목이 하나 있어야 한다"
    return found[0]


@pytest.mark.parametrize("term, allowed, forbidden", [
    ("memory", {"메모리"}, {"기억"}),
    ("free", {"비어 있는", "사용 가능한", "여유", "해제"}, {"무료"}),
    ("unicast", {"유니캐스트"}, {"단일 방향"}),
    ("multicast", {"멀티캐스트"}, set()),
    ("broadcast", {"브로드캐스트"}, set()),
])
def test_glossary_has_initial_entries(term, allowed, forbidden):
    e = _entry(term)
    assert allowed <= set(e["ko"])
    assert forbidden <= set(e["bad"])


def test_glossary_for_returns_only_terms_present_in_source():
    assert sorted(e["en"] for e in Q.glossary_for("Free memory is reported.")) == ["free", "memory"]
    assert [e["en"] for e in Q.glossary_for("It uses Unicast routing.")] == ["unicast"]
    assert Q.glossary_for("Explain how a stack works.") == []


def test_glossary_for_matches_whole_words_only():
    assert Q.glossary_for("freedom and memoryless") == []


def test_translate_prompt_includes_only_matching_glossary_entries(monkeypatch):
    seen = {}

    def fake_generate(model, prompt, system=None, fmt=None, seed=DEFAULT_SEED):
        seen["prompt"], seen["seed"] = prompt, seed
        return "번역"

    monkeypatch.setattr(H, "generate", fake_generate)
    text = "The free memory is reported."
    H.translate(TRANSLATOR, text, glossary=True)
    assert text in seen["prompt"]
    assert "메모리" in seen["prompt"]
    assert "사용 가능한" in seen["prompt"]
    assert "유니캐스트" not in seen["prompt"]


def test_v1_translate_prompt_has_no_glossary(monkeypatch):
    """v1(glossary 기본 False)은 용어가 있어도 예전 TG_PROMPT 그대로 보낸다(v1 결과 유지)."""
    seen = {}
    monkeypatch.setattr(H, "generate", lambda model, prompt, **kw: seen.setdefault("prompt", prompt) or "번역")
    text = "The free memory is reported."
    H.translate(TRANSLATOR, text)
    assert seen["prompt"] == H.TG_PROMPT.format(text=text)


def test_v1_writer_system_is_the_original_prompt():
    """v1 작성 프롬프트는 바꾸지 않았다. PT-88 지시(영어 원문·hint)는 v2 전용 WRITER_SYSTEM_V2 에만 있다."""
    assert "모두 한국어" in H.WRITER_SYSTEM and "<hint>" not in H.WRITER_SYSTEM
    assert "<hint>" in H.WRITER_SYSTEM_V2


def test_translate_prompt_unchanged_without_glossary_terms(monkeypatch):
    seen = {}
    monkeypatch.setattr(H, "generate", lambda model, prompt, **kw: seen.setdefault("prompt", prompt) or "번역")
    text = "Explain how a stack works."
    H.translate(TRANSLATOR, text)
    assert seen["prompt"] == H.TG_PROMPT.format(text=text)


def test_translate_passes_seed_for_retranslation(monkeypatch):
    seeds = []

    def fake_generate(model, prompt, system=None, fmt=None, seed=DEFAULT_SEED):
        seeds.append(seed)
        return "번역"

    monkeypatch.setattr(H, "generate", fake_generate)
    H.translate(TRANSLATOR, "a stack")
    H.translate(TRANSLATOR, "a stack", seed=DEFAULT_SEED + 1)
    assert seeds == [DEFAULT_SEED, DEFAULT_SEED + 1]


# ---------- 수용 기준 5~8: 통과해야 하는 번역 ----------

@pytest.mark.parametrize("en, ko", [
    ("Explain the stack.", "스택을 설명하세요."),
    ("free memory", "사용 가능한 메모리"),
    ("The medium is free.", "매체가 비어 있다."),
    ("unicast routing", "유니캐스트 라우팅"),
    ("RPB works like RPF", "RPB는 RPF처럼 동작한다"),
    ("TCP over IPv4", "IPv4 위의 TCP"),
    ("3 hosts", "세 호스트"),
    ("three hosts", "3개의 호스트"),
    ("twice as much memory", "두 배의 메모리"),
    ("a simple method", "간단한 방법"),
    ("more than 3 frames", "3개 초과의 프레임"),
    ("more than 3 frames", "3개보다 많은 프레임"),
    ("greater than 3", "3보다 크다"),
    ("at most 5 steps", "최대 5단계"),
    ("at least 2 nodes", "최소 2개의 노드"),
    ("less than 4 hops", "4홉 미만"),
    ("it only holds the address", "주소만 저장한다"),
    ("This type can not grow.", "그 유형은 늘릴 수 없다."),
    ("it does not grow", "늘어나지 않는다"),
])
def test_trans_check_passes_faithful_translation(en, ko):
    assert Q.trans_check(en, ko) is None


# ---------- 수용 기준 5~8: 실패해야 하는 번역 ----------

@pytest.mark.parametrize("en, ko, rule", [
    ("The medium is free.", "매체가 무료이다.", "glossary:free"),
    ("The medium is free.", "매체가 있다.", "glossary:free"),
    ("location in memory", "기억 속의 위치", "glossary:memory"),
    ("unicast routing", "단일 방향 라우팅", "glossary:unicast"),
    ("RPB works like RPF", "RPF는 RPF처럼 동작한다", "acronym:RPB"),
    ("TCP is reliable", "tcp는 신뢰성이 있다", "acronym:TCP"),
    ("3 hosts", "4개", "number"),
    ("3 hosts", "호스트", "number"),
    ("twice as fast", "세 배 빠르다", "number"),
    ("more than 3", "3개 이상", "cmp:more_than"),
    ("more than 3", "3개", "cmp:more_than"),
    ("at most the same number of steps", "거의 동일한 단계 수", "cmp:at_most"),
    ("at least 3", "3개", "cmp:at_least"),
    ("less than 3", "3개 이하", "cmp:less_than"),
    ("it only holds the address", "주소를 저장한다", "only"),
    ("it does not grow", "늘어난다", "neg"),
    ("without errors", "오류와 함께", "neg"),
])
def test_trans_check_reports_rule(en, ko, rule):
    assert Q.trans_check(en, ko) == rule


def test_trans_check_free_memory_as_free_of_charge_fails():
    assert Q.trans_check("free memory", "무료 기억").startswith("glossary:")


# ---------- 수용 기준 10: 사람 검수(2026-11-25)에서 찾은 번역 오류 5행 ----------
# review_sheet.csv 의 영어 원문·번역 결과에서 해당 문장만 잘라 왔다.

REVIEW_TRANS_ERRORS = [
    ("MohlerASAG-E12.Q01.A10", "The address of a location in memory.",
     "기억 속에 있는 특정 장소의 주소.", "glossary:memory"),
    ("MohlerASAG-E12.Q10.A21",
     "to find a node in a binary search tree takes at most the same number of steps as there are levels of the tree.",
     "이진 검색 트리에서 노드를 찾는 데 필요한 단계 수는 트리 자체의 레벨 수와 거의 동일합니다.", "cmp:at_most"),
    ("SAF-43a0597d", "therefore sense the medium to be free even though there is an overlap of the transmission ranges.",
     "따라서 전송 범위가 겹치는 상황임에도 불구하고 매체를 무료로 인식합니다.", "glossary:free"),
    ("SAF-523bc5e9", "So we have to look for the remaining paths with more than 3 H's",
     "따라서, 3개 이상의 H가 나타나는 나머지 경로를 찾아야 합니다.", "cmp:more_than"),
    ("SAF-6267c2f8", "Each node has a routing table stemming from unicast routing algorithms.",
     "각 노드는 단일 방향 라우팅 알고리즘을 기반으로 하는 라우팅 테이블을 가지고 있습니다.", "glossary:unicast"),
]


@pytest.mark.parametrize("rid, en, ko, rule", REVIEW_TRANS_ERRORS, ids=[r[0] for r in REVIEW_TRANS_ERRORS])
def test_review_trans_errors_are_caught(rid, en, ko, rule):
    assert Q.trans_check(en, ko) == rule
