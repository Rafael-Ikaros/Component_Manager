#!/bin/sh
set -eu

case "$(cat /tmp/componentvault-role)" in
    web) url=http://127.0.0.1/ ;;
    updater) url=http://127.0.0.1:8790/health ;;
    *) exit 1 ;;
esac

exec python -c 'import sys, urllib.request; urllib.request.urlopen(sys.argv[1], timeout=3).close()' "$url"
