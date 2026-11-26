"""PT-88 학습 데이터 품질 규칙(순수 함수, LLM·파일 없음).

- trans_check: 영어 원문 → 한국어 번역 한 칸을 규칙으로 검사한다(용어집·약어·숫자·비교·한정·부정).
- expl_check: 작성 모델이 쓴 reason·feedback 을 검사한다(이미 쓴 내용을 빠졌다고 함, 근거 없는 수량, 라벨 모순).
- is_no_answer / is_german: 원천 행 규칙(무응답 → unknown, 독일어 SAF 답안 제외).

규칙은 2026-11-25 사람 검수(30행 중 13행 오류)에서 나온 오류 유형을 막으려고 만들었다.
의미가 바뀐 번역 전체를 잡지는 못한다(LLM 판정 없음).
"""

from __future__ import annotations

import functools
import re

# 검사 규칙을 바꾸면 올린다. 번역 캐시 키(#p=)에 들어가 예전 번역을 다시 검사·번역하게 한다.
# 예외: 2026-11-25 Codex 2라운드의 약어(5자 이하·KNOWN_ACRONYMS)·'just + 수' 규칙은 v3 생성 도중 더해서 올리지 않았다
# (올리면 이미 번역한 2,480행 전체가 다시 번역된다). 조립 단계가 캐시 번역을 지금 규칙으로 다시 검사하므로 규칙은
# 적용되고, 새 규칙에 처음 걸린 행은 재번역 없이 빠진다. 다음 규칙 변경 때 키에서 이 버전을 빼면서 정리한다(README).
CHECK_VERSION = "pt88-3"

# en: 영어 용어(단어 단위, 대소문자 무시). ko: 허용 번역(번역 프롬프트에도 보인다). bad: 금지 번역.
# forms(v4 항목만): 영어 표기 목록. 낱말 사이는 공백·붙임표 모두 맞고, 마지막 낱말은 s/es/ed/ing 활용형도 맞는다
# (traverse → traversed·traversing, subtree → sub-tree). forms 가 없는 처음 5개 항목은 예전처럼 정확한 낱말만 본다.
# 처음 5개 항목의 내용은 바꾸지 않는다: 번역 캐시 키(trans_prompt_hash)에 항목이 들어가 v3 번역을 그대로 쓰게 한다.
# v4 항목(2026-11-26 v3 사람 검수)의 허용형은 v3 번역 캐시에서 용어별 탈락률을 재고 넓혔다(README v4 절).
GLOSSARY = [
    {"en": "memory", "ko": ("메모리",), "bad": ("기억",)},
    {"en": "free", "ko": ("비어 있는", "사용 가능한", "여유", "해제", "유휴", "빈 ", "자유"), "bad": ("무료",)},
    {"en": "unicast", "ko": ("유니캐스트", "유니캐스팅"), "bad": ("단일 방향",)},
    {"en": "multicast", "ko": ("멀티캐스트", "멀티캐스팅"), "bad": ("다중 방향",)},
    {"en": "broadcast", "ko": ("브로드캐스트", "브로드캐스팅"), "bad": ("방송",)},
    # ---- v4: 코딩(Mohler) ----
    {"en": "dimension", "ko": ("차원",), "bad": ("치수",), "forms": ("dimension",)},
    # 'in-order' 표기는 SAF 의 순서 보장 전달(in-order delivery) 뜻으로 더 많이 쓰여 넣지 않는다.
    {"en": "inorder", "ko": ("중위",), "bad": ("인오더", "인 오더"), "forms": ("inorder",)},
    {"en": "preorder", "ko": ("전위",), "bad": ("프리오더", "프리 오더"), "forms": ("preorder", "pre-order")},
    {"en": "postorder", "ko": ("후위",), "bad": ("포스트오더", "포스트 오더"), "forms": ("postorder", "post-order")},
    {"en": "traversal", "ko": ("순회",), "bad": ("트래버설", "트래버스"), "forms": ("traversal",)},
    {"en": "traverse", "ko": ("순회",), "bad": ("트래버스",), "forms": ("traverse",)},
    {"en": "subtree", "ko": ("하위 트리", "서브트리", "서브 트리", "하위트리"), "bad": (),
     "forms": ("subtree", "sub-tree", "sub tree")},
    {"en": "entity", "ko": ("항목", "개체", "엔티티"), "bad": (), "forms": ("entity", "entities")},
    {"en": "argument", "ko": ("인수", "인자"), "bad": ("논쟁", "주장"), "forms": ("argument",)},
    {"en": "base case", "ko": ("기저 사례", "기저 조건", "기본 사례", "기저사례"), "bad": (), "forms": ("base case",)},
    {"en": "constructor", "ko": ("생성자",), "bad": (), "forms": ("constructor",)},
    {"en": "destructor", "ko": ("소멸자",), "bad": (), "forms": ("destructor",)},
    {"en": "dereference", "ko": ("역참조",), "bad": (), "forms": ("dereference",)},
    {"en": "reference", "ko": ("참조",), "bad": (), "forms": ("reference",)},
    {"en": "element", "ko": ("요소", "원소"), "bad": (), "forms": ("element",)},
    # ---- v4: 통신(SAF). 서비스 종류는 넓은 항목(connection-oriented)보다 앞에 둔다 ----
    {"en": "unconfirmed connectionless", "ko": ("수신 확인 없는 비연결형", "수신 확인 응답이 없는 비연결형",
                                                "확인 응답이 없는 비연결형", "확인 응답 없는 비연결형"),
     "bad": ("확인되지 않은", "확인되지 않음"), "forms": ("unconfirmed connectionless",)},
    {"en": "confirmed connectionless", "ko": ("수신 확인 비연결형", "수신 확인이 있는 비연결형", "수신 확인 있는 비연결형",
                                              "수신 확인 응답이 있는 비연결형", "확인 응답이 있는 비연결형",
                                              "확인 응답 비연결형"),
     "bad": ("확인된 비연결",), "forms": ("confirmed connectionless",)},
    {"en": "connection-oriented", "ko": ("연결형", "연결 지향", "연결지향"), "bad": (),
     "forms": ("connection-oriented", "connection oriented")},
    {"en": "hidden terminal", "ko": ("숨은 단말", "숨겨진 단말", "은닉 단말"), "bad": ("숨겨진 터미널",),
     "forms": ("hidden terminal", "hidden station", "hidden node")},
    {"en": "exposed terminal", "ko": ("노출 단말", "노출된 단말"), "bad": ("노출된 터미널",),
     "forms": ("exposed terminal", "exposed station", "exposed node")},
    {"en": "interference", "ko": ("간섭",), "bad": (), "forms": ("interference",)},
    {"en": "sender", "ko": ("송신자", "송신 측", "송신측", "송신 노드", "송신기", "송신 장치", "발신자", "보내는 쪽"),
     "bad": (), "forms": ("sender",)},
    {"en": "receiver", "ko": ("수신자", "수신 측", "수신측", "수신 노드", "수신기", "수신 장치", "받는 쪽"), "bad": (),
     "forms": ("receiver",)},
    {"en": "perfect clock", "ko": ("완벽한 클럭", "완벽한 시계", "완벽한 클록", "오차 없이", "오차가 없는",
                                   "클록이 완벽", "클럭이 완벽", "시계가 완벽"),
     "bad": ("완벽한 시간",), "forms": ("perfect clock",)},
    # '보' 한 글자는 '보다'·'보면' 에도 들어 있어 허용형으로 쓰지 않는다(강지 Round 1 판정 e).
    {"en": "baud", "ko": ("보드", "바우드", "보(baud)", "보당", "보 당"), "bad": (), "forms": ("baud",)},
    {"en": "collision", "ko": ("충돌",), "bad": (), "forms": ("collision",)},
    {"en": "acknowledgement", "ko": ("확인 응답", "확인응답", "수신 확인", "승인", "ACK"), "bad": (),
     "forms": ("acknowledgement", "acknowledgment")},
    # 'time frame'(시간 구간)은 데이터 프레임이 아니다(SAF 원문 76곳, 고정 번역 8471e96a 의 '관찰 시간').
    {"en": "frame", "ko": ("프레임",), "bad": (), "forms": ("frame",), "except": (r"time[\s-]*frames?",)},
    {"en": "spanning tree", "ko": ("스패닝 트리", "스패닝트리", "신장 트리", "신장트리"), "bad": (),
     "forms": ("spanning tree",)},
    {"en": "CSMA/CD", "ko": ("CSMA/CD",), "bad": (), "forms": ("CSMA/CD",)},
]


