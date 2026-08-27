(ns books.reader
  "The **Session check** port: the seam between the app and whatever proves that
  a request was made by a signed-in **Reader**.

  The handler depends on this contract and never on an adapter, so a test can
  inject a double and the production wiring can inject the Clerk adapter
  (`books.clerk`) without either knowing about the other — the same shape
  `books.catalog` uses for the Book search port.

  A **Reader** is the signed-in person the app answers to. See
  `docs/domain-glossary.md`.

  ## The contract

  A **Session check is a plain function of one argument**: the session token as
  it arrived, a string, or nil when the request carried none.

  It returns a map, and **never throws**: a forged, expired or absent token is
  an outcome the handler answers, not an exception it catches.

      {:outcome :signed-in  :reader {:id \"user_…\"}}
      {:outcome :signed-out :reason reason}

  `reason` is one of:
    :absent          the request carried no token at all
    :expired         the token was valid and its lifetime has run out
    :invalid         everything else — a bad signature, a wrong authorized
                     party, an unresolvable key, a malformed token
    :not-configured  this deployment has no Clerk configuration, so no token
                     could be checked

  The reasons are separated because they are different things to tell a Reader:
  `:expired` and `:absent` mean 'sign in'; `:not-configured` means 'this
  deployment cannot sign anyone in'; `:invalid` means something is wrong that
  signing in again will not fix.

  **Every reason is a refusal.** Nothing in this vocabulary lets a request
  through — a Session check that cannot verify answers signed-out, and the
  gate stays shut.")

(def not-configured
  "The Session check used when no Clerk configuration was supplied: every check
  answers `:not-configured`, and every gated route therefore stays closed.

  This is the **fail-closed** default, and it is the whole reason the port has
  a default at all. A deploy that loses its Clerk variables gets a service
  whose gated pages honestly say sign-in is not configured here — never a
  service whose gate quietly opened. Compare `DB_OPTIONAL`, which makes the
  same choice for the database: absent configuration is a fault by default.

  It is also the ONE owner of that result value — `books.clerk` returns this
  rather than rebuilding the map, so the contract cannot drift between the port
  and its adapter."
  (constantly {:outcome :signed-out :reason :not-configured}))

(defn signed-in?
  "Whether `outcome` is a Reader the app should answer to. Named rather than
  spelled `(= :signed-in (:outcome …))` at each call site, so the one place
  that decides what 'signed in' means is here."
  [outcome]
  (= :signed-in (:outcome outcome)))
