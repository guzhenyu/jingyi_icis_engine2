-- Run while icis_j3 and jingyi_icis_engine2 are stopped.
-- This migration only renames columns; PostgreSQL preserves the column data.

BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'patient_serial_number'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'his_encounter_id'
    ) THEN
        RAISE EXCEPTION 'his_patient_records contains both patient_serial_number and his_encounter_id';
    ELSIF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'patient_serial_number'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'his_encounter_id'
    ) THEN
        ALTER TABLE his_patient_records
            RENAME COLUMN patient_serial_number TO his_encounter_id;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'his_encounter_id'
    ) THEN
        RAISE EXCEPTION 'his_patient_records has neither patient_serial_number nor his_encounter_id';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_patient_serial_number'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_encounter_id'
    ) THEN
        RAISE EXCEPTION 'patient_records contains both his_patient_serial_number and his_encounter_id';
    ELSIF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_patient_serial_number'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_encounter_id'
    ) THEN
        ALTER TABLE patient_records
            RENAME COLUMN his_patient_serial_number TO his_encounter_id;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_encounter_id'
    ) THEN
        RAISE EXCEPTION 'patient_records has neither his_patient_serial_number nor his_encounter_id';
    END IF;
END
$$;

COMMENT ON COLUMN his_patient_records.his_encounter_id IS 'HIS就诊ID';
COMMENT ON COLUMN patient_records.his_encounter_id IS 'HIS就诊ID';

COMMIT;

SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name IN ('his_patient_records', 'patient_records')
  AND column_name IN (
      'patient_serial_number',
      'his_patient_serial_number',
      'his_encounter_id'
  )
ORDER BY table_name, column_name;
