"""후보 모델(또는 파인튜닝 모델)을 같은 평가 세트로 비교한다.

    python -m eval.run_eval                         # candidates.json의 모델 전부
    python -m eval.run_eval --models llama3.2:3b --limit 10 --run-name smoke
    python -m eval.run_eval --run-name baseline     # 중단돼도 같은 이름으로 다시 실행하면 이어서 한다

결과: results/<run-name>/results.jsonl, summary.json, summary.md, ko_review.csv
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from eval import report, sandbox
from eval.ollama_client import OllamaClient, OllamaError
from eval.textutil import extract_code, hangul_ratio, strip_think

EVAL_DIR = Path(__file__).resolve().parent
TRAINING_DIR = EVAL_DIR.parent

CODE_SYSTEM = (
    "You are an expert Python programmer. Solve the task. Reply with exactly one ```python code block "
    "containing the complete solution, with no tests and no example usage. Use only the Python standard library. "
    "Define every required function at the top level of the module. "
    "Do not read from standard input or print at module level; the functions will be imported and called by tests."
)

# tutipal_back/src/main/java/com/my/tutipal_back/learning/tutor/TopicPrompt.java 의 CODING 지시문과 같게 유지한다.
TUTOR_SYSTEM = (
    "너는 학생을 돕는 친절한 AI 학습 도우미 TUTIPAL 이다. 한국어로 쉽고 정확하게 답한다.\n"
    "모르는 것은 모른다고 말하고 지어내지 않는다. 답은 필요한 만큼만 짧게 한다.\n"
    "지금 주제는 프로그래밍이다. 개념을 먼저 짧게 설명하고, 필요하면 짧은 코드 예시를 코드 블록으로 보여 준다.\n"
    "오류 메시지를 받으면 원인과 고치는 방법을 단계별로 알려 준다."
)


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def code_prompt(item: dict) -> str:
    decl = "\n".join(item["declarations"])
    return f"{item['question']}\n\nYour solution must define:\n{decl}"


def done_keys(records: list[dict]) -> set[tuple[str, str, str]]:
    """이미 끝난 (종류, 모델, 문항). Ollama 오류로 끝난 문항은 다시 시도한다."""
    return {(r["kind"], r["model"], r["item_id"]) for r in records if r.get("status") != "llm_error"}


def eval_code_item(client: OllamaClient, model: dict, item: dict, args) -> dict:
    rec = {"kind": "code", "model": model["name"], "item_id": item["id"], "difficulty": item["difficulty"]}
    try:
        res = client.chat(model["name"], [{"role": "system", "content": CODE_SYSTEM},
                                          {"role": "user", "content": code_prompt(item)}],
                          think=model.get("think", False), num_predict=args.num_predict)
    except OllamaError as e:
        return rec | {"status": "llm_error", "error": str(e)[:500]}
    code = extract_code(res.content, item["function_names"])
    result = sandbox.run_tests(code, item["test"], mode=args.sandbox, timeout=args.test_timeout)
    return rec | {
        "status": result.status, "wall_seconds": res.wall_seconds, "eval_tokens": res.eval_tokens,
        "tokens_per_second": res.tokens_per_second, "done_reason": res.done_reason,
        "response": res.content, "test_output": result.output,
    }


def eval_ko_item(client: OllamaClient, model: dict, item: dict, args) -> dict:
    rec = {"kind": "ko", "model": model["name"], "item_id": item["id"], "category": item["category"],
           "question": item["question"], "checklist": item.get("checklist", [])}
    try:
        res = client.chat(model["name"], [{"role": "system", "content": TUTOR_SYSTEM},
                                          {"role": "user", "content": item["question"]}],
                          think=model.get("think", False), temperature=args.ko_temperature,
                          num_predict=args.num_predict)
    except OllamaError as e:
        return rec | {"status": "llm_error", "error": str(e)[:500]}
    answer = strip_think(res.content)
    return rec | {
        "status": "answered", "answer": answer, "hangul_ratio": hangul_ratio(answer),
        "wall_seconds": res.wall_seconds, "eval_tokens": res.eval_tokens,
        "tokens_per_second": res.tokens_per_second, "done_reason": res.done_reason,
    }


def pick_models(candidates: list[dict], names: list[str] | None) -> list[dict]:
    if not names:
        return candidates
    known = {c["name"]: c for c in candidates}
    # candidates.json에 없는 모델(예: 파인튜닝 결과)도 이름만으로 평가할 수 있다
    return [known.get(n, {"name": n, "label": n, "think": False}) for n in names]


def write_outputs(run_dir: Path, records: list[dict], models: list[dict]) -> str:
    # 마지막 기록만 남긴다(오류 후 재시도한 문항)
    latest: dict[tuple, dict] = {}
    for r in records:
        latest[(r["kind"], r["model"], r["item_id"])] = r
    final = list(latest.values())
    order = {m["name"]: i for i, m in enumerate(models)}
    summary = dict(sorted(report.summarize(final).items(), key=lambda kv: order.get(kv[0], len(order))))
    labels = {m["name"]: m.get("label", m["name"]) for m in models}
    md = report.to_markdown(summary, labels)
    (run_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    (run_dir / "summary.md").write_text(md, encoding="utf-8")
    report.write_ko_review(final, run_dir / "ko_review.csv")
    return md


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--models", nargs="+", help="Ollama 모델 이름. 비우면 candidates.json 전부")
    ap.add_argument("--candidates", type=Path, default=EVAL_DIR / "candidates.json")
    ap.add_argument("--eval-set", type=Path, default=TRAINING_DIR / "data" / "eval" / "kodcode_eval.jsonl")
    ap.add_argument("--ko-set", type=Path, default=EVAL_DIR / "ko_questions.jsonl")
    ap.add_argument("--run-name", default="baseline")
    ap.add_argument("--limit", type=int, help="코딩·한국어 문항을 각각 앞에서 N개만(빠른 점검용)")
    ap.add_argument("--skip-code", action="store_true")
    ap.add_argument("--skip-ko", action="store_true")
    ap.add_argument("--sandbox", choices=["docker", "local"], default="docker")
    ap.add_argument("--test-timeout", type=float, default=20)
    ap.add_argument("--num-predict", type=int, default=1024)
    ap.add_argument("--ko-temperature", type=float, default=0.0)
    ap.add_argument("--ollama-url", default="http://127.0.0.1:11434")
    args = ap.parse_args(argv)

    candidates = json.loads(args.candidates.read_text(encoding="utf-8"))["models"]
    models = pick_models(candidates, args.models)
    code_items = [] if args.skip_code else load_jsonl(args.eval_set)[: args.limit]
    ko_items = [] if args.skip_ko else load_jsonl(args.ko_set)[: args.limit]

    client = OllamaClient(args.ollama_url)
    try:
        installed = client.installed_models()
    except OllamaError as e:
        print(f"오류: {e}\nOllama를 실행했는지 확인하세요.", file=sys.stderr)
        return 2
    missing = [m["name"] for m in models if m["name"] not in installed]
    if missing:
        print("설치되지 않은 모델이 있습니다. 먼저 받으세요:", file=sys.stderr)
        for name in missing:
            print(f"  ollama pull {name}", file=sys.stderr)
        return 2
    if code_items and args.sandbox == "docker":
        try:
            sandbox.check_docker()
        except sandbox.SandboxUnavailable as e:
            print(f"오류: {e}", file=sys.stderr)
            return 2
    if args.sandbox == "local" and code_items:
        print("주의: local 모드는 모델이 만든 코드를 격리 없이 이 PC에서 실행합니다.", file=sys.stderr)

    run_dir = TRAINING_DIR / "results" / args.run_name
    run_dir.mkdir(parents=True, exist_ok=True)
    results_path = run_dir / "results.jsonl"
    records = load_jsonl(results_path) if results_path.exists() else []
    done = done_keys(records)

    with results_path.open("a", encoding="utf-8") as out:
        for model in models:
            todo = [("code", it) for it in code_items] + [("ko", it) for it in ko_items]
            todo = [(k, it) for k, it in todo if (k, model["name"], it["id"]) not in done]
            print(f"== {model.get('label', model['name'])}: {len(todo)}문항 남음")
            for i, (kind, item) in enumerate(todo, 1):
                fn = eval_code_item if kind == "code" else eval_ko_item
                rec = fn(client, model, item, args)
                out.write(json.dumps(rec, ensure_ascii=False) + "\n")
                out.flush()
                records.append(rec)
                print(f"  [{i}/{len(todo)}] {kind} {item['id']}: {rec['status']}")

    print()
    print(write_outputs(run_dir, records, models))
    print(f"저장: {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