def _word(en: str) -> re.Pattern:
    # 하이픈으로 붙은 말(collision-free, memory-mapped)은 다른 뜻이라 용어로 보지 않는다.
    return re.compile(rf"(?<![\w-]){re.escape(en)}(?![\w-])", re.IGNORECASE)


def _inflect(word: str) -> str:
    """마지막 낱말의 활용형: traverse → travers(e|es|ed|ing), element → element(s|es|ed|ing)?."""
    if not word.isalpha():
        return re.escape(word)
    if word.endswith("e"):
        return re.escape(word[:-1]) + "(?:e[sd]?|ing)"
    return re.escape(word) + "(?:s|es|ed|ing)?"


@functools.lru_cache(maxsize=None)
def _forms_pattern(forms: tuple[str, ...]) -> re.Pattern:
    alts = []
    for form in forms:
        # 공백으로 쓴 표기는 공백·붙임표 모두 맞고(base case, base-case), 붙임표 표기는 붙임표만 맞는다
        # ('in-order' 가 'in order to' 에 맞지 않게).
        parts = re.split(r"(\s+|-)", form)
        words, seps = parts[::2], parts[1::2]
        pat = ""
        for i, w in enumerate(words):
            pat += _inflect(w) if i == len(words) - 1 else re.escape(w)
            if i < len(seps):
                pat += "-" if seps[i] == "-" else r"[\s-]+"
        alts.append(pat)
    # 앞뒤가 영문자·숫자면 다른 낱말이다(elementary, referee, unconfirmed 안의 confirmed). 붙임표로 붙은 합성어의
    # 한 부분도 용어로 보지 않는다(time-frame 의 frame, collision-free 의 collision; 강지 Round 1 판정 b). 그래서
    # multi-dimensional 의 dimension 도 보지 않는다(알려진 한계, README).
    return re.compile(r"(?<![\w-])(?:" + "|".join(alts) + r")(?![\w-])", re.IGNORECASE)


def _entry_pattern(e: dict) -> re.Pattern:
    return _forms_pattern(tuple(e["forms"])) if e.get("forms") else _word(e["en"])


