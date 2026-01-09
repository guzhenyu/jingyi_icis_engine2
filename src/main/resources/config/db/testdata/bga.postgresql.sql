INSERT INTO raw_bga_records ("id", "mrn_bednum", "bga_category_id", "effective_time") VALUES (1, '100100', 1, '2025-09-25 01:00:00');
INSERT INTO raw_bga_record_details ("id", "record_id", "monitoring_param_code", "param_value_str") VALUES (1, 1, 'bga_ph', '7.46');
INSERT INTO raw_bga_record_details ("id", "record_id", "monitoring_param_code", "param_value_str") VALUES (2, 1, 'bga_na+', '135');

INSERT INTO raw_bga_records ("id", "mrn_bednum", "bga_category_id", "effective_time") VALUES (2, 'A100-display', 1, '2025-09-25 02:00:00');
INSERT INTO raw_bga_record_details ("id", "record_id", "monitoring_param_code", "param_value_str") VALUES (3, 2, 'bga_ph', '7.47');
INSERT INTO raw_bga_record_details ("id", "record_id", "monitoring_param_code", "param_value_str") VALUES (4, 2, 'bga_na+', '136');
INSERT INTO raw_bga_record_details ("id", "record_id", "monitoring_param_code", "param_value_str") VALUES (5, 2, 'bga_k+', '3.6');

-- 设置序列的下一个值为当前最大ID + 1
SELECT setval('raw_bga_records_id_seq', (SELECT MAX(id) FROM raw_bga_records) + 1);
SELECT setval('raw_bga_record_details_id_seq', (SELECT MAX(id) FROM raw_bga_record_details) + 1);


-- delete from patient_bga_record_details;
-- delete from patient_bga_records;