#!/bin/sh
set -eu

# Run from ~/app (repository root layout). Never copy the production .env from Git.
# CD passes IMAGE_REGISTRY/IMAGE_TAG as environment variables; they override infra/.env.prod.
if [ ! -f infra/.env.prod ]; then
  echo 'Create infra/.env.prod on the host before enabling CD.' >&2
  exit 1
fi

profiles=''
if [ "${ENABLE_CLOUDFLARE:-false}" = 'true' ]; then
  profiles='cloudflare'
fi
# PT-110: FastAPI on this host (old layout). Default: the AI runs on the PC node at FAST_API_BASE_URL.
if [ "${ENABLE_EC2_AI:-false}" = 'true' ]; then
  profiles="${profiles:+$profiles,}ec2-ai"
fi
if [ -n "$profiles" ]; then
  export COMPOSE_PROFILES="$profiles"
fi

compose() {
  docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml "$@"
}

compose config --quiet

# Value from the environment, else the last KEY= line of infra/.env.prod (quotes and CR removed).
# Used only for the backup name and the final message; Compose resolves the images itself.
setting() {
  eval "current=\${$1:-}"
  if [ -z "$current" ]; then
    current=$(sed -n "s/^$1=//p" infra/.env.prod | tail -n 1 | tr -d '\r"'"'")
  fi
  printf '%s' "${current:-unknown}"
}
registry=$(setting IMAGE_REGISTRY)
image_tag=$(setting IMAGE_TAG)

# Private GHCR packages: CD passes the job's short-lived GITHUB_TOKEN. Log out when the
# script ends (also on HUP/INT/TERM) so no registry credential is left on the host.
if [ -n "${GHCR_TOKEN:-}" ]; then
  trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
  trap 'exit 1' HUP INT TERM
  printf '%s' "$GHCR_TOKEN" | docker login ghcr.io -u "${GHCR_USER:?Set GHCR_USER}" --password-stdin
fi
compose pull

# Back up the database before new Flyway migrations run, on every deploy. A stopped DB
# container is started as it is (--no-recreate keeps its current image), so the data volume is
# never migrated without a dump; a missing container is created (an empty volume only initialises).
compose up -d --no-deps --no-recreate --wait --wait-timeout 120 tutipal-db
db=$(compose ps -q tutipal-db)
if [ -z "$db" ]; then
  echo 'Database container is not running; deployment stopped before the backup.' >&2
  exit 1
fi
# Dumps hold production data: owner-only directory and files.
umask 077
mkdir -p "$HOME/backups"
chmod 700 "$HOME/backups"
# Keep the file name valid even for tags such as feature/x.
tag=$(printf '%s' "$image_tag" | tr -c 'A-Za-z0-9._-' '_' | cut -c1-12)
dump="$HOME/backups/tutipal-$(date +%Y%m%d-%H%M%S)-pre-$tag.dump"
if ! docker exec "$db" sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc' > "$dump"; then
  rm -f "$dump"
  echo 'Database backup failed; deployment stopped before the application containers changed.' >&2
  exit 1
fi
ls -1t "$HOME"/backups/tutipal-*.dump | tail -n +11 | xargs -r rm -f
echo "Database backup: $dump"

compose up -d --remove-orphans --wait --wait-timeout 180

# Remove unused images older than 7 days so repeated deploys do not fill the disk.
docker image prune -af --filter 'until=168h' >/dev/null || true

echo "Deployed $registry tag $image_tag."
