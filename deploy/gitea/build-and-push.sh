#!/usr/bin/env bash
# Trusted Gitea host-runner only. Publishes images; never modifies live containers.
set -Eeuo pipefail
umask 077

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
for name in ARCHIVE_REGISTRY_HOST ARCHIVE_IMAGE_OWNER ARCHIVE_REGISTRY_USERNAME ARCHIVE_REGISTRY_TOKEN; do
  [[ -n "${!name:-}" ]] || fail "Missing required Actions variable/secret: $name"
done
command -v docker >/dev/null || fail "Docker CLI missing on dedicated build runner"
command -v git >/dev/null || fail "git missing on dedicated build runner"
docker info >/dev/null 2>&1 || fail "Docker Engine is not reachable from the build runner"

# Registry hostname only; do not allow slashes or schemes in docker login.
[[ "$ARCHIVE_REGISTRY_HOST" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*(:[0-9]{1,5})?$ ]] ||
  fail "ARCHIVE_REGISTRY_HOST must be a registry host or host:port, without https://"
[[ "$ARCHIVE_IMAGE_OWNER" =~ ^[a-z0-9][a-z0-9._-]*$ ]] ||
  fail "ARCHIVE_IMAGE_OWNER must be the lowercase Gitea package owner"
[[ -d frontend && -d backend/Archive/Archive ]] || fail "Run at repository root"

# Full commit identity is the immutable image tag, with no mutable latest tag.
COMMIT_SHA="$(git rev-parse --verify HEAD)"
[[ "$COMMIT_SHA" =~ ^[a-f0-9]{40}$ ]] || fail "Cannot determine a valid Git commit"
TAG="sha-${COMMIT_SHA}"
REGISTRY="${ARCHIVE_REGISTRY_HOST}"
BACKEND_IMAGE="${REGISTRY}/${ARCHIVE_IMAGE_OWNER}/central-archive-backend:${TAG}"
FRONTEND_IMAGE="${REGISTRY}/${ARCHIVE_IMAGE_OWNER}/central-archive-frontend:${TAG}"

# A fresh per-job credential store prevents changing the runner's normal Docker config.
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_CONFIG
cleanup() { docker logout "$REGISTRY" >/dev/null 2>&1 || true; rm -rf -- "$DOCKER_CONFIG"; }
trap cleanup EXIT
printf '%s' "$ARCHIVE_REGISTRY_TOKEN" | docker login "$REGISTRY" \
  --username "$ARCHIVE_REGISTRY_USERNAME" --password-stdin >/dev/null ||
  fail "Registry login failed; verify token permissions and Docker host TLS trust"
unset ARCHIVE_REGISTRY_TOKEN

printf 'Building backend for %s\n' "$COMMIT_SHA"
docker build --tag "$BACKEND_IMAGE" ./backend/Archive/Archive
printf 'Building frontend for %s\n' "$COMMIT_SHA"
docker build --tag "$FRONTEND_IMAGE" ./frontend

# If a push fails, do not initiate deployment; any already pushed image remains
# harmless because Portainer uses explicitly pinned image tags.
docker push "$BACKEND_IMAGE"
docker push "$FRONTEND_IMAGE"

printf '\nPUBLISHED (no deployment performed):\n'
printf 'ARCHIVE_BACKEND_IMAGE=%s\n' "$BACKEND_IMAGE"
printf 'ARCHIVE_FRONTEND_IMAGE=%s\n' "$FRONTEND_IMAGE"
printf 'Commit: %s\n' "$COMMIT_SHA"
