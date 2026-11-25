FROM --platform=$BUILDPLATFORM node:22-alpine AS web-build

WORKDIR /app
COPY admin-web/package.json admin-web/package-lock.json* ./
RUN npm ci
COPY admin-web/ .

ARG VITE_DEFAULT_API_BASE_URL=
ENV VITE_DEFAULT_API_BASE_URL=$VITE_DEFAULT_API_BASE_URL
RUN npm run build

# Keep Docker 28 compatibility with older NAS Engines and API negotiation enabled.
FROM docker:28-cli AS docker-cli

FROM python:3.12-slim AS runtime
LABEL io.componentvault.server-update="1" \
      io.componentvault.image-layout="unified"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    DATABASE_PATH=/data/component_vault.db

RUN apt-get update \
    && apt-get install -y --no-install-recommends nginx \
    && rm -rf /var/lib/apt/lists/* \
    && rm -f /etc/nginx/sites-enabled/default \
    && ln -sf /dev/stdout /var/log/nginx/access.log \
    && ln -sf /dev/stderr /var/log/nginx/error.log

WORKDIR /app
COPY server/requirements.txt server/requirements.txt
COPY updater/requirements.txt updater/requirements.txt
RUN pip install --no-cache-dir -r server/requirements.txt -r updater/requirements.txt

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
COPY server/app app
COPY updater/app.py updater/app.py
COPY tools/deployment/switch-channel.sh /app/switch-channel.sh
COPY tools/deployment/docker-entrypoint.sh /usr/local/bin/componentvault-entrypoint
COPY tools/deployment/docker-healthcheck.sh /usr/local/bin/componentvault-healthcheck
COPY admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=web-build /app/dist /usr/share/nginx/html

RUN sed -i 's/\r$//' /app/switch-channel.sh /usr/local/bin/componentvault-entrypoint /usr/local/bin/componentvault-healthcheck \
    && chmod +x /usr/local/bin/componentvault-entrypoint /usr/local/bin/componentvault-healthcheck \
    && mkdir -p /data \
    && nginx -t \
    && docker --version \
    && docker compose version

ARG APP_VERSION=source
ARG APP_REVISION=unknown
ENV COMPONENT_VAULT_BUILD_VERSION=${APP_VERSION} \
    COMPONENT_VAULT_BUILD_REVISION=${APP_REVISION}

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["/usr/local/bin/componentvault-healthcheck"]
ENTRYPOINT ["/usr/local/bin/componentvault-entrypoint"]

FROM runtime AS legacy-web
EXPOSE 80 8790
CMD ["web"]

FROM runtime AS api
EXPOSE 8787
CMD ["api"]
