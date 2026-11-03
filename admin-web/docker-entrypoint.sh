#!/bin/sh
set -eu

case "${1:-web}" in
    web)
        printf '%s\n' web > /tmp/componentvault-role
        exec /docker-entrypoint.sh nginx -g 'daemon off;'
        ;;
    updater)
        printf '%s\n' updater > /tmp/componentvault-role
        exec uvicorn app:app --host 0.0.0.0 --port 8790 --workers 1
        ;;
    *)
        echo "Unknown management container role: $1 (expected web or updater)" >&2
        exit 1
        ;;
esac
