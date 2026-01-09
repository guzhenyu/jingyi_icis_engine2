INSERT INTO patient_lis_items (
report_id, mrn, his_pid, lis_item_name, lis_item_short_name, lis_item_code, order_id, order_dept, order_dept_id, order_doctor, order_doctor_id, sample_id, sample_name, collect_time, receive_time, auth_time, auth_doctor, status
) VALUES (
    'RPT1001', '100100', 'hisPatientId100', '肝功能', '肝功能', 'ggn', 'ORDER20250721001', '肝病科', 'dept001', '张医生', 'doc1001', 'SMP20250721001', '静脉血', '2025-07-20 22:00:00', '2025-07-20 23:00:00', '2025-07-21 01:00:00', '李医生', '审核'
);

INSERT INTO patient_lis_results (
    report_id, external_param_code, external_param_name, unit, result_str, auth_time, auth_doctor, notes, alarm_flag, danger_flag, normal_min_str, normal_max_str, danger_min_str, danger_max_str, is_deleted, modified_by, modified_at
) VALUES 
('RPT1001', 'ALT', '谷丙转氨酶', 'U/L', '432.0', '2025-07-21 01:00:00', '李四', '值显著升高，可能肝损伤', '高', '危险', '0', '40', '0', '200', false, 'system', NOW()),
('RPT1001', 'AST', '谷草转氨酶', 'U/L', '35.0', '2025-07-21 01:00:00', '李四', NULL, NULL, NULL, '0', '40', '0', '200', false, 'system', NOW()),
('RPT1001', 'TBIL', '总胆红素', 'umol/L', '35.0', '2025-07-21 01:00:00', '李四', '略高', '高', NULL, '0', '25', NULL, NULL, false, 'system', NOW());