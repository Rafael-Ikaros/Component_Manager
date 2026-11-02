# Keep the 28.x client/Compose baseline for older NAS Engines. Docker 29 removed
# client-side compatibility handling for Engine APIs older than 1.44.
# Leave API negotiation enabled; do not force DOCKER_API_VERSION.
FROM docker:28-cli AS docker-cli

FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
WORKDIR /app
COPY updater/requirements.txt requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY updater/app.py app.py
COPY tools/deployment/switch-channel.sh /app/switch-channel.sh
# The workspace may use CRLF on Windows; the runtime helper must be POSIX text.
RUN sed -i 's/\r$//' /app/switch-channel.sh && docker --version && docker compose version
EXPOSE 8790
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8790/health', timeout=3)" || exit 1
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8790", "--workers", "1"]
