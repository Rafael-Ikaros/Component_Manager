FROM --platform=$BUILDPLATFORM node:22-alpine AS build

WORKDIR /app

COPY admin-web/package.json admin-web/package-lock.json* ./
RUN npm ci

COPY admin-web/ .

ARG VITE_DEFAULT_API_BASE_URL=
ENV VITE_DEFAULT_API_BASE_URL=$VITE_DEFAULT_API_BASE_URL

RUN npm run build

# Keep Docker 28 compatibility with older NAS Engines and API negotiation enabled.
FROM docker:28-cli AS docker-cli

FROM nginx:1.27-alpine
LABEL io.componentvault.server-update="1"

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PATH="/opt/updater-venv/bin:$PATH"
RUN apk add --no-cache python3 py3-pip \
    && python3 -m venv /opt/updater-venv
COPY updater/requirements.txt /app/requirements.txt
RUN pip install --no-cache-dir -r /app/requirements.txt
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose
COPY updater/app.py /app/app.py
COPY tools/deployment/switch-channel.sh /app/switch-channel.sh
COPY admin-web/docker-entrypoint.sh /usr/local/bin/componentvault-entrypoint
COPY admin-web/docker-healthcheck.sh /usr/local/bin/componentvault-healthcheck
RUN sed -i 's/\r$//' /app/switch-channel.sh /usr/local/bin/componentvault-entrypoint /usr/local/bin/componentvault-healthcheck \
    && chmod +x /usr/local/bin/componentvault-entrypoint /usr/local/bin/componentvault-healthcheck \
    && docker --version && docker compose version

WORKDIR /app
COPY admin-web/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80 8790
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD ["/usr/local/bin/componentvault-healthcheck"]
ENTRYPOINT ["/usr/local/bin/componentvault-entrypoint"]
CMD ["web"]
