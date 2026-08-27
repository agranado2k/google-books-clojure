-- One Reader's decision to keep one Volume (ADR-0006).
--
-- The primary key IS the uniqueness constraint, and it is the whole of it: a
-- Reader cannot bookmark the same Volume twice, whatever races. It is also the
-- index for the only lookup this table has — this Reader's rows — so no second
-- index is created.
--
-- reader_id is the Clerk `sub` claim of a verified session. There is no foreign
-- key because there is no Readers table to point at: a Reader has no row here
-- (ADR-0005).
--
-- The four snapshot columns are a copy of the Volume as the Catalog described it
-- at bookmark time, captured so a page listing Bookmarks never calls the
-- Catalog. `description` is deliberately absent.
create table bookmarks (
  reader_id      text        not null,
  volume_id      text        not null,
  title          text,
  authors        text[]      not null default '{}',
  thumbnail      text,
  published_date text,
  bookmarked_at  timestamptz not null default now(),
  primary key (reader_id, volume_id)
);
