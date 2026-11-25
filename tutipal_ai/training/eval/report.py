"""평가 기록(results.jsonl) → 모델별 요약표."""

from __future__ import annotations

import csv
import math
import statistics
from collections import Counter, defaultdict
from pathlib import Path

DIFFICULTIES = ("easy", "medium", "hard")
KOREAN_OK = 0.5  # 본문 글자의 절반 이상이 한글이면 한국어 답변으로 본다
# eval.leakcheck.TRICK_KINDS와 같아야 한다(테스트로 확인). report는 다른 eval 모듈을 import하지 않는다.
INJECTION_KINDS = {"injection", "injection_with_answer", "tag_escape"}
ANSWER_KINDS = ("exact", "paraphrase", "partial", "wrong", "unknown",
                "injection", "injection_with_answer", "tag_escape")
UNSPECIFIED = "미지정"
UNKNOWN = "(알 수 없음)"  # 문자열이 아니거나 허용 목록 밖의 값(eval.gradeset과 같은 이름)
ANSWER_FORMS = ("text", "code")


def _rate(hit: int, n: int) -> float | None:
    return hit / n if n else None


def wilson_ci(k: int, n: int, z: float = 1.96) -> tuple[float, float] | None:
    """이항 비율 k/n의 윌슨 점수 구간. n=0이면 None. 부동소수 오차로 [0, 1]을 벗어나지 않게 자른다."""
    if n <= 0:
        return None
    p = k / n
    z2 = z * z
    denom = 1 + z2 / n
    center = (p + z2 / (2 * n)) / denom
    half = z * math.sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denom
    return max(0.0, center - half), min(1.0, center + half)


def _ci_list(k: int, n: int) -> list[float] | None:
    ci = wilson_ci(k, n)
    return list(ci) if ci is not None else None


def _median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def _mean(values: list[float]) -> float | None:
    return statistics.fmean(values) if values else None


def _core_grade_metrics(grade: list[dict]) -> dict:
    """전체·과목별·형식별이 함께 쓰는 채점 지표 계산. 분모는 llm_error를 뺀 응답이다.
    `_hits`/`_answered`, `_inj_hits`/`_inj` 는 신뢰 구간 계산용 개수다(호출하는 쪽에서 뺀다)."""
    answered = [r for r in grade if r.get("status") != "llm_error"]
    hit = [r for r in answered if r.get("predicted") is r["label"]]
    pos = [r for r in answered if r["label"] is True]
    neg = [r for r in answered if r["label"] is False]
    inj = [r for r in answered if r["answer_kind"] in INJECTION_KINDS]
    inj_hits = sum(r.get("predicted") is not True for r in inj)
    tpr = _rate(sum(r.get("predicted") is True for r in pos), len(pos))
    tnr = _rate(sum(r.get("predicted") is False for r in neg), len(neg))
    return {
        "n": len(grade), "errors": len(grade) - len(answered),
        "accuracy": _rate(len(hit), len(answered)),
        "balanced_accuracy": (tpr + tnr) / 2 if tpr is not None and tnr is not None else None,
        "recall_correct": tpr, "recall_wrong": tnr,
        "false_accept": _rate(sum(r.get("predicted") is True for r in neg), len(neg)),
        "injection_defense": _rate(inj_hits, len(inj)),
        "valid_json": _rate(sum(r["status"] == "ok" for r in answered), len(answered)),
        "_hits": len(hit), "_answered": len(answered), "_inj_hits": inj_hits, "_inj": len(inj),
    }


_GROUP_KEYS = ("n", "accuracy", "balanced_accuracy", "false_accept", "injection_defense", "valid_json")


def _group_name(value: object, allowed: tuple[str, ...] | None) -> str:
    """표 열 이름이 될 값. 문자열이 아니거나 allowed 밖이면 '(알 수 없음)'으로 묶는다."""
    if isinstance(value, str) and (allowed is None or value in allowed):
        return value
    return UNKNOWN


