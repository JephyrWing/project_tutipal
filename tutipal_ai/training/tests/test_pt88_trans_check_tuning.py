"""PT-88 번역 검사 조정(CHECK_VERSION pt88-2) 회귀 테스트.

2026-11-25 grading_v2 번역 캐시(2972행)에 규칙을 돌려 본 결과에서 뽑은 문장들이다.
오탐(맞는 번역인데 걸림)은 통과해야 하고, 실제 오류는 계속 걸려야 한다.
"""

import pytest

from data_tools import quality as Q


@pytest.mark.parametrize("en, ko", [
    # 강조용 대문자 낱말·오타는 약어가 아니다
    ("WHAT is the purpose of RPB? HOW do they work?", "RPB의 목적은 무엇인가요? 어떻게 작동하나요?"),
    ("Does this hold for real INTERNET traffic?", "실제 인터넷 트래픽에도 맞나요?"),
    ("FThe frame is sent.", "프레임이 전송된다."),
    # 한국어에만 있는 수 낱말(both·서수·관사)
    ("They both involve repetition.", "두 경우 모두 반복을 포함한다."),
    ("places it on the second position", "두 번째 위치에 놓는다"),
    ("It splits itself in half.", "반으로 나눈다."),
    ("There are four ways: a, b, c, d.", "네 가지 경우가 있다:\n1. a\n2. b\n3. c\n4. d"),
    # 비교가 아닌 표현
    ("The D-cell is hooked up to the negative end.", "D형 전지는 음극에 연결되어 있다."),
    ("it identifies the minimum from the remaining elements", "남은 요소 중 가장 작은 값을 찾는다"),
    ("Peanut has more than walnut.", "땅콩은 호두보다 더 많은 영양소를 가진다."),
    ("checks if it is greater or less then the value", "값보다 크거나 작은지 확인한다"),
    # 부정이 아닌 관용구, 부정 동사
    ("whether or not they take a running start", "달리기 시작 여부"),
    ("Why or why not?", "그 이유는 무엇인가요?"),
    ("I do not know.", "저는 모릅니다."),
    ("should not eat so many cookies", "쿠키를 너무 많이 먹지 말라고"),
    ("not shortest route", "최단 경로가 아님"),
    # 'just' 는 한정 검사에서 뺐다
    ("just because one burns out", "하나가 고장 나더라도"),
])
def test_tuned_false_positives_pass(en, ko):
    assert Q.trans_check(en, ko) is None


# CHECK_VERSION pt88-3: 숫자 규칙 오탐(SAF 실패율 54.6%의 주원인) 조정
@pytest.mark.parametrize("en, ko", [
    # 영어는 한 줄에 이어 쓴 목록, 번역은 줄을 나눈 목록
    ("1. Weigh one gram of each food. 2. Put it on 2 pieces of paper. 3. Leave for 2 days. 4. Check samples.",
     "1. 각 식품을 1그램씩 잰다.\n2. 종이 2장에 올린다.\n3. 2일 동안 둔다.\n4. 샘플을 확인한다."),
    ("1) open the file\n2) read it", "1) 파일을 연다\n2) 읽는다"),
    # 문장 속 수는 목록 번호가 아니다
    ("both the coding stage (phase 5) and the refinement stage (phase 7)", "코딩 단계(5단계)와 개선 단계(7단계) 모두"),
    ("When the size of the array is 1 (or 2)", "배열의 크기가 1 (또는 2)일 때"),
    ("The answer is 5. Then stop.", "답은 5입니다. 그다음 멈춘다."),
    # 점 뒤에 띄어쓰기 없이 이어 쓴 목록
    ("two problems: 1.There is a fairness problem. 2.Throughput deteriorates.",
     "두 가지 문제:\n1. 공정성 문제\n2. 처리량 저하"),
    # '1 + 명사' 의 1 은 '각각·하나' 로 옮겨도 된다
    ("Also, give 1 advantage and disadvantage.", "또한, 장점과 단점을 각각 제시해주세요."),
    # 코드 배열의 쉼표는 소수점이 아니다
    ("int array[]={0,1,2,etc.}", "int 배열[]={0, 1, 2, 등}"),
    # 붙임표로 이은 낱말 속 수
    ("i.e. two-way communication over a half-duplex link", "즉, 반이중 링크에서의 양방향 통신"),
    ("a one-way hash", "단방향 해시"),
    # 한국어가 낱말 안에 붙이는 수
    ("How are bi-dimensional arrays stored in memory?", "2차원 배열은 메모리에 어떻게 저장되나요?"),
    # 유럽식 소수점
    ("P(H) = 0,6 then P(T) = 0,4", "P(H) = 0.6 이므로 P(T) = 0.4"),
    ("expected time 56,94 s", "예상 시간 56.94초"),
    # 1000 단위 쉼표는 그대로
    ("about 1,000 hosts", "약 1000개의 호스트"),
])
def test_number_false_positives_pass(en, ko):
    assert Q.trans_check(en, ko) is None