def _entry_hit(e: dict, en: str) -> bool:
    """항목이 영어 원문에 나오는가. except(다른 뜻의 관용구, 예: time frame)는 지우고 찾는다."""
    text = en or ""
    for phrase in e.get("except", ()):
        text = re.sub(rf"\b{phrase}\b", " ", text, flags=re.IGNORECASE)
    return bool(_entry_pattern(e).search(text))


def glossary_for(en: str) -> list[dict]:
    """영어 원문에 단어 단위로 나오는 용어집 항목(GLOSSARY 순서)."""
    return [e for e in GLOSSARY if _entry_hit(e, en)]


CONSISTENCY_SKIP = frozenset({"free"})


def _forms_used(e: dict, ko: str) -> set[str]:
    """한국어 칸에 쓰인 허용형(공백을 뺀 형태). 칸 사이 일관성 검사용."""
    return {_ko_stem(f).replace(" ", "") for f in e["ko"] if _ko_stem(f) in ko}


def consistency_error(fields: list[tuple[str, str]]) -> str | None:
    """같은 행의 (영어, 한국어) 칸들에서, 영어 두 칸 이상에 나온 용어가 칸마다 공통 허용형 없이 옮겨졌으면 그 용어.

    허용형이 여럿인 용어(subtree → 하위 트리 / 서브트리)를 한 행 안에서 섞어 쓰면 학생이 다른 개념으로 읽는다.
    뜻이 여럿인 'free' 는 칸마다 다른 말로 옮기는 게 맞을 수 있어 보지 않는다.
    """
    for e in GLOSSARY:
        if e["en"] in CONSISTENCY_SKIP:
            continue
        used = [_forms_used(e, ko) for en, ko in fields if _entry_hit(e, en)]
        if len(used) >= 2 and all(used) and not set.intersection(*used):
            return e["en"]
    return None


def _ko_stem(form: str) -> str:
    """'비어 있는' → '비어 있' 처럼 관형형 끝 글자를 떼어 '비어 있다'도 허용한다."""
    return form[:-1] if len(form) > 2 and form[-1] in "는한" else form


# ---------- 숫자 ----------

