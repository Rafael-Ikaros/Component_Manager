#!/bin/sh
# Switch an existing official Docker Hub deployment without changing its data.
set -eu
set -f
umask 077

usage() {
    cat <<'EOF'
Usage: sh switch-channel.sh --project-name NAME [--channel stable|dev] [--dry-run]

Switch the existing Compose project's API and deployed Web console together.
  --project-name NAME   Existing Compose project name (required).
  --channel stable|dev  Stable uses latest/web-latest; dev uses dev/web-dev.
                        When omitted, an interactive terminal offers a menu.
  --dry-run             Show the plan without changing files or containers.
  -h, --help            Show this help.

The original Compose files and working directory are read from the API
container's labels. Keep those files and its .env at their original location.
Only the three image/channel settings in .env are changed, with a backup.
Images are pulled before .env or running containers are changed. No volumes
are removed. A deployed API/Web pair must have matching release labels.
Run the same command again to retry an interrupted switch.
EOF
}

fail() { printf '%s\n' "Error: $*" >&2; exit 1; }
project=
channel=
dry_run=false
while [ "$#" -gt 0 ]; do
    case "$1" in
        --project-name|--channel)
            [ "$#" -ge 2 ] || fail "$1 requires a value; see --help."
            case "$1" in
                --project-name) project=$2 ;;
                --channel) channel=$2 ;;
            esac
            shift 2
            ;;
        --dry-run) dry_run=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) fail "Unknown option: $1; see --help." ;;
    esac
done
[ -n "$project" ] || fail '--project-name must name the existing deployment; see --help.'
if [ -z "$channel" ]; then
    [ -t 0 ] || fail 'Non-interactive use requires --channel stable or --channel dev.'
    printf '1) Stable (latest)\n2) Development (dev)\nSelect channel [1/2]: ' >&2
    read -r selection || fail 'Channel selection cancelled.'
    case "$selection" in
        1|stable) channel=stable ;;
        2|dev) channel=dev ;;
        *) fail 'Select 1 (stable) or 2 (dev), or use --channel.' ;;
    esac
fi
case "$channel" in
    stable) image_channel=latest ;;
    dev) image_channel=dev ;;
    *) fail '--channel must be stable or dev.' ;;
esac
command -v docker >/dev/null 2>&1 || fail 'Docker CLI is required on the deployment host.'
docker compose version >/dev/null || fail 'Docker Compose v2 is required (docker compose).'

api_ids=$(docker ps -aq --filter "label=com.docker.compose.project=$project" \
    --filter 'label=com.docker.compose.service=api')
[ -n "$api_ids" ] || fail "No existing API container found for project $project. Check docker compose ls."
set -- $api_ids
[ "$#" -eq 1 ] || fail "Multiple API containers found for $project; resolve the duplicate deployment first."
api_id=$1
working_dir=$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.working_dir" }}' "$api_id")
config_files=$(docker inspect --format '{{ index .Config.Labels "com.docker.compose.project.config_files" }}' "$api_id")
[ -d "$working_dir" ] || fail 'The original Compose working directory is unavailable on this host.'
[ -n "$config_files" ] && [ "$config_files" != '<no value>' ] || fail 'The API container has no original Compose file labels.'
env_file=$working_dir/.env
[ -f "$env_file" ] || fail "Original .env is missing: $env_file. Restore it before switching."

official_repository=rafaelikaros/component_manager
verify_official_image() {
    current_image=$(docker inspect --format '{{.Config.Image}}' "$1")
    case "$current_image" in
        "$official_repository":*|"$official_repository"@*) ;;
        *) fail "This helper supports official $official_repository images only. Handle custom image deployments manually." ;;
    esac
}
verify_official_image "$api_id"
web_ids=$(docker ps -aq --filter "label=com.docker.compose.project=$project" \
    --filter 'label=com.docker.compose.service=admin-web')
has_web=false
if [ -n "$web_ids" ]; then
    set -- $web_ids
    [ "$#" -eq 1 ] || fail "Multiple Web containers found for $project; resolve the duplicate deployment first."
    verify_official_image "$1"
    has_web=true
fi

