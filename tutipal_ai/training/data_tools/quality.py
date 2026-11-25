"""PT-88 학습 데이터 품질 규칙(순수 함수, LLM·파일 없음).

- trans_check: 영어 원문 → 한국어 번역 한 칸을 규칙으로 검사한다(용어집·약어·숫자·비교·한정·부정).
- expl_check: 작성 모델이 쓴 reason·feedback 을 검사한다(이미 쓴 내용을 빠졌다고 함, 근거 없는 수량, 라벨 모순).
- is_no_answer / is_german: 원천 행 규칙(무응답 → unknown, 독일어 SAF 답안 제외).

규칙은 2026-11-25 사람 검수(30행 중 13행 오류)에서 나온 오류 유형을 막으려고 만들었다.
의미가 바뀐 번역 전체를 잡지는 못한다(LLM 판정 없음).
"""

from __future__ import annotations

import re

# 검사 규칙을 바꾸면 올린다. 번역 캐시 키(#p=)에 들어가 예전 번역을 다시 검사·번역하게 한다.
# 예외: 2026-11-25 Codex 2라운드의 약어(5자 이하·KNOWN_ACRONYMS)·'just + 수' 규칙은 v3 생성 도중 더해서 올리지 않았다
# (올리면 이미 번역한 2,480행 전체가 다시 번역된다). 조립 단계가 캐시 번역을 지금 규칙으로 다시 검사하므로 규칙은
# 적용되고, 새 규칙에 처음 걸린 행은 재번역 없이 빠진다. 다음 규칙 변경 때 키에서 이 버전을 빼면서 정리한다(README).
CHECK_VERSION = "pt88-3"

# en: 영어 용어(단어 단위, 대소문자 무시). ko: 허용 번역(번역 프롬프트에도 보인다). bad: 금지 번역.
GLOSSARY = [
    {"en": "memory", "ko": ("메모리",), "bad": ("기억",)},
    {"en": "free", "ko": ("비어 있는", "사용 가능한", "여유", "해제", "유휴", "빈 ", "자유"), "bad": ("무료",)},
    {"en": "unicast", "ko": ("유니캐스트", "유니캐스팅"), "bad": ("단일 방향",)},
    {"en": "multicast", "ko": ("멀티캐스트", "멀티캐스팅"), "bad": ("다중 방향",)},
    {"en": "broadcast", "ko": ("브로드캐스트", "브로드캐스팅"), "bad": ("방송",)},
]


def _word(en: str) -> re.Pattern:
    # 하이픈으로 붙은 말(collision-free, memory-mapped)은 다른 뜻이라 용어로 보지 않는다.
    return re.compile(rf"(?<![\w-]){re.escape(en)}(?![\w-])", re.IGNORECASE)


def glossary_for(en: str) -> list[dict]:
    """영어 원문에 단어 단위로 나오는 용어집 항목(GLOSSARY 순서)."""
    return [e for e in GLOSSARY if _word(e["en"]).search(en or "")]


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


def _digits(text: str, ko: bool = False) -> set[float]:
    text = _THOUSANDS.sub(lambda m: m.group(1).replace(",", ""), text.translate(_SUBSUP))
    text = _DECIMAL_COMMA.sub(r"\1.\2", text)
    text = _LIST_NO.sub(" ", text)
    if ko:
        text = _KO_WORD_DIGIT.sub(" ", text)
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


def trans_check(en: str, ko: str) -> str | None:
    """번역 한 칸 검사. 통과하면 None, 아니면 첫 번째로 어긴 규칙 이름."""
    for e in glossary_for(en):
        if _has(ko, e["bad"]) or not any(_ko_stem(f) in ko for f in e["ko"]):
            return f"glossary:{e['en']}"
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
    if _ONLY.search(en) and not _has(ko, KO_ONLY):
        return "only"
    if _NEG.search(_NOT_NEG.sub(" ", _NO_CMP.sub(" ", en))) and not _has(ko, KO_NEG):
        return "neg"
    return None


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


def expl_check(row: dict, label: bool | None) -> str | None:
    """작성 결과(question·model_answer·student_answer·reason·feedback, 모두 한국어) 검사. 통과하면 None."""
    texts = (row["reason"], row["feedback"])
    if label is False and any(_already_present(t, row["student_answer"]) for t in texts):
        return "expl:already_present"
    source = "\n".join((row["question"], row["model_answer"], row["student_answer"]))
    if any(_unsupported_quantity(t, source) for t in texts):
        return "expl:unsupported_quantity"
    if label is True and any(_has(t, TRUE_CONTRA_CUES) for t in texts):
        return "expl:label_contradiction"
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
