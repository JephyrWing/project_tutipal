from eval.textutil import extract_code, hangul_ratio, question_hash, strip_think


def test_strip_think_removes_closed_and_unclosed_blocks():
    assert strip_think("<think>음...</think>답") == "답"
    assert strip_think("답<think>끝나지 않은 생각") == "답"


def test_extract_code_prefers_block_defining_required_function():
    text = (
        "예시:\n```python\nprint(f(1))\n```\n"
        "풀이:\n```python\ndef f(x):\n    return x + 1\n```\n"
    )
    assert extract_code(text, ["f"]) == "def f(x):\n    return x + 1"


def test_extract_code_falls_back_to_longest_python_block_and_ignores_other_languages():
    text = "```bash\npip install x y z w v u t s r q p\n```\n```py\na = 1\n```\n```\nb = 22\n```"
    assert extract_code(text) == "b = 22"


def test_extract_code_without_fence_returns_whole_text():
    assert extract_code("  def g():\n    return 1  ") == "def g():\n    return 1"


def test_extract_code_skips_think_block_code():
    text = "<think>```python\ndef f(): return 0\n```</think>```python\ndef f():\n    return 1\n```"
    assert extract_code(text, ["f"]) == "def f():\n    return 1"


def test_hangul_ratio_ignores_code_blocks_and_inline_code():
    text = "리스트는 바꿀 수 있어요 `list.append`\n```python\nxs = [1, 2]\nxs.append(3)\n```"
    assert hangul_ratio(text) == 1.0
    assert hangul_ratio("hello") == 0.0
    assert hangul_ratio("") == 0.0
    assert 0.4 < hangul_ratio("안녕 hi") < 0.6


def test_question_hash_ignores_whitespace_and_case():
    assert question_hash("Write  a\nFunction") == question_hash("write a function")
    assert question_hash("a") != question_hash("b")
