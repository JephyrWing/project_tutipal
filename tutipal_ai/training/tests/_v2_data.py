"""채점 학습 데이터 v2 테스트용 합성 데이터(가짜 parquet 표, 가짜 LLM, 캐시 파일).

실제 원천 데이터(저장소 밖 datasets 폴더)는 읽지 않는다. build_grading_train.rows 를 가짜로 바꿔
이 모듈이 만든 표를 돌려준다. 표에 없는 파일을 읽으려 하면 AssertionError 로 실패한다
(SAF test_unseen_* 같은 쓰지 않을 split 을 읽으면 바로 드러난다).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools.common import DEFAULT_SEED

SCI = "SciEntsBank"
MOH = "MohlerASAG"
SAF = "SAF-communication-networks"
TRANSLATOR = "translategemma:4b"
WRITER = "gemma3:4b"

TRUE_REASON = "모범 답안과 같은 뜻을 담고 있다."
TRUE_FEEDBACK = "핵심을 정확히 담았어요. 계속 이렇게 해 보세요."
FALSE_REASON = "핵심 개념이 누락되어 있다."
FALSE_FEEDBACK = "핵심 개념을 다시 확인해 보세요."

SCI_NAMES = ["correct", "contradictory", "partially_correct_incomplete", "irrelevant", "non_domain"]


def sha_num(text: str, mod: int = 10**8) -> int:
    return int(hashlib.sha256(text.encode("utf-8")).hexdigest(), 16) % mod


def qid_of(source: str, q_en: str) -> str:
    """설계 결정: qid = <source>|sha256(영어 문제)[:16]."""
    return f"{source}|{hashlib.sha256(q_en.encode('utf-8')).hexdigest()[:16]}"


def file_sha(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def folder_hashes(folder: Path) -> dict[str, str]:
    return {p.name: file_sha(p) for p in sorted(Path(folder).iterdir()) if p.is_file()}


# ---------- 가짜 parquet 표 ----------

def mohler_row(rid, q, ref, ans, g1, g2):
    return {"id": rid, "question": q, "instructor_answer": ref, "student_answer": ans,
            "score_avg": (g1 + g2) / 2, "score_grader_1": g1, "score_grader_2": g2}


def mohler_q(qi):
    return f"Explain how the imaginary zeta structure number {qi} inserts a new element."


def mohler_ref(qi):
    return (f"Zeta structure {qi} places each new element at the end and keeps "
            "the order of all earlier elements intact.")


def make_tables(*, n_mohler_q=6, per_mohler=14, n_saf_q=4, per_saf_train=8, per_saf_val=4,
                n_sci_q=3, per_sci=10, include_validation=True) -> dict[str, list[dict]]:
    """B.SOURCE_FILES 상대 경로 → 행 목록.

    Mohler: 행 j 의 점수 패턴 j%4 = 0 (5,5) 정답, 1 (3,3) 부분, 2 (1,1) 오답, 3 (2,1.5) 오답.
            각 문제의 마지막 행은 (4,4) 평균 4.0 — 애매한 점수라 버려야 한다.
    close_ended 표에는 문제 1개 4행.
    SAF: Correct / Partially correct / Incorrect 순환. train 과 validation 은 같은 문제, 다른 id.
    Sci: label 0(correct) 1(contradictory) 2(partial) 3(irrelevant) 순환.
    """
    open_rows = []
    for qi in range(n_mohler_q):
        for j in range(per_mohler):
            rid = f"M{qi:02d}.A{j:02d}"
            pattern = [(5.0, 5.0), (3.0, 3.0), (1.0, 1.0), (2.0, 1.5)][j % 4]
            if j == per_mohler - 1:
                pattern = (4.0, 4.0)
            good = pattern[0] + pattern[1] >= 9.0
            ans = (f"student {rid} says zeta {qi} puts every new element at the end and keeps the order"
                   if good else f"student {rid} thinks zeta {qi} shuffles all of the elements randomly")
            open_rows.append(mohler_row(rid, mohler_q(qi), mohler_ref(qi), ans, *pattern))
    close_rows = []
    for j in range(4):
        rid = f"C00.A{j:02d}"
        pattern = [(5.0, 5.0), (3.0, 3.0), (1.0, 1.0), (5.0, 4.0)][j]
        good = pattern[0] + pattern[1] >= 9.0
        ans = (f"student {rid} says the closed structure keeps elements in a fixed sorted order"
               if good else f"student {rid} thinks the closed structure deletes elements")
        close_rows.append(mohler_row(rid, "Is the closed zeta structure sorted after insertion?",
                                     "Yes, the closed zeta structure always stays sorted after every insertion.",
                                     ans, *pattern))
    verdicts = ["Correct", "Partially correct", "Incorrect"]

    def saf_rows(split, per):
        out = []
        for qi in range(n_saf_q):
            for j in range(per):
                rid = f"{split[:2]}{qi:02d}{j:02d}" + "0" * 26
                v = verdicts[j % 3]
                ans = (f"answer {rid[:6]} says the omega protocol {qi} resends lost frames after a timeout"
                       if v == "Correct" else f"answer {rid[:6]} says the omega protocol {qi} never resends frames")
                out.append({"id": rid, "question": f"How does the imaginary omega protocol {qi} handle lost frames?",
                            "reference_answer": f"Omega protocol {qi} waits for a timeout and then resends "
                                                "every lost frame to the receiver.",
                            "provided_answer": ans, "verification_feedback": v,
                            "answer_feedback": "Synthetic grader feedback."})
        return out

    sci = []
    for qi in range(n_sci_q):
        for j in range(per_sci):
            lab = j % 4
            rid = f"SC.{qi}.{j:02d}.1"
            ans = (f"pupil {rid} says the sigma liquid {qi} warms up because heat flows into it"
                   if lab == 0 else f"pupil {rid} says the sigma liquid {qi} gets colder by magic")
            sci.append({"id": rid, "question": f"Why does the imaginary sigma liquid {qi} warm up in the sun?",
                        "reference_answer": f"Sigma liquid {qi} absorbs heat energy from sunlight and warms up.",
                        "student_answer": ans, "label": lab})
    tables = {
        B.SOURCE_FILES["scientsbank"]: sci,
        B.SOURCE_FILES["mohler_open"]: open_rows,
        B.SOURCE_FILES["mohler_close"]: close_rows,
        B.SOURCE_FILES["saf"]: saf_rows("train", per_saf_train),
    }
    if include_validation:
        tables[B.SOURCE_FILES["saf_validation"]] = saf_rows("validation", per_saf_val)
    return tables


class FakeRows:
    """build_grading_train.rows 대신 쓴다. 읽은 상대 경로를 reads 에 남긴다."""

    def __init__(self, tables: dict[str, list[dict]]):
        self.tables = tables
        self.reads: list[str] = []

    def __call__(self, datasets_dir, rel):
        self.reads.append(rel)
        if rel not in self.tables:
            raise AssertionError(f"읽으면 안 되는(또는 준비하지 않은) 원천 파일: {rel}")
        return [dict(r) for r in self.tables[rel]]


# ---------- 영어 원문 항목(테스트 쪽 기대값) ----------

def english_items(tables: dict[str, list[dict]]) -> list[dict]:
    """v2 라벨 규칙을 테스트 쪽에서 따로 계산한 영어 항목. 애매한 Mohler 행은 뺀다."""
    items = []
    kmap = {0: (True, "paraphrase"), 1: (False, "wrong"), 2: (False, "partial"), 3: (False, "wrong"),
            4: (False, "unknown")}
    for r in tables[B.SOURCE_FILES["scientsbank"]]:
        label, kind = kmap[r["label"]]
        items.append({"source": SCI, "source_id": r["id"], "subject": "과학", "q": r["question"],
                      "ref": r["reference_answer"], "ans": r["student_answer"], "label": label, "kind": kind})
    for rel in (B.SOURCE_FILES["mohler_open"], B.SOURCE_FILES["mohler_close"]):
        for r in tables[rel]:
            s = r["score_avg"]
            if 3.5 < s < 4.5:
                continue
            label = s >= 4.5 and r["score_grader_1"] >= 4 and r["score_grader_2"] >= 4
            if s >= 4.5 and not label:
                continue
            kind = "paraphrase" if label else ("partial" if s >= 2.0 else "wrong")
            items.append({"source": MOH, "source_id": r["id"], "subject": "코딩", "q": r["question"],
                          "ref": r["instructor_answer"], "ans": r["student_answer"], "label": label, "kind": kind})
    vmap = {"Correct": (True, "paraphrase"), "Partially correct": (False, "partial"), "Incorrect": (False, "wrong")}
    saf_rels = [B.SOURCE_FILES["saf"]]
    if B.SOURCE_FILES.get("saf_validation") in tables:
        saf_rels.append(B.SOURCE_FILES["saf_validation"])
    for rel in saf_rels:
        for r in tables[rel]:
            label, kind = vmap[r["verification_feedback"]]
            items.append({"source": SAF, "source_id": r["id"], "subject": "코딩", "q": r["question"],
                          "ref": r["reference_answer"], "ans": r["provided_answer"], "label": label, "kind": kind})
    for x in items:
        x["qid"] = qid_of(x["source"], x["q"])
    return items


# ---------- 한국어 번역·작성 결과(캐시 내용) ----------

# PT-88 번역 검사(trans_check)를 통과하도록 영어 원문의 숫자·약어 토큰을 그대로 옮기고, 구분용 번호는
# 숫자 대신 한글 글자로 쓴다(번역에 원문에 없는 숫자가 있으면 'number' 규칙에 걸린다).
def _tag(n: int) -> str:
    return "".join("가까나다따라마바빠사"[int(d)] for d in str(n))


def _carry(en: str) -> str:
    keep = " ".join(w for w in en.split() if re.search(r"\d|[A-Z]{2}", w))
    return f" ({keep})" if keep else ""


def ko_question(q_en: str) -> str:
    return f"합성 문항 {_tag(sha_num(q_en, 10**6))}: 가상의 구조가 새 항목을 다루는 규칙을 우리말로 정리하세요.{_carry(q_en)}"


def ko_ref(ref_en: str) -> str:
    return f"합성 모범 답안 {_tag(sha_num(ref_en, 10**6))}: 새 항목을 끝에 두고 앞선 항목의 순서를 유지한다.{_carry(ref_en)}"


def ko_answer(ans_en: str) -> str:
    n = _tag(sha_num(ans_en))
    if " says " in ans_en and ("keeps" in ans_en or "resends lost" in ans_en or "heat flows" in ans_en):
        return f"학생 답안 {n}입니다 새 항목을 끝에 붙이고 기존 순서를 그대로 지킵니다{_carry(ans_en)}"
    return f"학생 답안 {n}입니다 항목을 매번 무작위로 섞어 버리고 순서를 지키지 않습니다{_carry(ans_en)}"


def translate_text(text: str, kind_hint: str) -> str:
    return {"q": ko_question, "ref": ko_ref, "ans": ko_answer}[kind_hint](text)


def written(item: dict, reason: str | None = None) -> dict:
    tr = {"question": ko_question(item["q"]), "model_answer": ko_ref(item["ref"]),
          "student_answer": ko_answer(item["ans"])}
    if item["label"]:
        return {**tr, "reason": reason or TRUE_REASON, "feedback": TRUE_FEEDBACK}
    return {**tr, "reason": reason or FALSE_REASON, "feedback": FALSE_FEEDBACK}


def tkey(item, translator=TRANSLATOR):
    return f"{item['source']}:{item['source_id']}:{translator}"


def wkey(item, translator=TRANSLATOR, writer=WRITER):
    return f"{item['source']}:{item['source_id']}:{translator}>{writer}"


def write_caches(folder: Path, items: list[dict], *, trans_only=(), reasons=None, new_keys=False) -> None:
    """items 전부의 번역 캐시와(trans_only 에 든 id 는 빼고) 작성 캐시를 folder 에 쓴다.

    기본은 예전(2026-11-25 v2) 키만 쓴다. new_keys=True 면 PT-88 키(#p=, #w=) 줄도 함께 쓴다
    (과학 행 선별은 예전 키, 번역·작성 재사용은 새 키로 하므로 둘 다 있어야 LLM 을 부르지 않는다).
    """
    from data_tools import (
        build_grading_c as BC,  # 순환 import 방지(테스트 도우미에서만 필요)
    )

    ns = argparse.Namespace(translator=TRANSLATOR, writer=WRITER, seed=DEFAULT_SEED, v2=True)
    reasons = reasons or {}
    folder.mkdir(parents=True, exist_ok=True)
    t_lines, w_lines = [], []
    for x in items:
        out = written(x, reasons.get(x["source_id"]))
        tr = {k: out[k] for k in ("question", "model_answer", "student_answer")}
        tkeys = [tkey(x)] + ([BC.tkey(x, ns)] if new_keys else [])
        wkeys = [wkey(x)] + ([BC.wkey(x, ns)] if new_keys else [])
        t_lines += [json.dumps({"key": k, "out": tr, "error": None}, ensure_ascii=False) for k in tkeys]
        if x["source_id"] not in trans_only:
            w_lines += [json.dumps({"key": k, "out": out, "error": None}, ensure_ascii=False) for k in wkeys]
    (folder / "trans_cache.jsonl").write_text("".join(s + "\n" for s in t_lines), encoding="utf-8")
    (folder / "write_cache.jsonl").write_text("".join(s + "\n" for s in w_lines), encoding="utf-8")


def write_caches_v3(folder: Path, items: list[dict], **kw) -> None:
    """PT-88 이전 테스트용: 예전 키와 새 키 캐시를 모두 쓴다(캐시 재사용 동작을 그대로 검사하려고)."""
    write_caches(folder, items, new_keys=True, **kw)


class FakeLLM:
    """compare_hybrid.translate / generate 대신 쓴다. 호출을 모두 기록한다."""

    def __init__(self, items: list[dict], forbid: bool = False):
        self.forbid = forbid
        self.translated: list[str] = []
        self.generated: list[str] = []
        self.by_text: dict[str, str] = {}
        for x in items:
            self.by_text[x["q"]] = ko_question(x["q"])
            self.by_text[x["ref"]] = ko_ref(x["ref"])
            self.by_text[x["ans"]] = ko_answer(x["ans"])

    def translate(self, model, text, seed=None, **kw):
        self.translated.append(text)
        if self.forbid:
            raise RuntimeError("translate must not be called")
        return self.by_text.get(text, f"한국어 번역 문장 {_tag(sha_num(text))}{_carry(text)}")

    def generate(self, model, prompt, system=None, fmt=None, seed=None):
        self.generated.append(prompt)
        if self.forbid:
            raise RuntimeError("generate must not be called")
        if prompt.startswith("정답 여부: 정답"):
            return json.dumps({"reason": TRUE_REASON, "feedback": TRUE_FEEDBACK}, ensure_ascii=False)
        return json.dumps({"reason": FALSE_REASON, "feedback": FALSE_FEEDBACK}, ensure_ascii=False)


def quizai_file(path: Path, system: str) -> Path:
    body = "\n".join("            " + line for line in system.split("\n"))
    path.write_text("class QuizAi {\n    static final String GRADE_SYSTEM = \"\"\"\n" + body
                    + "\n            \"\"\";\n}\n", encoding="utf-8")
    return path


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
