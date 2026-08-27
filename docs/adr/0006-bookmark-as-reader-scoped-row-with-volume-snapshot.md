# ADR-0006: Persist a Bookmark as a Reader-scoped row carrying a Volume snapshot

- **Status**: Accepted
- **Date**: 2026-08-27
- **Deciders**: Arthur Granado (ticket [#9](https://github.com/agranado2k/google-books-clojure/issues/9), part of PRD [#1](https://github.com/agranado2k/google-books-clojure/issues/1))
- **Supersedes / amends**: —
- **Superseded by**: —

## Context and problem statement

ADR-0003 clause 9 explicitly deferred the domain schema and "the bookmark data
model" to the ticket that first needed one. Ticket #9 is that ticket: a Reader
can now keep a Volume, and something has to hold it.

Two questions have to be settled together, because the answer to the second
follows from the first. **What identifies a Bookmark**, given that a Reader is a
Clerk `sub` claim and has no row here (ADR-0005) and a Volume is a read type
this repo never persisted before. And **what a Bookmark holds about its
Volume**, given that ticket #10 renders a bookmarks page: either that page asks
the Catalog for every Volume it lists — N calls against a rate-limited third
party, on a page that must render when the Catalog is down — or the row already
carries what the page draws.

The pressure is real today rather than hypothetical: the Catalog answers `429`
under load (`books.catalog`'s `:quota` outcome exists for it), and a bookmarks
page whose content disappears when the Catalog rate-limits us is a page that
fails at exactly the moment a reader wants their own saved list.

## Decision drivers

- **Reader isolation is a security property, not a filter.** One Reader must be
  structurally unable to observe or affect another's rows. ADR-0005's honest
  limitation says it in as many words: the gate protects routes, and "the day
  two Readers can see each other's bookmarks, the check belongs on the data".
- **Uniqueness enforced where it cannot be bypassed.** A duplicate must be
  impossible, not merely unlikely — an application-level check races itself
  across two requests and two instances.
- **A bookmarks page must render without the Catalog.**
- **No new dependency for a handful of statements** (ADR-0003 clause 1 already
  refuses an ORM and a query DSL).

## Considered options

1. **One row per (Reader, Volume) pair, keyed by that pair, carrying a
   denormalized snapshot of the Volume** *(chosen)* — the pair is the identity,
   the database enforces it, and the snapshot is captured once at bookmark time.
2. **A surrogate `id` column plus a `unique` constraint on the pair** —
   rejected: the surrogate identifies nothing the pair does not, and no other
   table refers to a Bookmark, so it buys a column and an index for no reader.
   Revisit if a Bookmark ever gains children.
3. **Store the Volume id alone and re-fetch from the Catalog** — rejected
   against the third driver: the bookmarks page would then be one Catalog call
   per Volume, on a rate-limited API, and would empty itself during an outage.
4. **A `volumes` table the bookmarks join to** — rejected: it makes one Reader's
   write visible in another Reader's read (a shared row), which is the opposite
   of the first driver, and normalizes data nobody updates. A Volume is the
   Catalog's, not ours; a snapshot is honestly a copy and is named as one.
5. **HoneySQL for the queries** — rejected against the fourth driver: three
   statements, no dynamic query construction, no dependency added.

## Decision outcome

Chosen: **one row per (Reader, Volume), keyed by that pair, carrying a Volume
snapshot.**

1. **`resources/migrations/20260827120000-bookmarks.{up,down}.sql` creates the
   table**, the first real migration in this repo (the baseline creates nothing
   on purpose). The down migration drops it.

2. **The primary key is `(reader_id, volume_id)`.** That is the uniqueness
   constraint, enforced by the database: bookmarking the same Volume twice
   cannot create a second row no matter how many instances race. A surrogate key
   is deliberately absent (option 2). The pair's leading column also indexes the
   only lookup this table has — "this Reader's rows" — so no second index is
   created.

3. **`reader_id` is the Clerk `sub` claim of the *verified* session**, taken
   from `:reader` on the request that the gate attached, and **never** from a
   request parameter. Every statement in `books.bookmarks` carries
   `reader_id = ?` in its `where` clause — the delete included, which is what
   makes "delete somebody else's bookmark" a no-op rather than a permission
   check that could be forgotten. A read is scoped the same way, so there is no
   query in this repo that can answer another Reader's row.

4. **The snapshot is `title`, `authors`, `thumbnail`, `published_date`** — the
   four fields the Volume card draws, and no more. `description` is deliberately
   not stored: it is a paragraph the card clamps to three lines and the
   bookmarks page has no use for, and it is the one Volume field that arrives
   containing HTML. `authors` is a Postgres `text[]`, because a Volume's authors
   are a list and a joined string cannot be un-joined.

5. **The snapshot is a copy, and it is never refreshed.** A Volume the Catalog
   later re-titles keeps its old title in a Bookmark. That is the honest reading
   of "captured at bookmark time" and it is what makes clause 4's page work
   offline.

6. **`books.bookmarks` is the only namespace that writes SQL for this table**,
   and it talks to `next.jdbc` directly (ADR-0003 clause 1). It takes the
   datasource `books.handler` already holds — the same shape `books.db/checker`
   is handed.

7. **Explicit non-goals**: this does not decide the bookmarks *page* (ticket
   #10) — its route, its paging, or its ordering; it does not decide a
   connection pool (ADR-0003's honest limitation still stands, and this is the
   first real query path, so that limitation is now due rather than theoretical);
   and it does not decide how a mutating request proves itself, which is
   ADR-0007.

## Consequences

- **Good**: a duplicate is impossible by construction, and the test that proves
  it inserts twice through raw SQL rather than through our own code — so it
  fails if the constraint is dropped, not merely if the application check is.
- **Good**: the bookmarks page ticket #10 will build can render with the Catalog
  unreachable, because nothing it draws comes from the Catalog at read time.
- **Good**: Reader isolation is a `where` clause on every statement in one small
  namespace, which is a surface a reviewer can read in full.
- **Bad / trade-off**: the snapshot is supplied by the browser. A Reader can
  therefore store any text as their own bookmark's title, by crafting the form.
  It is their own row, visible only to them, and it is rendered escaped by
  hiccup2 like every other string — so the cost is a Reader lying to themselves.
  The alternative (re-fetch the Volume from the Catalog on every bookmark) pays a
  rate-limited network call to prevent that, which is the wrong trade.
- **Bad / trade-off**: stale snapshots. A corrected title or a new cover never
  reaches an existing Bookmark. Accepted per clause 5; a refresh path is a later
  ticket if anyone ever asks for one.
- **Neutral**: the table is unbounded per Reader. Nothing here caps how many
  Volumes one Reader may keep, and nothing needs to yet.
- **Honest limitation**: `reader_id` is a bare `text` column with no foreign key,
  because there is no Readers table to point at (ADR-0005: a Reader has no row).
  Nothing in this database can tell a live Clerk user from a deleted one, so rows
  belonging to a deleted Clerk account are orphaned and this repo will not
  notice. That is the price of holding no account data, and it is the right side
  of the trade — but it means a deletion request is a Clerk operation plus a
  `delete from bookmarks where reader_id = …`, done by hand, until something
  automates it.
- **Honest limitation**: `text[]` is a Postgres type, so this schema is not
  portable to a database without array columns. ADR-0003 already binds this repo
  to PostgreSQL, so the constraint is not new — but it is now in the schema
  rather than only in the connection string.
- **Honest limitation**: the write path opens a connection per request, exactly
  as ADR-0003's "no connection pool" limitation predicted. It is fine at this
  traffic and it will not be fine at real traffic; the pool is now the first
  thing to revisit rather than the last.

## More information

- Related: ADR-0003 (clause 9 deferred this schema; clause 1 is why there is no
  query DSL here), ADR-0005 (the Reader is a claim, and its closing limitation
  is what clause 3 answers), ADR-0007 (how a mutating request proves itself),
  `docs/domain-glossary.md` (**Bookmark**).
