"""채점 학습 데이터 v2 사람 확인 시트 (PT-88).

학습 데이터(train_aug.jsonl)에서 꼼수가 아닌 코딩 행을 출처 비례로 뽑아(SAF 최소 보장) 영어 원문과 한국어
번역·라벨·이유·피드백을 나란히 적은 CSV 를 만든다. 사람이 verdict 열을 채운 뒤 check 로 통과 여부를 본다.

  make : <dir>/train_aug.jsonl → <dir>/review_sheet.csv (엑셀용 UTF-8 BOM, verdict·note 는 빈칸)
         + review_sheet.meta.json (만든 train_aug.jsonl 의 sha256 과 시트 행 id).
         verdict 가 하나라도 채워진 기존 시트는 덮지 않는다(종료 코드 2). --force 면 백업을 남기고 덮는다.
         =, +, -, @, 탭, CR 로 시작하는 칸은 엑셀 수식으로 실행되지 않게 앞에 ' 를 붙인다.
         후보가 모자라 --n 행이나 SAF --min-saf 행을 못 채우면 만들지 않는다(종료 코드 2).
  check: verdict 가 모두 채워졌고(OK|LABEL_ERR|TRANS_ERR|EXPL_ERR) OK 가 아닌 행이 3개 이하면 통과.
         종료 코드 0 통과, 1 OK 아닌 행 4개 이상, 2 빈칸·모르는 값·입력 오류(빈칸 판정이 우선).
         meta 는 필수다. 시트 옆 meta 로 같은 폴더의 train_aug.jsonl sha256·행 id 와 대조하고, 행 수·SAF 행 수가
         meta 의 n·min_saf 이상인지 본다(어기면 2).
         시트 옆에 meta 가 없으면(손으로 만든 시트, meta 삭제 등) 출처를 확인할 수 없으므로 check 는 2 로 끝난다.

시트에는 학습 원문이 들어가므로 저장소 밖 데이터 폴더(--dir)에만 쓴다.

사용 (tutipal_ai/training 에서):
  python -m data_tools.review_sheet make --dir <derived/grading_v2> --datasets-dir <datasets 폴더> [--n 30] [--min-saf 8]
  python -m data_tools.review_sheet check --sheet <derived/grading_v2/review_sheet.csv>
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import shutil
import sys
import time
from pathlib import Path

from data_tools import build_grading_train as B
from data_tools.augment_tricks import largest_remainder
from data_tools.clean_grading_c import CHEAT
from data_tools.common import load_jsonl, require_outside_repo, utf8_stdout, write_files_atomic

SHEET_COLUMNS = ["id", "source", "q_en", "ref_en", "ans_en", "q_ko", "ref_ko", "ans_ko", "label", "kind",
                 "reason", "feedback", "verdict", "note"]
VERDICTS = ("OK", "LABEL_ERR", "TRANS_ERR", "EXPL_ERR")
MAX_NOT_OK = 3
SAF_SOURCE = "SAF-communication-networks"
CODING_SUBJECT = "코딩"
DEFAULT_SEED = 20261125
SHEET_NAME = "review_sheet.csv"
DEFAULT_N = 30
DEFAULT_MIN_SAF = 8
TRAIN_NAME = "train_aug.jsonl"
FORMULA_START = ("=", "+", "-", "@", "\t", "\r")
ENCODING_HINT = "엑셀에서 다시 저장했다면 'CSV UTF-8(쉼표로 분리)' 형식으로 저장한다"


def _key(seed: int, rid: str) -> str:
    return hashlib.sha256(f"{seed}\x1fsheet\x1f{rid}".encode()).hexdigest()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def meta_path(sheet: Path) -> Path:
    return sheet.with_name(sheet.stem + ".meta.json")


def escape_cell(value: object) -> str:
    """엑셀 수식 주입 방지: 수식으로 해석될 수 있는 첫 글자 앞에 ' 를 붙인다."""
    text = "" if value is None else str(value)
    return "'" + text if text.startswith(FORMULA_START) else text


def unescape_cell(text: str) -> str:
    return text[1:] if text.startswith("'") and text[1:].startswith(FORMULA_START) else text


