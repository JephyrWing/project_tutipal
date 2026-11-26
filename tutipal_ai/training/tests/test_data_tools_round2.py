"""data_tools Codex 2차 리뷰 회귀 테스트.

- write_files_atomic: os.replace 가 중간 파일에서 실패해도 모든 대상이 이전 내용으로 돌아가고
  임시(.tmp)·백업(.bak) 파일이 남지 않는다.
- LLM 캐시 키와 --seed: 번역 요청(build_grading_train.ollama)·작성 요청(compare_hybrid.generate)은
  --seed 를 options.seed 로 쓰므로 seed 가 캐시 키에 들어간다. 기본 seed 는 옛 키와 같아 기존 캐시를
  그대로 이어 쓴다. build_grading_c 의 번역(compare_hybrid.translate)은 고정 seed 라 번역 캐시는 seed 와 무관하다.

데이터는 모두 합성이고 경로는 tmp_path(저장소 밖)만 쓴다. 네트워크는 conftest 가 막는다.
"""

import io
import json
from pathlib import Path

import pytest

from data_tools import build_grading_c as BC
from data_tools import build_grading_train as B
from data_tools import common
from data_tools import compare_hybrid as H
from eval import run_eval

TRAINING = Path(__file__).resolve().parents[1]
DEV_FILE = TRAINING / "eval" / "grading_set.jsonl"


# ---------- write_files_atomic: 바꿔 넣기 도중 실패 시 되돌리기 ----------

def _flaky_replace(monkeypatch, fail_on):
    """임시 파일(.tmp)을 대상으로 바꿔 넣는 fail_on 번째 호출에서만 실패한다(되돌리기 호출은 통과)."""
    real_replace = common.os.replace
    calls = {"tmp": 0}

    def replace(src, dst):
        if str(src).endswith(".tmp"):
            calls["tmp"] += 1
            if calls["tmp"] == fail_on:
                raise OSError(13, "Permission denied (injected)")
        return real_replace(src, dst)

    monkeypatch.setattr(common.os, "replace", replace)
    return calls


def test_replace_failure_on_second_file_rolls_back_all(tmp_path, monkeypatch):
    names = ["train.jsonl", "rejected.jsonl", "stats.json"]
    for name in names:
        (tmp_path / name).write_text(f"previous {name}\n", encoding="utf-8")
    calls = _flaky_replace(monkeypatch, fail_on=2)
    with pytest.raises(OSError, match="injected"):
        common.write_files_atomic({tmp_path / n: (f"new {n}\n", "utf-8") for n in names})
    assert calls["tmp"] == 2
    for name in names:
        assert (tmp_path / name).read_text(encoding="utf-8") == f"previous {name}\n", name
    assert sorted(p.name for p in tmp_path.iterdir()) == sorted(names)  # .tmp·.bak 없음


def test_replace_failure_removes_newly_created_target(tmp_path, monkeypatch):
    (tmp_path / "a.jsonl").write_text("previous a\n", encoding="utf-8")
    (tmp_path / "c.json").write_text("previous c\n", encoding="utf-8")
    _flaky_replace(monkeypatch, fail_on=3)
    files = {tmp_path / "a.jsonl": ("new a\n", "utf-8"), tmp_path / "b.jsonl": ("new b\n", "utf-8"),
             tmp_path / "c.json": ("new c\n", "utf-8")}
    with pytest.raises(OSError, match="injected"):
        common.write_files_atomic(files)
    assert (tmp_path / "a.jsonl").read_text(encoding="utf-8") == "previous a\n"
    assert (tmp_path / "c.json").read_text(encoding="utf-8") == "previous c\n"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["a.jsonl", "c.json"]  # 새로 생긴 b 는 지워짐


def test_success_replaces_all_and_leaves_no_backup(tmp_path):
    (tmp_path / "a.jsonl").write_text("previous a\n", encoding="utf-8")
    common.write_files_atomic({tmp_path / "a.jsonl": ("new a\n", "utf-8"), tmp_path / "b.json": ("new b\n", "utf-8")})
    assert (tmp_path / "a.jsonl").read_text(encoding="utf-8") == "new a\n"
    assert (tmp_path / "b.json").read_text(encoding="utf-8") == "new b\n"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["a.jsonl", "b.json"]


# ---------- 요청 payload: 어떤 요청이 --seed 를 쓰는가 ----------

class _FakeResponse(io.BytesIO):
    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


def _capture_requests(monkeypatch, module, content):
    bodies = []

    def urlopen(req, timeout=None):
        bodies.append(json.loads(req.data.decode()))
        return _FakeResponse(json.dumps({"message": {"content": content}}).encode())

    monkeypatch.setattr(module.urllib.request, "urlopen", urlopen)
    return bodies


def test_build_grading_train_request_uses_given_seed(monkeypatch):
    bodies = _capture_requests(monkeypatch, B, "{}")
    B.ollama("m", "sys", "user", 7)
    assert bodies[0]["options"]["seed"] == 7


def test_writer_request_uses_given_seed_but_translate_uses_fixed_seed(monkeypatch):
    bodies = _capture_requests(monkeypatch, H, "번역")
    H.generate("writer", "prompt", system="sys", fmt="json", seed=7)
    H.translate("translategemma:4b", "Energy is conserved.")
    H.translate("other-llm", "Energy is conserved.")
    assert [b["options"]["seed"] for b in bodies] == [7, common.DEFAULT_SEED, common.DEFAULT_SEED]


