"""PT-87 question clustering: L2-normalized embeddings -> KMeans with k chosen by silhouette.

Deterministic for the same input (fixed random_state, lloyd, one BLAS thread). CPU only, no model call.
Thresholds below are NOT calibrated on TUTIPAL data (see docs/PT-87_INSIGHTS_CONTRACT.md).
"""
import threading
import warnings
from collections.abc import Sequence

import numpy as np
from sklearn.cluster import KMeans
from sklearn.metrics import silhouette_score
from threadpoolctl import threadpool_limits

from app.views.insights_view import ClusterItemView, ClusterView, ConceptMatchView

ALGORITHM = "kmeans-silhouette-v2"
MAX_K = 8
MIN_QUESTIONS_PER_CLUSTER = 5
# Uncalibrated: below this silhouette (of the chosen k) the grouping is reported as LOW_QUALITY.
MIN_SILHOUETTE = 0.1
# Uncalibrated: a larger adjacent k scoring within this margin of the best silhouette counts as a tie and is preferred
# (see choose_k). Short Korean questions on related concepts give flat, low silhouettes (k=2 0.0763 vs k=3 0.0697 on the
# PT-87 synthetic set), and keeping the smaller k merged distinct concepts (purity 64% vs 93%).
SILHOUETTE_TIE_MARGIN = 0.01
# Uncalibrated: cosine between a normalized cluster center and a concept vector needed to label the cluster.
CONCEPT_MATCH_THRESHOLD = 0.55
N_INIT = 10
RANDOM_STATE = 0
# threadpool_limits changes process-wide BLAS/OpenMP settings and restores them on exit; two overlapping calls from
# the thread pool could restore each other's values. One clustering at a time (it is CPU-bound anyway).
_CLUSTER_LOCK = threading.Lock()


def _normalize(matrix: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(matrix, axis=1, keepdims=True)
    return np.divide(matrix, norms, out=np.zeros_like(matrix), where=norms > 0)


def _candidates(n: int, distinct: int) -> range:
    # k in 2..min(8, n // 5, n - 1), and never more clusters than distinct points (KMeans would warn).
    return range(2, min(MAX_K, n // MIN_QUESTIONS_PER_CLUSTER, n - 1, distinct) + 1)


def _fit(points: np.ndarray, k: int) -> np.ndarray:
    model = KMeans(n_clusters=k, n_init=N_INIT, random_state=RANDOM_STATE, algorithm="lloyd")
    with warnings.catch_warnings():
        # Only the Windows + MKL notice; pinned pip numpy uses OpenBLAS and does not emit it.
        warnings.filterwarnings("ignore", message="KMeans is known to have a memory leak", category=UserWarning)
        return model.fit_predict(points)


def _concept_match(center: np.ndarray, concepts: np.ndarray) -> ConceptMatchView | None:
    if concepts.shape[0] == 0:
        return None
    similarities = concepts @ center
    best = int(np.argmax(similarities))   # first index wins a tie
    similarity = float(similarities[best])
    if similarity < CONCEPT_MATCH_THRESHOLD:
        return None
    return ConceptMatchView(index=best, similarity=round(similarity, 4))


def choose_k(scores: dict[int, float]) -> int | None:
    """Start at the best-scoring k (smallest on an exact tie) and step up one k at a time while the next k is still
    within SILHOUETTE_TIE_MARGIN of the best score; stop at the first k that is missing or falls outside.

    Stepping only through adjacent k keeps a flat, noisy score curve from jumping to a far larger k (on the PT-87
    synthetic set k=5 was also within the margin, which would split topics further). None when there is no candidate.
    """
    if not scores:
        return None
    best = max(scores.values())
    k = min(candidate for candidate, score in scores.items() if score == best)
    while k + 1 in scores and scores[k + 1] >= best - SILHOUETTE_TIE_MARGIN:
        k += 1
    return k


def cluster_vectors(vectors: Sequence[Sequence[float]], concepts: Sequence[Sequence[float]]) -> ClusterView:
    points = _normalize(np.asarray(vectors, dtype=np.float64))
    concept_matrix = _normalize(np.asarray(concepts, dtype=np.float64).reshape(len(concepts), -1)) \
        if len(concepts) else np.zeros((0, points.shape[1]))
    n = points.shape[0]
    distinct = int(np.unique(points, axis=0).shape[0])

    fitted: dict[int, np.ndarray] = {}
    scores: dict[int, float] = {}
    with _CLUSTER_LOCK, threadpool_limits(limits=1):
        for k in _candidates(n, distinct):
            labels = _fit(points, k)
            if len(set(labels.tolist())) < 2:
                continue
            fitted[k] = labels
            scores[k] = float(silhouette_score(points, labels, metric="euclidean"))

    chosen = choose_k(scores)
    if chosen is None:
        best_k, best_labels, best_score = 1, np.zeros(n, dtype=int), None
    else:
        best_k, best_labels, best_score = chosen, fitted[chosen], scores[chosen]

    groups: dict[int, list[int]] = {}
    for index, label in enumerate(best_labels.tolist()):
        groups.setdefault(int(label), []).append(index)
    ordered = sorted(groups.values(), key=lambda members: (-len(members), members[0]))

    clusters = []
    for members in ordered:
        center = points[members].mean(axis=0)
        norm = np.linalg.norm(center)
        center = center / norm if norm > 0 else center
        clusters.append(ClusterItemView(members=members, concept=_concept_match(center, concept_matrix)))

    silhouette = None if best_score is None else round(best_score, 6)
    status = "OK" if silhouette is not None and silhouette >= MIN_SILHOUETTE else "LOW_QUALITY"
    return ClusterView(status=status, algorithm=ALGORITHM, k=best_k, silhouette=silhouette, clusters=clusters)
