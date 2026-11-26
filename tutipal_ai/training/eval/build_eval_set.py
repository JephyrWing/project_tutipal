"""KodCode-V1 원본에서 코딩 평가 세트를 만든다.

    python -m eval.build_eval_set --per-difficulty 50 --sandbox docker

- 원본 데이터셋의 특정 커밋(revision)과 조각 하나만 받아 재현할 수 있게 한다.
- 난이도(easy/medium/hard)별로 같은 수를 고정 시드로 뽑는다.
- 참조 정답이 우리 채점 환경에서 통과하는 문항만 남긴다(--no-verify로 생략 가능).
- data/eval/holdout.jsonl에 문항 ID와 지문 해시를 남겨, 이후 학습 데이터에서 반드시 뺀다.
"""

from __future__ import annotations

import argparse
import ast
import json
import random
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path

from eval import sandbox
from eval.textutil import question_hash

REPO_ID = "KodCode/KodCode-V1"
DEFAULT_SHARD = "data/train-00001-of-00015.parquet"  # Taco 32,274건, 118MB
DIFFICULTIES = ("easy", "medium", "hard")
COLUMNS = ["style", "subset", "question_id", "question", "solution", "test", "test_info",
           "gpt_difficulty", "gpt_pass_percentage", "benchmark_similarity", "filter_reason"]
ALLOWED_THIRD_PARTY = {"solution", "pytest"}
TRAINING_DIR = Path(__file__).resolve().parent.parent
DEFAULT_OUT = TRAINING_DIR / "data" / "eval"


def imported_modules(code: str) -> set[str] | None:
    """최상위 모듈 이름 집합. 문법 오류면 None."""
    try:
        tree = ast.parse(code)
    except SyntaxError:
        return None
    mods: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            mods.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom) and node.module and node.level == 0:
            mods.add(node.module.split(".")[0])
    return mods


def stdlib_only(*codes: str) -> bool:
    allowed = set(sys.stdlib_module_names) | ALLOWED_THIRD_PARTY
    for code in codes:
        mods = imported_modules(code)
        if mods is None or not mods <= allowed:
            return False
    return True


def is_candidate(row: dict, max_benchmark_similarity: float) -> bool:
    if row.get("style") != "instruct" or row.get("gpt_difficulty") not in DIFFICULTIES:
        return False
    if row.get("filter_reason"):
        return False
    sim = row.get("benchmark_similarity")
    if sim is not None and sim >= max_benchmark_similarity:  # HumanEval 등 공개 벤치마크와 비슷한 문항 제외
        return False
    test = row.get("test") or ""
    if "from solution import" not in test or not row.get("test_info"):
        return False
    return stdlib_only(row.get("solution") or "", test)


def select_items(rows: list[dict], per_difficulty: int, seed: int,
                 max_benchmark_similarity: float = 0.8, oversample: float = 1.0) -> list[dict]:
    """난이도별로 per_difficulty * oversample개를 결정적으로 뽑는다(같은 입력·시드 → 같은 결과)."""
    groups: dict[str, list[dict]] = defaultdict(list)
    for row in rows:
        if is_candidate(row, max_benchmark_similarity):
            groups[row["gpt_difficulty"]].append(row)
    rng = random.Random(seed)
    want = int(per_difficulty * oversample + 0.999)
    picked: list[dict] = []
    for diff in DIFFICULTIES:
        pool = sorted(groups[diff], key=lambda r: r["question_id"])
        picked.extend(rng.sample(pool, min(want, len(pool))))
    return picked


def to_eval_item(row: dict) -> dict:
    infos = row["test_info"]
    return {
        "id": row["question_id"],
        "source": f"{REPO_ID}/{row['subset']}",
        "difficulty": row["gpt_difficulty"],
        "question": row["question"],
        "function_names": [i["function_name"] for i in infos if i.get("function_name")],
        "declarations": [i["function_declaration"] for i in infos if i.get("function_declaration")],
        "test": row["test"],
        "reference_solution": row["solution"],
        "question_hash": question_hash(row["question"]),
    }


