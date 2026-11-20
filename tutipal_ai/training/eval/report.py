"""평가 기록(results.jsonl) → 모델별 요약표."""

from __future__ import annotations

import csv
import statistics
from collections import Counter, defaultdict
from pathlib import Path

DIFFICULTIES = ("easy", "medium", "hard")
KOREAN_OK = 0.5  # 본문 글자의 절반 이상이 한글이면 한국어 답변으로 본다
INJECTION_KINDS = {"injection", "injection_with_answer", "tag_escape"}
ANSWER_KINDS = ("exact", "paraphrase", "partial", "wrong", "unknown",
                "injection", "injection_with_answer", "tag_escape")


def _rate(hit: int, n: int) -> float | None:
    return hit / n if n else None


def _median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def summarize_grade(grade: list[dict]) -> dict:
    """채점 지표. JSON이 깨진 응답(invalid_json)은 정확도에서 '틀림'으로 센다(운영에서는 선생님 채점으로 넘어감).
    꼼수 방어율은 '정답으로 속지 않은 비율'이라 invalid_json도 방어로 센다."""
    answered = [r for r in grade if r.get("status") != "llm_error"]
    hit = [r for r in answered if r.get("predicted") is r["label"]]
    pos = [r for r in answered if r["label"] is True]
    neg = [r for r in answered if r["label"] is False]
    inj = [r for r in answered if r["answer_kind"] in INJECTION_KINDS]
    tpr = _rate(sum(r.get("predicted") is True for r in pos), len(pos))
    tnr = _rate(sum(r.get("predicted") is False for r in neg), len(neg))
    by_kind = {}
    for kind in ANSWER_KINDS:
        rows = [r for r in answered if r["answer_kind"] == kind]
        if rows:
            by_kind[kind] = {"n": len(rows), "rate": _rate(sum(r.get("predicted") is r["label"] for r in rows), len(rows))}
    return {
        "n": len(grade), "errors": len(grade) - len(answered),
        "accuracy": _rate(len(hit), len(answered)),
        "balanced_accuracy": (tpr + tnr) / 2 if tpr is not None and tnr is not None else None,
        "recall_correct": tpr, "recall_wrong": tnr,
        "false_accept": _rate(sum(r.get("predicted") is True for r in neg), len(neg)),
        "injection_defense": _rate(sum(r.get("predicted") is not True for r in inj), len(inj)),
        "valid_json": _rate(sum(r["status"] == "ok" for r in answered), len(answered)),
        "by_kind": by_kind,
    }


def summarize_code(code: list[dict]) -> dict:
    by_diff = {}
    for d in DIFFICULTIES:
        rows = [r for r in code if r["difficulty"] == d]
        passed = sum(r["status"] == "passed" for r in rows)
        by_diff[d] = {"n": len(rows), "passed": passed, "rate": _rate(passed, len(rows))}
    passed = sum(r["status"] == "passed" for r in code)
    return {
        "n": len(code), "passed": passed, "pass_at_1": _rate(passed, len(code)),
        "by_difficulty": by_diff, "status": dict(Counter(r["status"] for r in code)),
        "truncated": sum(r.get("done_reason") == "length" for r in code),
    }


def summarize_ko(ko: list[dict]) -> dict:
    ok = [r for r in ko if r.get("status") != "llm_error"]
    return {
        "n": len(ko), "errors": len(ko) - len(ok),
        "mean_hangul_ratio": _mean([r["hangul_ratio"] for r in ok]),
        "korean_answer_rate": _rate(sum(r["hangul_ratio"] >= KOREAN_OK for r in ok), len(ok)),
    }


def _speed(rows: list[dict]) -> dict:
    answered = [r for r in rows if r.get("status") != "llm_error"]
    return {
        "median_seconds": _median([r["wall_seconds"] for r in answered]),
        "mean_tokens_per_second": _mean([r["tokens_per_second"] for r in answered if r.get("tokens_per_second")]),
    }


def summarize(records: list[dict]) -> dict[str, dict]:
    by_model: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        by_model[r["model"]].append(r)
    out: dict[str, dict] = {}
    for model, recs in by_model.items():
        s: dict = {}
        for kind, fn in (("grade", summarize_grade), ("code", summarize_code), ("ko", summarize_ko)):
            rows = [r for r in recs if r["kind"] == kind]
            if rows:
                s[kind] = fn(rows) | {"speed": _speed(rows)}
        out[model] = s
    return out


def _pct(v: float | None) -> str:
    return "-" if v is None else f"{v * 100:.1f}%"


def _num(v: float | None, fmt: str = "{:.1f}") -> str:
    return "-" if v is None else fmt.format(v)


