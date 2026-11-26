from eval.build_eval_set import is_candidate, select_items, stdlib_only, to_eval_item
from eval.textutil import question_hash


def row(qid, diff="easy", **over):
    base = {
        "style": "instruct", "subset": "Taco", "question_id": qid, "question": f"Q {qid}",
        "solution": "import math\n\ndef f(x):\n    return math.floor(x)\n",
        "test": "from solution import f\n\ndef test_f():\n    assert f(1.5) == 1\n",
        "test_info": [{"function_name": "f", "function_declaration": "def f(x):"}],
        "gpt_difficulty": diff, "gpt_pass_percentage": 1.0, "benchmark_similarity": 0.2,
        "filter_reason": None,
    }
    return base | over


def test_stdlib_only_allows_stdlib_and_pytest_rejects_third_party_and_syntax_errors():
    assert stdlib_only("import collections\nfrom itertools import chain", "import pytest\nfrom solution import f")
    assert not stdlib_only("import numpy as np")
    assert not stdlib_only("def (:")


def test_is_candidate_filters():
    assert is_candidate(row("a"), 0.8)
    assert not is_candidate(row("a", style="complete"), 0.8)
    assert not is_candidate(row("a", diff="unknown"), 0.8)
    assert not is_candidate(row("a", benchmark_similarity=0.95), 0.8)
    assert not is_candidate(row("a", filter_reason="dup"), 0.8)
    assert not is_candidate(row("a", test="def test_x():\n    assert True\n"), 0.8)
    assert not is_candidate(row("a", test_info=[]), 0.8)
    assert not is_candidate(row("a", solution="import pandas\n"), 0.8)
    assert is_candidate(row("a", benchmark_similarity=None), 0.8)


def test_select_items_is_deterministic_and_balanced():
    rows = [row(f"{d}-{i}", d) for d in ("easy", "medium", "hard") for i in range(20)]
    first = select_items(rows, 5, seed=1)
    again = select_items(list(reversed(rows)), 5, seed=1)  # 입력 순서가 달라도 같은 결과
    assert [r["question_id"] for r in first] == [r["question_id"] for r in again]
    assert [r["gpt_difficulty"] for r in first].count("hard") == 5
    assert len(select_items(rows, 5, seed=1, oversample=1.5)) == 24


def test_select_items_caps_at_pool_size():
    assert len(select_items([row("only", "hard")], 5, seed=1)) == 1


def test_to_eval_item_keeps_hash_and_signature():
    item = to_eval_item(row("x1"))
    assert item["function_names"] == ["f"]
    assert item["declarations"] == ["def f(x):"]
    assert item["question_hash"] == question_hash("Q x1")
    assert item["source"] == "KodCode/KodCode-V1/Taco"
