# ADR-0005: Sign readers in with Clerk — a third-party script on every page, and the security headers that bound it

- **Status**: Accepted
- **Date**: 2026-08-27
- **Deciders**: Arthur Granado, with agent research
- **Supersedes / amends**: takes up the explicit non-goal in ADR-0004 clause 7
  (and its 2026-08-10 amendment, clause 5), which deferred security response
  headers "to the authentication ticket". This is that ticket. ADR-0004 is not
  reversed: its no-CDN stance is **narrowed**, once, for one script, on the
  reasoning below.
- **Superseded by**: —

## Context and problem statement

Ticket #7 asks for the first thing in this app that knows who you are: readers
sign in with Google, and pages behind the gate answer nobody else. Two
decisions have to be made together, because each constrains the other.

**First, how a session is proved on the server.** Clerk publishes no Clojure
SDK. Its documented answer for a backend it does not ship one for is *manual
JWT verification*: fetch the instance's JWKS, check the RS256 signature, and
validate the claims yourself. That is a decision about a dependency and about
which claims are load-bearing, and it is recorded here because getting it
wrong is a breach rather than a bug.

**Second, what the browser loads.** Clerk's sign-in UI is ClerkJS, and ClerkJS
is a script served by Clerk. ADR-0004 rejected a CDN for the stylesheet, and
its 2026-08-10 amendment vendored htmx with a committed file and a digest pin
rather than loading it from one. A third-party `<script>` on every page is
exactly what that amendment refused. So either this ticket reverses that
posture, or it states honestly why this one script is different — and either
way, a script from another origin is precisely the situation the security
response headers ADR-0004 deferred exist to bound. The two questions are one
question.

## Decision drivers

- **The gate must fail closed.** Every configuration mistake, every unreachable
  dependency, every unreadable token has to end in "signed out", never in
  "signed in" and never in a 500 that a retry turns into access.
- **The verification path the tests exercise must be the path production
  runs.** A gate proved by a stub is not proved.
- **Supply-chain honesty, consistent with ADR-0002 and ADR-0004**: what this
  repo serves is either pinned or explicitly, visibly not pinned.
- **A security header set chosen once, against a real threat model** — which is
  what ADR-0004 was waiting for, and what now exists: a session credential in a
  cookie and a third-party script on every page.
- **No new runtime, no Node, no second deployable** (ADR-0004's driver, still
  binding).

## Considered options

### Verifying the session token

1. **buddy-sign + buddy-core, with our own JWKS fetch and cache** *(chosen)* —
   buddy owns the RS256 check; this repo owns the key cache, the rotation
   policy and the claim checks buddy does not make.
2. **`com.github.sikt-no/clj-jwt`** — rejected, though it is real, maintained,
   and does exactly the JWKS-fetch-and-cache job we then wrote ourselves. Three
   reasons. It is a thin wrapper over the same `buddy-sign` we would depend on
   anyway, so it removes no crypto from our hands; it does not check `azp`, the
   one claim whose absence is a security hole rather than a missing nicety, so
   that code gets written here regardless; and its verification entry point
   takes a JWKS **URL** and fetches it, which makes "exercise the real
   verification path with a test keypair" need a local HTTP server, where an
   injected fetch function needs nothing. It also brings `data.json`,
   `tools.logging` and a URI library into a tree that already has `jsonista`.
3. **`CLERK_JWT_KEY` (a PEM public key in an environment variable)** — Clerk
   documents this "networkless" path, and it is genuinely simpler. Rejected as
   the primary mechanism because it pins one key: a rotation becomes an
   operator action under time pressure, on a service whose readers are all
   signed out until it happens. JWKS plus refetch-on-unknown-key makes rotation
   a non-event.
4. **Clerk's Backend API** (`/v1/sessions/…/verify`) — rejected: a network
   round trip to a vendor on every gated request, a secret key to hold, and a
   hard dependency on Clerk's availability for pages that do not otherwise need
   it. Manual verification needs the JWKS once and then nothing.

### Loading ClerkJS

1. **Load ClerkJS from the instance's own Frontend API host** *(chosen)*.
2. **Vendor it, as htmx is vendored** — rejected, and this is the option worth
   arguing rather than dismissing, because it is what ADR-0004 would say.
   ClerkJS is not a static widget: it holds the session, refreshes a
   sixty-second token, and runs the bot and fraud checks Clerk's own service
   depends on. A pinned copy is a copy that does not receive the fix for a
   vulnerability in a security component, and the pin is what stops it. htmx
   can be vendored precisely because it is inert — an attribute vocabulary and
   an XHR — and the trade that makes a stale htmx acceptable makes a stale
   sign-in library unacceptable. Clerk does not support a self-hosted
   `clerk.browser.js`, and running one anyway means running a version its
   Frontend API has stopped expecting.