def candidates(rows: list[dict]) -> list[dict]:
    """시트 후보: 코딩 과목, 꼼수 아님, 보강 행(source=augment) 아님."""
    return [r for r in rows if r.get("subject") == CODING_SUBJECT and r.get("kind") not in CHEAT
            and r.get("source") != "augment"]


def allocate(counts: dict[str, int], n: int, min_saf: int) -> dict[str, int]:
    """출처 비례 배분. SAF 는 min_saf 개(있는 만큼) 이상을 보장하고 나머지를 다른 출처에 비례 배분한다."""
    total = min(n, sum(counts.values()))
    alloc = largest_remainder(counts, total)
    saf_floor = min(min_saf, counts.get(SAF_SOURCE, 0), total)
    if alloc.get(SAF_SOURCE, 0) >= saf_floor:
        return alloc
    others = {k: c for k, c in counts.items() if k != SAF_SOURCE}
    rest = largest_remainder(others, total - saf_floor)
    return {**rest, SAF_SOURCE: saf_floor}


def pick_rows(rows: list[dict], n: int = 30, min_saf: int = 8, seed: int = DEFAULT_SEED) -> list[dict]:
    """확인할 행을 고른다. 출처 안에서는 sha256(seed, id) 순이라 입력 순서와 무관하다. 결과는 id 순."""
    by_source: dict[str, list[dict]] = {}
    for r in candidates(rows):
        by_source.setdefault(r["source"], []).append(r)
    alloc = allocate({k: len(v) for k, v in by_source.items()}, n, min_saf)
    picked = []
    for src, group in by_source.items():
        picked += sorted(group, key=lambda r: _key(seed, r["id"]))[: alloc.get(src, 0)]
    return sorted(picked, key=lambda r: r["id"])


def sheet_text(picked: list[dict], origin: dict[tuple[str, str], dict]) -> str:
    buf = io.StringIO()
    w = csv.writer(buf)
    w.writerow(SHEET_COLUMNS)
    for r in picked:
        x = origin[(r["source"], r["source_id"])]
        w.writerow([escape_cell(v) for v in (
            r["id"], r["source"], x["q"], x["ref"], x["ans"], r["question"], r["model_answer"],
            r["student_answer"], r["label"], r["kind"], r["reason"], r["feedback"], "", "")])
    return buf.getvalue()


def sample_shortfall(n_rows: int, n_saf: int, n: int, min_saf: int) -> str | None:
    """표본 크기·SAF 행 수가 요구치에 못 미치면 사유, 아니면 None."""
    if n_rows < n:
        return f"시트 행 {n_rows}개 < 요구 {n}개"
    if n_saf < min_saf:
        return f"SAF 행 {n_saf}개 < 요구 {min_saf}개"
    return None


def check_verdicts(verdicts: list[str]) -> int:
    """0 통과, 1 OK 아닌 행이 MAX_NOT_OK 초과, 2 빈칸이나 모르는 값(우선)."""
    vs = [unescape_cell(v.strip()) for v in verdicts]
    if not vs or any(v not in VERDICTS for v in vs):
        return 2
    return 1 if sum(v != "OK" for v in vs) > MAX_NOT_OK else 0


def read_sheet(path: Path) -> tuple[list[str], list[dict]]:
    """(열 이름, 행). 인코딩·CSV 형식 오류는 ValueError(안내 문구 포함)."""
    try:
        with open(path, encoding="utf-8-sig", newline="") as f:
            reader = csv.DictReader(f)
            return list(reader.fieldnames or []), list(reader)
    except UnicodeDecodeError:
        raise ValueError(f"{Path(path).name}: UTF-8 로 읽을 수 없음 — {ENCODING_HINT}") from None
    except csv.Error as e:
        raise ValueError(f"{Path(path).name}: CSV 형식 오류 ({e}) — {ENCODING_HINT}") from None


def filled_verdicts(path: Path) -> int:
    """기존 시트에서 verdict 가 채워진 행 수. 읽을 수 없는 시트는 사람이 손댄 것으로 보고 1 이상으로 센다."""
    try:
        _, rows = read_sheet(path)
    except (OSError, ValueError):
        return 1
    return sum(bool((r.get("verdict") or "").strip()) for r in rows)


