(ns books.assets
  "The vendored front-end assets and their pins.

  htmx is served from our own origin rather than a CDN — ADR-0004 rejected a
  CDN for CSS, and a third-party script on the critical path of every page is
  the same bet with a bigger payout for whoever wins it. So the release is
  **committed to this repo**, version-pinned, and verified by digest: the pin
  below is the contract, and `books.assets-test` proves the committed bytes
  still satisfy it on every test run. `scripts/vendor-htmx.sh` is the only
  sanctioned way to change any of this.

  The URL carries the version, which is what allows the immutable cache policy
  in `books.handler` — see the 2026-08-10 amendment to ADR-0004.")

(def htmx-version
  "The pinned htmx release. `scripts/vendor-htmx.sh` carries the same value and
  refuses to run when the two disagree."
  "2.0.10")

(def htmx-sha256
  "SHA-256 of the vendored `dist/htmx.min.js` for `htmx-version`, taken from
  the npm registry tarball whose own `integrity` hash was checked, and
  cross-checked against the CDN copy of the same release."
  "71ea67185bfa8c98c39d31717c6fce5d852370fcdfd129db4543774d3145c0de")

(def htmx-resource
  "Where the vendored script lives on the classpath."
  (str "public/js/htmx-" htmx-version ".min.js"))

(def htmx-path
  "Where the vendored script is served. Version-stamped on purpose: a URL that
  can never change contents can be cached forever."
  (str "/js/htmx-" htmx-version ".min.js"))
