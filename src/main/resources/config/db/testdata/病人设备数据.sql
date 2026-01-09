-- 张医嘱(device_data)
-- 体温，心率
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'temperature', '2025-07-31 10:00:00', '37.1');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'hr', '2025-07-31 10:00:00', '90');
-- 呼吸
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'vent_respiratory_rate', '2025-07-31 10:00:00', '10');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'respiratory_rate', '2025-07-31 11:00:00', '12');
-- 血压 (2025-07-30 23:59:00) 2025-07-31 00:00:00 (2025-07-31 00:05:00)
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_s', '2025-07-30 23:59:00', '110');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_s', '2025-07-31 00:05:00', '111');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-07-30 23:55:00', '81');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-07-31 00:01:00', '80');


-- 张医嘱(device_data_hourly)
-- 体温，心率
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'temperature', '2025-07-31 10:00:00', '37.1');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'hr', '2025-07-31 10:00:00', '90');
-- 呼吸
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'vent_respiratory_rate', '2025-07-31 10:00:00', '10');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'respiratory_rate', '2025-07-31 11:00:00', '12');

-- 张医嘱(device_data_hourly_approx)
-- 血压 (2025-07-30 23:59:00) 2025-07-31 00:00:00 (2025-07-31 00:05:00)
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_s', '2025-07-30 23:59:00', '110');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_s', '2025-07-31 00:05:00', '111');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-07-30 23:55:00', '81');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-07-31 00:01:00', '80');

-- 设置序列的下一个值为当前最大ID + 1
SELECT setval('device_data_id_seq', (SELECT MAX(id) FROM device_data) + 1);
SELECT setval('device_data_hourly_id_seq', (SELECT MAX(id) FROM device_data_hourly) + 1);
SELECT setval('device_data_hourly_approx_id_seq', (SELECT MAX(id) FROM device_data_hourly_approx) + 1);