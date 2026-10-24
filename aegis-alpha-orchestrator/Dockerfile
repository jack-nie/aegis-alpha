# syntax=docker/dockerfile:1

FROM python:3.11-slim
WORKDIR /app

# Package index is configurable at build time. Defaults to the official PyPI so the
# image builds anywhere; docker-compose.aegis-alpha.yml passes a China mirror by default.
# pip reads these build ARGs directly as PIP_* environment variables during RUN.
ARG PIP_INDEX_URL=https://pypi.org/simple
ARG PIP_DEFAULT_TIMEOUT=60
ARG PIP_RETRIES=5

# Dependency layer first: only re-runs when requirements.txt or the ARGs above change.
COPY requirements.txt ./
RUN pip install --no-cache-dir --disable-pip-version-check -r requirements.txt

COPY app/ ./app/

ENV AEGIS_ALPHA_LANGGRAPH_PORT=8787
ENV AEGIS_ALPHA_LANGGRAPH_HOST=0.0.0.0
ENV AEGIS_ALPHA_LANGCHAIN_MOCK=true

EXPOSE 8787
HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=5 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8787/health').status==200 else 1)"

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8787"]
