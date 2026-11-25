#!/bin/sh
set -eu

usage() {
    cat <<'EOF'
Usage: componentvault-entrypoint [api|web|updater] [arguments...]

Start one service per container. With no command, start the API.
  api       Inventory and sync API on port 8787; persist /data.
  web       Web management console on port 80.
  updater   Optional Compose update worker on port 8790.

Additional arguments are passed to the selected service.
Existing uvicorn and nginx commands retain their original arguments.
EOF
}

role=${1:-api}
case "$role" in
    api)
        if [ "$#" -gt 0 ]; then shift; fi
        set -- uvicorn app.main:app --host 0.0.0.0 --port 8787 "$@"
        ;;
    uvicorn|/usr/local/bin/uvicorn)
        role=api
        ;;
    web)
        shift
        set -- nginx -g 'daemon off;' "$@"
        ;;
    nginx|/usr/sbin/nginx)
        role=web
        ;;
    updater)
        shift
        set -- uvicorn app:app --app-dir /app/updater --host 0.0.0.0 --port 8790 --workers 1 "$@"
        ;;
    -h|--help|help)
        usage
        exit 0
        ;;
    *)
        echo "Unknown container role: $role (expected api, web, or updater; see --help)" >&2
        exit 1
        ;;
esac

printf '%s\n' "$role" > /tmp/componentvault-role
exec "$@"
