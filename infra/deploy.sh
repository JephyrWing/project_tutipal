#!/bin/sh
set -eu

# Run from ~/app (repository root layout). Never copy the production .env from Git.
if [ ! -f infra/.env.prod ]; then
  echo 'Create infra/.env.prod on the host before enabling CD.' >&2
  exit 1
fi

if [ "${ENABLE_CLOUDFLARE:-false}" = 'true' ]; then
  export COMPOSE_PROFILES=cloudflare
fi

# Host user must be able to run Docker and log in to private Docker Hub repos.
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml config --quiet
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml pull
docker compose --env-file infra/.env.prod -f infra/docker-compose.prod.yml up -d --remove-orphans --wait --wait-timeout 120

echo 'Server containers started. Business features still require implementation.'
