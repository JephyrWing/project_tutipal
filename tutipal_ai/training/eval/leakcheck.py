"""학습 데이터와 평가 세트(dev, sealed) 사이의 누수를 검사한다.

    python -m eval.leakcheck --train <train.jsonl> [--dev eval/grading_set.jsonl] [--sealed <sealed.jsonl>] [--report <dir>]

--train / --sealed 를 주지 않으면 환경 변수 TUTIPAL_GRADING_TRAIN / TUTIPAL_GRADING_SEALED 를 쓴다.
보고서(stdout, <report>/leakcheck.json)에는 train-dev 쌍은 id와 점수, sealed가 낀 쌍(train-sealed, dev-sealed)은
실패한 id 쌍만(점수·이유·검토 항목 없음) 남기고, 학습·sealed 원문은 절대 쓰지 않는다.
id는 내용을 담지 않는 식별자라고 가정하고, 형식(영문·숫자·._-, 128자 이하)을 검사한다.
종료 코드: 0 누수 없음(검토 항목은 있어도 됨), 1 누수, 2 입력·출력 오류.

규칙 (N1 = NFKC → 소문자 → 보이지 않는 문자·결합 부호(Cf, Cc, Mn, Me, 한글 채움 문자) 제거 → 공백 하나로,
      N2 = N1에서 공백과 유니코드 문장부호 제거, 유사도는 N2의 difflib ratio)
- Q1 question     (train-dev, train-sealed, dev-sealed): N1 같음, ratio ≥ 0.8, 짧은 쪽 N2(10자 이상)가 긴 쪽에 포함 → 실패. 0.6~0.8 검토.
- M1 model_answer (같은 쌍): 두 N2가 15자 이상이고 ratio ≥ 0.8 → 실패. 0.6~0.8 검토.
- S1 student_answer (train-dev, train-sealed): N1 같음 또는 ratio ≥ 0.9 → 실패. 0.75~0.9 검토.
  한쪽이라도 N2가 15자 미만이거나 kind가 unknown이면 비교하지 않는다.
- T1 꼼수 문구 (train-dev, train-sealed): 꼼수 kind 행끼리 student_answer N2의 가장 긴 공통 부분 문자열 길이 ≥ 20 → 실패. 12~19 검토.
- X1 학습 messages (train-dev, train-sealed): 트레이너가 실제로 학습하는 건 messages이므로 필드와 따로 본다.
  train 행에 messages가 있으면 system을 뺀 각 메시지의 N2에 평가 행의 question(N2 10자 이상), model_answer(15자 이상),
  student_answer(15자 이상, kind가 unknown이 아님)의 N2가 들어 있으면 실패. 검토 항목 없이 실패만 낸다.
  messages가 없는 행은 필드 규칙만 적용한다.
  꼼수 문구의 부분 겹침(T1식 공통 부분 문자열)은 messages에 적용하지 않는다. user 메시지의 프롬프트 틀(태그 이름 등)을
  흉내 내는 tag_escape 평가 행이 모든 학습 행과 겹쳐 오탐이 되기 때문이다(실제 데이터에서 확인).
  messages는 {role, content} 객체 목록이어야 하고(role·content는 문자열) 아니면 입력 오류다.
검토 항목은 train-dev만, 규칙마다 점수 높은 순 10개까지 남기고, 자르기 전 개수는 review_total에 적는다.
sealed가 낀 쌍(train-sealed, dev-sealed)은 유사도 오라클이 되지 않도록 실패의 id 쌍만 남긴다(점수·이유·검토 항목 없음).
한계: 어순만 바꾼 문항은 ratio가 낮아 검토로만 잡힐 수 있다.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import unicodedata
from difflib import SequenceMatcher
from functools import lru_cache
from pathlib import Path

EVAL_DIR = Path(__file__).resolve().parent
TRAINING_DIR = EVAL_DIR.parent
DEFAULT_DEV = EVAL_DIR / "grading_set.jsonl"
DEFAULT_REPORT = TRAINING_DIR / "results" / "leakcheck"
REPORT_NAME = "leakcheck.json"
ENV_TRAIN = "TUTIPAL_GRADING_TRAIN"
ENV_SEALED = "TUTIPAL_GRADING_SEALED"

# 2: 정규화에서 보이지 않는 문자·결합 부호를 지운다(1에서는 이런 문자를 끼워 넣어 T1·Q1을 피할 수 있었다).
# 3: X1 추가. 필드가 달라도 학습 messages에 평가 문구가 들어 있으면 잡는다.
RULE_VERSION = "3"
REQUIRED_FIELDS = ("id", "question", "model_answer", "student_answer", "kind")
TRICK_KINDS = frozenset({"injection", "injection_with_answer", "tag_escape"})
SEALED_PAIRS = frozenset({"train-sealed", "dev-sealed"})
REVIEW_TOP = 10
ID_PATTERN = re.compile(r"[A-Za-z0-9._-]{1,128}")
THRESHOLDS: dict[str, dict[str, float]] = {
    "Q1": {"fail_ratio": 0.8, "review_ratio": 0.6, "contain_min_len": 10},
    "M1": {"fail_ratio": 0.8, "review_ratio": 0.6, "min_len": 15},
    "S1": {"fail_ratio": 0.9, "review_ratio": 0.75, "min_len": 15},
    "T1": {"fail_len": 20, "review_len": 12},
    "X1": {"question_min_len": 10, "answer_min_len": 15},
}
# 형식 문자(ZWSP, WORD JOINER, SOFT HYPHEN, 태그 문자 등), 제어 문자, 결합 부호(이형 선택자 포함).
_DROP_CATEGORIES = frozenset({"Cf", "Cc", "Mn", "Me"})
# 분류는 글자(Lo)지만 화면에 보이지 않는 한글 채움 문자.
_DROP_CHARS = frozenset({"\u115f", "\u1160", "\u3164", "\uffa0"})
_CACHE_SIZE = 1 << 16


class InputError(Exception):
    """입력 파일 문제. 메시지에는 파일 이름·줄 번호·필드 이름만 넣고 내용은 넣지 않는다."""


# ---------- 정규화 ----------

@lru_cache(maxsize=_CACHE_SIZE)
def normalize_n1(text: str) -> str:
    """NFKC → 소문자 → 보이지 않는 문자·결합 부호 제거 → 연속 공백을 하나로, 앞뒤 공백 제거."""
    # NFD로 풀어야 NFKC가 합쳐 둔 '글자+결합 부호'의 부호도 지울 수 있다. 한글은 NFC에서 다시 합쳐진다.
    decomposed = unicodedata.normalize("NFD", unicodedata.normalize("NFKC", text).lower())
    visible = "".join(ch for ch in decomposed
                      if ch.isspace() or (unicodedata.category(ch) not in _DROP_CATEGORIES
                                          and ch not in _DROP_CHARS))
    return " ".join(unicodedata.normalize("NFC", visible).split())


@lru_cache(maxsize=_CACHE_SIZE)
def normalize_n2(text: str) -> str:
    """N1에서 공백과 유니코드 문장부호(P*)를 모두 뺀다."""
    return "".join(ch for ch in normalize_n1(text)
                   if not ch.isspace() and not unicodedata.category(ch).startswith("P"))


# ---------- 비교 ----------

def _groups(rows: list[dict], field: str, keep=None) -> dict[str, tuple[str, list[str]]]:
    """N2 → (N1 하나, id 목록). 같은 문장을 여러 번 비교하지 않으려고 묶는다."""
    out: dict[str, tuple[str, list[str]]] = {}
    for r in rows:
        if keep is not None and not keep(r):
            continue
        n2 = normalize_n2(r[field])
        if n2 in out:
            out[n2][1].append(r["id"])
        else:
            out[n2] = (normalize_n1(r[field]), [r["id"]])
    return out


def _ratio_pairs(a_groups, b_groups, lo: float):
    """ratio ≥ lo 인 (a_n2, b_n2, ratio). quick_ratio 계열로 먼저 거른다."""
    sm = SequenceMatcher(autojunk=False)
    for b_n2 in b_groups:
        sm.set_seq2(b_n2)
        for a_n2 in a_groups:
            sm.set_seq1(a_n2)
            if sm.real_quick_ratio() < lo or sm.quick_ratio() < lo:
                continue
            r = sm.ratio()
            if r >= lo:
                yield a_n2, b_n2, r


def _emit(out: list, rule: str, pair: str, a_ids, b_ids, score: float, reason: str) -> None:
    for a in a_ids:
        for b in b_ids:
            out.append({"rule": rule, "pair": pair, "a_id": a, "b_id": b,
                        "score": round(score, 4), "reason": reason})


def _q1(a_rows, b_rows, pair, fails, reviews) -> None:
    t = THRESHOLDS["Q1"]
    a_g, b_g = _groups(a_rows, "question"), _groups(b_rows, "question")
    done: set[tuple[str, str]] = set()
    for a_n2, b_n2, r in _ratio_pairs(a_g, b_g, t["review_ratio"]):
        done.add((a_n2, b_n2))
        a_n1, a_ids = a_g[a_n2]
        b_n1, b_ids = b_g[b_n2]
        if a_n1 == b_n1:
            _emit(fails, "Q1", pair, a_ids, b_ids, 1.0, "n1_equal")
        elif r >= t["fail_ratio"]:
            _emit(fails, "Q1", pair, a_ids, b_ids, r, "ratio")
        elif _contained(a_n2, b_n2, t["contain_min_len"]):
            _emit(fails, "Q1", pair, a_ids, b_ids, r, "contained")
        else:
            _emit(reviews, "Q1", pair, a_ids, b_ids, r, "ratio")
    # 포함 관계는 ratio가 낮아도(짧은 문항이 긴 문항 안에 통째로 들어간 경우) 실패다.
    for a_n2, (_, a_ids) in a_g.items():
        for b_n2, (_, b_ids) in b_g.items():
            if (a_n2, b_n2) not in done and _contained(a_n2, b_n2, t["contain_min_len"]):
                r = SequenceMatcher(None, a_n2, b_n2, autojunk=False).ratio()
                _emit(fails, "Q1", pair, a_ids, b_ids, r, "contained")


def _contained(a: str, b: str, min_len: float) -> bool:
    short, long_ = (a, b) if len(a) <= len(b) else (b, a)
    return len(short) >= min_len and short in long_


def _m1(a_rows, b_rows, pair, fails, reviews) -> None:
    t = THRESHOLDS["M1"]
    a_g, b_g = _groups(a_rows, "model_answer"), _groups(b_rows, "model_answer")
    for a_n2, b_n2, r in _ratio_pairs(a_g, b_g, t["review_ratio"]):
        if min(len(a_n2), len(b_n2)) < t["min_len"]:
            continue
        target = fails if r >= t["fail_ratio"] else reviews
        _emit(target, "M1", pair, a_g[a_n2][1], b_g[b_n2][1], r, "ratio")


def _s1_keep(r: dict) -> bool:
    return r["kind"] != "unknown" and len(normalize_n2(r["student_answer"])) >= THRESHOLDS["S1"]["min_len"]


def _s1(a_rows, b_rows, pair, fails, reviews) -> None:
    t = THRESHOLDS["S1"]
    a_g = _groups(a_rows, "student_answer", _s1_keep)
    b_g = _groups(b_rows, "student_answer", _s1_keep)
    for a_n2, b_n2, r in _ratio_pairs(a_g, b_g, t["review_ratio"]):
        a_n1, a_ids = a_g[a_n2]
        b_n1, b_ids = b_g[b_n2]
        if a_n1 == b_n1:
            _emit(fails, "S1", pair, a_ids, b_ids, 1.0, "n1_equal")
        elif r >= t["fail_ratio"]:
            _emit(fails, "S1", pair, a_ids, b_ids, r, "ratio")
        else:
            _emit(reviews, "S1", pair, a_ids, b_ids, r, "ratio")


def _is_trick(r: dict) -> bool:
    return r["kind"] in TRICK_KINDS


def _t1(a_rows, b_rows, pair, fails, reviews) -> None:
    t = THRESHOLDS["T1"]
    a_g = _groups(a_rows, "student_answer", _is_trick)
    b_g = _groups(b_rows, "student_answer", _is_trick)
    sm = SequenceMatcher(autojunk=False)
    for b_n2, (_, b_ids) in b_g.items():
        sm.set_seq2(b_n2)
        for a_n2, (_, a_ids) in a_g.items():
            sm.set_seq1(a_n2)
            size = sm.find_longest_match(0, len(a_n2), 0, len(b_n2)).size
            if size >= t["fail_len"]:
                _emit(fails, "T1", pair, a_ids, b_ids, size, "common_substring")
            elif size >= t["review_len"]:
                _emit(reviews, "T1", pair, a_ids, b_ids, size, "common_substring")


def _message_texts(r: dict) -> list[str]:
    """train 행 messages에서 system을 뺀 메시지마다의 N2. 메시지 경계를 넘는 우연한 포함을 막으려고 잇지 않는다."""
    return [normalize_n2(m["content"]) for m in r.get("messages") or () if m["role"] != "system"]


def _x1(train_rows, eval_rows, pair, fails) -> None:
    t = THRESHOLDS["X1"]
    targets = [(r["id"], _message_texts(r)) for r in train_rows if r.get("messages")]
    if not targets:
        return
    for e in eval_rows:
        needles: list[tuple[str, str]] = []
        q = normalize_n2(e["question"])
        if len(q) >= t["question_min_len"]:
            needles.append(("question_contained", q))
        m = normalize_n2(e["model_answer"])
        if len(m) >= t["answer_min_len"]:
            needles.append(("model_answer_contained", m))
        s = normalize_n2(e["student_answer"])
        if e["kind"] != "unknown" and len(s) >= t["answer_min_len"]:
            needles.append(("student_answer_contained", s))
        for tid, texts in targets:
            for reason, needle in needles:
                if any(needle in text for text in texts):
                    _emit(fails, "X1", pair, [tid], [e["id"]], len(needle), reason)


def _top_reviews(reviews: list[dict]) -> tuple[list[dict], dict[str, int]]:
    """(규칙, 쌍)마다 점수 높은 순 REVIEW_TOP개와, 자르기 전 개수 {"규칙 쌍": n}."""
    by_key: dict[tuple[str, str], list[dict]] = {}
    for x in reviews:
        by_key.setdefault((x["rule"], x["pair"]), []).append(x)
    out: list[dict] = []
    totals: dict[str, int] = {}
    for key in sorted(by_key):
        items = sorted(by_key[key], key=lambda x: (-x["score"], x["a_id"], x["b_id"]))
        out.extend(items[:REVIEW_TOP])
        totals[f"{key[0]} {key[1]}"] = len(items)
    return out, totals


def _redact_sealed(fails: list[dict]) -> list[dict]:
    """sealed가 낀 실패는 점수·이유를 지운다. 반복 실행으로 sealed 원문을 점수로 더듬어 찾지 못하게 한다."""
    out: list[dict] = []
    seen: set[tuple[str, str, str, str]] = set()
    for x in fails:
        if x["pair"] not in SEALED_PAIRS:
            out.append(x)
            continue
        key = (x["rule"], x["pair"], x["a_id"], x["b_id"])
        if key not in seen:
            seen.add(key)
            out.append({"rule": x["rule"], "pair": x["pair"], "a_id": x["a_id"], "b_id": x["b_id"]})
    return out


def check_leaks(train: list[dict], dev: list[dict], sealed: list[dict] | None = None) -> dict:
    """누수 검사.

    반환: failures(rule/pair/a_id/b_id, train-dev는 score/reason도), review(train-dev만, (규칙, 쌍)마다 상위 10개),
    review_total({"규칙 쌍": 자르기 전 개수}), sealed(checked|not_checked).
    """
    fails: list[dict] = []
    reviews: list[dict] = []
    pairs = [("train-dev", train, dev)]
    if sealed is not None:
        pairs += [("train-sealed", train, sealed), ("dev-sealed", dev, sealed)]
    for pair, a, b in pairs:
        # sealed 쌍의 검토 항목은 버린다(임계값 아래 유사도도 오라클이 된다).
        pair_reviews: list[dict] = [] if pair in SEALED_PAIRS else reviews
        _q1(a, b, pair, fails, pair_reviews)
        _m1(a, b, pair, fails, pair_reviews)
        if pair != "dev-sealed":
            _s1(a, b, pair, fails, pair_reviews)
            _t1(a, b, pair, fails, pair_reviews)
            _x1(a, b, pair, fails)
    fails = _redact_sealed(fails)
    fails.sort(key=lambda x: (x["rule"], x["pair"], x["a_id"], x["b_id"]))
    top, totals = _top_reviews(reviews)
    return {"failures": fails, "review": top, "review_total": totals,
            "sealed": "checked" if sealed is not None else "not_checked"}


# ---------- 입출력 ----------

def load_rows(path: Path) -> list[dict]:
    """JSONL을 읽고 필수 필드·id 형식·id 중복을 확인한다. 문제가 있으면 InputError(파일·줄·필드만 언급)."""
    name = path.name
    if not path.is_file():
        raise InputError(f"{name}: 파일이 없습니다")
    rows: list[dict] = []
    seen: dict[str, int] = {}
    try:
        text = path.read_text(encoding="utf-8-sig")
    except (OSError, UnicodeDecodeError) as e:
        raise InputError(f"{name}: 파일을 UTF-8로 읽을 수 없습니다 ({type(e).__name__})") from None
    # 줄 경계는 \n·\r·\r\n 만 쓴다(run_eval.load_jsonl 과 같다). str.splitlines() 는 U+2028·U+0085·\x0c 등에서도
    # 쪼개서, ensure_ascii=False 로 쓴 정상 행 안의 문자를 경계로 오인하고 U+2028로 이은 두 행은 통과시킨다.
    lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    for no, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except (ValueError, RecursionError):  # JSONDecodeError, 너무 긴 정수, 너무 깊은 중첩
            raise InputError(f"{name}:{no}: JSON 형식이 아닙니다") from None
        if not isinstance(obj, dict):
            raise InputError(f"{name}:{no}: JSON 객체가 아닙니다")
        for field in REQUIRED_FIELDS:
            if field not in obj:
                raise InputError(f"{name}:{no}: 필수 필드 '{field}'가 없습니다")
            if not isinstance(obj[field], str):
                raise InputError(f"{name}:{no}: 필드 '{field}'는 문자열이어야 합니다")
        if "messages" in obj and not _valid_messages(obj["messages"]):
            raise InputError(f"{name}:{no}: 필드 'messages'는 role·content가 문자열인 객체 목록이어야 합니다")
        rid = obj["id"]
        if not ID_PATTERN.fullmatch(rid):
            raise InputError(f"{name}:{no}: id 형식이 잘못됐습니다 (영문·숫자·._- 128자 이하)")
        if rid in seen:
            raise InputError(f"{name}:{no}: id가 {seen[rid]}번째 줄과 중복됩니다")
        seen[rid] = no
        rows.append(obj)
    if not rows:
        raise InputError(f"{name}: 행이 없습니다")
    return rows


def _valid_messages(messages) -> bool:
    return isinstance(messages, list) and all(
        isinstance(m, dict) and isinstance(m.get("role"), str) and isinstance(m.get("content"), str)
        for m in messages)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _parse(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m eval.leakcheck", description="학습 데이터 <-> 평가 세트 누수 검사")
    p.add_argument("--train", default=os.environ.get(ENV_TRAIN), help=f"학습 JSONL (기본: ${ENV_TRAIN})")
    p.add_argument("--dev", default=str(DEFAULT_DEV), help="dev 평가 세트 JSONL")
    p.add_argument("--sealed", default=os.environ.get(ENV_SEALED), help=f"sealed 평가 세트 JSONL (기본: ${ENV_SEALED})")
    p.add_argument("--report", default=str(DEFAULT_REPORT),
                   help="결과 JSON을 쓸 폴더 (권장: git이 무시하는 results/ 아래, 아니면 경고)")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    if not args.train:
        print(f"오류: --train 또는 환경 변수 {ENV_TRAIN}로 학습 데이터를 지정하세요", file=sys.stderr)
        return 2
    if args.sealed == "":
        print(f"주의: {ENV_SEALED}가 비어 있어 sealed는 검사하지 않습니다", file=sys.stderr)
    train_path, dev_path = Path(args.train), Path(args.dev)
    sealed_path = Path(args.sealed) if args.sealed else None
    try:
        train = load_rows(train_path)
        dev = load_rows(dev_path)
        sealed = load_rows(sealed_path) if sealed_path else None
    except InputError as e:
        print(f"입력 오류: {e}", file=sys.stderr)
        return 2

    result = check_leaks(train, dev, sealed)
    report_dir = Path(args.report)
    if "results" not in report_dir.resolve().parts:
        # 막지는 않는다(테스트·임시 폴더). 저장소 안에 쓰면 실수로 커밋될 수 있어 알린다.
        print("주의: --report가 results/ 폴더 아래가 아닙니다. 저장소 안이라면 커밋하지 않게 조심하세요", file=sys.stderr)
    out = report_dir / REPORT_NAME
    try:
        result["sha256"] = {"train": _sha256(train_path), "dev": _sha256(dev_path),
                            "sealed": _sha256(sealed_path) if sealed_path else None}
        result["counts"] = {"train": len(train), "dev": len(dev), "sealed": len(sealed) if sealed else None}
        result["rule_version"] = RULE_VERSION
        result["thresholds"] = THRESHOLDS
        report_dir.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    except OSError as e:
        # 쓰기 실패가 종료 코드 1(누수)로 보이지 않게 2로 끝낸다.
        print(f"출력 오류: 보고서를 쓸 수 없습니다 ({type(e).__name__})", file=sys.stderr)
        return 2

    _print_summary(result, out)
    return 1 if result["failures"] else 0


def _print_summary(result: dict, out: Path) -> None:
    def count(items):
        c: dict[str, int] = {}
        for x in items:
            k = f"{x['rule']} {x['pair']}"
            c[k] = c.get(k, 0) + 1
        return c

    sealed_note = "" if result["sealed"] == "checked" else " (dev만 검사, 참고용 - 학습 게이트가 아님)"
    print(f"sealed: {result['sealed']}{sealed_note}")
    print(f"failures: {len(result['failures'])} {count(result['failures'])}")
    print(f"review: {len(result['review'])} (자르기 전 {result['review_total']})")
    for x in result["failures"][:20]:
        detail = f" score={x['score']} ({x['reason']})" if "score" in x else ""
        print(f"  FAIL {x['rule']} {x['pair']} {x['a_id']} <-> {x['b_id']}{detail}")
    print(f"결과: {out}")


def _utf8_stdio() -> None:
    """Windows 파이프(cp949)에서 출력하다 UnicodeEncodeError로 종료 코드 1이 나지 않게 한다."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


if __name__ == "__main__":
    _utf8_stdio()
    sys.exit(main())
