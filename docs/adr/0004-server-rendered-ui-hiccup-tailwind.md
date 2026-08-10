# ADR-0004: Render the UI on the server with Hiccup2 and build its CSS with the standalone Tailwind CLI

- **Status**: Accepted
- **Date**: 2026-08-09
- **Deciders**: Arthur Granado, with agent research
- **Supersedes / amends**: —
- **Amended**: 2026-08-10 — clauses 5 and 6 extended for the vendored htmx (see
  **Amendments** below). The decision is unchanged; its scope is stated for a
  second asset type.
- **Superseded by**: —

## Context and problem statement

The walking skeleton (ADR-0001, ADR-0002) shipped a service with one endpoint,
`/health`, and no user-facing surface at all. The next tracer bullet needs a
page a human can open — and every page after it needs the same frame, so the
choice of *how* a page gets built and *how* it gets styled is made once, here,
before there are ten pages to migrate.

Two things had to be settled together, because in practice each constrains the
other. First, the rendering approach: this is a server-side Clojure service with
no client-side state to speak of, so the question is which server-rendering
seam, not whether to render on the server. Second, the CSS pipeline: any
utility-CSS approach implies a build step, and a build step implies a toolchain
in the image — and the project has deliberately no Node toolchain, in a
digest-pinned image where an unpinned download would be the one unverified thing
in the build.

The pressure is real and current: the deploy target is a container built from a
repo-root Dockerfile with no build config on Railway's side (ADR-0002), so
whatever the styling pipeline is, it has to run inside that image.

## Decision drivers

- **No new runtime or toolchain** unless it pays for itself — a Node install in
  the image to produce one CSS file is a poor trade
- **XSS safety by default**, not by developer discipline: a templating seam
  where forgetting to escape is possible is a seam that will eventually be
  forgotten
- **Supply-chain auditability** consistent with ADR-0002: everything the image
  fetches is verified, not merely named
- **Least web-reachable surface** — serving static assets should expose exactly
  what is intended and nothing that happens to be adjacent on the classpath
- One page frame, reused, so the second page costs less than the first

## Considered options

**Rendering**

1. **Hiccup2 (`hiccup2.core`) with a shared `layout` fn** *(chosen)* — pages are
   Clojure data; `hiccup2.core/html` escapes string content and attribute values
   by default, and raw HTML must be asked for explicitly via `h/raw`.
2. **Hiccup 1 (`hiccup.core`)** — rejected: it does *not* escape by default, so
   every interpolation of user data is an XSS bug waiting for the first author
   who forgets `h/h`. Same library, opposite default; the default is the point.
3. **Selmer / Mustache-style string templates** — rejected: adds a second
   language (template syntax) and a runtime resource-loading path for no gain
   over Clojure data, and the escaping story is per-tag rather than by default.
4. **A client-side SPA (ClojureScript / JS framework)** — rejected: a build
   toolchain, a second deployable, and a hydration story for pages that are
   currently static text. Not warranted at this size.

**CSS pipeline**

1. **Tailwind standalone CLI, no Node** *(chosen)* — a single self-contained
   binary compiles `styles/app.css` into the served stylesheet.
2. **Tailwind via npm/PostCSS** — rejected: pulls a Node toolchain and a
   `node_modules` tree into the build stage of a JVM image, for the same output.
3. **Hand-written CSS, no build step** — rejected: no build step is genuinely
   simpler, but the styles then live in a file nothing scans, and every page
   accretes one-off classes. The utility approach keeps styling in the same
   file as the markup it applies to, which is where it is read.
4. **A CSS framework loaded from a CDN** — rejected: an unpinned third-party
   origin on the critical path of every page render, in a project that pins its
   base images by digest. Inconsistent with ADR-0002's whole posture.

## Decision outcome

Chosen: **Hiccup2 for rendering, standalone Tailwind CLI for CSS, static
serving scoped to the stylesheet.**