3. **Load it from a public CDN** (`jsdelivr`, `unpkg`) — rejected outright.
   That is strictly worse than option 1 on every axis: a *third* origin with no
   relationship to our data, and one whose compromise reaches every Clerk
   customer at once. Clerk's own documentation serves the script from the
   instance host, which is the same origin the session tokens come from — so
   trusting it adds no party we were not already trusting completely.
4. **Build our own sign-in UI against Clerk's API** — rejected: a
   password/OAuth/MFA surface written here is a larger security liability than
   the script, and it is the thing this ticket was buying a vendor to avoid.

## Decision outcome

Chosen: **manual JWT verification with buddy, ClerkJS loaded from the
instance's own Frontend API host, and a security header set that bounds it.**

1. **`books.reader` is the Session check port.** A plain function of the
   session token, answering an outcome map and never throwing. Its default,
   `not-configured`, **refuses everything**. This is the fail-closed posture
   and it is the opposite of `DB_OPTIONAL` and of an absent
   `GOOGLE_BOOKS_API_KEY`: a missing database fails a health check, a missing
   Books key renders a message, and a missing Clerk configuration must never
   become an open gate. There is deliberately no option that turns the gate
   off.

2. **`books.clerk` is the adapter, and it checks five things.** buddy-sign
   verifies the RS256 signature (and so refuses both classic forgeries:
   `alg: none`, and an HS256 token offered to an RS256 verifier) and validates
   `exp` and `nbf`. This repo additionally requires that `exp` is **present** —
   buddy accepts a signed token that omits it, and a credential with no
   deadline is the last thing to accept on trust — that `sub` is a non-blank
   string, since the Reader *is* that claim, and that `azp` equals this app's
   own origin. `azp` is the one Clerk's guidance calls out: a token minted for
   another origin carries a perfectly valid signature, and accepting it is a
   CSRF hole rather than a missing nicety.

3. **The instance is named once.** The JWKS endpoint and the browser's script
   URL are both **derived from the publishable key**, which encodes the
   instance's Frontend API host. Two configured values could name two
   instances, and a browser and a verifier that disagree about who signs tokens
   is a hole rather than a typo. A key that does not decode is not a key: it
   reads as "not configured", which closes the gate.

4. **A key id we do not hold refetches the JWKS, at most once per minute.**
   That is how a Clerk key rotation is picked up without a restart. The
   rate limit is not incidental: the `kid` is read off an *unverified* token on
   a route anyone can reach, so an unbounded refetch is an amplifier — one
   cheap forged header per outbound request to Clerk. A refetch that *resolves*
   the key is never delayed; only fruitless ones back off.

5. **ClerkJS is loaded from `https://<frontend-api>/npm/@clerk/clerk-js@<major>/…`,
   and it is the only third-party script this app serves.** It is `defer`red
   and `crossorigin="anonymous"`, and the publishable key reaches it as a
   `data-` **attribute** — so the page still carries no inline script. This
   repo's own browser code is a served file under a third scoped static root,
   `/app/`, which keeps "what we wrote" and "what we vendored" on different
   URLs with different cache policies (ADR-0004 clauses 5 and 6, unchanged and
   applied).

6. **The security response headers ADR-0004 deferred are now sent, on every
   response** — pages, static assets, the 404 and the 500 alike, because a
   policy with a hole in it is not a policy:

   - `Content-Security-Policy`, composed from a strict same-origin base plus
     exactly the origins Clerk needs. Those origins come from
     `books.clerk/csp-sources`, which answers **nothing at all** when no
     instance is configured — so an unconfigured deployment sends the strict
     policy rather than one naming a vendor it never contacts.
   - `X-Content-Type-Options: nosniff`
   - `Referrer-Policy: strict-origin-when-cross-origin` — a search URL carries
     what a reader typed, and it must not travel to the Catalog's image host.
   - `frame-ancestors 'none'` in the CSP, which is the modern spelling of
     `X-Frame-Options: DENY`.

7. **Explicit non-goals, still.** This does not decide asset fingerprinting for
   the stylesheet, a Permissions-Policy (getting it wrong disables the WebAuthn
   surface Clerk uses for passkeys, and there is no measured need yet), HSTS
   preloading, organizations or roles, or the Clerk Backend API — no secret key
   is read anywhere in this repo, because nothing here needs one.