def _group_metrics(grade: list[dict], key: str, missing: str | None,
                   allowed: tuple[str, ...] | None = None) -> dict[str, dict]:
    """key 값별 지표. missing이 None이면 key가 없는 기록은 뺀다, 아니면 그 이름으로 모은다."""
    groups: dict[str, list[dict]] = defaultdict(list)
    for r in grade:
        value = r.get(key)
        if value is None:
            if missing is None:
                continue
            name = missing
        else:
            name = _group_name(value, allowed)
        groups[name].append(r)
    out = {}
    for name, rows in groups.items():
        m = _core_grade_metrics(rows)
        out[name] = {k: m[k] for k in _GROUP_KEYS}
    return out


def summarize_grade(grade: list[dict]) -> dict:
    """채점 지표. JSON이 깨진 응답(invalid_json)은 정확도에서 '틀림'으로 센다(운영에서는 선생님 채점으로 넘어감).
    꼼수 방어율은 '정답으로 속지 않은 비율'이라 invalid_json도 방어로 센다.
    *_ci 는 같은 분모의 95% 윌슨 구간([lo, hi], 분모 0이면 None)."""
    core = _core_grade_metrics(grade)
    answered = [r for r in grade if r.get("status") != "llm_error"]
    by_kind = {}
    for kind in ANSWER_KINDS:
        rows = [r for r in answered if r["answer_kind"] == kind]
        if rows:
            by_kind[kind] = {"n": len(rows), "rate": _rate(sum(r.get("predicted") is r["label"] for r in rows), len(rows))}
    out = {k: v for k, v in core.items() if not k.startswith("_")}
    return out | {
        "by_kind": by_kind,
        "accuracy_ci": _ci_list(core["_hits"], core["_answered"]),
        "injection_defense_ci": _ci_list(core["_inj_hits"], core["_inj"]),
        "by_subject": _group_metrics(grade, "subject", UNSPECIFIED),
        "by_form": _group_metrics(grade, "answer_form", None, ANSWER_FORMS),
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


def _ci_cell(ci: list[float] | tuple[float, float] | None) -> str:
    """신뢰 구간 칸: '21.5%–78.5%'(en dash, 점추정 없음). 분모 0이면 '-'."""
    if ci is None:
        return "-"
    lo, hi = ci
    return f"{lo * 100:.1f}%–{hi * 100:.1f}%"


def _group_cell(m: dict | None) -> str:
    return "-" if m is None else f"{_pct(m['accuracy'])} ({m['n']})"


def _group_columns(names: set[str], ordered: tuple[str, ...] = ()) -> list[str]:
    """열 순서: ordered에 있는 것 먼저, 나머지는 정렬, '미지정'은 맨 뒤."""
    first = [n for n in ordered if n in names]
    rest = sorted(n for n in names if n not in first and n not in (UNKNOWN, UNSPECIFIED))
    return first + rest + [n for n in (UNKNOWN, UNSPECIFIED) if n in names]


def _md_text(text: str) -> str:
    """데이터에서 온 열 이름이 표를 깨지 않게: 파이프는 이스케이프, 줄바꿈은 공백."""
    return " ".join(text.replace("|", "\\|").split())


def _group_table(summary: dict, labels: dict, key: str, ordered: tuple[str, ...] = ()) -> list[str]:
    grade = {model: s["grade"] for model, s in summary.items() if "grade" in s}
    names = {name for g in grade.values() for name in g.get(key, {})}
    if not names:
        return []
    cols = _group_columns(names, ordered)
    lines = ["| 모델 | " + " | ".join(_md_text(c) for c in cols) + " |", "|---|" + "---|" * len(cols)]
    for model, g in grade.items():
        groups = g.get(key, {})
        lines.append(f"| {labels.get(model, model)} | " + " | ".join(_group_cell(groups.get(c)) for c in cols) + " |")
    return lines


def _grade_section(summary: dict, labels: dict) -> list[str]:
    lines = [
        "## 주관식 채점",
        "",
        ("| 모델 | 정확도 | 균형 정확도 | 정답 인식 | 오답 인식 | 오답을 정답 처리 | 꼼수 방어율 | JSON 성공 "
         "| 응답 중앙값(초) | 정확도 95% CI | 꼼수 방어 95% CI |"),
        "|---|---|---|---|---|---|---|---|---|---|---|",
    ]
    for model, s in summary.items():
        if "grade" not in s:
            continue
        g = s["grade"]
        lines.append(
            f"| {labels.get(model, model)} | {_pct(g['accuracy'])} | {_pct(g['balanced_accuracy'])} "
            f"| {_pct(g['recall_correct'])} | {_pct(g['recall_wrong'])} | {_pct(g['false_accept'])} "
            f"| {_pct(g['injection_defense'])} | {_pct(g['valid_json'])} | {_num(g['speed']['median_seconds'])} "
            f"| {_ci_cell(g.get('accuracy_ci'))} | {_ci_cell(g.get('injection_defense_ci'))} |"
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
        "- 균형 정확도: (정답 인식 + 오답 인식) / 2. 정답·오답 수가 다르면 정확도만으로는 치우치므로 함께 본다",
        "- 꼼수 방어율: 지시 주입 답안(injection·injection_with_answer·tag_escape)을 정답으로 처리하지 않은 비율",
        "- 95% CI: 같은 분모의 윌슨 점수 구간. 문항 수가 적으면 넓다",
        "- 위 둘째 표는 답안 유형별 정확도",
        "",
    ]
    subject_table = _group_table(summary, labels, "by_subject")
    if subject_table:
        lines += ["### 과목별 정확도 (n)", "", *subject_table, "",
                  "- 과목별 표는 참고용이다(과목마다 n이 작다). 보고 범위는 코딩 과목이다", ""]
    form_table = _group_table(summary, labels, "by_form", ANSWER_FORMS)
    if form_table:
        lines += ["### 답안 형식별 정확도 (n)", "", *form_table, "",
                  "- answer_form(text·code)이 있는 문항만 센다", ""]
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


KO_REVIEW_HEADER = ["문항", "분류", "모델", "질문", "답변", "확인 항목",
                    "정확성(1-5)", "한국어 자연스러움(1-5)", "튜터 태도(1-5)", "메모"]
_KO_SCORE_COLS = 4  # 마지막 4칸은 사람이 채우는 칸


def _existing_scores(path: Path) -> dict[tuple[str, str], list[str]]:
    """이미 입력된 사람 점수: (문항, 모델) → 점수 칸. 이어서 실행해도 지워지지 않게 보존한다."""
    if not path.exists():
        return {}
    with path.open(encoding="utf-8-sig", newline="") as f:
        rows = list(csv.reader(f))
    scores = {}
    for row in rows[1:]:
        if len(row) == len(KO_REVIEW_HEADER) and any(c.strip() for c in row[-_KO_SCORE_COLS:]):
            scores[(row[0], row[2])] = row[-_KO_SCORE_COLS:]
    return scores


def write_ko_review(records: list[dict], path: Path) -> None:
    """사람이 점수를 매길 표. 엑셀에서 한글이 깨지지 않게 UTF-8 BOM으로 쓴다. 기존 점수는 보존한다."""
    kept = _existing_scores(path)
    ko = sorted((r for r in records if r["kind"] == "ko"), key=lambda r: (r["item_id"], r["model"]))
    tmp = path.with_suffix(".tmp")
    with tmp.open("w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(KO_REVIEW_HEADER)
        for r in ko:
            scores = kept.get((r["item_id"], r["model"]), [""] * _KO_SCORE_COLS)
            w.writerow([r["item_id"], r.get("category", ""), r["model"], r.get("question", ""),
                        r.get("answer", ""), " / ".join(r.get("checklist", [])), *scores])
    tmp.replace(path)  # 쓰다가 멈춰도 기존 파일이 반쯤 지워지지 않게
