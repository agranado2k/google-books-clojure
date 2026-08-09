#!/bin/sh
# build-css.sh — build the served stylesheet with the standalone Tailwind CLI.
#
# Usage:  scripts/build-css.sh [--watch] [tailwindcss options…]
#
# Input:  styles/app.css               (committed; declares the scanned sources)
# Output: resources/public/css/app.css (generated; gitignored; served at /css/app.css)
#
# Locally: `brew install tailwindcss`, then run this once — or pass --watch
# while working on pages: `scripts/build-css.sh --watch`.
#
# The Dockerfile's build stage runs THIS script, against a version-pinned,
# checksum-verified binary, so the local build and the shipped build are the
# same build definition rather than two invocations that drift apart.
set -eu
cd "$(dirname "$0")/.."

# ---------------------------------------------------------------------------
# The one place the expected Tailwind version lives.
#
# The Dockerfile's `ARG TAILWIND_VERSION` must equal this. It has to be stated
# twice — the Dockerfile needs it before this script exists in the image, to
# build the URL it fetches — but the two cannot drift silently: the build stage
# runs this script, so a mismatch fails the image build instead of shipping a
# stylesheet compiled by an unexpected Tailwind.
# ---------------------------------------------------------------------------
EXPECTED_TAILWIND_VERSION=4.3.3

command -v tailwindcss >/dev/null 2>&1 || {
	echo "build-css.sh: tailwindcss not found on PATH." >&2
	echo "  Install the standalone CLI: brew install tailwindcss" >&2
	exit 2
}

if [ "${SKIP_TAILWIND_VERSION_CHECK:-0}" = "1" ]; then
	echo "build-css.sh: SKIP_TAILWIND_VERSION_CHECK=1 — version check skipped." >&2
else
	# Tailwind v4's standalone CLI has no version-only flag: `--version` falls
	# through to a build and prints a whole stylesheet to stdout. `--help`
	# prints the same version banner, costs nothing, and exits 0.
	# The banner is colorized whenever the CLI decides to emit ANSI (it does so
	# on CI runners even when stdout is a pipe), which puts escape sequences
	# between "tailwindcss" and the version. Strip them before matching.
	esc=$(printf '\033')
	found=$(tailwindcss --help 2>&1 |
		sed "s/${esc}\[[0-9;]*m//g" |
		sed -n 's/.*tailwindcss v\([0-9][0-9A-Za-z.+-]*\).*/\1/p' | head -1)
	if [ "$found" != "$EXPECTED_TAILWIND_VERSION" ]; then
		echo "build-css.sh: Tailwind version mismatch — refusing to build." >&2
		echo "  expected: $EXPECTED_TAILWIND_VERSION" >&2
		echo "  found:    ${found:-<could not read a version>}  ($(command -v tailwindcss))" >&2
		echo "  The shipped stylesheet is compiled by $EXPECTED_TAILWIND_VERSION (Dockerfile" >&2
		echo "  ARG TAILWIND_VERSION); building locally with another version produces" >&2
		echo "  different CSS than production and hides the difference." >&2
		echo "  Fix the local install (brew upgrade tailwindcss), or bump both this" >&2
		echo "  script's EXPECTED_TAILWIND_VERSION and the Dockerfile ARG together." >&2
		echo "  To bypass once, deliberately: SKIP_TAILWIND_VERSION_CHECK=1 scripts/build-css.sh" >&2
		exit 3
	fi
fi

exec tailwindcss -i styles/app.css -o resources/public/css/app.css --minify "$@"
