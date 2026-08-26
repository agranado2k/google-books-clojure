# Local product — who uses this, and how they reach it

Project-specific elaboration of the root `AGENTS.md`. Read it before running
`/dogfood`, and before making any change to a user-facing entry point.

## DOGFOOD DECLARATION

<!-- ========================================================================
     This section is the one `/dogfood` reads. Keep it accurate or delete it —
     a stale persona produces a report about a user who does not exist, which
     reads exactly like evidence.
     ===================================================================== -->

### SURFACES — the real thing a person touches

One surface today: the server-rendered web app. There is no CLI, no public API,
and no tool server, so none are declared. `GET /health` is part of this same
surface rather than a second one — it is answered by the same process on the
same port, and the persona who reads it is a person with a browser or `curl`,
not a separate client.

| Surface | What it is | How to bring it up | Notes / prerequisites |
| --- | --- | --- | --- |
| Books web app | Server-rendered web app (Ring/Jetty + reitit + Hiccup), driven in a browser at `http://localhost:3000` | `scripts/build-css.sh` then `DB_OPTIONAL=true clojure -M -m books.server` | The stylesheet is generated and gitignored — skip the CSS build and the page renders unstyled, which is a false finding, not a bug. `DB_OPTIONAL=true` is the database-less mode; to drive the database-backed states instead, start Postgres (`docker run -d --rm --name books-test-pg -p 5544:5432 -e POSTGRES_PASSWORD=test postgres:16`) and pass `DATABASE_URL` as `README.md` shows. No login, no seed data, no fixture account is needed for any journey below. |

**Ready means reachable, not started.** A `200` from `GET /health` carrying
`"status":"ok"` is how you know the surface is actually up — Jetty accepts the
socket before the routes and the migration run are ready, so a spawned process
is not yet a product that answers.

### PERSONAS — who is trying to do what

Two personas, because two is what the product currently supports. **The
roadmap cards on the landing page name Search, Bookmarks and Sign-in; none of
those are built** (tickets #5 and #7 are the open frontier — see
`docs/diary.md`). A "reader who searches for a book" is therefore not a persona
this repo has, and inventing one would produce a report about a user who does
not exist.

| Persona | Goal | Enters at | Permission level | Surface |
| --- | --- | --- | --- | --- |
| Prospective reader | Understand what this thing is and whether it will do what they want | The landing page at `/` | Anonymous — no account exists to have | Books web app |
| Service operator | Decide whether a running instance is healthy, and tell "degraded" apart from "down" | `GET /health` | Unauthenticated by design — the endpoint opens no database connection per request | Books web app |

### Where a dogfood session may and may not go

- **Environments it may drive**: a locally started server only — the two
  commands in the surface table above, against `localhost`. The deployed
  Railway instance is **not** on this list and is never to be driven; it is a
  shared environment and it is currently serving a stale image.
- **Data it may create**: none. Neither journey writes: the landing page is
  static Hiccup and the health check reads. A session that finds itself needing
  to create data has left the declared surface and should stop and report that.
- **Credentials**: none exist, and none are to be created. There is no sign-up,
  no login, and no fixture account; `DATABASE_URL` is a local test credential
  supplied by the operator, never by the session.
- **Off limits entirely**: the deployed environment, the production database,
  anything that sends mail or moves money (this product does neither today, and
  the line is here before it does), and the Google Books API itself — the
  integration is unbuilt, so a session calling a third-party API is testing
  someone else's product.

## What the product is for

Letting a reader find a book in the Google Books catalog and keep the ones
worth returning to. Today it is a walking skeleton of that: the landing page
states the intent and the health endpoint proves the service and its database
are wired. A behavior change serves the product when it moves a roadmap card
from "planned" to something a persona above can actually do end to end; a
change that only compiles has not.
