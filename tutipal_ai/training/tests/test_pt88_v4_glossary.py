"""PT-88 v4 번역 규칙 테스트: 용어집 확장(AC-1), 단위(AC-2), 칸 사이 일관성(AC-3), SAF 통신 용어(AC-7),
번역 캐시 키 유지(AC-6 번역 쪽).

v3 사람 검수(2026-11-26)에서 나온 오류 문장을 썼다. LLM·네트워크를 쓰지 않는다.
용어집 항목의 구조(항목 하나에 영어 형태를 몇 개 두는지)는 정하지 않고, glossary_for 로 찾은 항목들의
허용형(ko)·금지형(bad) 합집합으로 확인한다.

규칙 이름: 'glossary:<영어 용어>', 'unit', 행 단위 'trans_check:consistency:<영어 용어>'.
"""

import pytest

from data_tools import build_grading_c as BC
from data_tools import compare_hybrid as H
from data_tools import quality as Q
from data_tools.common import DEFAULT_SEED

TRANSLATOR = "translategemma:4b"
V3_TRANS_HASH = "cf0d2ede"  # derived/grading_v3/stats.json prompt_hash.trans (v3 생성 때 값)


def _allowed(word):
    return {f for e in Q.glossary_for(word) for f in e["ko"]}


def _forbidden(word):
    return {f for e in Q.glossary_for(word) for f in e.get("bad", ())}


def _terms(text):
    return {e["en"] for e in Q.glossary_for(text)}


# ---------- AC-1: 새 용어집 항목 (허용형·금지형) ----------

@pytest.mark.parametrize("word, allowed, forbidden", [
    ("dimension", {"차원"}, {"치수"}),
    ("inorder", {"중위"}, {"인오더"}),
    ("preorder", {"전위"}, {"프리오더"}),
    ("postorder", {"후위"}, {"포스트오더"}),
    ("traversal", {"순회"}, {"트래버설"}),
    ("traverse", {"순회"}, set()),
    ("subtree", {"하위 트리", "서브트리"}, set()),
    ("entity", {"항목", "개체"}, set()),
    ("argument", {"인수", "인자"}, {"논쟁", "주장"}),
    ("base case", {"기저 사례"}, set()),
    ("constructor", {"생성자"}, set()),
    ("destructor", {"소멸자"}, set()),
    ("dereference", {"역참조"}, set()),
    ("reference", {"참조"}, set()),
    ("element", {"요소"}, set()),
])
def test_new_coding_glossary_entries(word, allowed, forbidden):
    assert Q.glossary_for(word), f"용어집에 {word!r} 항목이 있어야 한다"
    assert allowed <= _allowed(word)
    assert forbidden <= _forbidden(word)


@pytest.mark.parametrize("word, allowed, forbidden", [
    ("unconfirmed connectionless", {"수신 확인 없는 비연결형"}, {"확인되지 않은"}),
    ("interference", {"간섭"}, set()),
    ("hidden terminal", {"숨은 단말"}, set()),
    ("exposed terminal", {"노출 단말"}, set()),
    ("sender", {"송신자"}, set()),
    ("receiver", {"수신자"}, set()),
])
def test_new_saf_glossary_entries_with_fixed_korean(word, allowed, forbidden):
    """AC-7: SAF 를 빼지 않으므로 통신 용어도 용어집에 넣는다."""
    assert Q.glossary_for(word), f"용어집에 {word!r} 항목이 있어야 한다"
    assert allowed <= _allowed(word)
    assert forbidden <= _forbidden(word)


@pytest.mark.parametrize("word", [
    "confirmed connectionless", "connection-oriented", "perfect clock", "baud", "collision",
    "acknowledgement", "frame", "spanning tree", "CSMA/CD",
])
def test_saf_terms_listed_in_plan_are_in_glossary(word):
    assert Q.glossary_for(word), f"용어집에 {word!r} 항목이 있어야 한다"


def test_existing_entries_are_kept():
    for word, ko in (("memory", "메모리"), ("free", "사용 가능한"), ("unicast", "유니캐스트"),
                     ("multicast", "멀티캐스트"), ("broadcast", "브로드캐스트")):
        assert ko in _allowed(word)


def test_confirmed_connectionless_is_not_matched_inside_unconfirmed():
    """'unconfirmed connectionless' 안의 'confirmed connectionless' 는 낱말 경계가 아니라 따로 잡지 않는다."""
    confirmed = _terms("confirmed connectionless")
    assert confirmed
    assert not (confirmed <= _terms("unconfirmed connectionless"))


# ---------- AC-1: 복수형·활용형·붙임표 매칭 ----------

