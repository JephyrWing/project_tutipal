"""JSONL 줄 경계 회귀 테스트: LF·CR·CRLF 만 줄 경계다(run_eval.load_jsonl 과 같은 규칙).

str.splitlines() 는 U+2028·U+0085·FF(0x0C) 등에서도 쪼갠다. 그러면 ensure_ascii=False 로 쓴 정상 행이
"JSON 아님"으로 오탐되고, 반대로 U+2028로 이은 두 행이 통과한다. eval.leakcheck.load_rows 와
data_tools.common.load_jsonl 모두 같은 규칙을 따르는지 확인한다.
(특수 문자는 소스에 그대로 두지 않고 chr() 로 만든다.)
"""

import json

import pytest

from data_tools.common import load_jsonl
from eval.leakcheck import InputError, load_rows, main

LS, NEL, PS, FF, FS = chr(0x2028), chr(0x85), chr(0x2029), chr(0x0C), chr(0x1C)
# json.dumps(ensure_ascii=False) 가 이스케이프하지 않고 문자열 안에 그대로 쓰는 문자(FF 등 제어 문자는 이스케이프됨)
RAW_IN_STRING = [LS, NEL, PS]
# splitlines() 는 경계로 보지만 JSONL 경계가 아닌 문자
SEPARATORS = [LS, NEL, FF, FS]


def _ids(chars):
    return [f"U+{ord(c):04X}" for c in chars]


def _row(rid, text):
    return {"id": rid, "question": f"문항 {rid}{text}설명하세요.", "model_answer": f"모범{text}답안",
            "student_answer": f"학생{text}답안", "kind": "exact"}


def _line(obj):
    return json.dumps(obj, ensure_ascii=False)


def _joined(sep):
    return _line(_row("a1", " ")) + sep + _line(_row("a2", " ")) + "\n"


def test_raw_chars_survive_json_dumps():
    assert all(ch in _line(_row("a1", ch)) for ch in RAW_IN_STRING)


@pytest.mark.parametrize("ch", RAW_IN_STRING, ids=_ids(RAW_IN_STRING))
def test_leakcheck_accepts_raw_char_inside_string(tmp_path, ch):
    path = tmp_path / "train.jsonl"
    path.write_text(_line(_row("a1", ch)) + "\n" + _line(_row("a2", ch)) + "\n", encoding="utf-8", newline="")
    rows = load_rows(path)
    assert [r["id"] for r in rows] == ["a1", "a2"]
    assert ch in rows[0]["question"]


@pytest.mark.parametrize("sep", SEPARATORS, ids=_ids(SEPARATORS))
def test_leakcheck_rejects_two_rows_joined_by_non_newline_separator(tmp_path, sep):
    path = tmp_path / "train.jsonl"
    path.write_text(_joined(sep), encoding="utf-8", newline="")
    with pytest.raises(InputError, match=r"train\.jsonl:1: JSON 형식이 아닙니다"):
        load_rows(path)


def test_leakcheck_cli_exits_2_for_rows_joined_by_u2028(tmp_path, capsys):
    train = tmp_path / "train.jsonl"
    train.write_text(_joined(LS), encoding="utf-8", newline="")
    dev = tmp_path / "dev.jsonl"
    dev.write_text(_line(_row("d1", " ")) + "\n", encoding="utf-8")
    assert main(["--train", str(train), "--dev", str(dev), "--report", str(tmp_path / "results")]) == 2
    assert "JSON 형식이 아닙니다" in capsys.readouterr().err


def test_leakcheck_still_splits_on_crlf_and_lone_cr_with_line_numbers(tmp_path):
    path = tmp_path / "train.jsonl"
    path.write_text(_line(_row("a1", " ")) + "\r\n" + _line(_row("a2", " ")) + "\r" + "{broken\n",
                    encoding="utf-8", newline="")
    with pytest.raises(InputError, match=r"train\.jsonl:3: JSON 형식이 아닙니다"):
        load_rows(path)


@pytest.mark.parametrize("ch", RAW_IN_STRING, ids=_ids(RAW_IN_STRING))
def test_data_tools_load_jsonl_accepts_raw_char_inside_string(tmp_path, ch):
    path = tmp_path / "rows.jsonl"
    path.write_text(_line(_row("a1", ch)) + "\n" + _line(_row("a2", ch)) + "\n", encoding="utf-8", newline="")
    assert [r["id"] for r in load_jsonl(path, ("id",))] == ["a1", "a2"]


@pytest.mark.parametrize("sep", SEPARATORS, ids=_ids(SEPARATORS))
def test_data_tools_load_jsonl_rejects_two_rows_joined_by_non_newline_separator(tmp_path, sep):
    path = tmp_path / "rows.jsonl"
    path.write_text(_joined(sep), encoding="utf-8", newline="")
    with pytest.raises(ValueError, match=r"rows\.jsonl:1: JSON 형식이 아님"):
        load_jsonl(path)
