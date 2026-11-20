"""평가 기록(results.jsonl) → 모델별 요약표."""

from __future__ import annotations

import csv
import statistics
from collections import Counter, defaultdict
from pathlib import Path

DIFFICULTIES = ("easy", "medium", "hard")
KOREAN_OK = 0.5  # 본문 글자의 절반 이상이 한글이면 한국어 답변으로 본다


def _rate(passed: int, n: int) -> float | None:
    return passed / n if n else None


def _median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def summarize(records: list[dict]) -> dict[str, dict]:
    by_model: dict[str, list[dict]] = defaultdict(list)
    for r in records:
        by_model[r["model"]].append(r)

    out: dict[str, dict] = {}
    for model, recs in by_model.items():
        code = [r for r in recs if r["kind"] == "code"]
        ko = [r for r in recs if r["kind"] == "ko"]
        by_diff = {}
        for d in DIFFICULTIES:
            rows = [r for r in code if r["difficulty"] == d]
            passed = sum(r["status"] == "passed" for r in rows)
            by_diff[d] = {"n": len(rows), "passed": passed, "rate": _rate(passed, len(rows))}
        passed = sum(r["status"] == "passed" for r in code)
        answered = [r for r in recs if r.get("status") != "llm_error"]
        ko_ok = [r for r in ko if r.get("status") != "llm_error"]
        out[model] = {
            "code": {
                "n": len(code), "passed": passed, "pass_at_1": _rate(passed, len(code)),
                "by_difficulty": by_diff,
                "status": dict(Counter(r["status"] for r in code)),
                "truncated": sum(r.get("done_reason") == "length" for r in code),
            },
            "ko": {
                "n": len(ko),
                "mean_hangul_ratio": _mean([r["hangul_ratio"] for r in ko_ok]),
                "korean_answer_rate": _rate(sum(r["hangul_ratio"] >= KOREAN_OK for r in ko_ok), len(ko_ok)),
                "errors": len(ko) - len(ko_ok),
            },
            "speed": {
                "median_seconds": _median([r["wall_seconds"] for r in answered]),
                "mean_tokens_per_second": _mean([r["tokens_per_second"] for r in answered
                                                 if r.get("tokens_per_second")]),
            },
        }
    return out


def _pct(v: float | None) -> str:
    return "-" if v is None else f"{v * 100:.1f}%"


def _num(v: float | None, fmt: str = "{:.1f}") -> str:
    return "-" if v is None else fmt.format(v)


def to_markdown(summary: dict[str, dict], labels: dict[str, str] | None = None) -> str:
    labels = labels or {}
    lines = [
        "| 모델 | 코딩 pass@1 | 쉬움 | 보통 | 어려움 | 한국어 답변율 | 한글 비율 | 응답 중앙값(초) | 토큰/초 |",
        "|---|---|---|---|---|---|---|---|---|",
    ]
    for model, s in summary.items():
        c, k, sp = s["code"], s["ko"], s["speed"]
        d = c["by_difficulty"]
        lines.append(
            f"| {labels.get(model, model)} | {_pct(c['pass_at_1'])} ({c['passed']}/{c['n']}) "
            f"| {_pct(d['easy']['rate'])} | {_pct(d['medium']['rate'])} | {_pct(d['hard']['rate'])} "
            f"| {_pct(k['korean_answer_rate'])} | {_pct(k['mean_hangul_ratio'])} "
            f"| {_num(sp['median_seconds'])} | {_num(sp['mean_tokens_per_second'])} |"
        )
    lines += [
        "",
        "- pass@1: 온도 0, 문항당 1회 생성한 코드가 KodCode pytest를 모두 통과한 비율",
        f"- 한국어 답변율: 코드 블록을 뺀 본문의 한글 비율이 {int(KOREAN_OK * 100)}% 이상인 답변 비율(자동 지표). "
        "설명 품질은 ko_review.csv에 직접 점수를 매겨 보완한다",
    ]
    trunc = {m: s["code"]["truncated"] for m, s in summary.items() if s["code"]["truncated"]}
    if trunc:
        lines.append(f"- 길이 제한으로 잘린 코딩 응답: {trunc}")
    return "\n".join(lines) + "\n"


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
