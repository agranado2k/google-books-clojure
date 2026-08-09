-- Baseline migration. It exists to prove the Migratus machinery runs against
-- this database — the connection, the transaction, and the schema_migrations
-- bookkeeping — and deliberately changes nothing: the proof is the recorded
-- row, not a leftover table. Real domain tables arrive in later migrations.
SELECT 1;
