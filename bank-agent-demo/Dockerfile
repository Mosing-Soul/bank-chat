FROM python:3.11-slim AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DEFAULT_TIMEOUT=300

WORKDIR /app

COPY requirements-docker.txt .
RUN python -m venv /opt/venv
ARG PIP_INDEX_URL=https://pypi.org/simple
ARG TORCH_INDEX_URL=https://download.pytorch.org/whl/cpu
ARG TORCH_VERSION=2.12.0
RUN --mount=type=cache,target=/root/.cache/pip \
    /opt/venv/bin/pip install --retries 10 --timeout 300 \
    --index-url "${TORCH_INDEX_URL}" "torch==${TORCH_VERSION}"
RUN --mount=type=cache,target=/root/.cache/pip \
    /opt/venv/bin/pip install --retries 10 --timeout 300 \
    --index-url "${PIP_INDEX_URL}" -r requirements-docker.txt

FROM python:3.11-slim

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TOKENIZERS_PARALLELISM=false

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /opt/venv /opt/venv
COPY python ./python

WORKDIR /app/python

EXPOSE 8000

STOPSIGNAL SIGTERM
CMD ["sh", "-c", "python docker_init_vector_store.py && exec python -m uvicorn rag_service:app --host 0.0.0.0 --port 8000 --no-access-log"]
