"""PT-87 Round 1 F32: concurrent clustering calls give the same result as a single call and leave the
process-wide thread limits as they were (threadpool_limits is serialized by a lock)."""
from concurrent.futures import ThreadPoolExecutor

import numpy as np
from threadpoolctl import threadpool_info

from app.services import insights_service

DIM = 1024


def _vectors(seed: int) -> list[list[float]]:
    rng = np.random.default_rng(seed)
    rows = []
    for group in range(3):
        center = np.zeros(DIM)
        center[group * 7] = 1.0
        rows.extend((center + rng.normal(0.0, 0.01, DIM)).round(5).tolist() for _ in range(10))
    return rows


def _limits() -> list:
    return sorted((info["internal_api"], info["num_threads"]) for info in threadpool_info())


def test_parallel_calls_match_a_single_call_and_restore_thread_limits():
    vectors = _vectors(5)
    before = _limits()
    expected = insights_service.cluster_vectors(vectors, []).model_dump()

    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: insights_service.cluster_vectors(vectors, []).model_dump(), range(4)))

    assert all(result == expected for result in results)
    assert _limits() == before


def test_the_lock_is_not_held_after_a_call():
    insights_service.cluster_vectors(_vectors(6), [])
    assert not insights_service._CLUSTER_LOCK.locked()
