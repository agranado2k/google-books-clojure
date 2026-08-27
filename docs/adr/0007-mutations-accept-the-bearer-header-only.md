# ADR-0007: A mutating request proves itself with the bearer header alone

- **Status**: Accepted
- **Date**: 2026-08-27
- **Deciders**: Arthur Granado (ticket [#9](https://github.com/agranado2k/google-books-clojure/issues/9), part of PRD [#1](https://github.com/agranado2k/google-books-clojure/issues/1))
- **Supersedes / amends**: —
- **Superseded by**: —

## Context and problem statement

Until ticket #9 every gated route in this app was a `GET`. Nothing could be
changed by a request, so cross-site request forgery had no target and the
question never had to be answered. Bookmarking a Volume is the first write, and
it arrives with the question attached.

ADR-0005 clause 8 accepts **two** transports for a session token: an
`Authorization: Bearer` header, minted per request by ClerkJS and attached by
`resources/public/app/session.js`, and the `__session` **cookie** that an
ordinary document navigation carries. Clause 2 calls the `azp` check a CSRF
defence, and for the attack it names — a token minted for another origin,
replayed at ours — it is one.

It is not one here, and that gap is what forces this record. A cross-site form
submitted from `evil.example` to `POST /bookmarks` sends **our own** cookie, so
the token it carries is genuine, its signature verifies, and its `azp` is
exactly this app's origin. Every check ADR-0005 lists passes. The only thing
standing between that form and a write is the `SameSite` attribute of a cookie
**this repo does not set, does not read the attributes of, and cannot pin** —
Clerk sets `__session`, its attributes are Clerk's to choose, and they can change
on Clerk's release schedule without any deploy here. A security property that
rests on an attribute we neither set nor observe is a property we cannot claim.

## Decision drivers

- The defence must hold **without depending on a third party's cookie
  attributes**, since this repo cannot see or pin them.
- No new secret, no new token, no new middleware to forget on the next route.
- Consistency with ADR-0005's fail-closed posture: the safe answer must be the
  default one, and a route added without thinking must refuse rather than admit.

## Considered options

1. **Accept only the bearer header on a mutating route** *(chosen)* — the
   cookie is not a credential for a write.
2. **A synchronizer token (classic CSRF token)** — rejected: it needs
   server-side state or a signing key this app deliberately does not have
   (ADR-0005: no Clerk secret key is read anywhere here), plus a hidden field on
   every form and a second thing to get wrong. It defends the same attack the
   chosen option defends structurally.
3. **Check `Origin` / `Sec-Fetch-Site` on writes** — rejected as the primary
   control: it is a header comparison rather than a structural impossibility, it
   is another allow-list to keep in step with the deployment's origin, and it
   fails open on any request that arrives without the header. Worth adding later
   as a second line; not worth being the first.
4. **Rely on the `__session` cookie's `SameSite`** — rejected on the driver
   above: it is a property of somebody else's cookie.

## Decision outcome

Chosen: **a mutating route accepts the bearer header and nothing else.**

1. **`books.handler` reads the credential as a named transport**, not as a bare
   string: `:bearer-header` or `:session-cookie`. The gate is handed the set of
   transports a route accepts, so "which credentials count here" is data at the
   route rather than a condition inside the middleware.

2. **`gated-paths` names each gated path with the request methods it answers.**
   `{"/search" #{:get :head}, "/bookmarks" #{:post :delete}}`. It remains the one
   seam `books.auth-test` walks to prove every gated path refuses an anonymous
   request — now with the method each path actually answers, so a mutation route
   is probed as a mutation rather than as a `GET` that would 404 and look gated.

3. **The sign-in return path is a gated path that answers `GET`.** ADR-0005
   clause 7 chose it from `gated-paths` to make an open redirect structurally
   impossible; that still holds, narrowed by one condition, because returning a
   Reader to a `POST`-only path lands them on a 404. The return path is still
   never echoed from the request.

4. **Why the bearer header is enough.** A cross-origin `<form>` can send
   `application/x-www-form-urlencoded` with no preflight, and the browser will
   attach cookies. It **cannot** set an `Authorization` header: doing so makes
   the request non-simple, so the browser must first receive a permissive CORS
   preflight response — and this app answers no `OPTIONS` route and sends no
   `Access-Control-Allow-*` header anywhere. The header therefore cannot be
   forged cross-site by construction rather than by policy, and a request that
   carries one was made by script running on this origin.

5. **The cost to a Reader is nothing**, because the toggle is an htmx request
   and `session.js` attaches a freshly minted token to every htmx request
   already (ADR-0005 clause 8). A mutation was never going to arrive as a
   document navigation.

6. **A cookie-only mutation is refused exactly as a signed-out one is** — the
   htmx refusal, `401` with `HX-Redirect`, not a 403. It is the same fact from
   the app's side: this request carried no credential this route accepts.

7. **Explicit non-goals**: this does not add an `Origin` check (option 3 — a
   named follow-up, as a second line rather than a replacement), does not add
   CORS to this app, and does not change how a `GET` proves itself. Reading a
   gated page still accepts either transport, because a document navigation has
   only the cookie and a cross-site `GET` changes nothing.

## Consequences

- **Good**: the defence is a property of the browser's own request model, not of
  a configuration value, a cookie attribute or a third party's release notes.
  Nothing this repo can deploy weakens it except deliberately adding CORS.
- **Good**: the transports a route accepts are visible at the route, so review of
  "is this new write protected?" is reading one line of route data.
- **Bad / trade-off**: bookmarking now requires JavaScript, with no no-JS
  fallback — a deliberate reversal of the progressive-enhancement posture the
  search form and the paging controls hold to (ADR-0004). A no-JS toggle would
  be a plain form POST carrying only the cookie, which is precisely the request
  this decision refuses. Search, paging and sign-in are unaffected; the reader
  who blocks scripts loses bookmarking and keeps everything else.
- **Bad / trade-off**: a Reader whose token refresh failed (a blocked Clerk
  origin, a network blip) is bounced to sign-in on a click rather than told
  their token went stale. It is loud, and it recovers in one sign-in.
- **Neutral**: `gated-paths` changed shape from a vector to a map. It is a
  public var read by one test and by `return-path`; both moved with it.
- **Honest limitation**: this protects against a *cross-site* forgery. It does
  nothing against an XSS bug in our own code or in ClerkJS — script running on
  this origin can read the token and make any request a Reader can. ADR-0005 is
  where that risk is weighed, and nothing here reduces it.
- **Honest limitation**: none of this has been exercised against a live browser.
  The suite proves the server refuses a cookie-only mutation; that the browser
  refuses to forge the header cross-site is a property of the fetch and CORS
  specifications, asserted here rather than measured.
- **Honest limitation**: `Sec-Fetch-Site` would catch a same-site-but-different-
  subdomain attacker that a cookie scoped to a parent domain would reach. That
  case does not exist for this deployment today (one host, no subdomains), which
  is why option 3 is a follow-up rather than a clause.

## More information

- Related: ADR-0005 (the two transports, the `azp` check, and the fail-closed
  gate this narrows), ADR-0006 (the write this protects), ADR-0004 (the
  progressive-enhancement posture the trade-off above departs from).