EN_NUM_WORDS = {"zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6, "seven": 7,
                "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12, "twice": 2, "half": 0.5}
KO_NUM_WORDS = {"한": 1, "하나": 1, "두": 2, "둘": 2, "세": 3, "셋": 3, "네": 4, "넷": 4, "다섯": 5, "여섯": 6,
                "일곱": 7, "여덟": 8, "아홉": 9, "열": 10, "절반": 0.5, "반": 0.5}
KO_COUNTERS = "개|번|배|가지|단계|명|줄|칸|비트|바이트|홉|층|자리|차례"
_DIGITS = re.compile(r"\d+(?:\.\d+)?")
_EN_WORDS = re.compile(r"\b(" + "|".join(EN_NUM_WORDS) + r")\b", re.IGNORECASE)
# 한국어 수 낱말은 앞에 한글이 없고 뒤에 띄어쓰기·단위가 올 때만(세계·네트워크·간단한 은 수가 아님).
# '열'(10)은 '열 이름' 같은 다른 뜻이 많아 단위가 붙을 때만 센다.
_KO_WORDS = re.compile(r"(?<![가-힣])(하나|다섯|여섯|일곱|여덟|아홉|절반|한|두|둘|세|셋|네|넷)(?=\s|" + KO_COUNTERS
                       + r"|$)|(?<![가-힣])(열)(?=" + KO_COUNTERS + r")|(?<![가-힣])(반)(?=으로|씩|만큼)")


_SUBSUP = str.maketrans("₀₁₂₃₄₅₆₇₈₉⁰¹²³⁴⁵⁶⁷⁸⁹", "01234567890123456789")  # log₂(n) 의 ₂ 도 수로 본다


_THOUSANDS = re.compile(r"(?<![\d,])(\d{1,3}(?:,\d{3})+)(?![\d,])")  # 1,000 · 12,345,678 → 쉼표 제거
# 숫자 한 쌍 사이 쉼표는 유럽식 소수점(0,6 → 0.6, SAF 독일 학생 답안). '{0,1,2}' 처럼 셋 이상 이어지면 목록이다.
_DECIMAL_COMMA = re.compile(r"(?<![\d,])(\d+),(\d+)(?![\d,])")
# 목록 번호 '1. 2. 3.' 는 수가 아니다. 줄 맨 앞('1.' '1)')과, 영어처럼 한 줄에 이어 쓴 목록('… food. 2. Put …')의
# 문장부호 바로 뒤 'N.' 을 뺀다. 'is 5. Then' · '(phase 5)' 같은 문장 속 수는 남긴다(양쪽 모두 같은 규칙).
_LIST_NO = re.compile(r"(?m)^\s*[-*]?\s*\d+[.)](?!\d)|(?<=[.!?:;]\s)\d{1,2}\.(?=\s|[A-Z])")
# 'give 1 advantage' → '장점을 각각' 처럼 '1 + 명사'의 1 은 관사 'one' 처럼 빠지는 일이 많아 요구하지 않는다.
_EN_ONE_NOUN = re.compile(r"(?<![\d.,])1(?=\s+[A-Za-z])")
# 'two-way→양방향', 'half-duplex→반이중' 처럼 붙임표로 이은 낱말 속 수는 셈이 아니다('twenty-two' 같은 수끼리는 제외).
_EN_HYPHEN_WORD = re.compile(r"\b(" + "|".join(EN_NUM_WORDS) + r")-(?!(?:" + "|".join(EN_NUM_WORDS) + r")\b)[a-z]",
                             re.IGNORECASE)
# 'bi-dimensional→2차원', 'binary→2진' 처럼 한국어가 낱말 안에 붙이는 수.
_KO_WORD_DIGIT = re.compile(r"\d+(?=차원|진수|진법|진 )")
# 'factor of 10' → '10분의 1' 처럼 한국어 분수 'N분의 1' 의 1 은 영어에 없는 수다. N 만 센다('N분의 M' 일반은 그대로).
_KO_FRACTION_ONE = re.compile(r"(\d+)\s*분의\s*1(?![\d.])")


def _digits(text: str, ko: bool = False) -> set[float]:
    text = _THOUSANDS.sub(lambda m: m.group(1).replace(",", ""), text.translate(_SUBSUP))
    text = _DECIMAL_COMMA.sub(r"\1.\2", text)
    text = _LIST_NO.sub(" ", text)
    if ko:
        text = _KO_WORD_DIGIT.sub(" ", text)
        text = _KO_FRACTION_ONE.sub(r"\1 ", text)
    return {float(m) for m in _DIGITS.findall(text)}


def _numbers(text: str, ko: bool) -> tuple[set[float], set[float]]:
    """(아라비아 숫자, 낱말 수)."""
    if ko:
        words = {float(KO_NUM_WORDS[next(g for g in m if g)]) for m in _KO_WORDS.findall(text)}
    else:
        words = {float(EN_NUM_WORDS[w.lower()]) for w in _EN_WORDS.findall(_EN_HYPHEN_WORD.sub(" ", text))}
    return _digits(text, ko), words


def _numbers_match(en: str, ko: str) -> bool:
    """영어의 수(숫자·낱말)는 모두 한국어에 있어야 하고, 한국어의 아라비아 숫자는 모두 영어에 있어야 한다.

    한국어 수 낱말은 영어에 없어도 된다('both'→'두 경우 모두', 'second'→'두 번째', 'a'→'한'). 영어 'one' 도
    관사처럼 빠지는 일이 많아 요구하지 않는다(아라비아 1 은 요구한다).
    """
    en_d, en_w = _numbers(en, ko=False)
    ko_d, ko_w = _numbers(ko, ko=True)
    need_d = _digits(_EN_ONE_NOUN.sub(" ", en))  # '1 + 명사'만 빼고 다른 자리의 1 은 그대로 요구한다
    need = need_d | (en_w - {1.0})
    return need <= ko_d | ko_w and ko_d <= en_d | en_w


# ---------- 비교·한정·부정 (영어 → 한국어 방향만) ----------

# 'up to' 는 수가 뒤따를 때만('hooked up to the end' 는 비교가 아니다).
_UP_TO_NUM = r"up to(?=\s+(?:\d|one\b|two\b|three\b|four\b|five\b|six\b|seven\b|eight\b|nine\b|ten\b))"
_AT_MOST = re.compile(r"\b(at most|no more than|" + _UP_TO_NUM + r"|maximum)\b", re.IGNORECASE)
_AT_LEAST = re.compile(r"\b(at least|minimum|no less than)\b", re.IGNORECASE)
# 'more than one/once' 는 '둘 이상/여러 번'으로 옮기는 게 자연스러워 비교 검사에서 뺀다.
_MORE_THAN = re.compile(r"\b(more than(?! one\b| once\b)|greater than|exceeds?)\b", re.IGNORECASE)
_LESS_THAN = re.compile(r"\b(less than|fewer than)\b", re.IGNORECASE)
# 'just' 는 'just because·just like' 처럼 한정이 아닌 뜻이 많아, 수가 뒤따를 때('just 3 hosts', 'just one bit')만 한정으로 본다.
_ONLY = re.compile(r"\b(only|solely)\b|\bjust(?=\s+(?:\d|one\b|two\b|three\b|four\b|five\b|once\b))", re.IGNORECASE)
_NEG = re.compile(r"\b(not|never|no|none|without|cannot|neither|nor)\b|n't\b", re.IGNORECASE)
_NO_CMP = re.compile(r"\bno (more|less|fewer) than\b", re.IGNORECASE)  # 부정이 아니라 비교 표현
_NOT_NEG = re.compile(r"\b(whether )?or (why )?not\b|\bno matter\b", re.IGNORECASE)  # '여부·관계없이' 로 옮기는 관용구

# 'maximum·minimum' 은 명사(최댓값)로도 쓰여 '가장 큰/작은' 도 허용한다. '보다' 는 '보다 더 많은' 같은 비교.
KO_AT_MOST = ("최대", "최댓", "이하", "까지", "넘지", "가장 크", "가장 큰", "가장 높", "최고")
KO_AT_LEAST = ("최소", "최솟", "이상", "적어도", "가장 작", "가장 낮", "최저")
KO_MORE_THAN = ("초과", "보다", "넘", "더 많", "더 크", "더 큰")
KO_LESS_THAN = ("미만", "보다", "덜", "더 적", "더 작", "적은")
KO_ONLY = ("만", "뿐", "오직", "단지", "유일", "밖에")
KO_NEG = ("않", "안", "못", "없", "아니", "아님", "말고", "말라", "말아", "마세", "모르", "모릅", "불가", "금지")


_INSTEAD_OF_ONLY = re.compile(r"\binstead of only(?=\s+(?:\d|one\b|two\b|three\b|four\b|five\b|half\b))",
                              re.IGNORECASE)


def _has(ko: str, cues: tuple[str, ...]) -> bool:
    return any(c in ko for c in cues)


def _cmp_rule(en: str, ko: str) -> str | None:
    at_most, at_least = bool(_AT_MOST.search(en)), bool(_AT_LEAST.search(en))
    rest = _NO_CMP.sub(" ", en)  # 'no more than' 이 'more than' 으로 다시 걸리지 않게
    if at_most and not _has(ko, KO_AT_MOST):
        return "cmp:at_most"
    if at_least and not _has(ko, KO_AT_LEAST):
        return "cmp:at_least"
    if _MORE_THAN.search(rest) and (not _has(ko, KO_MORE_THAN) or (not at_least and "이상" in ko)):
        return "cmp:more_than"
    if _LESS_THAN.search(rest) and (not _has(ko, KO_LESS_THAN) or (not at_most and "이하" in ko)):
        return "cmp:less_than"
    return None


# 약어: 대문자 2자 이상 + (숫자 | v숫자 | 복수 s). 'FThe·ALso' 같은 오타, 강조용 대문자 영어 낱말은 약어가 아니다.
_ACRONYM = re.compile(r"\b[A-Z]{2,}(?:v?\d+|s)?\b")
CAPS_WORDS = frozenset({"WHAT", "WHICH", "HOW", "WHY", "WHEN", "WHERE", "WHO", "NOT", "NO", "YES", "IS", "ARE", "AND",
                        "OR", "BUT", "ALL", "ANY", "THE", "THIS", "THAT", "THOSE", "BEST", "BIG", "LAST", "FIRST", "MUST",
                        "ONLY", "WITH", "NAME", "OK", "TRUE", "FALSE", "NOTE", "IF", "THEN", "ELSE", "PRO", "CON",
                        "ONE", "TWO", "DO", "IN", "OF", "OH", "TO", "AT", "ON", "BY", "AS", "IT", "BE", "AN", "SO",
                        "UP", "WE", "AFTER", "NEVER", "THERE", "THEIR", "WHILE", "EVERY", "OTHER", "UNTIL", "ABOUT",
                        "ALWAYS", "BEFORE", "AGAIN", "ORDER", "VALUE", "FRONT", "QUEUE", "STACK", "ARRAY", "INPUT"})


# 모음이 많아 낱말처럼 보이지만 약어인 것(원천 데이터의 C/C++·자료구조·네트워크 과목).
KNOWN_ACRONYMS = frozenset({"ASCII", "EIGRP", "ALOHA", "DOCSIS", "IEEE", "OSI", "AES", "AIMD", "ARQ", "ARP",
                            "DHCP", "DNS", "FIFO", "LIFO", "LAN", "WAN", "MAC", "MTU", "NAT", "OSPF", "QOS", "RAID",
                            "RAM", "ROM", "RPB", "RPF", "TCP", "UDP", "IPV", "CSMA", "TDMA", "FDMA", "CDMA", "IO"})


def _is_caps_word(tok: str) -> bool:
    """강조로 대문자로 쓴 영어 낱말(WHAT, INTERNET)인가. 6자 이상이고 모음이 2개 이상이면 낱말로 본다.

    5자 이하(ASCII·EIGRP·ALOHA)와 KNOWN_ACRONYMS 는 모음이 많아도 약어로 본다.
    """
    if tok in KNOWN_ACRONYMS:
        return False
    return tok in CAPS_WORDS or (tok.isalpha() and len(tok) >= 6 and sum(c in "AEIOU" for c in tok) >= 2)


# 단위: 영어에 bit 만 있는데 한국어에 '바이트'(또는 그 반대)면 단위가 바뀐 번역이다(SAF '1 bit per Baud' → '1 바이트당').
# bitstream·bitrate 처럼 붙여 쓴 말도 bit 으로 본다. 영어에 둘 다 있으면 보지 않는다.
_EN_BIT = re.compile(r"(?<![A-Za-z])(?:kilo|mega|giga)?bit(?:s|stream|streams|rate|rates)?(?![A-Za-z])", re.IGNORECASE)
_EN_BYTE = re.compile(r"(?<![A-Za-z])(?:kilo|mega|giga|tera)?bytes?(?![A-Za-z])", re.IGNORECASE)


def _unit_swapped(en: str, ko: str) -> bool:
    has_bit, has_byte = bool(_EN_BIT.search(en)), bool(_EN_BYTE.search(en))
    if has_bit and not has_byte:
        return "바이트" in ko
    if has_byte and not has_bit:
        return "비트" in ko
    return False


def trans_check(en: str, ko: str) -> str | None:
    """번역 한 칸 검사. 통과하면 None, 아니면 첫 번째로 어긴 규칙 이름.

    순서: 처음 5개 용어 → 약어 → 수 → 비교 → 한정 → 부정 → v4 용어(forms 항목) → 단위. v4 규칙을 뒤에 두어
    예전 규칙에 걸리던 칸은 예전 규칙 이름 그대로 남는다(v3 와 탈락 사유 통계를 비교할 수 있게).
    """
    entries = glossary_for(en)
    rule = _glossary_rule([e for e in entries if not e.get("forms")], ko) or _base_rule(en, ko)
    if rule:
        return rule
    rule = _glossary_rule([e for e in entries if e.get("forms")], ko)
    if rule:
        return rule
    return "unit" if _unit_swapped(en, ko) else None


def trans_check_all(en: str, ko: str) -> list[str]:
    """trans_check 가 보는 규칙 중 걸린 것을 모두(trans_check 와 같은 순서). 고정 번역 시트 오류를 한 번에 알리려고 쓴다."""
    out = []
    entries = glossary_for(en)
    out += [f"glossary:{e['en']}" for e in entries if not e.get("forms") and _glossary_rule([e], ko)]
    for tok in dict.fromkeys(_ACRONYM.findall(en)):
        if not _is_caps_word(tok) and tok not in ko and not (tok.endswith("s") and tok[:-1] in ko):
            out.append(f"acronym:{tok}")
    if not _numbers_match(en, ko):
        out.append("number")
    out += [r for r in (_cmp_rule(en, ko), _only_rule(en, ko), _neg_rule(en, ko)) if r]
    out += [f"glossary:{e['en']}" for e in entries if e.get("forms") and _glossary_rule([e], ko)]
    if _unit_swapped(en, ko):
        out.append("unit")
    return out


def _glossary_rule(entries: list[dict], ko: str) -> str | None:
    for e in entries:
        if _has(ko, e["bad"]) or not any(_ko_stem(f) in ko for f in e["ko"]):
            return f"glossary:{e['en']}"
    return None


def _base_rule(en: str, ko: str) -> str | None:
    """약어·수·비교·한정·부정."""
    for tok in dict.fromkeys(_ACRONYM.findall(en)):
        if _is_caps_word(tok):
            continue
        # 복수형 'ACKs' 는 'ACK들' 처럼 옮겨도 된다
        if tok not in ko and not (tok.endswith("s") and tok[:-1] in ko):
            return f"acronym:{tok}"
    if not _numbers_match(en, ko):
        return "number"
    rule = _cmp_rule(en, ko)
    if rule:
        return rule
    return _only_rule(en, ko) or _neg_rule(en, ko)


def _only_rule(en: str, ko: str) -> str | None:
    # 'instead of only 0.5' → '0.5 대신' 은 only 를 '대신' 으로 옮긴 것으로 본다(강지 Round 1 판정 e, 이 구문만).
    only_en = _INSTEAD_OF_ONLY.sub(" ", en) if "대신" in ko else en
    return "only" if _ONLY.search(only_en) and not _has(ko, KO_ONLY) else None


def _neg_rule(en: str, ko: str) -> str | None:
    return "neg" if _NEG.search(_NOT_NEG.sub(" ", _NO_CMP.sub(" ", en))) and not _has(ko, KO_NEG) else None


# ---------- 설명(reason·feedback) 검사 ----------

MISSING_CUES = ("빠졌", "빠져", "빠뜨", "누락", "언급하지 않", "설명하지 않", "포함되지 않", "설명하지 못", "언급하지 못",
                "언급해야", "포함해야")
# 여러 낱말 표현('설명하지 못했습니다')의 낱말 자체는 내용 토큰이 아니다.
_CUE_WORDS = tuple({c.split()[0] for c in MISSING_CUES} | {"않", "못"})
TRUE_CONTRA_CUES = ("빠졌", "누락", "부족", "틀렸", "잘못")
# 수량 근거 검사: 비교 표현은 수와 붙어 있을 때만 본다('최대 log2(n)', '3개 이상'). '경계 초과 오류'·'적어도 하나의'
# 처럼 수가 없는 말은 근거 검사 대상이 아니다(2026-11-25 v2 작성 캐시 측정).
_EXPL_CMP = re.compile(r"(최대|최소|적어도)\s*\S*\d|\d\S*\s*(이하|이상|초과|미만)")
ALREADY_PRESENT_MIN_TOKENS = 2
ALREADY_PRESENT_RATIO = 0.6
_PARTICLE = re.compile(r"(이라는|라는|다는|한다는|에서는|에서|으로|에게|까지|부터|처럼|보다|은|는|이|가|을|를|의|에|로|와|과|도|만)$")
STOPWORDS = frozenset({"학생", "학생은", "답안", "답변", "내용", "부분", "설명", "핵심", "개념", "있습니다", "있다", "없다",
                       "합니다", "했습니다", "하는", "하고", "그리고", "하지만", "다만", "또한", "대한", "관한", "것을", "것이",
                       "점을", "점이", "어떻게", "무엇", "다시", "생각해", "보세요"})
_SENT = re.compile(r"(?<=[.!?])\s+|\n+")


def _content_tokens(sentence: str) -> list[str]:
    out = []
    for w in re.findall(r"[가-힣]+|[A-Za-z0-9]+", sentence):
        if any(c in w for c in MISSING_CUES) or w.startswith(_CUE_WORDS) or w in STOPWORDS:
            continue
        if re.fullmatch(r"[가-힣]+", w):
            w = _PARTICLE.sub("", w)
            if len(w) < 2 or w in STOPWORDS:
                continue
        out.append(w)
    return out


def _already_present(text: str, ans_ko: str) -> bool:
    for s in _SENT.split(text):
        if not any(c in s for c in MISSING_CUES):
            continue
        toks = _content_tokens(s)
        if len(toks) >= ALREADY_PRESENT_MIN_TOKENS and sum(t in ans_ko for t in toks) / len(toks) >= ALREADY_PRESENT_RATIO:
            return True
    return False


def _unsupported_quantity(text: str, source: str) -> bool:
    if not _digits(text) <= _digits(source):
        return True
    return any((m.group(1) or m.group(2)) not in source for m in _EXPL_CMP.finditer(text.translate(_SUBSUP)))


# ---- v4 (2026-11-26 v3 사람 검수) ----
# vague_missing: 오답(wrong·partial) 행의 reason·feedback 이 모범 답안에만 있는 내용(문제·학생 답안에 없는 낱말)을
# 하나도 말하지 않으면 '핵심을 누락했다'만 말하는 모호한 설명으로 본다. 한국어 낱말은 조사를 떼고
# 비교한다(한쪽이 다른 쪽으로 시작하면 같은 말: '반복' ~ '반복적인'). 어디에나 나오는 말은 비교에서 뺀다.
VAGUE_KINDS = frozenset({"wrong", "partial"})
VAGUE_GENERIC = frozenset({"경우", "가지", "있습", "있는", "있다", "있으", "합니", "하는", "하고", "하여", "해야", "때문",
                           "위해", "위한", "통해", "같은", "대한", "관한", "것을", "것이", "것은", "수있", "없습", "필요",
                           "그리", "하지", "또는", "또한", "따라", "그런", "이러", "이는", "다음", "되는", "된다", "됩니"})
# wrong_affirmed: kind=wrong(틀린 개념) 행에서 학생 주장을 맞다고 인정하는 말. partial 은 맞은 부분을 인정해도 된다.
_AFFIRM = re.compile(r"맞습니다|맞지만|맞아요|맞는 (?:내용|설명|부분|말|지적)|옳습니다|옳지만|옳다|옳은 (?:내용|설명|부분|말|지적)"
                     r"|정확합니다|정확하지만|정확해요|정확하다")
# 긍정 낱말 뒤에 부정이 와서 뜻이 '맞지 않다' 인 표현은 인정이 아니다(Round 1 Z10 오탐 쪽):
# '정확하다고 보기 어렵다', '옳다고 볼 수 없다', '맞는 설명이 아니다'. 이 구간을 지운 뒤 _AFFIRM 을 찾는다.
_AFFIRM_NEGATED = re.compile(r"(?:정확하다|옳다|맞다)고\s*(?:보기|볼\s*수|하기|할\s*수|말하기|말할\s*수)\s*(?:는\s*)?(?:어렵|없)"
                             r"|(?:맞는|옳은|정확한)\s*(?:내용|설명|부분|말|지적|답)(?:이|은|는)?\s*(?:아니|아닙|아님|아닌)")


def _words(text: str) -> set[str]:
    """비교용 낱말: 한국어는 조사를 뗀 2자 이상(어디에나 나오는 말·누락 표현 제외), 영어는 소문자."""
    out = set()
    for w in re.findall(r"[가-힣]+|[A-Za-z][A-Za-z0-9_]*", text):
        if re.fullmatch(r"[가-힣]+", w):
            if any(c in w for c in MISSING_CUES) or w.startswith(_CUE_WORDS) or w in STOPWORDS:
                continue
            w = _PARTICLE.sub("", w)
            if len(w) >= 2 and w not in STOPWORDS and w[:2] not in VAGUE_GENERIC:
                out.add(w)
        elif len(w) >= 2:
            out.add(w.lower())
    return out


def _related(a: str, b: str) -> bool:
    """한쪽이 다른 쪽으로 시작하면 같은 말로 본다('반복' ~ '반복적인', '생성자' ~ '생성자를'). '생성자'·'생성될' 은 다르다."""
    return a.startswith(b) or b.startswith(a)


def _vague_missing(row: dict) -> bool:
    known = _words(row["question"]) | _words(row["student_answer"])
    ref_only = [w for w in _words(row["model_answer"]) if not any(_related(w, k) for k in known)]
    if not ref_only:
        return False  # 모범 답안에만 있는 말이 없으면 판단하지 않는다
    said = _words(row["reason"]) | _words(row["feedback"])
    return not any(_related(w, s) for w in ref_only for s in said)


def expl_check(row: dict, label: bool | None, kind: str | None = None) -> str | None:
    """작성 결과(question·model_answer·student_answer·reason·feedback, 모두 한국어) 검사. 통과하면 None.

    kind(답안 유형)를 주면 v4 규칙도 본다: vague_missing(wrong·partial), wrong_affirmed(wrong 만).
    """
    texts = (row["reason"], row["feedback"])
    if label is False and any(_already_present(t, row["student_answer"]) for t in texts):
        return "expl:already_present"
    source = "\n".join((row["question"], row["model_answer"], row["student_answer"]))
    if any(_unsupported_quantity(t, source) for t in texts):
        return "expl:unsupported_quantity"
    if label is True and any(_has(t, TRUE_CONTRA_CUES) for t in texts):
        return "expl:label_contradiction"
    if label is False and kind in VAGUE_KINDS and _vague_missing(row):
        return "expl:vague_missing"
    if label is False and kind == "wrong" and any(_AFFIRM.search(_AFFIRM_NEGATED.sub(" ", t)) for t in texts):
        return "expl:wrong_affirmed"
    return None


# foreign: reason·feedback 에 JSON 잔해('"}', '{')나 영어 원문에 없는 라틴 낱말(3자 이상)이 있으면 실패
# (v3: '"}' 잔해 7행, 'pyeong' 같은 원문에 없는 낱말). 원문(en)·한국어 번역 칸에 있는 낱말, 약어, 아래 목록은 허용한다.
FOREIGN_DEBRIS = ('"}', "{")
FOREIGN_ALLOWED = frozenset({w.lower() for w in KNOWN_ACRONYMS} | {
    # 코드 식별자·키워드(C/C++·자료구조 과목)
    "int", "char", "float", "double", "void", "bool", "true", "false", "null", "nullptr", "new", "delete", "const",
    "static", "class", "struct", "public", "private", "protected", "return", "while", "for", "else", "main", "cout",
    "cin", "endl", "std", "printf", "scanf", "malloc", "free", "sizeof", "this", "virtual", "template", "string",
    "vector", "include", "define", "push", "pop", "enqueue", "dequeue", "head", "tail", "next", "prev", "root",
    "node", "list", "array", "stack", "queue", "tree", "pointer", "loop", "switch", "case", "break", "continue",
    # 통신 과목 약어(KNOWN_ACRONYMS 밖)
    "ack", "acks", "nak", "crc", "lsr", "bgp", "rip", "icmp", "http", "https", "ftp", "smtp", "ipv4", "ipv6",
    "lte", "wlan", "mac", "llc", "pdu", "sdu", "ttl", "rtt", "dqdb", "fddi", "isdn",
    # 수식에 쓰는 함수 이름
    "log", "max", "min", "sqrt", "mod", "abs", "sum",
})
_ACRONYM_SHAPE = re.compile(r"^[A-Z]{2,6}s?$")
# 한국어 낱말·약어 바로 뒤 괄호 속 영어 풀이('정적(static)', '참조(reference)', 'LIFO(Last-In, First-Out)')는
# 작성 모델이 용어를 밝히는 흔한 방식이라 외국어 낱말로 보지 않는다(v3 train_aug 측정: foreign 23행 중 7행).
_GLOSS = re.compile(r"(?<=[가-힣A-Z])\(\s*[A-Za-z][A-Za-z0-9 ,.:/'-]*\)")


def _stem(w: str) -> str:
    w = w.lower()
    for suf in ("ing", "es", "ed", "s"):
        if w.endswith(suf) and len(w) - len(suf) >= 3:
            return w[: -len(suf)]
    return w


def expl_foreign(texts: tuple[str, ...], en: str, ko_source: str = "") -> bool:
    """reason·feedback 에 잔해나 원문에 없는 라틴 낱말이 있으면 True. 낱말 비교는 대소문자를 무시한다."""
    if any(d in t for t in texts for d in FOREIGN_DEBRIS):
        return True
    known = {_stem(w) for w in re.findall(r"[A-Za-z]+", f"{en}\n{ko_source}")}
    for t in texts:
        for w in re.findall(r"[A-Za-z]{3,}", _GLOSS.sub(" ", t)):
            if _ACRONYM_SHAPE.match(w) or w.lower() in FOREIGN_ALLOWED or _stem(w) in known:
                continue
            return True
    return False


# 설명 속 금지형은 명사로 쓴 것만 본다. 바로 뒤에 동사·형용사를 만드는 말이 오면('기억해 주세요', '주장했습니다')
# 다른 뜻의 낱말이다(v3 train_aug 측정: memory 행 feedback 의 '기억하시면' 8행이 모두 이 경우). 모든 용어에 같게 적용한다.
_VERB_TAIL = ("하", "해", "할", "했", "합", "한", "되", "돼", "될", "된", "됩")


def _bad_noun(text: str, bad: str) -> bool:
    start = text.find(bad)
    while start >= 0:
        if not text[start + len(bad):].startswith(_VERB_TAIL):
            return True
        start = text.find(bad, start + 1)
    return False


def expl_glossary(texts: tuple[str, ...], en: str) -> str | None:
    """영어 원문(en)에 나온 용어의 금지 번역이 reason·feedback 에 있으면 그 영어 용어(강지 판정 3)."""
    for e in glossary_for(en):
        if any(_bad_noun(t, b) for t in texts for b in e["bad"]):
            return e["en"]
    return None


# ---------- 원천 행 규칙 ----------

_NO_ANSWER = re.compile(r"^(not answered|no answer|n/?a|i don['’]?t know|idk)\.?$", re.IGNORECASE)
NO_ANSWER_REASON = "학생이 답을 쓰지 않아 내용을 판단할 근거가 없으므로 오답이다."
NO_ANSWER_FEEDBACK = "답안이 비어 있어요. 알고 있는 내용을 짧게라도 적어 보세요."

_GERMAN_CHARS = re.compile(r"[äöüßÄÖÜ]")
GERMAN_STOPWORDS = frozenset({"und", "der", "die", "das", "ist", "nicht", "ein", "eine", "mit", "für", "werden",
                              "sind", "auch", "wenn", "oder"})


def is_no_answer(en: str) -> bool:
    return bool(_NO_ANSWER.match((en or "").strip()))


def is_german(text: str) -> bool:
    if _GERMAN_CHARS.search(text or ""):
        return True
    words = {w.lower() for w in re.findall(r"[A-Za-zäöüß]+", text or "")}
    return len(words & GERMAN_STOPWORDS) >= 3