def backup_sheet(sheet: Path, stamp: str) -> Path:
    """시트를 <이름>.<시각>[-번호].bak.csv 로 복사한다. 배타 생성('xb')이라 기존 백업을 절대 덮지 않는다
    (같은 초에 두 번 실행해도 다음 번호를 쓴다)."""
    for i in range(10000):
        backup = sheet.with_name(f"{sheet.stem}.{stamp}{'' if i == 0 else f'-{i}'}.bak.csv")
        try:
            with open(sheet, "rb") as src, open(backup, "xb") as dst:
                shutil.copyfileobj(src, dst)
        except FileExistsError:
            continue
        shutil.copystat(sheet, backup)
        return backup
    raise OSError(f"{sheet.name}: 백업 이름을 만들 수 없음({stamp})")


def _err(message: str) -> int:
    print(f"오류: {message}", file=sys.stderr)
    return 2


def cmd_make(ap: argparse.ArgumentParser, a: argparse.Namespace) -> int:
    require_outside_repo(ap, a, "--dir")  # 아무것도 읽거나 쓰기 전에 확인한다
    if a.n < 1 or not 0 <= a.min_saf <= a.n:
        ap.error(f"--n 은 1 이상, --min-saf 는 0 이상 --n 이하여야 한다 (--n {a.n}, --min-saf {a.min_saf})")
    d = Path(a.dir)
    train_path, sheet = d / TRAIN_NAME, d / SHEET_NAME
    if not train_path.is_file():
        return _err(f"{train_path} 가 없음 (augment_tricks 를 먼저 실행)")
    if sheet.exists() and filled_verdicts(sheet) and not a.force:
        return _err(f"{sheet} 에 사람이 채운 verdict 가 있어 덮지 않음 (덮으려면 --force, 백업을 남김)")
    try:
        rows = load_jsonl(train_path)
    except ValueError as e:
        return _err(str(e))
    origin = {(x["source"], x["source_id"]): x for x in B.load_sources_v2(a.datasets_dir)}
    picked = pick_rows(rows, n=a.n, min_saf=a.min_saf, seed=a.seed)
    short = sample_shortfall(len(picked), sum(r["source"] == SAF_SOURCE for r in picked), a.n, a.min_saf)
    if short:
        # 표본이 작으면 "OK 아닌 행 3개 이하" 기준이 느슨해진다. 몰래 줄여 만들지 않는다.
        pool = candidates(rows)
        return _err(f"{train_path}: {short} (가능한 수: 코딩 후보 {len(pool)}행, "
                    f"SAF {sum(r['source'] == SAF_SOURCE for r in pool)}행)")
    missing = [r["id"] for r in picked if (r["source"], r["source_id"]) not in origin]
    if missing:
        return _err(f"원천에서 찾지 못한 행 {len(missing)}개: {missing[:5]}")
    if sheet.exists() and a.force:
        backup = backup_sheet(sheet, time.strftime("%Y%m%d-%H%M%S"))
        print(f"기존 시트 백업: {backup}")
    meta = {"train_file": TRAIN_NAME, "train_sha256": file_sha256(train_path), "seed": a.seed, "n": a.n,
            "min_saf": a.min_saf, "ids": [r["id"] for r in picked]}
    write_files_atomic({sheet: (sheet_text(picked, origin), "utf-8-sig"),
                        meta_path(sheet): (json.dumps(meta, ensure_ascii=False, indent=1), "utf-8")})
    saf = sum(r["source"] == SAF_SOURCE for r in picked)
    print(f"{sheet}: {len(picked)}행 (SAF {saf}행)")
    return 0


