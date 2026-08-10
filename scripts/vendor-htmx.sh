#!/bin/sh
# vendor-htmx.sh — fetch or verify the vendored htmx release.
#
# Usage:  scripts/vendor-htmx.sh            fetch the pinned release and verify it
#         scripts/vendor-htmx.sh --verify   verify what is already committed
#
# WHY THE FILE IS COMMITTED. ADR-0004 rejected a CDN for CSS; a third-party
# <script> is the same bet with a bigger payout for whoever wins it, so htmx is
# served from our own origin. Committing the release (rather than fetching it
# in the image, as the Tailwind binary is) means the build needs no network for
# it, the same bytes are exercised by the test suite that a container serves,
# and any change to them is a reviewable diff.
#
# WHAT VERIFIES IT. Three places hold the same two facts — the version and the
# digest — and none of them can drift silently:
#
#   * this script (the fetch, and `--verify`);
#   * src/books/assets.clj (what the app serves and what the page links);
#   * test/books/assets_test.clj, which hashes the committed bytes on EVERY
#     test run, locally and in CI. That test is the real gate; this script
#     checks itself against assets.clj so a bump cannot update only one of them.
#
# The digest was established from the npm registry tarball for this release —
# whose own `integrity` hash was checked — and cross-checked against the CDN
# copy of the same release, 2026-08-10.
#
# LICENCE: htmx is Zero-Clause BSD (0BSD), which imposes no condition on
# redistribution. Vendoring it is a supply-chain decision, not a licence one.
#
# BUMPING: change HTMX_VERSION here, run the script, then update
# `htmx-version` and `htmx-sha256` in src/books/assets.clj to match what it
# reports. The suite fails until they agree.
set -eu
cd "$(dirname "$0")/.."

HTMX_VERSION=2.0.10
HTMX_SHA256=71ea67185bfa8c98c39d31717c6fce5d852370fcdfd129db4543774d3145c0de

pins="src/books/assets.clj"
target="resources/public/js/htmx-${HTMX_VERSION}.min.js"
url="https://unpkg.com/htmx.org@${HTMX_VERSION}/dist/htmx.min.js"

# The pins in assets.clj are what the app actually serves; a bump that edits
# only this script would otherwise pass unnoticed until the suite ran.
grep -q "\"${HTMX_VERSION}\"" "$pins" || {
	echo "vendor-htmx.sh: ${pins} does not pin htmx ${HTMX_VERSION}." >&2
	echo "  Update htmx-version there, or fix HTMX_VERSION here." >&2
	exit 3
}
grep -q "${HTMX_SHA256}" "$pins" || {
	echo "vendor-htmx.sh: ${pins} does not pin digest ${HTMX_SHA256}." >&2
	echo "  Update htmx-sha256 there, or fix HTMX_SHA256 here." >&2
	exit 3
}

# sha256sum (GNU/Linux) or shasum -a 256 (macOS) — whichever this host has.
digest_of() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | cut -d' ' -f1
	else
		shasum -a 256 "$1" | cut -d' ' -f1
	fi
}

if [ "${1:-}" = "--verify" ]; then
	[ -f "$target" ] || {
		echo "vendor-htmx.sh: $target is missing — run scripts/vendor-htmx.sh" >&2
		exit 1
	}
else
	mkdir -p "$(dirname "$target")"
	tmp="${target}.tmp"
	trap 'rm -f "$tmp"' EXIT INT TERM HUP
	curl -fsSL -o "$tmp" "$url"
	found=$(digest_of "$tmp")
	if [ "$found" != "$HTMX_SHA256" ]; then
		echo "vendor-htmx.sh: digest mismatch — REFUSING to vendor this download." >&2
		echo "  expected: $HTMX_SHA256" >&2
		echo "  found:    $found" >&2
		echo "  url:      $url" >&2
		exit 4
	fi
	mv "$tmp" "$target"
	trap - EXIT INT TERM HUP
fi

found=$(digest_of "$target")
if [ "$found" != "$HTMX_SHA256" ]; then
	echo "vendor-htmx.sh: $target does not match its pin." >&2
	echo "  expected: $HTMX_SHA256" >&2
	echo "  found:    $found" >&2
	exit 4
fi

echo "vendor-htmx.sh: OK — $target is htmx ${HTMX_VERSION} (sha256 ${HTMX_SHA256})."
