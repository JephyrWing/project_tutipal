# PT-86: pinned reranker model (cross-encoder/mmarco-mMiniLMv2-L12-H384-v1, Apache-2.0), only when
# WITH_RERANKER=true. Default false: rerank is off until adoption (docs/PT-86_RAG_EVAL.md), so normal builds neither
# reach huggingface.co nor add ~136MB. Fixed revision + sha256 from the Hugging Face API (lfs.sha256); a mismatch
# fails the build. Build with: docker build --build-arg WITH_RERANKER=true tutipal_ai
FROM python:3.12.6-slim AS reranker-model
ARG WITH_RERANKER=false
ARG RERANK_REPO=cross-encoder/mmarco-mMiniLMv2-L12-H384-v1
ARG RERANK_REVISION=1427fd652930e4ba29e8149678df786c240d8825
COPY docker/fetch_reranker.py /tmp/fetch_reranker.py
RUN mkdir -p /models/reranker && if [ "$WITH_RERANKER" = "true" ]; then \
      python /tmp/fetch_reranker.py "$RERANK_REPO" "$RERANK_REVISION" /models/reranker \
        "onnx/model_quint8_avx2.onnx=model.onnx=6c2513767fb63d008a4377bef7a7a3555433d9436342bb53e35a3a72ffc52d4b" \
        "tokenizer.json=tokenizer.json=62c24cdc13d4c9952d63718d6c9fa4c287974249e16b7ade6d5a85e7bbb75626"; \
    fi

FROM python:3.12.6-slim
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
# PT-85: LangSmith/LangChain tracing would export request bodies; the app refuses to start if any of these is true.
ENV LANGSMITH_TRACING=false LANGCHAIN_TRACING_V2=false LANGSMITH_TRACING_V2=false LANGCHAIN_TRACING=false
# PT-90: lightgbm needs the OpenMP runtime (libgomp1); the focus model file itself is not in the image.
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY requirements.txt requirements.lock.txt ./
RUN pip install --no-cache-dir -r requirements.lock.txt && pip check
RUN groupadd --system tutipal && useradd --system --gid tutipal tutipal
# Model files stay root-owned (read-only for the app user). Empty directory when WITH_RERANKER=false
# (then /v1/rerank answers 503 even if RERANK_ENABLED=true).
COPY --from=reranker-model /models /models
# Measured/frozen config (docs/PT-86_RAG_EVAL.md 3): 256 tokens per (query, chunk) pair, 2 threads.
ENV RERANK_MODEL_DIR=/models/reranker \
    RERANK_MODEL_NAME=cross-encoder/mmarco-mMiniLMv2-L12-H384-v1 \
    RERANK_MAX_LENGTH=256
COPY --chown=tutipal:tutipal app app
USER tutipal
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