@pytest.mark.parametrize("text, ko", [
    ("All the dimensions, except the first one.", "차원"),
    ("Traverse the left sub-tree first.", "하위 트리"),
    ("both subtrees are visited", "하위 트리"),
    ("the tree is traversed", "순회"),
    ("traversing the list", "순회"),
    ("traverses every node", "순회"),
    ("the array elements", "요소"),
    ("two arguments are passed", "인수"),
    ("all base cases", "기저 사례"),
    ("constructors and destructors", "생성자"),
    ("the pointer is dereferenced", "역참조"),
    ("it is referenced twice", "참조"),
    ("the senders and the receivers", "송신자"),
    ("the senders and the receivers", "수신자"),
    ("hidden terminals", "숨은 단말"),
    ("What is the Inorder Traversal?", "중위"),
])
def test_glossary_matches_plural_inflected_and_hyphenated_forms(text, ko):
    assert ko in _allowed(text)


@pytest.mark.parametrize("text", [
    "elementary school",
    "freedom and memoryless",
    "a referee",
])
def test_glossary_does_not_match_other_words(text):
    assert not ({"요소", "메모리", "참조", "사용 가능한"} & _allowed(text))


def test_inference_typo_is_not_matched_as_interference():
    """SAF-b4ddfa2c: 원문 오타 'inference' 는 interference 로 잡지 않는다(검출 불가 한계)."""
    assert "간섭" not in _allowed("Wireless networks feature new possibilities for inference and collisions.")


# ---------- AC-1: trans_check 가 새 용어를 검사한다 ----------

@pytest.mark.parametrize("en, ko", [
    ("All the dimensions, except the first one.", "첫 번째를 제외한 모든 차원."),
    ("What is the inorder traversal of a binary tree?", "이진 트리의 중위 순회는 무엇인가요?"),
    ("Traverse the left subtree, then the root, then the right subtree.",
     "왼쪽 하위 트리를 순회한 다음, 루트, 그다음 오른쪽 하위 트리를 순회한다."),
    ("By using constructors.", "생성자를 사용하여"),
    ("passing a multi-dimensional array as an argument to a function",
     "다차원 배열을 함수 인수로 전달할 때"),
    ("Unconfirmed connectionless: Transmission of isolated, independent data units.",
     "수신 확인 없는 비연결형: 고립된 독립 데이터 단위 전송."),
    ("a connection is established between receiver and sender", "수신자와 송신자 사이에 연결이 설정된다"),
    ("Hidden Terminal Problem", "숨은 단말 문제"),
    ("Exposed Terminal Problem", "노출 단말 문제"),
])
def test_trans_check_passes_translation_with_glossary_terms(en, ko):
    assert Q.trans_check(en, ko) is None


@pytest.mark.parametrize("en, ko, rule", [
    # MohlerASAG-E04.Q05.A07 / A28 모범 답안
    ("All the dimensions, except the first one.", "첫 번째 항목을 제외한 모든 치수를 나타냅니다.", "glossary:dimension"),
    ("inorder", "인오더", "glossary:inorder"),
    ("passing an array as an argument", "배열을 논쟁으로 전달할 때", "glossary:argument"),
    # SAF-b610b9c1 학생 답안
    ("Unconfirmed connectionless: Transmission of isolated, independent data units.",
     "확인되지 않은 비연결 방식: 고립된, 독립적인 데이터 단위 전송.", "glossary:unconfirmed connectionless"),
    # SAF-b4ddfa2c 학생 답안
    ("Hidden Terminal Problem", "숨겨진 터미널 문제", "glossary:hidden terminal"),
    ("Exposed Terminal Problem", "노출된 터미널 문제", "glossary:exposed terminal"),
    ("Traverse the left subtree first.", "왼쪽 부분 나무를 먼저 순회한다.", "glossary:subtree"),
])
def test_trans_check_reports_glossary_rule(en, ko, rule):
    assert Q.trans_check(en, ko) == rule


def test_inorder_traversal_question_fails_on_glossary():
    """MohlerASAG-E10.Q06.A16 문제: '인오더 트래버설'."""
    rule = Q.trans_check("What is the inorder traversal of a binary tree?", "이진 트리의 인오더 트래버설은 무엇인가요?")
    assert rule in {"glossary:inorder", "glossary:traversal", "glossary:traverse"}


def test_sender_receiver_mistranslation_fails():
    """SAF-bca32dc7 학생 답안: '발신자와 수자'."""
    rule = Q.trans_check("a connection is established between receiver and sender", "발신자와 수자 간에 연결이 설정됩니다")
    assert rule in {"glossary:sender", "glossary:receiver"}


