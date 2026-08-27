# Architecture Decision Records

Each ADR captures **one** architectural decision for Google Books (Clojure), in
[MADR format](https://adr.github.io/madr/). The record is the contract; the
development chronology lives in `docs/diary.md`.

Copy `NNNN-template.md` to start a new one.

## Index

<!--
One row per ADR, in numeric order. The Status column carries the *live* status
plus the date it reached it, and any supersession/amendment note — so this table
alone answers "what is currently binding?" without opening 40 files.
-->

| # | Title | Status |
|---|---|---|
| [0001](0001-compose-web-stack-from-ring-reitit-jetty.md) | Compose the web service from Ring + Reitit + Jetty libraries | Accepted 2026-08-08 |
| [0002](0002-package-as-uberjar-in-docker-for-railway.md) | Package as an uberjar in a multi-stage Dockerfile for Railway | Accepted 2026-08-08, amended 2026-08-09 (pinning covers fetched build tools) |
| [0003](0003-persistence-next-jdbc-migratus-postgres.md) | Persist on Railway PostgreSQL via next.jdbc and Migratus, migrating at boot | Accepted 2026-08-09, amended 2026-08-10 (clause 2's credentials-never-in-URLs rule binds the Books API key too) |
| [0004](0004-server-rendered-ui-hiccup-tailwind.md) | Render the UI on the server with Hiccup2 and build its CSS with the standalone Tailwind CLI | Accepted 2026-08-09, amended 2026-08-10 (second scoped static root `/js/` for a vendored, digest-pinned htmx); clause 7's security-header non-goal taken up by 0005 |
| [0005](0005-clerk-sign-in-third-party-script-and-security-headers.md) | Sign readers in with Clerk — a third-party script on every page, and the security headers that bound it | Accepted 2026-08-27 |
| [0006](0006-bookmark-as-reader-scoped-row-with-volume-snapshot.md) | Persist a Bookmark as a Reader-scoped row carrying a Volume snapshot | Accepted 2026-08-27 |
| [0007](0007-mutations-accept-the-bearer-header-only.md) | A mutating request proves itself with the bearer header alone | Accepted 2026-08-27 |

## Conventions

- **File name**: `NNNN-short-kebab-title.md`, zero-padded to four digits.
  Numbers are never reused, even for a rejected ADR.
- **Status values**: `Proposed` · `Accepted` · `Rejected` · `Deprecated` ·
  `Superseded by NNNN`.
- **The "Decision outcome" section is the contract.** Implementation detail and
  historical context go in `More information` at the bottom, kept short.
- **When a decision is reversed or revised, do NOT edit the old ADR.** Write a
  new one and set the old one's status to `Superseded by NNNN`. An amendment
  that only *narrows or clarifies* the same decision may be recorded in place,
  dated and labelled as an amendment — but a reversal never is.
- **Write the ADR when the decision is made**, not when the code lands. An ADR
  written after the fact documents a rationalization, not a decision.
- **One decision per record.** If the title needs an "and", it is two ADRs.

## Decisions recorded in the diary, not as ADRs

<!--
Some material decisions do not warrant a standalone record but are still binding
policy. List them here with a date, so "it is not in docs/adr/" never means "it
was never decided". If one grows consequential enough, promote it to an ADR and
leave a back-reference in the diary entry.
-->

- **2026-08-09** — Deploys via Railway's native GitHub integration; GitHub
  Actions is CI-only (tests, docs gate, TDD pairing guard). See the diary
  entry of that date.
