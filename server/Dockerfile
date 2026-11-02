FROM python:3.12-slim
LABEL io.componentvault.server-update="1"

ARG APP_VERSION=source
ARG APP_REVISION=unknown
ENV COMPONENT_VAULT_BUILD_VERSION=${APP_VERSION} \
    COMPONENT_VAULT_BUILD_REVISION=${APP_REVISION}

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1
ENV DATABASE_PATH=/data/component_vault.db

WORKDIR /app

COPY requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

COPY app app

RUN mkdir -p /data

EXPOSE 8787

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/health', timeout=3)" || exit 1

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8787"]
