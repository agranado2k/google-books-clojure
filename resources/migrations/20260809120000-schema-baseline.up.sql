-- Baseline migration: proves the Migratus pipeline runs against this
-- database. Real domain tables (e.g. bookmarks) arrive in later migrations.
CREATE TABLE IF NOT EXISTS schema_baseline (
    applied_at timestamptz NOT NULL DEFAULT now()
);
