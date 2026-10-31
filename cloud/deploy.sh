#!/usr/bin/env bash
# Deploys the committed cloud/ code (git HEAD, not the working tree) to the release directory the
# slopmatica-cloud user service runs from, then restarts it. Uncommitted edits never go live:
# templates are read from disk on every request, so serving the working tree leaked half-done pages.
set -euo pipefail
repo="$(cd "$(dirname "$0")/.." && pwd)"
release="$HOME/.local/share/slopmatica-cloud/release"
rm -rf "$release.new"
mkdir -p "$release.new"
git -C "$repo" archive HEAD cloud | tar -x -C "$release.new"
rm -rf "$release.old"
[ -d "$release" ] && mv "$release" "$release.old"
mv "$release.new" "$release"
systemctl --user restart slopmatica-cloud
sleep 2
systemctl --user is-active slopmatica-cloud
echo "deployed $(git -C "$repo" rev-parse --short HEAD) to $release"
