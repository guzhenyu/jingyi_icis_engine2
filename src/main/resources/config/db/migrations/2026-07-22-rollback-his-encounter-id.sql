-- Stop icis_j3 and jingyi_icis_engine2 and deploy the old binaries before/with
-- this rollback. Restore the backed-up settings_pb values as part of rollback.

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
          AND column_name = 'his_encounter_id'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'patient_serial_number'
    ) THEN
        RAISE EXCEPTION 'his_patient_records contains both his_encounter_id and patient_serial_number';
    ELSIF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'his_encounter_id'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'patient_serial_number'
    ) THEN
        ALTER TABLE his_patient_records
            RENAME COLUMN his_encounter_id TO patient_serial_number;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'his_patient_records'
          AND column_name = 'patient_serial_number'
    ) THEN
        RAISE EXCEPTION 'his_patient_records has neither his_encounter_id nor patient_serial_number';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_encounter_id'
    ) AND EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_patient_serial_number'
    ) THEN
        RAISE EXCEPTION 'patient_records contains both his_encounter_id and his_patient_serial_number';
    ELSIF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_encounter_id'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_patient_serial_number'
    ) THEN
        ALTER TABLE patient_records
            RENAME COLUMN his_encounter_id TO his_patient_serial_number;
    ELSIF NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'patient_records'
          AND column_name = 'his_patient_serial_number'
    ) THEN
        RAISE EXCEPTION 'patient_records has neither his_encounter_id nor his_patient_serial_number';
    END IF;
END
$$;

COMMENT ON COLUMN his_patient_records.patient_serial_number IS '病人流水号';
COMMENT ON COLUMN patient_records.his_patient_serial_number IS 'HIS系统中的病人记录表中的病人流水号';

COMMIT;