def provenance_problem(sheet: Path, ids: list[str], sources: list[str]) -> str | None:
    """meta 는 필수. 없으면 문제로 본다. 시트가 지금 train_aug.jsonl 에서 나온 것인지, 행 수·SAF 행 수가 meta 의 n·min_saf
    (없으면 기본 30·8) 이상인지 본다. 문제 문자열 또는 None."""
    mp = meta_path(sheet)
    if not mp.exists():
        return (f"{mp.name} 가 없어 시트가 어느 학습 데이터에서 나왔는지 확인할 수 없음 — make 로 시트를 다시 만들고 "
                f"meta(기본 {meta_path(Path(SHEET_NAME)).name})를 시트와 같은 폴더에 둔다")
    try:
        meta = json.loads(mp.read_text(encoding="utf-8"))
        train_path = sheet.parent / meta["train_file"]
        if not train_path.is_file():
            return f"{train_path} 가 없음"
        if file_sha256(train_path) != meta["train_sha256"]:
            return f"{train_path.name} 가 시트를 만든 뒤 바뀜 (sha256 다름) — make 로 시트를 다시 만든다"
        current = {r["id"] for r in load_jsonl(train_path)}
        short = sample_shortfall(len(ids), sum(s == SAF_SOURCE for s in sources),
                                 int(meta.get("n", DEFAULT_N)), int(meta.get("min_saf", DEFAULT_MIN_SAF)))
    except (OSError, ValueError, KeyError, TypeError) as e:
        return f"{mp.name} 를 읽을 수 없음: {e}"
    if short:
        return short
    gone = [i for i in ids if i not in current]
    if gone:
        return f"시트 id {len(gone)}개가 현재 {meta['train_file']} 에 없음: {gone[:3]}"
    if sorted(ids) != sorted(meta.get("ids", [])):
        return "시트의 id 목록이 만들 때와 다름(행을 더하거나 뺐음)"
    return None


def cmd_check(a: argparse.Namespace) -> int:
    sheet = Path(a.sheet)
    try:
        fields, rows = read_sheet(sheet)
    except (OSError, ValueError) as e:
        return _err(str(e))
    if "verdict" not in fields or "id" not in fields:
        return _err("id·verdict 열이 없음")
    problem = provenance_problem(sheet, [unescape_cell(r["id"] or "") for r in rows],
                                 [unescape_cell(r.get("source") or "") for r in rows])
    if problem:
        return _err(problem)
    verdicts = [r["verdict"] or "" for r in rows]
    code = check_verdicts(verdicts)
    not_ok = sum(unescape_cell(v.strip()) != "OK" for v in verdicts)
    msg = {0: "통과", 1: f"실패: OK 아닌 행 {not_ok}개 > {MAX_NOT_OK}",
           2: f"미완료: verdict 는 {'/'.join(VERDICTS)} 중 하나로 모두 채워야 함"}[code]
    print(f"{len(verdicts)}행 · {msg}")
    return code


def build_parser() -> argparse.ArgumentParser:
    ap = argparse.ArgumentParser(prog="python -m data_tools.review_sheet",
                                 description="채점 학습 데이터 v2 사람 확인 시트를 만들고 검사한다.")
    sub = ap.add_subparsers(dest="cmd", required=True)
    mk = sub.add_parser("make", help="review_sheet.csv 만들기")
    mk.add_argument("--dir", required=True, help="train_aug.jsonl 이 있는 폴더(저장소 밖). 시트도 여기에 쓴다")
    mk.add_argument("--datasets-dir", required=True, help="원본 parquet 폴더(영어 원문)")
    mk.add_argument("--n", type=int, default=DEFAULT_N, help="시트 행 수(후보가 모자라면 만들지 않고 2)")
    mk.add_argument("--min-saf", type=int, default=DEFAULT_MIN_SAF, help="SAF 최소 행 수(모자라면 만들지 않고 2)")
    mk.add_argument("--seed", type=int, default=DEFAULT_SEED)
    mk.add_argument("--force", action="store_true", help="verdict 가 채워진 기존 시트도 덮는다(백업을 남김)")
    ck = sub.add_parser("check", help="채운 시트 검사")
    ck.add_argument("--sheet", required=True)
    return ap


def main(argv: list[str] | None = None) -> int:
    ap = build_parser()
    a = ap.parse_args(argv)
    utf8_stdout()
    if a.cmd == "make":
        return cmd_make(ap, a)
    return cmd_check(a)


if __name__ == "__main__":
    sys.exit(main())
