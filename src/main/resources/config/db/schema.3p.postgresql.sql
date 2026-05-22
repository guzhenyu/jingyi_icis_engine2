-- 1. 新安县人民医院
-- 云迭科技无纸化视图URL根地址，后续会在此基础上拼接具体的视图URL路径
CREATE OR REPLACE VIEW pr_medical_record AS
SELECT
    '801'::VARCHAR AS record_type_code,
    '重症监护记录单'::VARCHAR AS record_type_name,
    pr.id::VARCHAR AS patient_no,
    pr.his_mrn::VARCHAR AS admission_no,
    COALESCE(pr.his_admission_count, 0)::INTEGER AS admission_count,
    '重症监护记录单'::VARCHAR AS title,
    pr.dept_id::VARCHAR AS department_code,
    pr.dept_name::VARCHAR AS department_name,
    pr.ward_code::VARCHAR AS ward_code,
    pr.ward_name::VARCHAR AS ward_name,
    ''::VARCHAR AS creator_code,
    ''::VARCHAR AS creator_name,
    pa.modified_at AS create_time,
    ''::VARCHAR AS creator_department_code,
    ''::VARCHAR AS creator_department_name,
    NULL::TIMESTAMP AS sign_time,
    ''::VARCHAR AS sign_user_code,
    ''::VARCHAR AS sign_user_name,
    ('http://10.87.96.26:8080' || pa.relative_url)::VARCHAR AS pdf_url,
    pa.modified_at AS pdf_export_time,
    1::INTEGER AS pdf_export_status,
    COALESCE(pa.page_count, 0)::INTEGER AS page_count,
    pa.id::VARCHAR AS source_id,
    8::SMALLINT AS source_type,
    1::SMALLINT AS visit_type,
    pr.his_patient_id::VARCHAR AS registration_no
FROM patient_archives pa
JOIN patient_records pr ON pr.id = pa.pid
WHERE pa.is_deleted = FALSE
  AND pa.type = 1;

CREATE ROLE yundie LOGIN PASSWORD 'yundie@2026.';
GRANT CONNECT ON DATABASE jingyi_icis_db TO yundie;
GRANT USAGE ON SCHEMA public TO yundie;
GRANT SELECT ON public.pr_medical_record TO yundie;