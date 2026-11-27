"""PT-87 AC-19/AC-20: POST /v1/insights/cluster and its pure clustering function.

Only synthetic vectors (numpy); no embedding or model call. The service function is
app.services.insights_service.cluster_vectors(vectors, concepts) -> app.views.insights_view.ClusterView.
"""
import json
import re
from pathlib import Path

import httpx
import numpy as np
import pytest

from app.main import app
from app.services import insights_service

AUTH = {"X-Internal-Key": "pt26-test-key"}
PATH = "/v1/insights/cluster"
DIM = 1024
ROOT = Path(__file__).resolve().parents[1]


async def post(body, headers=AUTH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(PATH, json=body, headers=headers)


async def post_raw(content: str, headers=AUTH):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(PATH, content=content.encode(),
                                 headers={**headers, "Content-Type": "application/json"})


def unit(index: int) -> np.ndarray:
    v = np.zeros(DIM)
    v[index] = 1.0
    return v


def blobs(sizes, seed=0, noise=0.01, order_seed=1):
    """Well separated groups around orthogonal axes, shuffled. Returns (vectors, group index per row)."""
    rng = np.random.default_rng(seed)
    rows, labels = [], []
    for group, size in enumerate(sizes):
        center = unit(group * 7)
        for _ in range(size):
            rows.append(center + rng.normal(0.0, noise, DIM))
            labels.append(group)
    order = np.random.default_rng(order_seed).permutation(len(rows))
    return [rows[i].round(5).tolist() for i in order], [labels[i] for i in order]


def members_by_group(labels):
    groups = {}
    for index, label in enumerate(labels):
        groups.setdefault(label, set()).add(index)
    return groups


def body_for(vectors, concepts=()):
    return {"vectors": vectors, "concepts": [list(c) for c in concepts]}


def dump(result):
    return result.model_dump() if hasattr(result, "model_dump") else result


# ---------------------------------------------------------------- service (pure function)

def test_three_separated_groups_give_k3_ok_and_size_order():
    vectors, labels = blobs([12, 10, 8])

    result = dump(insights_service.cluster_vectors(vectors, []))

    assert result["status"] == "OK"
    assert result["algorithm"] == "kmeans-silhouette-v2"
    assert result["k"] == 3
    assert result["silhouette"] is not None and result["silhouette"] > 0.5
    expected = members_by_group(labels)
    got = [set(c["members"]) for c in result["clusters"]]
    assert got == [expected[0], expected[1], expected[2]]          # size descending: 12, 10, 8
    assert sorted(i for c in result["clusters"] for i in c["members"]) == list(range(len(vectors)))
    assert all(c["concept"] is None for c in result["clusters"])


def test_equal_sizes_are_ordered_by_smallest_member_index():
    vectors, labels = blobs([10, 10, 8], seed=3, order_seed=5)

    result = dump(insights_service.cluster_vectors(vectors, []))

    first, second = result["clusters"][0], result["clusters"][1]
    assert len(first["members"]) == len(second["members"]) == 10
    assert min(first["members"]) < min(second["members"])


def test_same_input_gives_same_output():
    vectors, _ = blobs([12, 10, 8], seed=7)

    first = dump(insights_service.cluster_vectors(vectors, []))
    second = dump(insights_service.cluster_vectors(vectors, []))

    assert first == second


def test_k_candidates_are_limited_by_n_over_five():
    # 10 vectors -> only k=2 is a candidate (min(8, 10 // 5, 9) = 2)
    vectors, _ = blobs([5, 5], seed=2)

    result = dump(insights_service.cluster_vectors(vectors, []))

    assert result["k"] == 2
    assert len(result["clusters"]) == 2


def test_near_tie_in_silhouette_goes_to_the_larger_k():
    # PT-87 bge-m3 synthetic set: k=2 merged two concepts although k=3 was only 0.0066 lower
    assert insights_service.choose_k({2: 0.0763, 3: 0.0697, 4: 0.0570}) == 3


def test_exact_tie_goes_to_the_larger_k():
    assert insights_service.choose_k({2: 0.2, 3: 0.2}) == 3


def test_clear_winner_is_kept_when_larger_k_is_outside_the_margin():
    assert insights_service.choose_k({2: 0.30, 3: 0.10, 4: 0.05}) == 2


def test_only_candidates_within_the_margin_are_considered():
    # 4 is within the margin of the best (3), 5 is not
    assert insights_service.choose_k({2: 0.10, 3: 0.40, 4: 0.395, 5: 0.30}) == 4


def test_tie_break_only_steps_through_adjacent_k():
    # PT-87 bge-m3 synthetic set: k=5 (0.0670) is also within 0.01 of k=2 but k=4 (0.0570) breaks the chain
    scores = {2: 0.0763, 3: 0.0697, 4: 0.0570, 5: 0.0670, 6: 0.0540, 7: 0.0580, 8: 0.0420}
    assert insights_service.choose_k(scores) == 3


def test_exact_best_tie_starts_from_the_smaller_k_then_steps_up():
    # best 0.30 at k=2 and k=4, k=3 outside the margin: start at 2 and stop there
    assert insights_service.choose_k({2: 0.30, 3: 0.10, 4: 0.30}) == 2


def test_no_candidate_gives_none():
    assert insights_service.choose_k({}) is None


def test_reported_silhouette_is_the_chosen_k_score(monkeypatch):
    # k=2 scores 0.105 (OK on its own), k=3 ties within the margin at 0.098: k=3 is chosen and its score is reported
    vectors, _ = blobs([6, 6, 6], seed=4)
    fake = {2: 0.105, 3: 0.098}
    monkeypatch.setattr(insights_service, "silhouette_score",
                        lambda points, labels, metric: fake[len(set(labels.tolist()))])

    result = dump(insights_service.cluster_vectors(vectors, []))

    assert result["k"] == 3
    assert result["silhouette"] == 0.098
    assert result["status"] == "LOW_QUALITY"
    assert len(result["clusters"]) == 3


def test_identical_vectors_give_one_cluster_without_silhouette():
    vectors = [unit(3).tolist()] * 12

    result = dump(insights_service.cluster_vectors(vectors, []))

    assert result["status"] == "LOW_QUALITY"
    assert result["k"] == 1
    assert result["silhouette"] is None
    assert len(result["clusters"]) == 1
    assert sorted(result["clusters"][0]["members"]) == list(range(12))


def test_structureless_noise_is_low_quality():
    rng = np.random.default_rng(11)
    vectors = rng.normal(0.0, 1.0, (40, DIM)).round(5).tolist()

    result = dump(insights_service.cluster_vectors(vectors, []))

    assert result["status"] == "LOW_QUALITY"
    assert result["silhouette"] is None or result["silhouette"] < 0.1


def test_concept_close_to_a_cluster_center_is_matched():
    vectors, labels = blobs([12, 10, 8])
    concepts = [unit(7).tolist(), unit(900).tolist()]           # concept 0 points at group 1 (axis 7)

    result = dump(insights_service.cluster_vectors(vectors, concepts))

    expected = members_by_group(labels)
    by_members = {frozenset(c["members"]): c for c in result["clusters"]}
    matched = by_members[frozenset(expected[1])]["concept"]
    assert matched is not None
    assert matched["index"] == 0
    assert matched["similarity"] > 0.9
    for group in (0, 2):
        concept = by_members[frozenset(expected[group])]["concept"]
        assert concept is None or concept["similarity"] < 0.55   # threshold may be applied here or in Spring


def test_result_has_no_representatives_or_text():
    vectors, _ = blobs([12, 10, 8])

    result = dump(insights_service.cluster_vectors(vectors, []))

    assert set(result) == {"status", "algorithm", "k", "silhouette", "clusters"}
    assert all(set(c) == {"members", "concept"} for c in result["clusters"])


# ---------------------------------------------------------------- API

pytestmark_api = pytest.mark.anyio


@pytestmark_api
async def test_api_returns_clusters_for_valid_input():
    vectors, labels = blobs([12, 10, 8])

    response = await post(body_for(vectors, [unit(7).tolist()]))

    assert response.status_code == 200
    data = response.json()
    assert set(data) == {"status", "algorithm", "k", "silhouette", "clusters"}
    assert data["status"] == "OK"
    assert data["k"] == 3
    assert "representatives" not in response.text


@pytestmark_api
async def test_api_is_deterministic():
    vectors, _ = blobs([12, 10, 8], seed=4)

    first = await post(body_for(vectors))
    second = await post(body_for(vectors))

    assert first.status_code == second.status_code == 200
    assert first.json() == second.json()


@pytestmark_api
@pytest.mark.parametrize("key", [None, "wrong", ""])
async def test_api_requires_the_internal_key(key):
    vectors, _ = blobs([5, 5])
    response = await post(body_for(vectors), {} if key is None else {"X-Internal-Key": key})
    assert response.status_code == 401


@pytestmark_api
@pytest.mark.parametrize("count", [0, 1, 9, 257])
async def test_api_rejects_vector_counts_outside_10_to_256(count):
    vectors = [unit(i % DIM).tolist() for i in range(count)]
    response = await post(body_for(vectors))
    assert response.status_code == 422


@pytestmark_api
async def test_api_rejects_more_than_50_concepts():
    vectors, _ = blobs([5, 5])
    response = await post(body_for(vectors, [unit(i).tolist() for i in range(51)]))
    assert response.status_code == 422


@pytestmark_api
async def test_api_accepts_50_concepts_and_no_concepts():
    vectors, _ = blobs([5, 5])
    assert (await post(body_for(vectors, [unit(i).tolist() for i in range(50)]))).status_code == 200
    assert (await post(body_for(vectors, []))).status_code == 200


@pytestmark_api
@pytest.mark.parametrize("dimension", [1023, 1025, 0])
async def test_api_rejects_wrong_vector_dimension(dimension):
    vectors, _ = blobs([5, 5])
    vectors[4] = [0.1] * dimension
    assert (await post(body_for(vectors))).status_code == 422


@pytestmark_api
async def test_api_rejects_wrong_concept_dimension():
    vectors, _ = blobs([5, 5])
    assert (await post(body_for(vectors, [[0.1] * 1023]))).status_code == 422


@pytestmark_api
@pytest.mark.parametrize("token", ["NaN", "Infinity", "-Infinity", "1e400"])
async def test_api_rejects_non_finite_values(token):
    vectors, _ = blobs([5, 5])
    text = json.dumps(body_for(vectors), separators=(",", ":"))
    first = json.dumps(vectors[0][0])
    text = text.replace("[" + first + ",", "[" + token + ",", 1)
    assert token in text
    response = await post_raw(text)
    assert response.status_code == 422


@pytestmark_api
async def test_api_rejects_non_finite_concept_values():
    vectors, _ = blobs([5, 5])
    text = json.dumps(body_for(vectors, [[0.5] * DIM]), separators=(",", ":"))
    text = text.replace('"concepts":[[0.5,', '"concepts":[[NaN,', 1)
    assert "NaN" in text
    assert (await post_raw(text)).status_code == 422


@pytestmark_api
@pytest.mark.parametrize("extra", [{"user_id": 7}, {"texts": ["PRIVATE-INPUT"]}, {"threshold": 0.1}])
async def test_api_rejects_unknown_fields_without_echoing_them(extra):
    vectors, _ = blobs([5, 5])
    response = await post({**body_for(vectors), **extra})
    assert response.status_code == 422
    assert "PRIVATE-INPUT" not in response.text
    assert all("input" not in error and "ctx" not in error for error in response.json()["detail"])


@pytestmark_api
async def test_api_rejects_missing_fields_and_wrong_types():
    vectors, _ = blobs([5, 5])
    assert (await post({"concepts": []})).status_code == 422
    assert (await post({"vectors": "PRIVATE-INPUT", "concepts": []})).status_code == 422
    assert (await post({"vectors": [["x"] * DIM] * 10, "concepts": []})).status_code == 422


@pytestmark_api
async def test_largest_rounded_request_is_under_4_5_mb_and_is_accepted():
    rng = np.random.default_rng(21)
    # worst-case width for 5-decimal values: "-0.12345"
    vectors = [[-abs(x) - 0.1 for x in row] for row in rng.uniform(0.0, 0.8, (256, DIM)).round(5).tolist()]
    concepts = rng.uniform(-0.9, -0.1, (50, DIM)).round(5).tolist()
    text = ('{"vectors":[' + ",".join("[" + ",".join(f"{v:.5f}" for v in row) + "]" for row in vectors)
            + '],"concepts":[' + ",".join("[" + ",".join(f"{v:.5f}" for v in row) + "]" for row in concepts) + "]}")

    assert len(text.encode()) < 4_500_000

    response = await post_raw(text)
    assert response.status_code == 200
    data = response.json()
    assert sorted(i for c in data["clusters"] for i in c["members"]) == list(range(256))


# ---------------------------------------------------------------- AC-20 pinned dependencies

def _pins(path: Path) -> dict:
    pins = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        match = re.match(r"^\s*([A-Za-z0-9_.\-]+)\s*==\s*([^\s;#]+)", line)
        if match:
            pins[match.group(1).lower().replace("_", "-")] = match.group(2)
    return pins


def test_numpy_and_scikit_learn_are_pinned_in_requirements():
    pins = _pins(ROOT / "requirements.txt")
    assert "numpy" in pins
    assert "scikit-learn" in pins


def test_lock_pins_numpy_scikit_learn_and_their_runtime_dependencies():
    pins = _pins(ROOT / "requirements.lock.txt")
    for name in ("numpy", "scikit-learn", "scipy", "joblib", "threadpoolctl"):
        assert name in pins, name
    assert pins["numpy"] == _pins(ROOT / "requirements.txt")["numpy"]
    assert pins["scikit-learn"] == _pins(ROOT / "requirements.txt")["scikit-learn"]