def test_seed_key_suffix_is_empty_only_for_default_seed():
    assert common.seed_key_suffix(common.DEFAULT_SEED) == ""
    assert common.seed_key_suffix(7) == ":seed=7"
    assert common.DEFAULT_SEED == 20261120  # 기존 캐시 파일이 만들어진 seed(바꾸면 기존 캐시와 어긋남)


# ---------- 생성기 캐시: 기본 seed 는 옛 캐시 재사용, 다른 seed 는 새로 추론 ----------

ITEM = {"source": "Synthetic", "source_id": "S1", "subject": "과학", "q": "Explain energy.",
        "ref": "Energy is conserved.", "ans": "It stays the same.", "label": True, "kind": "paraphrase", "hint": ""}
KO = {"question": "합성 문항: 에너지가 어떻게 되는지 우리말로 설명하세요.",
      "model_answer": "에너지는 형태가 바뀌어도 총량이 보존된다.",
      "student_answer": "에너지의 양은 그대로 유지됩니다."}
WRITE = {"reason": "모범 답안과 같은 뜻을 담고 있다.", "feedback": "핵심을 정확히 담았어요. 계속 이렇게 해 보세요."}


@pytest.fixture
def quizai_ok(tmp_path):
    body = "\n".join("            " + line for line in run_eval.GRADE_SYSTEM.split("\n"))
    path = tmp_path / "QuizAi.java"
    path.write_text("class QuizAi {\n    static final String GRADE_SYSTEM = \"\"\"\n" + body
                    + "\n            \"\"\";\n}\n", encoding="utf-8")
    return path


def _argv(tmp_path, quizai, *extra):
    datasets = tmp_path / "datasets"
    datasets.mkdir(exist_ok=True)
    return ["--out", str(tmp_path / "out"), "--datasets-dir", str(datasets), "--quizai", str(quizai),
            "--eval-set", str(DEV_FILE), "--n", "3", *extra]  # n=3 이면 정답 몫이 1건


def _write_cache(path, rows):
    path.parent.mkdir(parents=True, exist_ok=True)
    text = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
    path.write_text(text, encoding="utf-8")
    return text


def test_build_grading_train_cache_key_includes_non_default_seed(tmp_path, quizai_ok, monkeypatch):
    monkeypatch.setattr(B, "load_sources", lambda datasets_dir: [ITEM])
    legacy = {"key": "Synthetic:S1:qwen3.5:4b", "out": {**KO, **WRITE}, "error": None}  # seed 넣기 전 키
    cache_path = tmp_path / "out" / "cache.jsonl"
    before = _write_cache(cache_path, [legacy])
    seeds = []

    def fake_ollama(model, system, user, seed):
        seeds.append(seed)
        return json.dumps({**KO, **WRITE}, ensure_ascii=False)

    monkeypatch.setattr(B, "ollama", fake_ollama)

    B.main(_argv(tmp_path, quizai_ok))  # 기본 seed: 옛 캐시 그대로 재사용
    assert seeds == []
    stats = json.loads((tmp_path / "out" / "stats.json").read_text(encoding="utf-8"))
    assert stats["seed"] == common.DEFAULT_SEED and "seed" in stats["cache_key_fields"]
    assert stats["train"] >= 1

    B.main(_argv(tmp_path, quizai_ok, "--seed", "7"))  # 다른 seed: 새로 추론
    assert seeds == [7]
    text = cache_path.read_text(encoding="utf-8")
    assert text.startswith(before)  # 기존 캐시 줄은 고치지 않고 뒤에 덧붙이기만 한다
    assert json.loads(text[len(before):])["key"] == "Synthetic:S1:qwen3.5:4b:seed=7"


def test_build_grading_c_write_cache_keyed_by_seed_translate_cache_is_not(tmp_path, quizai_ok, monkeypatch):
    monkeypatch.setattr(B, "load_sources", lambda datasets_dir: [ITEM])
    out = tmp_path / "out"
    trans_before = _write_cache(out / "trans_cache.jsonl",
                                [{"key": "Synthetic:S1:translategemma:4b", "out": KO, "error": None}])
    write_before = _write_cache(out / "write_cache.jsonl",
                                [{"key": "Synthetic:S1:translategemma:4b>gemma3:4b", "out": {**KO, **WRITE},
                                  "error": None}])
    translated, written = [], []

    def fake_translate(model, text):
        translated.append(text)
        return "번역"

    def fake_generate(model, prompt, system=None, fmt=None, seed=common.DEFAULT_SEED):
        written.append(seed)
        return json.dumps(WRITE, ensure_ascii=False)

    monkeypatch.setattr(H, "translate", fake_translate)
    monkeypatch.setattr(H, "generate", fake_generate)

    BC.main(_argv(tmp_path, quizai_ok))  # 기본 seed: 두 캐시 모두 재사용
    assert translated == [] and written == []
    stats = json.loads((out / "stats.json").read_text(encoding="utf-8"))
    assert stats["train"] >= 1
    assert "seed" in stats["cache_key_fields"]["write_cache"]
    assert "seed" not in stats["cache_key_fields"]["trans_cache"]
    assert stats["translate_seed"] == common.DEFAULT_SEED

    BC.main(_argv(tmp_path, quizai_ok, "--seed", "7"))  # 다른 seed: 번역은 재사용, 작성만 새로
    assert translated == []
    assert written == [7]
    assert (out / "trans_cache.jsonl").read_text(encoding="utf-8") == trans_before
    wtext = (out / "write_cache.jsonl").read_text(encoding="utf-8")
    assert wtext.startswith(write_before)
    assert json.loads(wtext[len(write_before):])["key"] == "Synthetic:S1:translategemma:4b>gemma3:4b:seed=7"