1. **Pages are Hiccup2.** `books.views/layout` is the shared page frame — head,
   header, `<main>`, footer — and every page is a function that calls it with
   its content. A page never assembles its own `<html>`.
2. **Auto-escaping is the XSS stance.** `hiccup2.core` escapes string content
   and attribute values by default; unescaped output requires an explicit
   `h/raw`, which is therefore the thing a reviewer looks for. This is the
   project's answer to output-encoding for HTML, and it is a default rather than
   a discipline on purpose.
3. **CSS is built by `scripts/build-css.sh`**, and that script is the *only*
   definition of the CSS build: the Dockerfile's build stage runs the same
   script rather than repeating the invocation.
   `styles/app.css` (committed) declares the scanned sources explicitly with
   `source(none)` + `@source "../src"`; the compiled
   `resources/public/css/app.css` is **generated, gitignored, and packaged into
   the uberjar**. `build.clj` refuses to build a jar when it is missing.
4. **The Tailwind binary is checksum-pinned per architecture.** The image fetches
   it with `ADD --checksum=sha256:…` from a URL built out of
   `ARG TAILWIND_VERSION`, with one stage per arch selected by `TARGETARCH`.
   `# syntax=docker/dockerfile:1` pins the BuildKit frontend, because
   `ADD --checksum` needs frontend ≥ 1.6 and the builder's default is a property
   of the build host, not of this repo.
5. **The static surface is `/css/` and nothing else.** The resource handler is
   rooted at `public/css` and mounted at `/css/`, so no other classpath resource
   under `public/` — a keeper file, or a dependency jar shipping its own
   `public/` assets — is web-reachable. Only `GET` and `HEAD` reach it; other
   methods fall through to the router's default handler.
6. **Stylesheet responses revalidate.** The handler sends
   `Cache-Control: public, max-age=0, must-revalidate` and
   `wrap-not-modified`, so a conditional request answers `304`.
   **This clause is coupled to clause 3's unversioned URL**: because the
   stylesheet is always served at `/css/app.css`, a cached copy can outlive the
   deploy that changed it. Correctness is chosen over bytes until a
   cache-busting scheme (content-hashed filename or query) exists — and
   introducing one is exactly the change that should revisit this clause.
7. **Explicit non-goal**: this does not decide security response headers
   (CSP, `X-Content-Type-Options`, `Referrer-Policy`, HSTS), a design system or
   component library, client-side interactivity, or asset fingerprinting.

## Consequences

- **Good**: no Node anywhere — one binary in the build stage, verified by
  checksum like the base images are verified by digest. Pages are data, so they
  are testable at the handler seam without a browser.
- **Good**: the shared `layout` means the second page costs a function; the
  scoped resource handler means new pages cannot accidentally widen the static
  surface.
- **Bad / trade-off**: the served stylesheet is generated, so a fresh clone
  cannot build a runnable jar without running `scripts/build-css.sh` first. That
  cost is paid deliberately — `build.clj` fails loudly rather than shipping an
  unstyled jar — but it is a step a newcomer must be told about (README, and the
  quick-reference row in `AGENTS.md`).
- **Bad / trade-off**: `must-revalidate` means every page view costs a
  conditional request for the stylesheet. Cheap (a `304` is a header exchange),
  but it is not free, and it is the direct price of the unversioned URL.
- **Neutral**: utility classes live inline in the Hiccup, so the markup is
  noisier to read than semantic class names would be. Shared class strings are
  named in `books.views` where the repetition was real.
- **Honest limitation**: the app sends **no security response headers yet** —
  no CSP, no `X-Content-Type-Options`, no `Referrer-Policy`. Hiccup2's escaping
  covers output encoding for the HTML it renders; it does not substitute for
  those headers, and it does nothing for content injected by any future path
  that bypasses the templating. The decision to add them is deferred to the
  authentication ticket, where there will be a session cookie to protect and the
  header set can be chosen once against a real threat model rather than twice.
