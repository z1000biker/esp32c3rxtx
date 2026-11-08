#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
output=$(realpath -m "${1:-$root/dist/ESP32-C6-Workbench-Windows-x64.exe}")
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$(dirname "$output")"
cp "$root/windows/launcher.go" "$work"
cp -r "$root/web" "$work"
cd "$work"
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H=windowsgui" -o "$output" launcher.go
