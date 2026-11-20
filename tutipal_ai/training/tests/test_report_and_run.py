import csv
import json
from types import SimpleNamespace

from eval import report, run_eval
from eval.ollama_client import ChatResult, OllamaError


def code(model, item, diff, status, **kw):
    return {"kind": "code", "model": model, "item_id": item, "difficulty": diff, "status": status,
            "wall_seconds": 2.0, "tokens_per_second": 20.0, "done_reason": "stop"} | kw


def ko(model, item, ratio, status="answered"):
    return {"kind": "ko", "model": model, "item_id": item, "category": "개념", "question": "질문",
            "answer": "답", "checklist": ["a", "b"], "status": status, "hangul_ratio": ratio,
            "wall_seconds": 4.0, "tokens_per_second": 10.0}


def test_summarize_rates_by_difficulty_and_korean():
    recs = [code("m", "1", "easy", "passed"), code("m", "2", "easy", "failed"),
            code("m", "3", "hard", "passed", done_reason="length"),
            ko("m", "k1", 0.9), ko("m", "k2", 0.1)]
    s = report.summarize(recs)["m"]
    assert s["code"]["pass_at_1"] == 2 / 3
    assert s["code"]["by_difficulty"]["easy"]["rate"] == 0.5
    assert s["code"]["by_difficulty"]["medium"]["rate"] is None
    assert s["code"]["truncated"] == 1
    assert s["ko"]["korean_answer_rate"] == 0.5
    assert s["speed"]["median_seconds"] == 2.0
    md = report.to_markdown(report.summarize(recs), {"m": "모델M"})
    assert "| 모델M | 66.7% (2/3) | 50.0% | - | 100.0% | 50.0%" in md


def test_write_ko_review_has_bom_and_rows(tmp_path):
    path = tmp_path / "r.csv"
    report.write_ko_review([ko("m", "k1", 1.0), code("m", "1", "easy", "passed")], path)
    assert path.read_bytes().startswith(b"\xef\xbb\xbf")
    rows = list(csv.reader(path.open(encoding="utf-8-sig")))
    assert len(rows) == 2 and rows[1][5] == "a / b"


def test_done_keys_retries_llm_errors():
    recs = [code("m", "1", "easy", "passed"), code("m", "2", "easy", "llm_error")]
    assert run_eval.done_keys(recs) == {("code", "m", "1")}


def test_pick_models_accepts_unknown_finetuned_name():
    cands = [{"name": "a", "label": "A", "think": False}]
    assert run_eval.pick_models(cands, None) == cands
    assert run_eval.pick_models(cands, ["tutipal-ft"])[0]["name"] == "tutipal-ft"


class FakeClient:
    def __init__(self, content=None, error=None):
        self.content, self.error, self.calls = content, error, []

    def chat(self, model, messages, **kw):
        self.calls.append((model, messages, kw))
        if self.error:
            raise OllamaError(self.error)
        return ChatResult(self.content, 1.5, 30, 20.0, "stop")


ITEM = {"id": "t1", "difficulty": "easy", "question": "Add numbers.", "function_names": ["add"],
        "declarations": ["def add(a, b):"],
        "test": "from solution import add\n\ndef test_add():\n    assert add(2, 3) == 5\n"}
ARGS = SimpleNamespace(num_predict=256, sandbox="local", test_timeout=10, ko_temperature=0.0)


def test_eval_code_item_scores_extracted_code():
    client = FakeClient("풀이입니다\n```python\ndef add(a, b):\n    return a + b\n```")
    rec = run_eval.eval_code_item(client, {"name": "m", "think": False}, ITEM, ARGS)
    assert rec["status"] == "passed"
    assert "def add(a, b):" in client.calls[0][1][1]["content"]


def test_eval_code_item_records_llm_error():
    rec = run_eval.eval_code_item(FakeClient(error="HTTP 500"), {"name": "m"}, ITEM, ARGS)
    assert rec["status"] == "llm_error"


def test_eval_ko_item_uses_tutor_prompt_and_measures_korean():
    client = FakeClient("<think>x</think>리스트는 바꿀 수 있어요.")
    item = {"id": "ko-01", "category": "개념", "question": "차이?", "checklist": []}
    rec = run_eval.eval_ko_item(client, {"name": "m"}, item, ARGS)
    assert rec["answer"] == "리스트는 바꿀 수 있어요."
    assert rec["hangul_ratio"] == 1.0
    assert client.calls[0][1][0]["content"] == run_eval.TUTOR_SYSTEM


def test_tutor_prompt_matches_backend_coding_prompt():
    from pathlib import Path
    java = Path(__file__).resolve().parents[3] / "tutipal_back/src/main/java/com/my/tutipal_back/learning/tutor/TopicPrompt.java"
    src = java.read_text(encoding="utf-8")
    for line in run_eval.TUTOR_SYSTEM.splitlines():
        assert line in src, f"백엔드 지시문과 다름: {line}"


def test_ko_questions_file_is_valid():
    items = run_eval.load_jsonl(run_eval.EVAL_DIR / "ko_questions.jsonl")
    assert len(items) == 30
    assert len({i["id"] for i in items}) == 30
    assert all(i["question"] and i["category"] and i["checklist"] for i in items)


def test_candidates_file_is_valid():
    data = json.loads((run_eval.EVAL_DIR / "candidates.json").read_text(encoding="utf-8"))
    assert all({"name", "label"} <= m.keys() for m in data["models"])