def test_existing_free_memory_rules_still_hold():
    assert Q.trans_check("free memory", "사용 가능한 메모리") is None
    assert Q.trans_check("The medium is free.", "매체가 무료이다.") == "glossary:free"


def test_translate_prompt_includes_new_glossary_terms(monkeypatch):
    seen = {}

    def fake_generate(model, prompt, system=None, fmt=None, seed=DEFAULT_SEED):
        seen["prompt"] = prompt
        return "번역"

    monkeypatch.setattr(H, "generate", fake_generate)
    H.translate(TRANSLATOR, "What is the inorder traversal of a binary tree?", glossary=True)
    assert "중위" in seen["prompt"] and "순회" in seen["prompt"]
    assert "메모리" not in seen["prompt"]


# ---------- AC-2: 단위(bit/byte) ----------

@pytest.mark.parametrize("en, ko", [
    ("a 32 bit address", "32비트 주소"),
    ("a 4 byte header", "4바이트 헤더"),
    ("8 bits form a byte", "8비트가 바이트 하나를 이룬다"),
    ("encode bitstreams", "비트 스트림을 인코딩한다"),
])
def test_unit_rule_passes_matching_units(en, ko):
    assert Q.trans_check(en, ko) is None


@pytest.mark.parametrize("en, ko", [
    # SAF-9838a386 학생 답안: 1 bit per Baud → 1 바이트당 1 바우드
    ("The advantages are, that this encoding has a good utilization of the bandwith. (1 bit per Baud)",
     "이 인코딩 방식의 장점은 대역폭을 효율적으로 사용한다는 것입니다 (1 바이트당 1 바우드)."),
    ("a 32 bit address", "32바이트 주소"),
    ("a 4 byte header", "4비트 헤더"),
])
def test_unit_rule_fails_when_bit_and_byte_are_swapped(en, ko):
    assert Q.trans_check(en, ko) == "unit"


# ---------- AC-3: 같은 행 칸 사이 일관성 ----------

def _x(q, ref, ans):
    return {"q": q, "ref": ref, "ans": ans}


def _tr(q, ref, ans):
    return {"question": q, "model_answer": ref, "student_answer": ans}


def test_consistency_fails_when_fields_use_different_allowed_forms():
    x = _x("What is a subtree?", "A subtree is part of a tree.", "a subtree is a smaller tree")
    tr = _tr("하위 트리란 무엇인가요?", "하위 트리는 트리의 일부이다.", "서브트리는 더 작은 트리이다.")
    assert BC.trans_error(x, tr) == "trans_check:consistency:subtree"


def test_consistency_passes_when_fields_use_same_form():
    x = _x("What is a subtree?", "A subtree is part of a tree.", "a subtree is a smaller tree")
    tr = _tr("서브트리란 무엇인가요?", "서브트리는 트리의 일부이다.", "서브트리는 더 작은 트리이다.")
    assert BC.trans_error(x, tr) is None


def test_consistency_ignores_term_present_in_one_field_only():
    x = _x("What is a subtree?", "Part of a tree.", "a smaller tree")
    tr = _tr("하위 트리란 무엇인가요?", "트리의 일부.", "더 작은 트리.")
    assert BC.trans_error(x, tr) is None


def test_field_glossary_failure_is_reported_before_consistency():
    """MohlerASAG-E04.Q05.A07: 문제는 '차원', 모범 답안은 '치수'."""
    x = _x("How many dimensions need to be specified when passing a multi-dimensional array as an argument to a function?",
           "All the dimensions, except the first one.", "just the first one at least.")
    tr = _tr("다차원 배열을 함수 인수로 전달할 때, 몇 개의 차원을 명시해야 하나요?",
             "첫 번째 항목을 제외한 모든 치수를 나타냅니다.", "최소한 첫 번째 것만.")
    assert BC.trans_error(x, tr) == "trans_check:glossary:dimension:model_answer"


# ---------- AC-6(번역): TG_PROMPT·CHECK_VERSION 유지, 새 용어가 든 원문만 키가 바뀜 ----------

def test_base_trans_prompt_hash_is_unchanged_from_v3():
    assert BC.trans_prompt_hash() == V3_TRANS_HASH


def test_text_without_new_terms_keeps_v3_key():
    assert BC.trans_prompt_hash("Explain how a stack works.") == V3_TRANS_HASH


def test_text_with_new_terms_gets_new_key():
    assert BC.trans_prompt_hash("What is the inorder traversal of a binary tree?") != V3_TRANS_HASH
