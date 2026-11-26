"""채점 학습 데이터의 구성 검사(과목·꼼수 비율, messages 유무).

    python -m eval.trainmix --train <train.jsonl> [--min-coding-share 0.6] [--max-trick-share 0.15]

--train 을 주지 않으면 환경 변수 TUTIPAL_GRADING_TRAIN 을 쓴다(eval.leakcheck와 같음).
학습 범위가 '프로그래밍 과목'이므로 코딩 비율이 최소값 이상, 꼼수(TRICK_KINDS) 비율이 최대값 이하,
모든 행에 messages가 있어야 통과한다. 경계값과 같으면 통과다.
출력은 숫자 통계뿐이다(학습 원문·id·과목 이름을 쓰지 않는다).
종료 코드: 0 통과, 1 구성 위반, 2 입력 오류(파일 없음, 형식 오류, 비율 인자가 [0, 1] 밖).
"""

from __future__ import annotations

import argparse
import os
import sys
from collections import Counter
from fractions import Fraction
from pathlib import Path

from eval import leakcheck

CODING_SUBJECT = "코딩"
UNSPECIFIED = "미지정"
DEFAULT_MIN_CODING_SHARE = 0.6
DEFAULT_MAX_TRICK_SHARE = 0.15


def _count(rows: list[dict], field: str) -> dict[str, int]:
    c: Counter[str] = Counter(str(r[field]) if r.get(field) is not None else UNSPECIFIED for r in rows)
    return dict(sorted(c.items()))


def _label_key(v: object) -> str:
    if v is True:
        return "true"
    if v is False:
        return "false"
    return UNSPECIFIED


def _questions(rows: list[dict]) -> int:
    """서로 다른 문항 수. qid가 있으면 qid, 없으면 정규화(N1)한 question으로 센다."""
    keys = {("qid", r["qid"]) if isinstance(r.get("qid"), str) else ("q", leakcheck.normalize_n1(r["question"]))
            for r in rows}
    return len(keys)


def mix_stats(rows: list[dict]) -> dict:
    """구성 통계. coding/trick은 개수, *_share는 전체 행 대비 비율(행이 없으면 None)."""
    n = len(rows)
    coding = sum(r.get("subject") == CODING_SUBJECT for r in rows)
    trick = sum(r.get("kind") in leakcheck.TRICK_KINDS for r in rows)
    return {
        "n": n,
        "questions": _questions(rows),
        "subject": _count(rows, "subject"),
        "source": _count(rows, "source"),
        "kind": _count(rows, "kind"),
        "label": dict(sorted(Counter(_label_key(r.get("label")) for r in rows).items())),
        "coding": coding,
        "trick": trick,
        "coding_share": coding / n if n else None,
        "trick_share": trick / n if n else None,
        "missing_messages": sum(not r.get("messages") for r in rows),
    }


def _exact(share: float) -> Fraction:
    # 0.15 같은 소수를 2진 오차 없이 비교하려고 문자열 표현으로 분수를 만든다
    return Fraction(str(share))


def mix_problems(stats: dict, min_coding_share: float, max_trick_share: float) -> list[str]:
    """구성 위반 목록(문제당 한 줄, 숫자만). 경계값과 같으면 통과."""
    n = stats["n"]
    if not n:
        return ["행이 없습니다"]
    problems: list[str] = []
    coding, trick = Fraction(stats["coding"], n), Fraction(stats["trick"], n)
    if coding < _exact(min_coding_share):
        problems.append(f"코딩 비율 {float(coding):.3f} < 최소 {min_coding_share}")
    if trick > _exact(max_trick_share):
        problems.append(f"꼼수 비율 {float(trick):.3f} > 최대 {max_trick_share}")
    if stats["missing_messages"]:
        problems.append(f"messages 없는 행 {stats['missing_messages']}개")
    return problems


def _parse(argv: list[str] | None) -> argparse.Namespace:
    p = argparse.ArgumentParser(prog="python -m eval.trainmix", description="채점 학습 데이터 구성 검사")
    # 기본값은 파싱할 때 읽는다(모듈 import 뒤에 환경 변수를 바꿔도 반영되게)
    p.add_argument("--train", default=os.environ.get(leakcheck.ENV_TRAIN),
                   help=f"학습 JSONL (기본: ${leakcheck.ENV_TRAIN})")
    p.add_argument("--min-coding-share", type=float, default=DEFAULT_MIN_CODING_SHARE,
                   help="코딩 과목 최소 비율 (기본 0.6)")
    p.add_argument("--max-trick-share", type=float, default=DEFAULT_MAX_TRICK_SHARE,
                   help="꼼수 답안 최대 비율 (기본 0.15)")
    return p.parse_args(argv)


def _print_stats(s: dict) -> None:
    print(f"n: {s['n']}")
    print(f"questions: {s['questions']}")
    print(f"coding: {s['coding']} ({s['coding_share']:.3f})")
    print(f"trick: {s['trick']} ({s['trick_share']:.3f})")
    print(f"label true/false/미지정: {s['label'].get('true', 0)}/{s['label'].get('false', 0)}"
          f"/{s['label'].get(UNSPECIFIED, 0)}")
    print(f"distinct subject/source/kind: {len(s['subject'])}/{len(s['source'])}/{len(s['kind'])}")
    print(f"missing_messages: {s['missing_messages']}")


def main(argv: list[str] | None = None) -> int:
    args = _parse(argv)
    if not args.train:
        print(f"오류: --train 또는 환경 변수 {leakcheck.ENV_TRAIN}로 학습 데이터를 지정하세요", file=sys.stderr)
        return 2
    for flag, value in (("--min-coding-share", args.min_coding_share), ("--max-trick-share", args.max_trick_share)):
        if not 0.0 <= value <= 1.0:  # NaN도 여기서 걸린다
            print(f"오류: {flag}는 0~1 사이여야 합니다", file=sys.stderr)
            return 2
    train = Path(args.train)
    if train.is_dir():  # leakcheck.load_rows는 폴더도 '파일이 없습니다'라고 해서 여기서 먼저 알린다
        print(f"입력 오류: {train.name}: 파일이 아니라 폴더입니다", file=sys.stderr)
        return 2
    try:
        rows = leakcheck.load_rows(train)
    except leakcheck.InputError as e:
        print(f"입력 오류: {e}", file=sys.stderr)
        return 2
    s = mix_stats(rows)
    _print_stats(s)
    problems = mix_problems(s, args.min_coding_share, args.max_trick_share)
    for p in problems:
        print(f"위반: {p}", file=sys.stderr)
    print(f"결과: {'통과' if not problems else f'위반 {len(problems)}개'}")
    return 1 if problems else 0


def _utf8_stdio() -> None:
    """Windows 파이프(cp949)에서 출력하다 UnicodeEncodeError로 종료 코드가 바뀌지 않게 한다(leakcheck와 같은 방식)."""
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            reconfigure(encoding="utf-8", errors="backslashreplace")


if __name__ == "__main__":
    _utf8_stdio()
    sys.exit(main())