## Consequences

- **Good**: the gate is proved by tests that mint RS256 tokens with a keypair
  generated in the test process and drive them through the real adapter — the
  same signature check, the same `azp` comparison and the same expiry
  arithmetic production runs. No network, no Clerk instance, and no key
  material in the repository.
- **Good**: one publishable key is the whole browser-facing configuration, and
  it is not a secret. Nothing in this repo reads a Clerk secret key, so there
  is no Clerk credential to leak from a log line or a rendered page.
- **Good**: rotation is a non-event, and an unreachable JWKS endpoint signs
  nobody in but also signs nobody *out* — the keys already held survive a
  failed refetch.
- **Bad / trade-off, and the honest centre of this record**: **every page now
  loads and executes a script this repository does not control and no reviewer
  will read.** If that script is compromised, the attacker has script execution
  on every page of this app, including the session token, which is exactly what
  the script is there to manage. Nothing in this ADR reduces that to zero. What
  it does is bound the blast radius and refuse to pretend: the script comes
  from the same origin as the session tokens themselves, so it adds no party we
  were not already trusting with the whole session; the CSP names that origin
  explicitly rather than allowing scripts generally; and the gate lives on the
  **server**, so a browser that never runs the script — blocked, failed,
  disabled — still cannot reach a gated page. The script can only ever add a
  way in, never a way past.
- **Bad / trade-off**: `style-src` must include `'unsafe-inline'`. Clerk's
  components style themselves at runtime, and there is no nonce path for them
  outside Clerk's Next.js middleware. This is a real weakening — it re-enables
  a class of injection that would otherwise need a nonce — and it is accepted
  because the alternative is an unstyled sign-in form. It is worth revisiting
  the day Clerk documents a nonce path for a non-Next.js host.
- **Bad / trade-off**: what a CSP buys here is smaller than it looks, and
  saying so is the point of writing one down. It does **not** protect against a
  malicious ClerkJS release — that script is explicitly allowed, so a
  compromised version runs with full privileges. What it buys is the *other*
  cases: an injected `<script src>` pointing anywhere else is refused, an
  injected inline script is refused, `object-src`/`base-uri` tricks are refused,
  and exfiltration to an arbitrary origin is refused by `connect-src`. That is
  a real reduction in the surface of an XSS bug in *our* code, and no reduction
  at all in the surface of a supply-chain compromise of Clerk's.
- **Bad / trade-off**: sign-in cannot work without network access to Clerk's
  bot-protection origins, so the CSP has to admit them
  (`challenges.cloudflare.com`, `*.protect.clerk.com`). A CSP that omitted them
  would fail a challenge it never manages to show — a policy that breaks
  sign-in rather than hardening it.
- **Neutral**: `buddy-sign` brings `cheshire` and BouncyCastle into a tree that
  already parses JSON with `jsonista`. Two JSON libraries on one classpath is
  untidy; neither is on a path the other uses.
- **Honest limitation**: Clerk's **handshake** flow is deliberately not
  implemented. A document request whose `__session` cookie expired while the
  tab was closed cannot be silently renewed server-side, so it is answered with
  a redirect to sign-in. ClerkJS refreshes the token in an open tab, so the
  case is narrow — but it is a real one, and the reader sees a sign-in page
  where a handshake would have been invisible.
- **Honest limitation**: the `iss` claim is not compared. Binding to the
  instance is done by the *key*: a token is only ever verified against the JWKS
  the publishable key names. Adding an issuer comparison would be belt to that
  brace, and would break a proxied deployment whose issuer is not its JWKS
  host. Named here so the omission is a decision rather than an oversight.
- **Honest limitation**: a Clerk instance's Frontend API host is a
  `*.clerk.accounts.dev` subdomain on a development instance. The CSP therefore
  names a shared vendor domain rather than one we own, until the instance moves
  to a custom domain.

## More information

- Implemented in: PR for `feat/clerk-auth` (ticket #7 of PRD #1)
- Related: ADR-0004 (whose clause 7 non-goal this record takes up, and whose
  no-CDN stance it narrows once, for one script), ADR-0003 (credentials never
  in URL strings — the session token travels in a cookie or an `Authorization`
  header, never a query parameter), ADR-0001 (the handler seam these tests
  drive)
- Clerk's manual JWT verification guide, session-token claim reference, CSP
  guidance and JavaScript quickstart were read for this record, 2026-08-27.
  Where this repo departs from them — refusing a token with no `azp`, where
  Clerk's guide permits skipping the check — the departure is stated in
  `books.clerk` beside the code that makes it.
