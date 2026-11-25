"""채점 평가 세트(JSONL)의 형식 검사.

    python -m eval.gradeset <path> [--profile dev|sealed]      # 기본 dev

dev    : 공통 규칙만(다과목 개발 세트, eval/grading_set.jsonl). stdout에 과목 이름별 개수가 나오므로
         봉인 세트에는 반드시 --profile sealed를 쓴다.
sealed : 공통 규칙 + 봉인 세트 규칙(코딩 과목만, answer_form, 행 수·비율). 학습이 끝난 뒤 모델당 한 번만 평가하는 세트라
         stdout에는 숫자 통계·sha256·profile_version만, stderr에는 `파일명:줄:필드 — 사유`만 쓴다(id·qid·원문·과목 이름 없음).

공통 규칙
- 필수 필드와 타입: id·qid·subject·question·model_answer·student_answer·kind 는 문자열, label 은 불리언
- id 는 eval.leakcheck.ID_PATTERN에 맞고 중복이 없다
- kind 는 eval.report.ANSWER_KINDS 중 하나, label == (kind in {exact, paraphrase})
- 같은 qid 는 (question, model_answer, subject)가 하나다
- JSON이 아니거나 객체가 아닌 줄은 오류
sealed 규칙 (비율의 분모는 전체 행 수 n, 경계값은 통과)
- subject 는 모두 "코딩", answer_form 은 모든 행에서 text 또는 code
- n ≥ 60, qid마다 정답·오답이 하나 이상
- 꼼수(eval.leakcheck.TRICK_KINDS) ≥ 15%, code 형식 ≥ 20%, 정답 25%–45%

종료 코드: 0 통과, 1 내용 불량, 2 입력 오류(파일 없음, UTF-8 아님).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from dataclasses import dataclass
from fractions import Fraction
from pathlib import Path

from eval.leakcheck import ID_PATTERN, TRICK_KINDS
from eval.report import ANSWER_KINDS

# 1: 첫 판(공통 규칙 + sealed 규칙).
PROFILE_VERSION = "1"
PROFILES = ("dev", "sealed")
STR_FIELDS = ("id", "qid", "subject", "question", "model_answer", "student_answer", "kind")
CORRECT_KINDS = frozenset({"exact", "paraphrase"})
ANSWER_FORMS = ("text", "code")
SEALED_SUBJECT = "코딩"
SEALED_MIN_ROWS = 60
SEALED_MIN_TRICK = Fraction(15, 100)
SEALED_MIN_CODE = Fraction(20, 100)
SEALED_CORRECT_RANGE = (Fraction(25, 100), Fraction(45, 100))
SET_LINE = "-"  # 세트 단위 오류의 줄 자리
UNSPECIFIED = "미지정"
OTHER = "(알 수 없음)"


class InputError(Exception):
    """파일을 읽을 수 없음. 메시지에는 파일 이름만 넣는다."""


@dataclass(frozen=True)
class Issue:
    """검사 결과 하나. line이 None이면 세트 단위 오류. reason에 원문 값은 넣지 않는다."""
    line: int | None
    field: str
    reason: str


# ---------- 읽기 ----------

def read_bytes(path: Path) -> bytes:
    """파일 내용. 해시와 검사가 같은 바이트를 보도록 한 번만 읽는다."""
    if path.is_dir():
        raise InputError(f"{path.name}: 파일이 아니라 폴더입니다")
    if not path.is_file():
        raise InputError(f"{path.name}: 파일이 없습니다")
    try:
        return path.read_bytes()
    except OSError as e:
        raise InputError(f"{path.name}: 파일을 읽을 수 없습니다 ({type(e).__name__})") from None


def _split_records(text: str) -> list[str]:
    """run_eval.load_jsonl(텍스트 모드 `for line in f`)과 같은 줄 나눔: \\n, \\r\\n, \\r 만 줄 끝이다.
    str.splitlines()는 U+2028·U+0085·\\x0c 에서도 나눠 소비자와 결과가 달라진다."""
    return text.replace("\r\n", "\n").replace("\r", "\n").split("\n")


def parse_lines(data: bytes, name: str = "") -> list[tuple[int, dict | None]]:
    """[(줄 번호, 객체 또는 None)]. 빈 줄은 건너뛰고, JSON이 아니거나 객체가 아닌 줄은 None. BOM은 허용한다."""
    try:
        text = data.decode("utf-8-sig")
    except UnicodeDecodeError:
        raise InputError(f"{name}: 파일을 UTF-8로 읽을 수 없습니다") from None
    out: list[tuple[int, dict | None]] = []
    for no, line in enumerate(_split_records(text), 1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except (ValueError, RecursionError):
            obj = None
        out.append((no, obj if isinstance(obj, dict) else None))
    return out


def read_lines(path: Path) -> list[tuple[int, dict | None]]:
    """파일을 읽어 parse_lines. 파일 없음·폴더·UTF-8 아님은 InputError."""
    return parse_lines(read_bytes(path), path.name)


# ---------- 검사 ----------

def _row_issues(no: int, row: dict, seen_ids: dict[str, int],
                qids: dict[str, tuple[int, tuple[str, str, str]]]) -> list[Issue]:
    issues: list[Issue] = []
    for field in STR_FIELDS:
        if field not in row:
            issues.append(Issue(no, field, "필수 필드가 없습니다"))
        elif not isinstance(row[field], str):
            issues.append(Issue(no, field, "문자열이어야 합니다"))
    if "label" not in row:
        issues.append(Issue(no, "label", "필수 필드가 없습니다"))
    elif not isinstance(row["label"], bool):
        issues.append(Issue(no, "label", "불리언(true/false)이어야 합니다"))

    rid = row.get("id")
    if isinstance(rid, str):
        if not ID_PATTERN.fullmatch(rid):
            issues.append(Issue(no, "id", "형식이 잘못됐습니다(영문·숫자·._- 128자 이하)"))
        elif rid in seen_ids:
            issues.append(Issue(no, "id", f"{seen_ids[rid]}번째 줄과 중복됩니다"))
        else:
            seen_ids[rid] = no

    kind = row.get("kind")
    if isinstance(kind, str):
        if kind not in ANSWER_KINDS:
            issues.append(Issue(no, "kind", "허용되지 않는 답안 유형입니다"))
        elif isinstance(row.get("label"), bool) and row["label"] != (kind in CORRECT_KINDS):
            issues.append(Issue(no, "label", "kind와 맞지 않습니다(exact·paraphrase만 true)"))

    qid = row.get("qid")
    key = (row.get("question"), row.get("model_answer"), row.get("subject"))
    if isinstance(qid, str) and all(isinstance(v, str) for v in key):
        if qid not in qids:
            qids[qid] = (no, key)
        elif qids[qid][1] != key:
            first = qids[qid][0]
            diff = [f for f, a, b in zip(("question", "model_answer", "subject"), qids[qid][1], key) if a != b]
            issues.append(Issue(no, "qid", f"{first}번째 줄과 같은 qid인데 {'·'.join(diff)}가 다릅니다"))
    return issues


def _sealed_row_issues(no: int, row: dict) -> list[Issue]:
    issues: list[Issue] = []
    if isinstance(row.get("subject"), str) and row["subject"] != SEALED_SUBJECT:
        issues.append(Issue(no, "subject", f"sealed는 '{SEALED_SUBJECT}' 과목만 허용합니다"))
    if row.get("answer_form") not in ANSWER_FORMS:
        issues.append(Issue(no, "answer_form", "sealed는 text 또는 code가 있어야 합니다"))
    return issues


def _is_trick(row: dict) -> bool:
    # kind가 list·dict면 frozenset 멤버십에서 TypeError가 나므로 문자열일 때만 본다(타입 오류는 _row_issues가 낸다)
    kind = row.get("kind")
    return isinstance(kind, str) and kind in TRICK_KINDS


def _pct(share: Fraction) -> str:
    return f"{float(share) * 100:.1f}%"


def _sealed_set_issues(rows: list[tuple[int, dict]]) -> list[Issue]:
    issues: list[Issue] = []
    n = len(rows)
    if n < SEALED_MIN_ROWS:
        issues.append(Issue(None, "n", f"행이 {n}개입니다(최소 {SEALED_MIN_ROWS})"))
    if n == 0:
        return issues
    correct = Fraction(sum(r.get("label") is True for _, r in rows), n)
    lo, hi = SEALED_CORRECT_RANGE
    if not lo <= correct <= hi:
        issues.append(Issue(None, "label", f"정답 비율 {_pct(correct)}이 {_pct(lo)}–{_pct(hi)} 밖입니다"))
    trick = Fraction(sum(_is_trick(r) for _, r in rows), n)
    if trick < SEALED_MIN_TRICK:
        issues.append(Issue(None, "kind", f"꼼수 비율 {_pct(trick)}이 {_pct(SEALED_MIN_TRICK)} 미만입니다"))
    code = Fraction(sum(r.get("answer_form") == "code" for _, r in rows), n)
    if code < SEALED_MIN_CODE:
        issues.append(Issue(None, "answer_form", f"code 형식 비율 {_pct(code)}이 {_pct(SEALED_MIN_CODE)} 미만입니다"))
    # qid마다 정답·오답이 하나 이상. qid 자체는 출력하지 않고 그 qid의 첫 줄 번호로 가리킨다.
    by_qid: dict[str, tuple[int, set[bool]]] = {}
    for no, r in rows:
        qid, label = r.get("qid"), r.get("label")
        if isinstance(qid, str) and isinstance(label, bool):
            first, labels = by_qid.setdefault(qid, (no, set()))
            labels.add(label)
    for first, labels in sorted(by_qid.values(), key=lambda v: v[0]):
        if labels != {True, False}:
            missing = "정답" if True not in labels else "오답"
            issues.append(Issue(first, "qid", f"이 줄의 qid에 {missing} 행이 없습니다"))
    return issues


def check_rows(lines: list[tuple[int, dict | None]], profile: str) -> list[Issue]:
    """모든 오류 목록(순수 함수). profile: dev 또는 sealed."""
    if profile not in PROFILES:
        raise ValueError(f"알 수 없는 profile: {profile}")
    issues: list[Issue] = []
    seen_ids: dict[str, int] = {}
    qids: dict[str, tuple[int, tuple[str, str, str]]] = {}
    rows: list[tuple[int, dict]] = []
    for no, row in lines:
        if row is None:
            issues.append(Issue(no, "line", "JSON 객체가 아닙니다"))
            continue
        rows.append((no, row))
        issues += _row_issues(no, row, seen_ids, qids)
        if profile == "sealed":
            issues += _sealed_row_issues(no, row)
    if not lines:
        issues.append(Issue(None, "n", "행이 없습니다"))
    elif profile == "sealed":
        issues += _sealed_set_issues(rows)
    return issues


# ---------- 통계 ----------

def _count(rows: list[dict], field: str, allowed: tuple[str, ...] | frozenset[str] | None = None) -> dict[str, int]:
    """field 값별 개수. 없으면 '미지정', allowed 밖의 값(원문일 수 있음)은 '(알 수 없음)'으로 묶는다."""
    c: Counter[str] = Counter()
    for r in rows:
        v = r.get(field)
        if v is None:
            c[UNSPECIFIED] += 1
        elif isinstance(v, str) and (allowed is None or v in allowed):
            c[v] += 1
        else:
            c[OTHER] += 1
    return dict(sorted(c.items()))


def _label_key(v: object) -> str:
    if v is True:
        return "true"
    if v is False:
        return "false"
    return UNSPECIFIED if v is None else OTHER


def stats(rows: list[dict]) -> dict:
    """숫자 통계. subject는 과목 이름이 들어가므로 sealed 출력에서는 coding만 쓴다."""
    return {
        "n": len(rows),
        "qids": len({r["qid"] for r in rows if isinstance(r.get("qid"), str)}),
        "kind": _count(rows, "kind", ANSWER_KINDS),
        "label": dict(sorted(Counter(_label_key(r.get("label")) for r in rows).items())),
        "answer_form": _count(rows, "answer_form", ANSWER_FORMS),
        "subject": _count(rows, "subject"),
        "coding": sum(r.get("subject") == SEALED_SUBJECT for r in rows),
        "trick": sum(_is_trick(r) for r in rows),
    }


# ---------- CLI ----------

def _parse(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m eval.gradeset", description="채점 평가 세트 형식 검사")
    p.add_argument("path", type=Path, help="평가 세트 JSONL")
    p.add_argument("--profile", choices=PROFILES, default="dev", help="dev(기본) 또는 sealed")
    return p.parse_args(argv)


def _print_stats(s: dict, profile: str, sha: str) -> None:
    print(f"profile: {profile}")
    print(f"profile_version: {PROFILE_VERSION}")
    print(f"sha256: {sha}")
    print(f"n: {s['n']}")
    print(f"qids: {s['qids']}")
    print(f"label: {json.dumps(s['label'], ensure_ascii=False)}")
    print(f"kind: {json.dumps(s['kind'], ensure_ascii=False)}")
    print(f"trick: {s['trick']}")
    print(f"answer_form: {json.dumps(s['answer_form'], ensure_ascii=False)}")
    if profile == "sealed":
        print(f"coding: {s['coding']}")  # 과목 이름은 쓰지 않는다
    else:
        print(f"subject: {json.dumps(s['subject'], ensure_ascii=False)}")


def main(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    path: Path = args.path
    try:
        data = read_bytes(path)  # 해시와 검사가 같은 바이트를 보게 한 번만 읽는다
        lines = parse_lines(data, path.name)
    except InputError as e:
        print(f"입력 오류: {e}", file=sys.stderr)
        return 2
    sha = hashlib.sha256(data).hexdigest()
    issues = check_rows(lines, args.profile)
    _print_stats(stats([r for _, r in lines if r is not None]), args.profile, sha)
    for i in issues:
        line = SET_LINE if i.line is None else i.line
        print(f"{path.name}:{line}:{i.field} — {i.reason}", file=sys.stderr)
    print(f"결과: {'통과' if not issues else f'오류 {len(issues)}개'}")
    return 1 if issues else 0


def _utf8_stdio() -> None:
    """Windows 파이프(cp949)에서 출력하다 UnicodeEncodeError로 종료 코드가 바뀌지 않게 한다(leakcheck와 같은 방식)."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


if __name__ == "__main__":
    _utf8_stdio()
    sys.exit(main())
