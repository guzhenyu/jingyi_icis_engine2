------ 20251014
-- 查看数据库锁
SELECT pid, usename, application_name, client_addr, backend_start, state, query
FROM pg_stat_activity
WHERE state != 'idle';

SELECT pg_terminate_backend(<pid>);

------ 20250815
-- 参数格式化
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'temperature', '2025-07-31 10:00:00', '37.11111');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'temperature', '2025-07-31 10:00:00', '37.11111');

-- 班次临界获取就近原则数据
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-07-30 23:55:00', '91');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-07-30 23:55:00', '91');

-- 给定某时刻：监护仪采集到数据，对应的呼吸机数据不覆盖；监护仪没采集到数据，对应的呼吸机数据覆盖
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'vent_respiratory_rate', '2025-07-31 10:00:00', '10');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'vent_respiratory_rate', '2025-07-31 11:00:00', '11');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'respiratory_rate', '2025-07-31 11:00:00', '12'); -- 11没有覆盖12
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'respiratory_rate', '2025-07-31 12:00:00', '13');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'vent_respiratory_rate', '2025-07-31 10:00:00', '10');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'vent_respiratory_rate', '2025-07-31 11:00:00', '11');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'respiratory_rate', '2025-07-31 11:00:00', '12'); -- 11没有覆盖12
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'respiratory_rate', '2025-07-31 12:00:00', '13');

-- 非整点数据超过当前时间
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-08-15 10:00:00', '92');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A100-dev', 'nibp_d', '2025-08-15 10:00:00', '92');

-- 设备换绑(前面的数据可以刷出，只是之前设置了5分钟的时间差限定，现在改成30分钟）
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 1, null, null, 'hr', '2025-07-31 03:00:00', '81');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 2, null, null, 'hr', '2025-07-31 07:00:00', '82');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 1, null, null, 'hr', '2025-07-31 03:00:00', '81');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 2, null, null, 'hr', '2025-07-31 07:00:00', '82');

insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 1, null, null, 'nibp_s', '2025-07-31 03:10:00', '83');
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 2, null, null, 'nibp_s', '2025-07-31 07:10:00', '84');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 1, null, null, 'nibp_s', '2025-07-31 03:10:00', '83');
insert into device_data_hourly_approx (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', 2, null, null, 'nibp_s', '2025-07-31 07:10:00', '84');

-- 重返/设备数据 admission_status=>3
update his_patient_records set admission_status = 3 where mrn = '100105';
  -- 操作转出科室：patient_records.discharge_time = 2025-08-21 7:00 (shanghai: 15:00)

  -- id: 6=>7; admission_time: '2025-01-01 00:00:00' => '2025-08-21 10:00:00' (shanghai: 18:00)
INSERT INTO his_patient_records (
    "id", "pid", "mrn", "index_id", "patient_serial_number", "admission_count", "admission_time", "admission_diagnosis", "admission_diagnosis_code",
    "bed_number", "name", "gender", "date_of_birth", "dept_code", "dept_name", "admission_status", "icu_admission_time", "diagnosis_time",
    "diagnosis", "created_at"
) VALUES (7, 'hisPatientId105', '100105', 'hisIndexId105', 'hisPatientSerialNumber105', 1, '2025-08-21 10:00:00', '糖尿病酮症酸中毒', NULL, 
    'A105', '陈措施', 1, '2000-01-01 08:00:00', '99999', '晶医重症医学科', 1, '2025-08-21 10:00:00', '2025-08-21 10:00:00',
    '糖尿病酮症酸中毒', '2025-08-21 10:00:00');

  -- 设备数据 A105 (37.1 被忽略)
insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A105-dev', 'temperature', '2025-08-21 8:00', '37.1');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A105-dev', 'temperature', '2025-08-21 8:00', '37.1');

insert into device_data (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A105-dev', 'temperature', '2025-08-21 11:00', '38.2');
insert into device_data_hourly (department_id, device_id, device_type, device_bed_number, param_code, recorded_at, recorded_str) values (
'99999', null, null, 'A105-dev', 'temperature', '2025-08-21 11:00', '38.2');

-- 重返
update his_patient_records set admission_status = 3 where name = '陈措施';
-- 前端操作出科2025-02-01 00:00:00
update his_patient_records set admission_time = '2025-02-01 01:00:00', icu_admission_time = '2025-02-01 01:00:00', bed_number='C001', admission_status = 1 where name = '陈措施';


-- 省二院报表
select pid, effective_time_midnight, last_processed_at, latest_data_time from patient_nursing_reports where effective_time_midnight>'20250923' and pid = 1 order by effective_time_midnight;
delete from patient_nursing_reports where effective_time_midnight>'20250923';