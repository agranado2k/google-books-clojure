# Domain glossary — Ubiquitous Language

The registry of canonical terms for Google Books (Clojure). Use these spellings and
meanings consistently across **code** (type names, function names, table names),
commit messages, PR titles, ADRs, the diary, and conversations with agents.

One name per concept. An agent given two names for one thing will invent a
distinction between them.

**Adding a term** — introduce it in the same change that first uses it in code.
Say what it *is*, not what it does, and cross-reference the ADR or spec section
that defines its behavior.

**Changing a term** — rename across the whole codebase in a single change, and
update this file in the same commit. Do **not** leave aliases: the point of a
ubiquitous language is that there is exactly one name per concept.

**Retiring a term** — keep the entry, mark it _(superseded by `NewName`)_, and
say what replaced it. A deleted entry loses the fact that the old name ever
meant something, which is exactly what a reader of old code needs.

> **Source of truth.** This file is canonical for domain *language*. Where other
> documents disagree on a name, this one wins and they are synced to it. They
> still win on architecture — this carve-out is for naming only.

---

<!--
Group terms by bounded context (or by module, or by subsystem — whatever your
seams actually are). One `##` per context. Within a context, no particular
order; alphabetical stops being useful past about thirty terms, and grouping by
aggregate reads better.

Entry shape:

  - **Term** — what it is, in one or two sentences. Its kind (Aggregate root /
    Entity / Value Object / read type / port / adapter). Where it is persisted,
    if it is. Ref: <ADR-NNNN or spec section>.
    - _Avoid_: <the near-synonym people reach for, and why it is wrong>

Keep definitions short enough to read in a session-start scan. When one needs a
page of explanation, that page is an ADR and the entry points at it.
-->

## Catalog search

- **Volume** — one entry in the Catalog: a single *edition* of a book as the
  catalog describes it — its title, authors, published date, description and
  cover thumbnail. Read type (a value object built from a catalog response,
  never persisted). The name is the catalog's own, and it is what a search
  returns. Ref: `books.catalog` (the port's docstring carries the field list),
  ADR-0004 (rendered by a Hiccup2 page, always escaped).
  - _Avoid_: **Book** — one book has many Volumes (editions, translations,
    reprints), so the two are not the same thing and using both invites an
    invented distinction between them.
  - _Avoid_: **Result**, **Item**, **Hit** — these name a Volume's role in one
    response rather than what it is.
- **Catalog** — the external corpus of Volumes the app searches: Google Books.
  External system (a boundary, not a model — nothing in it is ours and none of
  it is persisted here). Reachable only through the Book search port, which is
  why "the Catalog is unavailable" is a state the UI renders rather than an
  exception that escapes a handler. Ref: `books.catalog` (the outcomes),
  `books.google-books` (the adapter that reaches it).
- **Results page** — the run of Volumes one Book search answers: at most
  `books.catalog/page-size` of them, beginning at the query's `:start-index`.
  Read type (never persisted). The Catalog is asked for exactly this many, which
  is what makes a page it could not fill the honest signal that the matches have
  run out — `totalItems` is an estimate and is not used. Ref: `books.catalog`
  (`page-size`), ADR-0004 (rendered into the `#results` region).
  - _Avoid_: **page** unqualified — this app also serves HTML pages, and the
    search page holds many Results pages one after another.
  - _Avoid_: **offset**, **cursor** — the Catalog pages by an index into the
    matches, and `:start-index` is its own name for it.
- **Page position** — where a Results page sits in the run of pages a search
  has: `:only-page`, `:first-page`, `:middle-page` or `:last-page`. Value object,
  derived by `books.catalog/page-position` from the offset asked for and the
  number of Volumes that came back. It is what decides which paging controls the
  results region renders. Ref: `books.catalog`.
  - _Avoid_: a pair of booleans (`first-page?` / `last-page?`) — the four states
    are what a reader of a call site needs, and they are what the view chooses
    between.
- **Book search** — the port the app searches the Catalog through: a **plain
  function of one argument**, the normalized query map, answering an outcome map
  and never throwing. Port. Its real adapter is `books.google-books/book-search`;
  the default is `books.catalog/not-configured`; tests inject a closure
  (`books.stub-book-search`). The handler depends on this contract and never on
  an adapter — it is injected as `make-app`'s `:book-search` option.
  Ref: `books.catalog` (the docstring carries the contract), ADR-0001 (the
  handler is the test seam).
  - _Avoid_: **Books port** — the same thing under a plural that reads like the
    banned **Book**, and under a shape (a protocol named `BookSearch`, with a
    `search-volumes` operation) the port has not had since it became a function.

---

## Words this project does not use

<!--
The other half of a ubiquitous language, and the half that is usually missing:
the terms that are ambiguous here and are therefore banned. Each line names the
banned word and the word to use instead.
-->

- **Book** — ambiguous as a name for **what a search returns** (a book is a
  work; the Catalog answers editions of it). Use **Volume** for that, always.
  The word itself is not banned outright, and the uses that remain are
  deliberate: the product's name, the `books` namespace root, the canonical
  term **Book search** above and the identifiers derived from it
  (`book-search`, the `:book-search` option, `books.stub-book-search`), and
  `GOOGLE_BOOKS_API_KEY` / the `:books-api-key` option, which name a third
  party's API and are therefore its spelling rather than ours.
- **Result**, **Item**, **Hit** — ambiguous as a name for **a thing the Catalog
  describes**: they name a position in a response. Use **Volume** for that.
  - _Carve-out_: "results" is the right word for the **region of the search
    page** that holds Volumes, and for that region's states — `#results`,
    `data-state="results"`, and `books.views/search-results`. A region in a
    response is precisely what the word means, so this is not the ambiguity the
    ban is about: the ban is on calling a Volume a result, never on naming the
    box the Volumes are rendered into — nor, in **Results page**, one page of
    what that box holds.
