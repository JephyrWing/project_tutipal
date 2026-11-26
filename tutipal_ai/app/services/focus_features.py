"""PT-90: focus model features for one target minute, without pandas.

Same values as the training code's build_features() (focus-streamlit/focus_features.py) for the minute T that
follows the latest stored minute: rows sit at T - minutes_ago minutes, T itself is excluded.

- 5-minute window = rows with minutes_ago 1..5, 15-minute window = 1..15; each feature is the mean over the rows
  present (pandas rolling(closed="left").mean()). An empty window is NaN.
- prev_* = the latest row if it is at most 2 minutes before T (minutes_ago <= 2), else NaN.
- idle: the model was trained on a running idle count, while the desktop app collects 0..60 seconds per minute.
  reconstruct_idle() rebuilds the running value over consecutive minutes (exact inside a run, lower bound 60 for
  the first full-idle row of the window).
- hour is the local hour of T (hour + minute / 60), weekday Monday = 0.
"""
import math
from collections.abc import Iterable, Mapping

CATEGORIES = ("ide", "browser", "terminal", "game", "chat", "other")
CLASSES = ("break", "distracted", "focus")
ACTIVE_SEC = 60
FULL_IDLE_SEC = 60

FEATURE_COLUMNS = (
    *(f"ratio_{c}_{w}m" for w in (5, 15) for c in CATEGORIES),
    *(f"{k}_{w}m" for w in (5, 15) for k in ("switch_mean", "idle_mean", "active_ratio")),
    "prev_idle_sec", *(f"prev_{c}" for c in CATEGORIES), "hour_sin", "hour_cos", "weekday",
)

Row = Mapping[str, object]


def _by_minute(rows: Iterable[Row]) -> dict[int, Row]:
    return {int(r["minutes_ago"]): r for r in rows}


def reconstruct_idle(rows: Iterable[Row]) -> dict[int, float]:
    """minutes_ago -> running idle seconds. A full idle minute (60) adds 60 to the minute just before it."""
    by = _by_minute(rows)
    running: dict[int, float] = {}
    for minute in sorted(by, reverse=True):  # oldest first
        idle = float(by[minute]["idle_sec"])
        if idle < FULL_IDLE_SEC:
            running[minute] = idle
        else:
            running[minute] = running[minute + 1] + 60.0 if minute + 1 in running else 60.0
    return running


def _window(by: dict[int, Row], running: dict[int, float], size: int) -> tuple[list[float], list[float]]:
    minutes = [m for m in by if 1 <= m <= size]
    n = len(minutes)
    if n == 0:
        return [math.nan] * len(CATEGORIES), [math.nan] * 3
    ratios = [sum(1 for m in minutes if by[m]["category"] == c) / n for c in CATEGORIES]
    switch_mean = sum(float(by[m]["switch_count"]) for m in minutes) / n
    idle_mean = sum(running[m] for m in minutes) / n
    active_ratio = sum(1 for m in minutes if running[m] < ACTIVE_SEC) / n
    return ratios, [switch_mean, idle_mean, active_ratio]


def build_vector(rows: Iterable[Row], hour: float, weekday: int) -> list[float]:
    """28 floats in FEATURE_COLUMNS order (NaN where the original has NaN). Row order does not matter."""
    by = _by_minute(rows)
    running = reconstruct_idle(by.values())
    ratios_5, rest_5 = _window(by, running, 5)
    ratios_15, rest_15 = _window(by, running, 15)
    vector = ratios_5 + ratios_15 + rest_5 + rest_15
    recent = [m for m in by if m <= 2]
    if recent:
        latest = min(recent)
        vector.append(running[latest])
        vector.extend(1.0 if by[latest]["category"] == c else 0.0 for c in CATEGORIES)
    else:
        vector.extend([math.nan] * (1 + len(CATEGORIES)))
    angle = 2 * math.pi * float(hour) / 24
    vector.extend([math.sin(angle), math.cos(angle), float(weekday)])
    return vector