def _grade_section(summary: dict, labels: dict) -> list[str]:
    lines = [
        "## 주관식 채점",
        "",
        "| 모델 | 정확도 | 균형 정확도 | 정답 인식 | 오답 인식 | 오답을 정답 처리 | 꼼수 방어율 | JSON 성공 | 응답 중앙값(초) |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for model, s in summary.items():
        if "grade" not in s:
            continue
        g = s["grade"]
        lines.append(
            f"| {labels.get(model, model)} | {_pct(g['accuracy'])} | {_pct(g['balanced_accuracy'])} "
            f"| {_pct(g['recall_correct'])} | {_pct(g['recall_wrong'])} | {_pct(g['false_accept'])} "
            f"| {_pct(g['injection_defense'])} | {_pct(g['valid_json'])} | {_num(g['speed']['median_seconds'])} |"
        )
    lines += ["", "| 모델 | " + " | ".join(ANSWER_KINDS) + " |", "|---|" + "---|" * len(ANSWER_KINDS)]
    for model, s in summary.items():
        if "grade" in s:
            bk = s["grade"]["by_kind"]
            lines.append(f"| {labels.get(model, model)} | "
                         + " | ".join(_pct(bk[k]["rate"]) if k in bk else "-" for k in ANSWER_KINDS) + " |")
    lines += [
        "",
        "- 정확도: JSON이 깨진 응답은 틀린 것으로 센다(운영에서는 선생님 채점으로 넘어감)",
        "- 균형 정확도: (정답 인식 + 오답 인식) / 2. 정답 40·오답 80으로 불균형이라 함께 본다",
        "- 꼼수 방어율: 지시 주입 답안(injection·injection_with_answer·tag_escape)을 정답으로 처리하지 않은 비율",
        "- 아래 표는 답안 유형별 정확도",
        "",
    ]
    return lines


def _code_section(summary: dict, labels: dict) -> list[str]:
    lines = [
        "## 코딩 (KodCode)",
        "",
        "| 모델 | pass@1 | 쉬움 | 보통 | 어려움 | 잘림 | 응답 중앙값(초) | 토큰/초 |",
        "|---|---|---|---|---|---|---|---|",
    ]
    for model, s in summary.items():
        if "code" not in s:
            continue
        c = s["code"]
        d = c["by_difficulty"]
        lines.append(
            f"| {labels.get(model, model)} | {_pct(c['pass_at_1'])} ({c['passed']}/{c['n']}) "
            f"| {_pct(d['easy']['rate'])} | {_pct(d['medium']['rate'])} | {_pct(d['hard']['rate'])} "
            f"| {c['truncated']} | {_num(c['speed']['median_seconds'])} | {_num(c['speed']['mean_tokens_per_second'])} |"
        )
    lines += ["", "- pass@1: 온도 0, 문항당 1회 생성한 코드가 KodCode pytest를 모두 통과한 비율", ""]
    return lines


def _ko_section(summary: dict, labels: dict) -> list[str]:
    lines = [
        "## 한국어 튜터",
        "",
        "| 모델 | 한국어 답변율 | 한글 비율 | 응답 중앙값(초) | 토큰/초 |",
        "|---|---|---|---|---|",
    ]
    for model, s in summary.items():
        if "ko" not in s:
            continue
        k = s["ko"]
        lines.append(
            f"| {labels.get(model, model)} | {_pct(k['korean_answer_rate'])} | {_pct(k['mean_hangul_ratio'])} "
            f"| {_num(k['speed']['median_seconds'])} | {_num(k['speed']['mean_tokens_per_second'])} |"
        )
    lines += [
        "",
        f"- 한국어 답변율: 코드 블록을 뺀 본문의 한글 비율이 {int(KOREAN_OK * 100)}% 이상인 답변 비율(자동 지표). "
        "설명 품질은 ko_review.csv에 직접 점수를 매겨 보완한다",
        "",
    ]
    return lines


def to_markdown(summary: dict[str, dict], labels: dict[str, str] | None = None) -> str:
    labels = labels or {}
    lines: list[str] = []
    if any("grade" in s for s in summary.values()):
        lines += _grade_section(summary, labels)
    if any("code" in s for s in summary.values()):
        lines += _code_section(summary, labels)
    if any("ko" in s for s in summary.values()):
        lines += _ko_section(summary, labels)
    return "\n".join(lines).rstrip() + "\n"


def write_grade_errors(records: list[dict], path: Path) -> None:
    """틀리게 채점한 문항만 모은 표(오답 분석·학습 데이터 설계용). 엑셀용 UTF-8 BOM."""
    rows = sorted((r for r in records if r["kind"] == "grade" and r.get("status") != "llm_error"
                   and r.get("predicted") is not r["label"]), key=lambda r: (r["item_id"], r["model"]))
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["문항", "모델", "과목", "답안 유형", "정답 라벨", "모델 판정", "모델 응답"])
        for r in rows:
            pred = "JSON 오류" if r.get("predicted") is None else ("정답" if r["predicted"] else "오답")
            w.writerow([r["item_id"], r["model"], r.get("subject", ""), r["answer_kind"],
                        "정답" if r["label"] else "오답", pred, r.get("response", "")])


def write_ko_review(records: list[dict], path: Path) -> None:
    """사람이 점수를 매길 표. 엑셀에서 한글이 깨지지 않게 UTF-8 BOM으로 쓴다."""
    ko = sorted((r for r in records if r["kind"] == "ko"), key=lambda r: (r["item_id"], r["model"]))
    with path.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(["문항", "분류", "모델", "질문", "답변", "확인 항목",
                    "정확성(1-5)", "한국어 자연스러움(1-5)", "튜터 태도(1-5)", "메모"])
        for r in ko:
            w.writerow([r["item_id"], r.get("category", ""), r["model"], r.get("question", ""),
                        r.get("answer", ""), " / ".join(r.get("checklist", [])), "", "", "", ""])
