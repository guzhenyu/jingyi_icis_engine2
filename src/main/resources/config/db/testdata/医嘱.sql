-- 药品 medications (demo)
INSERT INTO medications ("code", "name", "spec", "dose", "dose_unit", "created_at", "confirmed")
VALUES
('med001', '抗凝血药1', '5mg/ml', 5000, 'IU', now(), true),
('med002', '肺表面活性剂1', '25mg/ml', 100, 'mg', now(), true),
('med003', '盐水', '0.9%氯化钠', 1000, 'ml', now(), true),
('med004', '乳酸林格氏液', '平衡盐溶液', 1000, 'ml', now(), true),
('med005', '镇静剂1', '2mg/ml', 2, 'mg', now(), true),
('med006', '广谱抗菌药1', '2mg/粒', 1, '粒', now(), true);

-- 频次 medication_frequencies (预置)

-- 用药方式 administration_routes
-- group_id: 0-微泵，1-点滴，2-静推，3-肠胃，4-其他 
-- intake_type_id: 0-不计入，1-静脉，2-输血，3-肠胃，4-特殊，5-其他
insert into administration_routes ("id", "dept_id", "code", "name", "is_continuous", "group_id", "intake_type_id", "is_valid")
values
(1, '99999', 'infusion_pump', '输液泵', true, 0, 1, true),
(2, '99999', 'iv_intravenous', '点滴-静脉', true, 1, 1, true),
(3, '99999', 'iv_transfusion', '点滴-输血', true, 1, 2, true),
(4, '99999', 'iv_gastric', '点滴-肠胃', true, 1, 3, true),
(5, '99999', 'iv_special', '点滴-特殊', true, 1, 4, true),
(6, '99999', 'iv_other', '点滴-其他', true, 1, 5, true),
(7, '99999', 'push', '静推-静脉', false, 2, 1, true),
(8, '99999', 'push_na', '静推-不计入', false, 2, 0, true);

-- his医嘱 medical_orders
-- order_type: '西药', '中药', ...  (由 allowed_order_type 正向筛选)
-- status: '', '已完成', '已审核', '未审核', ... （由 deny_status 反向筛选）
-- stop_time: 停止时间 (停止时间以后的医嘱不分解，now > stopTime后，医嘱状态变为“已停止”order_group.medication_order_validity_type 为1-已停止)
-- cancel_time: 取消时间（一旦设置，order_group.medication_order_validity_type 为2-已取消，对应医嘱的所有未分解记录都取消）
-- order_duration_type: 0-长期，1-临时，2-补录，3-不确定
-- administration_route_code: 用药途径编码 （由 deny_administration_route_code 反向筛选）
insert into medical_orders (
    "order_id", "his_patient_id", "group_id", "ordering_doctor", "ordering_doctor_id", "dept_id",
    "order_type", "status", "order_time", "stop_time", "cancel_time",
    "order_code", "order_name", "spec", "dose", "dose_unit",
    "order_duration_type", "plan_time", "freq_code", "first_day_exe_count",
    "administration_route_code", "administration_route_name",
    "created_at"
) values
(
    'mo_1_1', 'hisPatientId100', 'mog_1', 'doctorA', 'doctorA-id', '99999',
    '西药', '', '2025-07-01 01:00:00', null, null,
    '多药物1/3-长嘱-一日2次-点滴', '多药物1/3-长嘱-一日2次-点滴', '100ml/袋', 1000, 'ml',
    0, '2025-07-01 01:00:00', 'bid', 2, 'iv_intravenous', '点滴', now()
),
(
    'mo_1_2', 'hisPatientId100', 'mog_1', 'doctorA', 'doctorA-id', '99999',
    '西药', '', '2025-07-01 01:00:00', null, null,
    '多药物2/3-长嘱-一日2次-点滴', '多药物2/3-长嘱-一日2次-点滴', '100ml/袋', 1000, 'ml',
    0, '2025-07-01 01:00:00', 'bid', 2, 'iv_intravenous', '点滴', now()
),
(
    'mo_1_3', 'hisPatientId100', 'mog_1', 'doctorA', 'doctorA-id', '99999',
    '西药', '', '2025-07-01 01:00:00', null, null,
    '多药物3/3-长嘱-一日2次-点滴', '多药物3/3-长嘱-一日2次-点滴', '100ml/袋', 1000, 'ml',
    0, '2025-07-01 01:00:00', 'bid', 2, 'iv_intravenous', '点滴', now()
),
(
    'mo_2_1', 'hisPatientId100', 'mog_2', 'doctorA', 'doctorA-id', '99999',
    '西药', '', '2025-07-01 01:00:00', null, null,
    '单药物1-长嘱-一日2次-点滴', '单药物1-长嘱-一日2次-点滴', '100ml/袋', 1000, 'ml',
    0, '2025-07-01 01:00:00', 'bid', 2, 'iv_intravenous', '点滴', now()
),
(
    'mo_3_1', 'hisPatientId100', 'mog_3', 'doctorA', 'doctorA-id', '99999',
    '西药', '', '2025-07-01 00:00:00', null, null,
    '单药物2-临时-一日2次-点滴', '单药物2-临时-一日2次-点滴', '100ml/袋', 1000, 'ml',
    1, '2025-07-01 01:00:00', 'bid', 2, 'iv_intravenous', '点滴', now()
),
(
    'mo_4_1', 'hisPatientId100', 'mog_4', 'doctorA', 'doctorA-id', '99999',
    '西药', '', '2025-07-01 00:00:00', null, null,
    '单药物2-临时-一日2次-点滴', '单药物2-临时-一日2次-点滴', '100ml/袋', 1000, 'ml',
    1, '2025-07-01 01:00:00', 'bid', 2, 'push', '静推', now()
);

-- 清理医嘱
delete from medication_execution_actions;
delete from medication_execution_record_stats;
delete from medication_execution_records;
delete from medication_order_groups;

-- 调试
select * from medication_execution_record_stats;
select * from patient_monitoring_records where monitoring_param_code in ('hourly_intake', 'intravenous_intake');
select * from patient_monitoring_record_stats_daily;

select pid, effective_time_midnight, last_processed_at, latest_data_time from patient_nursing_reports where effective_time_midnight>'20250923' and pid = 1 order by effective_time_midnight;
delete from patient_nursing_reports where effective_time_midnight>'20250923';