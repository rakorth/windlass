#!/bin/sh
# Build a shareable bundle from an explicit file list; never include local data.
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:-"$repo_dir/target/windlass-deployment.tar.gz"}
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
bundle="$staging/windlass-deployment"
mkdir -p "$bundle/docker/n8n" "$bundle/docker/n8n-backup"

cp "$repo_dir/compose.deploy.yaml" "$bundle/compose.yaml"
cp "$repo_dir/deploy/README.md" "$bundle/README.md"
cp "$repo_dir/deploy/.env.example" "$bundle/.env.example"
cp "$repo_dir/docs/n8n-backups.md" "$bundle/n8n-backups.md"
cp "$repo_dir/docker/n8n/Dockerfile" "$repo_dir/docker/n8n/process.mjs" \
    "$bundle/docker/n8n/"
cp "$repo_dir/docker/n8n-backup/Dockerfile" \
    "$repo_dir/docker/n8n-backup/.dockerignore" \
    "$repo_dir/docker/n8n-backup/manager.py" \
    "$repo_dir/docker/n8n-backup/test_manager.py" "$bundle/docker/n8n-backup/"

mkdir -p "$(dirname -- "$output")"
tar -czf "$output" -C "$staging" windlass-deployment
printf 'Deployment bundle: %s\n' "$output"