- **Honest limitation**: the *local* Tailwind binary (installed via Homebrew) is
  **version-checked, not checksum-pinned** — `scripts/build-css.sh` asserts the
  version and refuses to run on a mismatch, but it trusts Homebrew for the bytes.
  Only the binary the image fetches is checksum-verified. Since the generated
  stylesheet is what the image builds, not what a developer commits, the blast
  radius of a bad local binary is local — but it is not zero.
- **Honest limitation**: checksum pinning protects against a changed artifact at
  a URL, not against a release that was malicious when it was published — the
  same boundary ADR-0002 records for digest pinning.

## Amendments

### 2026-08-10 — a second scoped static root, for a vendored htmx

The search slice (ticket #5) needs client-side interactivity for the first
time: a form that swaps a results fragment in place. htmx is how that is done
here, which raises two questions this record already answers in spirit and now
answers in letter. **This extends clauses 4, 5 and 6; it reverses nothing.**

1. **htmx is vendored, not loaded from a CDN.** Considered option 4 under "CSS
   pipeline" rejected a CDN for the stylesheet; a third-party `<script>` is the
   same bet with a larger payout for whoever wins it, so the same answer holds.
   The release is **committed** to the repo at
   `resources/public/js/htmx-<version>.min.js`.
2. **It is pinned the way clause 4 pins Tailwind, by a different mechanism for
   a different reason.** The Tailwind binary is fetched at image-build time and
   so must be verified with `ADD --checksum`; htmx is *content we serve*, so
   committing it is strictly stronger — the bytes are a reviewable diff, the
   build needs no network for them, and the bytes the suite exercises are the
   bytes a container serves. The version and SHA-256 live in `books.assets`,
   `test/books/assets_test.clj` re-hashes the committed file on **every test
   run** (local and CI), and `scripts/vendor-htmx.sh` is the only sanctioned
   way to fetch or re-verify it — it refuses to run if its own pins and
   `books.assets` disagree. The digest was established from the npm registry
   tarball for the release, whose own `integrity` hash was checked, and
   cross-checked against the CDN copy of that release.
3. **The static surface becomes exactly two named roots, not one wide one.**
   Clause 5's rule is preserved as written: `/js/` is served by a *second*
   handler rooted at `public/js`, never by re-rooting the existing one at
   `public` or at `/`. The regression test that pins clause 5 now covers both
   roots, including their `..` traversals. A `static-root` helper builds both,
   so a third root is a data literal rather than a new copy of the chain — and
   is still a deliberate, reviewable act.
4. **Version-stamped URLs are cached forever, and that is clause 6 read
   correctly.** Clause 6 couples `must-revalidate` to the stylesheet's
   *unversioned* URL. The script's URL carries its version, so the bytes behind
   it can never change and it is served
   `Cache-Control: public, max-age=31536000, immutable`. Two policies now
   exist, each derived from whether its URL is versioned — which is the rule
   clause 6 was already stating. The stylesheet is untouched.
5. **Still not decided here**: security response headers (clause 7's non-goal
   is unchanged, and a CSP would now have a `script-src 'self'` story worth
   writing when that ticket comes), asset fingerprinting for the *stylesheet*,
   and any client-side framework beyond htmx's attribute vocabulary.

**Consequence, stated honestly**: the repo now carries 50 KB of minified
third-party JavaScript, which no reviewer will read. The pin plus the
re-hashing test is what makes that a *known* 50 KB rather than a trusted one;
it does not make the release itself trustworthy — the same boundary clause
"checksum pinning protects against a changed artifact at a URL, not against a
release that was malicious when published" already records for Tailwind.

## More information

- Implemented in: PR for `feat/tailwind-layout` (landing page + CSS pipeline),
  hardened per its review
- Related: ADR-0001 (the Ring/Reitit/Jetty composition this handler plugs into),
  ADR-0002 (the image this build stage lives in, amended 2026-08-09 to cover
  fetched build tools)
- Hiccup 2 escaping behaviour verified against the `hiccup2.core` docstring and
  the library's own migration notes, 2026-08-09
