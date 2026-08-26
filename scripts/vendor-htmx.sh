#!/bin/sh
# vendor-htmx.sh — fetch the pinned htmx release into resources/public/js/.
#
# Usage:  scripts/vendor-htmx.sh
#
# WHY THE FILE IS COMMITTED. ADR-0004 rejected a CDN for CSS; a third-party
# <script> is the same bet with a bigger payout for whoever wins it, so htmx is
# served from our own origin. Committing the release (rather than fetching it
# in the image, as the Tailwind binary is) means the build needs no network for
# it, the same bytes are exercised by the test suite that a container serves,
# and any change to them is a reviewable diff.
#
# WHERE THE PINS LIVE. src/books/assets.clj, and nowhere else. It is what the
# app serves and what the page links, so it is the only copy that can be right;
# this script READS it rather than repeating it. `books.assets-test` re-hashes
# the committed bytes against that same pin on every test run, locally and in
# CI, which is the standing gate — this script is only the fetch.
#
# The digest was established from the npm registry tarball for this release —
# whose own `integrity` hash was checked — and cross-checked against the CDN
# copy of the same release, 2026-08-10.
#
# LICENCE: htmx is Zero-Clause BSD (0BSD), which imposes no condition on
# redistribution. Vendoring it is a supply-chain decision, not a licence one.
#
# BUMPING: edit `htmx-version` and `htmx-sha256` in src/books/assets.clj, run
# this script, and commit the new file alongside the deleted old one. The suite
# fails until the bytes and the pin agree.
set -eu
cd "$(dirname "$0")/.."

pins="src/books/assets.clj"

# The value of a `(def <name> "<docstring>" "<value>")` in assets.clj: take the
# def's own s-expression (up to the first line that closes it) and read the line
# that is nothing but a string and a closing paren. Deliberately strict — a
# reformat that breaks this yields an EMPTY pin, which the checks below reject
# loudly rather than fetching something nobody pinned.
pin_of() {
	sed -n "/^(def $1\$/,/)\$/p" "$pins" |
		sed -n 's/^[[:space:]]*"\(.*\)")[[:space:]]*$/\1/p' | head -1
}

HTMX_VERSION=$(pin_of htmx-version)
HTMX_SHA256=$(pin_of htmx-sha256)

case "$HTMX_VERSION" in
[0-9]*.[0-9]*.[0-9]*) ;;
*)
	echo "vendor-htmx.sh: could not read htmx-version from ${pins}." >&2
	echo "  found: '${HTMX_VERSION}' — is the def still one string on its own line?" >&2
	exit 3
	;;
esac

case "$HTMX_SHA256" in
????????????????????????????????????????????????????????????????) ;;
*)
	echo "vendor-htmx.sh: could not read a 64-character htmx-sha256 from ${pins}." >&2
	echo "  found: '${HTMX_SHA256}'" >&2
	exit 3
	;;
esac

target="resources/public/js/htmx-${HTMX_VERSION}.min.js"
url="https://unpkg.com/htmx.org@${HTMX_VERSION}/dist/htmx.min.js"

# sha256sum (GNU/Linux) or shasum -a 256 (macOS) — whichever this host has.
digest_of() {
	if command -v sha256sum >/dev/null 2>&1; then
		sha256sum "$1" | cut -d' ' -f1
	else
		shasum -a 256 "$1" | cut -d' ' -f1
	fi
}

mkdir -p "$(dirname "$target")"
tmp="${target}.tmp"
trap 'rm -f "$tmp"' EXIT INT TERM HUP
curl -fsSL -o "$tmp" "$url"
found=$(digest_of "$tmp")
if [ "$found" != "$HTMX_SHA256" ]; then
	echo "vendor-htmx.sh: digest mismatch — REFUSING to vendor this download." >&2
	echo "  expected: $HTMX_SHA256  (${pins})" >&2
	echo "  found:    $found" >&2
	echo "  url:      $url" >&2
	exit 4
fi
mv "$tmp" "$target"
trap - EXIT INT TERM HUP

echo "vendor-htmx.sh: OK — $target is htmx ${HTMX_VERSION} (sha256 ${HTMX_SHA256})."
