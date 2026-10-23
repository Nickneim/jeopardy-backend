#!/bin/bash
# Starts the backend and Postgres. Run by jeopardy.service at every boot.
set -euo pipefail

region=us-east-2
registry=241200392621.dkr.ecr.us-east-2.amazonaws.com

# The database password from Parameter Store, into files under /run, which
# lives in memory and is gone after a reboot. Only root can read them.
password=$(aws ssm get-parameter --region "$region" --name /jeopardy/db-password \
  --with-decryption --query Parameter.Value --output text)
install -d -m 700 /run/jeopardy
umask 077
printf 'POSTGRES_PASSWORD=%s\n' "$password" > /run/jeopardy/postgres.env
printf 'DATABASE_PASSWORD=%s\n' "$password" > /run/jeopardy/backend.env

# Which backend image to run, set by deploy-backend.ps1.
tag=$(aws ssm get-parameter --region "$region" --name /jeopardy/backend-tag \
  --query Parameter.Value --output text)
printf 'BACKEND_TAG=%s\n' "$tag" > /run/jeopardy/backend-tag.env

# A 12-hour ECR token, from the instance role.
aws ecr get-login-password --region "$region" \
  | docker login --username AWS --password-stdin "$registry"

docker compose --project-directory /opt/jeopardy --env-file /opt/jeopardy/app.env \
  --env-file /run/jeopardy/backend-tag.env up -d --remove-orphans