# Preserve every original Compose override, including Watchtower labels.
set -- docker compose --project-name "$project" --project-directory "$working_dir"
old_ifs=$IFS
IFS=,
for compose_file in $config_files; do
    [ -f "$compose_file" ] || fail "Original Compose file is missing: $compose_file"
    set -- "$@" -f "$compose_file"
    printf 'Compose file: %s\n' "$compose_file" >&2
done
IFS=$old_ifs
if [ "$has_web" = true ]; then set -- "$@" --profile web; fi

api_image=$official_repository:$image_channel
web_image=$official_repository:web-$image_channel
printf 'Project: %s\nEnvironment: %s\nChannel: %s\nAPI image: %s\n' "$project" "$env_file" "$channel" "$api_image" >&2
if [ "$has_web" = true ]; then
    printf 'Web image: %s\nServices: api admin-web\n' "$web_image" >&2
else
    printf 'Services: api (Web is not deployed)\n' >&2
fi
printf 'Keep original Compose files, ports and volumes; pull images, back up .env, then recreate only these services.\n' >&2
if [ "$dry_run" = true ]; then
    printf 'Dry run: no files, images or containers changed.\n' >&2
    exit 0
fi

candidate=$(mktemp "$working_dir/.env.channel.XXXXXX")
cleanup() { if [ -n "$candidate" ]; then rm -f "$candidate"; fi; }
trap cleanup 0
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM
# Keep all other settings (including account keys and comments) untouched.
# Write full images as well so older Hub Compose files can use this helper.
awk -v channel="$image_channel" -v api="$api_image" -v web="$web_image" '
    /^[[:space:]]*(export[[:space:]]+)?COMPONENT_VAULT_(DOCKER_CHANNEL|IMAGE|WEB_IMAGE)[[:space:]]*=/ { next }
    { print }
    END {
        print "COMPONENT_VAULT_DOCKER_CHANNEL=" channel
        print "COMPONENT_VAULT_IMAGE=" api
        print "COMPONENT_VAULT_WEB_IMAGE=" web
    }
' "$env_file" > "$candidate"
# Compose gives process environment precedence over --env-file.
unset COMPONENT_VAULT_DOCKER_CHANNEL COMPONENT_VAULT_IMAGE COMPONENT_VAULT_WEB_IMAGE
if [ "$has_web" = true ]; then
    "$@" --env-file "$candidate" pull api admin-web || fail 'Image pull failed; .env and containers were not changed. Retry the same command.'
else
    "$@" --env-file "$candidate" pull api || fail 'Image pull failed; .env and containers were not changed. Retry the same command.'
fi
if [ "$has_web" = true ]; then
    image_identity() {
        version=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' "$1")
        revision=$(docker image inspect --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}' "$1")
        case "$version" in
            ''|'<no value>') fail "Image $1 has no release version label. Republish a matching API/Web pair before switching; .env and containers were not changed." ;;
        esac
        case "$revision" in
            ''|'<no value>') fail "Image $1 has no release revision label. Republish a matching API/Web pair before switching; .env and containers were not changed." ;;
        esac
        printf '%s %s\n' "$version" "$revision"
    }
    api_identity=$(image_identity "$api_image")
    web_identity=$(image_identity "$web_image")
    [ "$api_identity" = "$web_identity" ] || fail 'API/Web image versions or revisions differ. Publishing may still be in progress; retry after it completes, or deploy matching fixed versions. .env and containers were not changed.'
fi
backup=$env_file.before-channel-$(date +%Y%m%dT%H%M%S).$$
cp -p "$env_file" "$backup" || fail 'Could not back up .env; containers were not changed.'
mv "$candidate" "$env_file"
if [ "$has_web" = true ]; then
    "$@" --env-file "$env_file" up -d --no-deps --no-build --pull never api admin-web || fail "Service recreation failed; retry the same command. Previous .env backup: $backup"
else
    "$@" --env-file "$env_file" up -d --no-deps --no-build --pull never api || fail "Service recreation failed; retry the same command. Previous .env backup: $backup"
fi
printf 'Channel switched to %s. Previous .env backup: %s\n' "$channel" "$backup" >&2
