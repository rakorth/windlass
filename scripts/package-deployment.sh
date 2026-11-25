#!/bin/sh
# Build a shareable bundle from an explicit file list; never include local data.
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
output=${1:-"$repo_dir/target/windlass-deployment.tar.gz"}
staging=$(mktemp -d)
trap 'rm -rf "$staging"' EXIT HUP INT TERM
bundle="$staging/windlass-deployment"
mkdir -p "$bundle"

cp "$repo_dir/deploy/README.md" "$bundle/README.md"
cp "$repo_dir/.env.example" "$bundle/.env.example"

mkdir -p "$(dirname -- "$output")"
tar -czf "$output" -C "$staging" windlass-deployment
printf 'Deployment bundle: %s\n' "$output"
