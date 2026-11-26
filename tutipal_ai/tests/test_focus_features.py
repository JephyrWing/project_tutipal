"""PT-90 AC-14: the focus features rebuilt without pandas match the original build_features().

The golden fixture (tests/fixtures/focus_features_golden.json) was generated once from the original
focus_features.py with pandas; its header records the command, the original file hash and the idle rule.
CI only needs numpy (no pandas, no original source).
"""
import json
import math
from pathlib import Path

import pytest

from app.services.focus_features import CLASSES, FEATURE_COLUMNS, build_vector, reconstruct_idle

FIXTURE = Path(__file__).parent / "fixtures" / "focus_features_golden.json"
TOLERANCE = 1e-9

# Same order as the trained model's sidecar json (devdesk ml/models/focus_lgbm.json).
EXPECTED_COLUMNS = [
    "ratio_ide_5m", "ratio_browser_5m", "ratio_terminal_5m", "ratio_game_5m", "ratio_chat_5m", "ratio_other_5m",
    "ratio_ide_15m", "ratio_browser_15m", "ratio_terminal_15m", "ratio_game_15m", "ratio_chat_15m",
    "ratio_other_15m", "switch_mean_5m", "idle_mean_5m", "active_ratio_5m", "switch_mean_15m", "idle_mean_15m",
    "active_ratio_15m", "prev_idle_sec", "prev_ide", "prev_browser", "prev_terminal", "prev_game", "prev_chat",
    "prev_other", "hour_sin", "hour_cos", "weekday",
]


def load_cases():
    data = json.loads(FIXTURE.read_text(encoding="utf-8"))
    return data, data["cases"]


def request_rows(case):
    """What Spring sends: the collected idle 0..60, not the running value fed to the original."""
    return [{"minutes_ago": r["minutes_ago"], "category": r["category"], "idle_sec": r["idle_sec"],
             "switch_count": r["switch_count"]} for r in case["rows"]]


def row(m, idle, category="ide", switches=0):
    return {"minutes_ago": m, "category": category, "idle_sec": idle, "switch_count": switches}


# ---- constants ----

def test_feature_columns_are_the_28_model_columns_in_order():
    assert list(FEATURE_COLUMNS) == EXPECTED_COLUMNS
    assert len(FEATURE_COLUMNS) == 28


def test_classes_are_break_distracted_focus():
    assert list(CLASSES) == ["break", "distracted", "focus"]


def test_fixture_columns_match_code_columns():
    data, _ = load_cases()
    assert data["feature_columns"] == EXPECTED_COLUMNS


# ---- idle reconstruction (plan section 0) ----

def test_reconstruct_idle_keeps_values_below_60():
    rows = [row(3, 0), row(2, 59), row(1, 12)]

    assert reconstruct_idle(rows) == {3: 0.0, 2: 59.0, 1: 12.0}


def test_reconstruct_idle_accumulates_consecutive_full_idle_minutes():
    rows = [row(5, 10), row(4, 60), row(3, 60), row(2, 30), row(1, 60)]

    assert reconstruct_idle(rows) == {5: 10.0, 4: 70.0, 3: 130.0, 2: 30.0, 1: 90.0}


def test_reconstruct_idle_restarts_at_60_after_a_missing_minute():
    rows = [row(8, 60), row(7, 60), row(5, 60), row(4, 60)]  # minute 6 missing

    assert reconstruct_idle(rows) == {8: 60.0, 7: 120.0, 5: 60.0, 4: 120.0}


def test_reconstruct_idle_first_row_of_window_is_the_lower_bound_60():
    rows = [row(15, 60)]

    assert reconstruct_idle(rows) == {15: 60.0}


def test_reconstruct_idle_does_not_depend_on_input_order():
    rows = [row(1, 60), row(3, 60), row(2, 60)]

    assert reconstruct_idle(rows) == {3: 60.0, 2: 120.0, 1: 180.0}


def test_ten_idle_minutes_reach_the_break_range():
    rows = [row(m, 5 if m > 10 else 60) for m in range(15, 0, -1)]

    running = reconstruct_idle(rows)

    assert running[1] >= 540


# ---- golden comparison against the original ----

def test_fixture_has_the_planned_scenarios():
    _, cases = load_cases()
    names = {c["name"] for c in cases}
    assert {"continuous_15", "one_missing_minute", "gap_3_minutes_middle", "gap_3_minutes_recent",
            "idle_10_minutes", "empty_5_minute_window"} <= names


def test_fixture_running_idle_follows_the_reconstruction_rule():
    _, cases = load_cases()
    for case in cases:
        running = reconstruct_idle(request_rows(case))
        for r in case["rows"]:
            assert running[r["minutes_ago"]] == pytest.approx(r["idle_sec_running"], abs=TOLERANCE), case["name"]


@pytest.mark.parametrize("case", load_cases()[1], ids=lambda c: c["name"])
def test_build_vector_matches_original_features(case):
    vector = [float(v) for v in build_vector(request_rows(case), case["hour"], case["weekday"])]

    assert len(vector) == len(EXPECTED_COLUMNS)
    for name, got, want in zip(EXPECTED_COLUMNS, vector, case["expected"]):
        if want is None:
            assert math.isnan(got), f"{case['name']}: {name} should be NaN, got {got}"
        else:
            assert not math.isnan(got), f"{case['name']}: {name} should be {want}, got NaN"
            assert abs(got - want) <= TOLERANCE, f"{case['name']}: {name} = {got}, original {want}"


def test_build_vector_ignores_row_order():
    _, cases = load_cases()
    case = cases[0]
    rows = request_rows(case)

    forward = [float(v) for v in build_vector(rows, case["hour"], case["weekday"])]
    backward = [float(v) for v in build_vector(list(reversed(rows)), case["hour"], case["weekday"])]

    assert forward == pytest.approx(backward, abs=TOLERANCE, nan_ok=True)


def test_empty_five_minute_window_is_nan_for_5m_features_and_prev():
    vector = dict(zip(EXPECTED_COLUMNS, (float(v) for v in build_vector(
        [row(m, 20, "browser", 2) for m in range(15, 5, -1)], 10.0, 2))))

    for name in EXPECTED_COLUMNS:
        if name.endswith("_5m") or name.startswith("prev_"):
            assert math.isnan(vector[name]), name
    assert vector["ratio_browser_15m"] == pytest.approx(1.0)
    assert vector["weekday"] == 2.0


def test_prev_uses_the_latest_row_within_two_minutes():
    vector = dict(zip(EXPECTED_COLUMNS, (float(v) for v in build_vector(
        [row(3, 10, "ide"), row(2, 33, "chat")], 0.0, 0))))

    assert vector["prev_idle_sec"] == 33.0
    assert vector["prev_chat"] == 1.0
    assert vector["prev_ide"] == 0.0


def test_hour_is_encoded_as_sin_and_cos():
    vector = dict(zip(EXPECTED_COLUMNS, (float(v) for v in build_vector([row(1, 0)], 6.0, 0))))

    assert vector["hour_sin"] == pytest.approx(1.0, abs=TOLERANCE)
    assert vector["hour_cos"] == pytest.approx(0.0, abs=TOLERANCE)