def load_rows(revision: str, shard: str, cache_dir: Path) -> list[dict]:
    import pyarrow.parquet as pq
    from huggingface_hub import hf_hub_download

    path = hf_hub_download(REPO_ID, shard, repo_type="dataset", revision=revision, local_dir=cache_dir)
    return pq.read_table(path, columns=COLUMNS).to_pylist()


def resolve_revision(revision: str | None) -> str:
    if revision:
        return revision
    from huggingface_hub import HfApi

    return HfApi().dataset_info(REPO_ID).sha


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--per-difficulty", type=int, default=50)
    ap.add_argument("--seed", type=int, default=20261120)
    ap.add_argument("--shard", default=DEFAULT_SHARD)
    ap.add_argument("--revision", help="데이터셋 커밋 SHA. 비우면 현재 main을 고정해 기록한다")
    ap.add_argument("--max-benchmark-similarity", type=float, default=0.8)
    ap.add_argument("--sandbox", choices=["docker", "local"], default="docker")
    ap.add_argument("--no-verify", action="store_true", help="참조 정답 검증 생략")
    ap.add_argument("--out", type=Path, default=DEFAULT_OUT)
    ap.add_argument("--cache-dir", type=Path, default=TRAINING_DIR / "data" / "raw" / "kodcode-v1")
    args = ap.parse_args(argv)

    if not args.no_verify and args.sandbox == "docker":
        sandbox.check_docker()

    revision = resolve_revision(args.revision)
    print(f"[1/3] {REPO_ID}@{revision[:12]} {args.shard} 읽는 중")
    rows = load_rows(revision, args.shard, args.cache_dir)
    candidates = select_items(rows, args.per_difficulty, args.seed, args.max_benchmark_similarity,
                              oversample=1.0 if args.no_verify else 1.5)
    print(f"      전체 {len(rows)}건 → 후보 {len(candidates)}건")

    kept: dict[str, list[dict]] = defaultdict(list)
    dropped: list[dict] = []
    for i, row in enumerate(candidates, 1):
        if i % 20 == 0:
            print(f"[2/3] 참조 정답 검증 {i}/{len(candidates)}")
        diff = row["gpt_difficulty"]
        if len(kept[diff]) >= args.per_difficulty:
            continue
        if not args.no_verify:
            result = sandbox.run_tests(row["solution"], row["test"], mode=args.sandbox)
            if not result.passed:
                dropped.append({"id": row["question_id"], "status": result.status})
                continue
        kept[diff].append(to_eval_item(row))

    items = [item for diff in DIFFICULTIES for item in kept[diff]]
    short = {d: args.per_difficulty - len(kept[d]) for d in DIFFICULTIES if len(kept[d]) < args.per_difficulty}

    args.out.mkdir(parents=True, exist_ok=True)
    with (args.out / "kodcode_eval.jsonl").open("w", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps(item, ensure_ascii=False) + "\n")
    with (args.out / "holdout.jsonl").open("w", encoding="utf-8") as f:
        for item in items:
            f.write(json.dumps({"id": item["id"], "question_hash": item["question_hash"]}) + "\n")
    manifest = {
        "dataset": REPO_ID, "revision": revision, "shard": args.shard, "seed": args.seed,
        "per_difficulty": args.per_difficulty, "max_benchmark_similarity": args.max_benchmark_similarity,
        "verified": not args.no_verify, "sandbox": args.sandbox if not args.no_verify else None,
        "counts": {d: len(kept[d]) for d in DIFFICULTIES}, "dropped_by_verification": dropped,
        "created_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "license": "CC BY-NC 4.0 (KodCode-V1, arXiv 2503.02951)",
    }
    (args.out / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"[3/3] {len(items)}건 저장 → {args.out}  (검증 탈락 {len(dropped)}건)")
    if short:
        print(f"경고: 난이도별 목표 수에 못 미침 {short}. --per-difficulty를 줄이거나 다른 --shard를 쓰세요.")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