@pytest.mark.parametrize("en, ko", [
    ("twenty-two hosts", "스물 호스트"),            # 수끼리 이은 낱말은 여전히 수다(two 가 빠짐)
    ("Event B, p=0,0138", "이벤트 B, p=0.138"),     # 소수점 자리가 바뀐 오역
    ("Send 3 frames. 4 are lost.", "프레임 3개를 보낸다. 5개가 손실된다."),
    ("calculate 1 - p", "p 를 계산한다"),                # 명사 앞이 아닌 1 은 계속 요구한다
])
def test_number_real_errors_still_fail(en, ko):
    assert Q.trans_check(en, ko) == "number"


# Codex 2라운드: 모음이 많은 약어, 수가 뒤따르는 'just'
@pytest.mark.parametrize("en, ko, rule", [
    ("ASCII encoding uses 7 bits", "인코딩은 7비트를 쓴다", "acronym:ASCII"),
    ("EIGRP routing", "라우팅", "acronym:EIGRP"),
    ("just 3 hosts", "호스트 3개", "only"),
    ("it sends just one bit", "비트 하나를 보낸다", "only"),
])
def test_round2_real_errors_fail(en, ko, rule):
    assert Q.trans_check(en, ko) == rule


@pytest.mark.parametrize("en, ko", [
    ("ASCII encoding uses 7 bits", "ASCII 인코딩은 7비트를 쓴다"),
    ("Does this hold for real INTERNET traffic?", "실제 인터넷 트래픽에도 맞나요?"),  # 6자 이상 강조 낱말
    ("just because one burns out", "하나가 고장 나더라도"),                         # 수가 뒤따르지 않는 just
    ("just 3 hosts", "호스트 3개만"),
    ("A prototype is just a declaration", "프로토타입은 단순히 선언이다"),          # v3 캐시 오탐
    ("it checks the condition AFTER it runs", "실행한 뒤에 조건을 확인한다"),      # 5자 강조 낱말
])
def test_round2_false_positives_pass(en, ko):
    assert Q.trans_check(en, ko) is None


@pytest.mark.parametrize("en, ko, rule", [
    ("When more than 3 scoops are used, the mass drops.", "3 컵 이상 사용할 경우 무게가 줄어든다.", "cmp:more_than"),
    ("When less than 3 scoops are used, the mass grows.", "3 컵 이하로 사용할 경우 무게가 는다.", "cmp:less_than"),
    ("low transmission rates, often up to 200 bit/sec", "낮은 전송 속도, 일반적으로 200비트/초", "cmp:at_most"),
    ("test 3 beverages with 10 drops", "10방울로 음료를 테스트했다", "number"),
    ("a full bit per baud instead of only 0.5", "0.5보다 훨씬 더 많은 정보", "only"),
    ("the next hop on the unicast path", "단일 경로의 다음 홉", "glossary:unicast"),
    ("0.5 P for a sensible choice", "0.  5 P는 합리적인 선택", "number"),
])
def test_real_errors_still_fail(en, ko, rule):
    assert Q.trans_check(en, ko) == rule


# ---------- 설명 검사 조정 ----------

E08 = {"question": "배열로 스택을 어떻게 구현하나요?",
       "model_answer": "배열 끝에서 push 와 pop 을 수행한다.",
       "student_answer": "스택의 크기를 배열과 유사하게 미리 정의할 수 있습니다. 이 유형은 크기를 늘릴 수 없습니다.",
       "reason": "배열 끝에서 push 와 pop 을 수행한다는 원리를 설명하지 못했습니다.",
       "feedback": "push 위치를 생각해 보세요. 추가적으로, 배열의 크기를 늘릴 수 없는 점도 언급해야 합니다."}


def test_review_e08_already_present_is_caught():
    # 사람 검수 MohlerASAG-E08.Q03.A06: 학생이 이미 쓴 '크기를 늘릴 수 없다'를 언급하라고 함
    assert Q.expl_check(E08, False) == "expl:already_present"


@pytest.mark.parametrize("feedback", [
    "배열의 경계 초과 오류는 실행 중에 발생해요. 다시 살펴보세요.",
    "다른 광물 중 적어도 하나로 긁힌다는 점을 생각해 보세요.",
])
def test_comparison_words_without_numbers_are_not_quantities(feedback):
    row = {**E08, "reason": "배열 끝에서 연산한다는 원리를 답하지 않았다.", "feedback": feedback}
    assert Q.expl_check(row, False) is None


def test_number_with_comparison_needs_support():
    row = {**E08, "reason": "배열 끝에서 연산한다는 원리를 답하지 않았다.",
           "feedback": "push 는 배열 끝에서 해요. 크기는 미리 정해요.", "model_answer": "배열 끝에서 3번 이상 push 한다."}
    assert Q.expl_check(row, False) is None
    row["feedback"] = "push 는 3번 미만이면 돼요."
    assert Q.expl_check(row, False) == "expl:unsupported_quantity"


def test_subscript_digits_count_as_numbers():
    # 사람 검수 MohlerASAG-E12.Q10.A06: 피드백의 '최대 log₂(n)' 은 모범 답안('나무의 높이')에 근거가 없다
    row = {"question": "이진 검색 트리에서 노드를 검색하는 단계 수는?", "model_answer": "나무의 높이.",
           "student_answer": "루트에서 시작해 작으면 왼쪽으로 이동합니다.", "reason": "단계 수를 답하지 않았다.",
           "feedback": "필요한 단계 수는 최대 log₂(n) 단계입니다."}
    assert Q.expl_check(row, False) == "expl:unsupported_quantity"
