-- Optional query optimization. CREATE INDEX CONCURRENTLY must not run in a transaction.
-- Check production table size and existing indexes before executing.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_his_patient_records_his_encounter_id_id
    ON his_patient_records (his_encounter_id, id DESC);
