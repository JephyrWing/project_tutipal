from fastapi import APIRouter, Depends
from starlette.concurrency import run_in_threadpool

from app.core.security import internal_only
from app.models.insights import ClusterRequest
from app.services.insights_service import cluster_vectors
from app.views.insights_view import ClusterView

# PT-87: CPU-only clustering, no model provider involved (so no provider check here).
router = APIRouter(prefix="/v1/insights", dependencies=[Depends(internal_only)])


@router.post("/cluster", response_model=ClusterView)
async def cluster(request: ClusterRequest) -> ClusterView:
    # KMeans blocks for up to a few seconds: keep it off the event loop.
    return await run_in_threadpool(cluster_vectors, request.vectors, request.concepts)
