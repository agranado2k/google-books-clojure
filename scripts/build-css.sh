#!/bin/sh
# Build the Tailwind stylesheet with the standalone Tailwind CLI (no Node).
#
# Input:  styles/app.css            (committed; declares the scanned sources)
# Output: resources/public/css/app.css  (generated; gitignored; served at /css/app.css)
#
# Locally: `brew install tailwindcss`, then run this once — or pass --watch
# while working on pages: `scripts/build-css.sh --watch`.
# The Dockerfile build stage runs the same build with a version-pinned,
# checksum-verified binary.
set -eu
cd "$(dirname "$0")/.."

command -v tailwindcss >/dev/null 2>&1 || {
	echo "build-css.sh: tailwindcss not found on PATH." >&2
	echo "  Install the standalone CLI: brew install tailwindcss" >&2
	exit 2
}

exec tailwindcss -i styles/app.css -o resources/public/css/app.css --minify "$@"
