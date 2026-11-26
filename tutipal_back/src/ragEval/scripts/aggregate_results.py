"""PT-86: builds docs/eval/PT-86_rag_eval_results.json from tutipal_back/build/rag-eval (summary rows + extras).

Usage: python aggregate_results.py <repo root> <extras.json>   (extras = cost, decisions, adoption; see the report)
"""
import json
import sys
from pathlib import Path

repo = Path(sys.argv[1])
extras = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
out_dir = repo / "tutipal_back/build/rag-eval"
rows = {}
for line in (out_dir / "results/summary.jsonl").read_text(encoding="utf-8").splitlines():
    if line.strip():
        row = json.loads(line)
        rows[row["row"]] = row  # last run of a row wins (dev rows may be re-run; sealed rows are guarded)
probes = {}
for p in sorted((out_dir / "results").glob("bm25probe_*.json")):
    probes[p.stem] = json.loads(p.read_text(encoding="utf-8"))
index_meta = json.loads((out_dir / "cache/index-meta.json").read_text(encoding="utf-8"))
result = {
    "card": "PT-86",
    "report": "docs/PT-86_RAG_EVAL.md",
    "raw_results": "tutipal_back/build/rag-eval/results/*.jsonl (not committed; reproduce with the report commands)",
    "index": index_meta,
    "rows": [rows[k] for k in sorted(rows, key=lambda k: (rows[k]["split"] != "dev", k))],
    "bm25_probe_dev": probes,
    **extras,
}
target = repo / "docs/eval/PT-86_rag_eval_results.json"
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print("wrote", target, len(result["rows"]), "rows")
