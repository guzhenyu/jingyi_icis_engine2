-- $ psql -d jingyi_dev -U jingyi_dev
-- jingyi_dev=> \i 'schema.postgresql.sql'

-- DROP DATABASE IF EXISTS jingyi_icis_db;
-- CREATE DATABASE jingyi_icis_db;
-- \connect jingyi_icis_db;

---- 1. 用户权限模块
CREATE TABLE rbac_permissions (
    id INT,
    name VARCHAR(255),
    PRIMARY KEY (id)
);
COMMENT ON COLUMN rbac_permissions.id IS '权限ID';
COMMENT ON COLUMN rbac_permissions.name IS '权限名称';

CREATE TABLE rbac_roles (
    id INT,
    name VARCHAR(255) NOT NULL,
    is_primary BOOLEAN NOT NULL,
    PRIMARY KEY (id)
);
COMMENT ON COLUMN rbac_roles.id IS '角色ID';
COMMENT ON COLUMN rbac_roles.name IS '角色名称';

CREATE TABLE rbac_roles_permissions (
    role_id INT,
    permission_id INT,
    PRIMARY KEY (role_id, permission_id),
    FOREIGN KEY (role_id) REFERENCES rbac_roles(id),
    FOREIGN KEY (permission_id) REFERENCES rbac_permissions(id)
);
COMMENT ON COLUMN rbac_roles_permissions.role_id IS '角色';
COMMENT ON COLUMN rbac_roles_permissions.permission_id IS '权限';

CREATE TABLE rbac_roles_roles (
    parent_role_id INT,
    child_role_id INT,
    PRIMARY KEY (parent_role_id, child_role_id),
    FOREIGN KEY (parent_role_id) REFERENCES rbac_roles(id),
    FOREIGN KEY (child_role_id) REFERENCES rbac_roles(id)
);
COMMENT ON COLUMN rbac_roles_roles.parent_role_id IS '父角色';
COMMENT ON COLUMN rbac_roles_roles.child_role_id IS '子角色';

CREATE TABLE rbac_accounts (
    account_id VARCHAR(255),
    account_name VARCHAR(255),
    password_hash VARCHAR(1800),
    PRIMARY KEY (account_id)
);
COMMENT ON COLUMN rbac_accounts.account_id IS '账号';
COMMENT ON COLUMN rbac_accounts.password_hash IS '密码hash';

-- 账户基本信息，未来可以考虑扩展oauth2_id， oauth2_provider等字段
CREATE TABLE accounts (
    id BIGSERIAL PRIMARY KEY,
    account_id VARCHAR(255) NOT NULL,
    name VARCHAR(100) NOT NULL,
    gender INTEGER,
    date_of_birth TIMESTAMP,
    position VARCHAR(100),
    title VARCHAR(100),
    education_level VARCHAR(100),
    marital_status INTEGER,
    phone VARCHAR(100),
    start_date TIMESTAMP,
    id_card_number VARCHAR(100),
    sign_pic TEXT,
    ca_id VARCHAR(255),
    ca_sign_pic TEXT,
    ca_cert TEXT,
    is_disabled INTEGER,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    modified_at TIMESTAMP,
    modified_by VARCHAR(255)
);
COMMENT ON COLUMN accounts.id IS '自增主键';
COMMENT ON COLUMN accounts.account_id IS '账号';
COMMENT ON COLUMN accounts.name IS '姓名';
COMMENT ON COLUMN accounts.gender IS '性别';
COMMENT ON COLUMN accounts.date_of_birth IS '生日';
COMMENT ON COLUMN accounts.position IS '职位';
COMMENT ON COLUMN accounts.title IS '职称';
COMMENT ON COLUMN accounts.education_level IS '学历';
COMMENT ON COLUMN accounts.marital_status IS '婚姻状况，系统枚举值';
COMMENT ON COLUMN accounts.phone IS '手机号';
COMMENT ON COLUMN accounts.start_date IS '入职日期';
COMMENT ON COLUMN accounts.id_card_number IS '身份证号';
COMMENT ON COLUMN accounts.sign_pic IS '签名图片，base64编码';
COMMENT ON COLUMN accounts.ca_id IS 'CA证书ID';
COMMENT ON COLUMN accounts.ca_sign_pic IS 'CA签名图片';
COMMENT ON COLUMN accounts.ca_cert IS 'CA证书';
COMMENT ON COLUMN accounts.is_disabled IS '是否被禁用';
COMMENT ON COLUMN accounts.is_deleted IS '是否已删除';
COMMENT ON COLUMN accounts.deleted_at IS '删除时间';
COMMENT ON COLUMN accounts.deleted_by IS '删除人';
COMMENT ON COLUMN accounts.modified_at IS '修改时间';
COMMENT ON COLUMN accounts.modified_by IS '修改人';
CREATE UNIQUE INDEX idx_accounts_account_id ON accounts (account_id) WHERE is_deleted = false;

CREATE TABLE rbac_accounts_roles (
    account_id VARCHAR(255),
    role_id INT,
    PRIMARY KEY (account_id, role_id),
    FOREIGN KEY (account_id) REFERENCES rbac_accounts(account_id),
    FOREIGN KEY (role_id) REFERENCES rbac_roles(id)
);
COMMENT ON COLUMN rbac_accounts_roles.account_id IS '账号';
COMMENT ON COLUMN rbac_accounts_roles.role_id IS '角色';

CREATE TABLE rbac_accounts_permissions (
    account_id VARCHAR(255),
    permission_id INT,
    PRIMARY KEY (account_id, permission_id),
    FOREIGN KEY (account_id) REFERENCES rbac_accounts(account_id),
    FOREIGN KEY (permission_id) REFERENCES rbac_permissions(id)
);
COMMENT ON COLUMN rbac_accounts_permissions.account_id IS '账号';
COMMENT ON COLUMN rbac_accounts_permissions.permission_id IS '权限';

CREATE TABLE rbac_departments (
    dept_id VARCHAR(255),
    dept_name VARCHAR(255),
    PRIMARY KEY (dept_id)
);
COMMENT ON COLUMN rbac_departments.dept_id IS '部门id';
COMMENT ON COLUMN rbac_departments.dept_name IS '部门名称';

CREATE TABLE departments (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    abbreviation VARCHAR(255) NOT NULL,
    ward_code VARCHAR(255),
    ward_name VARCHAR(255),
    hospital_name VARCHAR(255) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    modified_at TIMESTAMP,
    modified_by VARCHAR(255)
);
COMMENT ON TABLE departments IS '部门表';
COMMENT ON COLUMN departments.id IS '自增主键';
COMMENT ON COLUMN departments.dept_id IS '部门编码，和his的一致';
COMMENT ON COLUMN departments.name IS '部门名称';
COMMENT ON COLUMN departments.abbreviation IS '部门简称';
COMMENT ON COLUMN departments.ward_code IS '病区编码';
COMMENT ON COLUMN departments.ward_name IS '病区名称';
COMMENT ON COLUMN departments.hospital_name IS '所属医院名称';
COMMENT ON COLUMN departments.is_deleted IS '是否已删除';
COMMENT ON COLUMN departments.deleted_at IS '删除时间';
COMMENT ON COLUMN departments.deleted_by IS '删除人';
COMMENT ON COLUMN departments.modified_at IS '修改时间';
COMMENT ON COLUMN departments.modified_by IS '修改人';
CREATE UNIQUE INDEX idx_departments_dept_id ON departments (dept_id) WHERE is_deleted = false;

CREATE TABLE rbac_accounts_departments (
    account_id VARCHAR(255),
    dept_id VARCHAR(255),
    primary_role_id INT,
    PRIMARY KEY (account_id, dept_id),
    FOREIGN KEY (account_id) REFERENCES rbac_accounts(account_id),
    FOREIGN KEY (dept_id) REFERENCES rbac_departments(dept_id),
    FOREIGN KEY (primary_role_id) REFERENCES rbac_roles(id)
);
COMMENT ON COLUMN rbac_accounts_departments.account_id IS '账号';
COMMENT ON COLUMN rbac_accounts_departments.dept_id IS '部门';
COMMENT ON COLUMN rbac_accounts_departments.primary_role_id IS '主要角色';

CREATE TABLE accounts_departments (
    id SERIAL PRIMARY KEY,
    employee_id  BIGINT NOT NULL,
    account_id  VARCHAR(255) NOT NULL,
    dept_id  VARCHAR(255) NOT NULL,
    primary_role_id  INT NOT NULL,
    start_date  TIMESTAMP NOT NULL,
    is_deleted  BOOLEAN NOT NULL,
    deleted_at  TIMESTAMP,
    deleted_by  VARCHAR(255)
);
COMMENT ON TABLE accounts_departments IS '账号部门关联表，记录了历史变更信息，用于质控统计';
COMMENT ON COLUMN accounts_departments.employee_id IS '员工ID，对应 accounts.id';
COMMENT ON COLUMN accounts_departments.account_id IS '账号，对应 accounts.account_id';
COMMENT ON COLUMN accounts_departments.dept_id IS '部门，对应 departments.dept_id';
COMMENT ON COLUMN accounts_departments.primary_role_id IS '主要角色';
COMMENT ON COLUMN accounts_departments.start_date IS '开始时间';
COMMENT ON COLUMN accounts_departments.is_deleted IS '是否已删除';
COMMENT ON COLUMN accounts_departments.deleted_at IS '删除时间';
COMMENT ON COLUMN accounts_departments.deleted_by IS '删除人';

CREATE UNIQUE INDEX idx_accounts_departments ON accounts_departments (account_id, dept_id)
WHERE is_deleted = false;

CREATE TABLE rbac_accounts_departments_roles (
    account_id VARCHAR(255),
    dept_id VARCHAR(255),
    role_id INT,
    PRIMARY KEY (account_id, dept_id, role_id),
    FOREIGN KEY (account_id, dept_id) REFERENCES rbac_accounts_departments(account_id, dept_id),
    FOREIGN KEY (role_id) REFERENCES rbac_roles(id)
);
COMMENT ON COLUMN rbac_accounts_departments_roles.account_id IS '账号';
COMMENT ON COLUMN rbac_accounts_departments_roles.dept_id IS '部门';
COMMENT ON COLUMN rbac_accounts_departments_roles.role_id IS '角色';

CREATE TABLE rbac_accounts_departments_permissions (
    account_id VARCHAR(255),
    dept_id VARCHAR(255),
    permission_id INT,
    PRIMARY KEY (account_id, dept_id, permission_id),
    FOREIGN KEY (account_id, dept_id) REFERENCES rbac_accounts_departments(account_id, dept_id),
    FOREIGN KEY (permission_id) REFERENCES rbac_permissions(id)
);
COMMENT ON COLUMN rbac_accounts_departments_permissions.account_id IS '账号';
COMMENT ON COLUMN rbac_accounts_departments_permissions.dept_id IS '部门';
COMMENT ON COLUMN rbac_accounts_departments_permissions.permission_id IS '权限';

-- 2. 病人基本信息模块
CREATE TABLE his_patient_records (
    id BIGSERIAL PRIMARY KEY,
    -- HIS相关
    pid VARCHAR(255), -- 本次病历全局ID，对应his_patient_id
    mrn VARCHAR(255) NOT NULL, -- 住院号，对应his_mrn
    index_id VARCHAR(255), -- 病案首页ID，对应his_index_id
    patient_serial_number VARCHAR(255), -- 病人流水号，对应his_patient_serial_number
    admission_count INT, -- 住院次数，对应his_admission_count
    admission_time TIMESTAMP, -- 住院时间，对应his_admission_time
    admission_diagnosis TEXT, -- 入院诊断，对应his_admission_diagnosis
    admission_diagnosis_code VARCHAR(1000), -- 入院诊断编码，对应his_admission_diagnosis_code
    bed_number VARCHAR(50), -- 床号，对应his_bed_number
    -- 基本信息
    name VARCHAR(100), -- 姓名，对应icu_name
    gender INTEGER, -- 性别，对应icu_gender
    date_of_birth TIMESTAMP, -- 出生年月，对应icu_date_of_birth
    height REAL, -- 身高，单位：厘米，对应height
    weight REAL, -- 体重，单位：千克，对应weight
    blood_type VARCHAR(50), -- 血型类型，对应blood_type
    blood_rh VARCHAR(50), -- 血型Rh，对应blood_rh
    -- 病史
    past_medical_history VARCHAR(255), -- 既往史，对应past_medical_history
    allergies VARCHAR(255), -- 过敏史，对应allergies
    -- 社会信息
    phone VARCHAR(50), -- 联系电话，对应phone
    home_address VARCHAR(255), -- 家庭地址，对应home_address
    document_type VARCHAR(50), -- 证件类型，对应document_type
    id_card_number VARCHAR(100), -- 身份证，对应id_card_number
    nation VARCHAR(50), -- 民族，对应nation
    native_place VARCHAR(50), -- 籍贯，对应native_place
    occupation VARCHAR(50), -- 职业，对应occupation
    emergency_contact_name VARCHAR(50), -- 联系人姓名，对应emergency_contact_name
    emergency_contact_relation VARCHAR(50), -- 联系人关系，对应emergency_contact_relation
    emergency_contact_phone VARCHAR(50), -- 联系人电话，对应emergency_contact_phone
    payment_method VARCHAR(50), -- 结算方式，对应payment_method
    insurance_type VARCHAR(50), -- 医保类型，对应insurance_type
    insurance_number VARCHAR(50), -- 医保卡号，对应insurance_number
    medical_card_number VARCHAR(50), -- 就诊卡号，对应medical_card_number
    -- 状态及需求
    is_vip_patient BOOLEAN, -- 绿色通道，对应is_vip_patient
    illness_severity_level VARCHAR(50), -- 病情分级，对应illness_severity_level
    chief_complaint VARCHAR(255), -- 主诉，对应chief_complaint
    -- 科室信息
    dept_code VARCHAR(50), -- 科室编码，对应dept_id
    dept_name VARCHAR(50), -- 科室名称，对应dept_name
    ward_code VARCHAR(50), -- 病区编码，对应ward_code
    ward_name VARCHAR(255), -- 病区名称，对应ward_name
    attending_doctor_name VARCHAR(255), -- 主治医生姓名，需要转化为为attending_doctor_id
    -- 入科信息
    admission_source_dept_name VARCHAR(255), -- 入科来源，对应admission_source_dept_name
    admission_status INTEGER, -- 在科状态，对应admission_status
    icu_admission_time TIMESTAMP, -- 入ICU时间，对应admission_time
    -- 诊断信息
    diagnosis_time TIMESTAMP, -- 诊断时间，原始表中未明确对应，新增字段
    diagnosis_code VARCHAR(1000), -- 入科诊断编码，原始表中未明确对应，参考diagnosis
    diagnosis TEXT, -- 入科诊断，对应diagnosis
    diagnosis_tcm_time TIMESTAMP, -- 中医诊断时间，对应diagnosis_tcm_time
    diagnosis_tcm_code VARCHAR(1000), -- 中医临床诊断编码，对应diagnosis_tcm_code
    diagnosis_tcm TEXT, -- 中医临床诊断，对应diagnosis_tcm
    -- 出科信息
    discharged_type INTEGER, -- 出科类型，对应discharged_type
    discharged_dept_id VARCHAR(255), -- 转出科室编码，对应discharged_dept_id
    discharged_dept_name VARCHAR(255), -- 转出科室名称，对应discharged_dept_name
    discharge_time TIMESTAMP, -- 转出ICU时间，对应discharge_time
    -- 手术信息
    operation VARCHAR(255), -- 最近的手术名称，新增字段
    operation_time TIMESTAMP, -- 最近的手术时间，新增字段
    created_at TIMESTAMP NOT NULL -- 记录创建时间，新增字段
);

-- 表注释
COMMENT ON TABLE his_patient_records IS '病人基本信息（HIS系统）中间表';

-- 字段注释
COMMENT ON COLUMN his_patient_records.id IS '自增主键';
COMMENT ON COLUMN his_patient_records.pid IS '本次病历全局ID，对应HIS系统中的病人记录表主键ID';
COMMENT ON COLUMN his_patient_records.mrn IS '住院号，Medical Record Number';
COMMENT ON COLUMN his_patient_records.index_id IS '病案首页ID';
COMMENT ON COLUMN his_patient_records.patient_serial_number IS '病人流水号';
COMMENT ON COLUMN his_patient_records.admission_count IS '住院次数';
COMMENT ON COLUMN his_patient_records.admission_time IS '住院时间';
COMMENT ON COLUMN his_patient_records.admission_diagnosis IS '入院诊断';
COMMENT ON COLUMN his_patient_records.admission_diagnosis_code IS '入院诊断编码';
COMMENT ON COLUMN his_patient_records.bed_number IS '床号';
COMMENT ON COLUMN his_patient_records.name IS '姓名';
COMMENT ON COLUMN his_patient_records.gender IS '性别，系统枚举值';
COMMENT ON COLUMN his_patient_records.date_of_birth IS '出生年月，TIMESTAMP格式以支持儿科重症需求';
COMMENT ON COLUMN his_patient_records.height IS '身高，单位：厘米';
COMMENT ON COLUMN his_patient_records.weight IS '体重，单位：千克';
COMMENT ON COLUMN his_patient_records.blood_type IS '血型类型，A, B, O, AB';
COMMENT ON COLUMN his_patient_records.blood_rh IS '血型Rh, +, -';
COMMENT ON COLUMN his_patient_records.past_medical_history IS '既往史，手术史、外伤史、家族史等';
COMMENT ON COLUMN his_patient_records.allergies IS '过敏史';
COMMENT ON COLUMN his_patient_records.phone IS '联系电话';
COMMENT ON COLUMN his_patient_records.home_address IS '家庭地址';
COMMENT ON COLUMN his_patient_records.document_type IS '证件类型，身份证、护照、军官证、驾驶证等';
COMMENT ON COLUMN his_patient_records.id_card_number IS '身份证号';
COMMENT ON COLUMN his_patient_records.nation IS '民族';
COMMENT ON COLUMN his_patient_records.native_place IS '籍贯';
COMMENT ON COLUMN his_patient_records.occupation IS '职业';
COMMENT ON COLUMN his_patient_records.emergency_contact_name IS '联系人姓名';
COMMENT ON COLUMN his_patient_records.emergency_contact_relation IS '联系人关系';
COMMENT ON COLUMN his_patient_records.emergency_contact_phone IS '联系人电话';
COMMENT ON COLUMN his_patient_records.payment_method IS '结算方式';
COMMENT ON COLUMN his_patient_records.insurance_type IS '医保类型';
COMMENT ON COLUMN his_patient_records.insurance_number IS '医保卡号';
COMMENT ON COLUMN his_patient_records.medical_card_number IS '就诊卡号';
COMMENT ON COLUMN his_patient_records.is_vip_patient IS '是否绿色通道（VIP病人）';
COMMENT ON COLUMN his_patient_records.illness_severity_level IS '病情分级';
COMMENT ON COLUMN his_patient_records.chief_complaint IS '主诉';
COMMENT ON COLUMN his_patient_records.dept_code IS '科室编码';
COMMENT ON COLUMN his_patient_records.dept_name IS '科室名称';
COMMENT ON COLUMN his_patient_records.ward_code IS '病区编码';
COMMENT ON COLUMN his_patient_records.ward_name IS '病区名称';
COMMENT ON COLUMN his_patient_records.attending_doctor_name IS '主治医生姓名';
COMMENT ON COLUMN his_patient_records.admission_source_dept_name IS '入科来源科室名称';
COMMENT ON COLUMN his_patient_records.admission_status IS '在科状态';
COMMENT ON COLUMN his_patient_records.icu_admission_time IS '入ICU时间';
COMMENT ON COLUMN his_patient_records.diagnosis_time IS '诊断时间';
COMMENT ON COLUMN his_patient_records.diagnosis_code IS '入科诊断编码';
COMMENT ON COLUMN his_patient_records.diagnosis IS '入科诊断';
COMMENT ON COLUMN his_patient_records.diagnosis_tcm_time IS '中医诊断时间';
COMMENT ON COLUMN his_patient_records.diagnosis_tcm_code IS '中医临床诊断编码';
COMMENT ON COLUMN his_patient_records.diagnosis_tcm IS '中医临床诊断';
COMMENT ON COLUMN his_patient_records.discharged_type IS '出科类型：转出、死亡、出院';
COMMENT ON COLUMN his_patient_records.discharged_dept_id IS '转出科室编码';
COMMENT ON COLUMN his_patient_records.discharged_dept_name IS '转出科室名称';
COMMENT ON COLUMN his_patient_records.discharge_time IS '转出ICU时间';
COMMENT ON COLUMN his_patient_records.operation IS '最近的手术名称';
COMMENT ON COLUMN his_patient_records.operation_time IS '最近的手术时间';
COMMENT ON COLUMN his_patient_records.created_at IS '记录创建时间';
CREATE INDEX idx_his_patient_records_mrn_admission_status ON his_patient_records (mrn, admission_status);

CREATE TABLE patient_records (
    id BIGSERIAL PRIMARY KEY,
    -- HIS
    his_mrn VARCHAR(255) NOT NULL,
    his_patient_id VARCHAR(255),
    his_index_id VARCHAR(255),
    his_patient_serial_number VARCHAR(255),
    his_admission_count INT,
    his_admission_time TIMESTAMP,
    his_admission_diagnosis TEXT,
    his_admission_diagnosis_code VARCHAR(1000),
    his_bed_number VARCHAR(50),
    icu_manual_entry BOOLEAN,
    icu_manual_entry_account_id VARCHAR(255),
    icu_name VARCHAR(100),
    -- 生理特征
    icu_gender INTEGER,
    icu_date_of_birth TIMESTAMP,
    height REAL,  -- cm
    weight REAL,  -- kg
    weight_type VARCHAR(100),
    weight_ibw VARCHAR(100),
    blood_type VARCHAR(50),
    blood_rh VARCHAR(50),
    bsa REAL,  -- 体表面积：m2
    -- 病史
    past_medical_history VARCHAR(255),
    allergies VARCHAR(255),
    -- 社会信息
    phone VARCHAR(50),
    home_phone VARCHAR(50),
    home_address VARCHAR(255),
    document_type VARCHAR(50),
    id_card_number VARCHAR(100),
    nation VARCHAR(50),
    native_place VARCHAR(50),
    education_level INT,
    occupation VARCHAR(50),
    marital_status INTEGER,
    emergency_contact_name VARCHAR(50),
    emergency_contact_relation VARCHAR(50),
    emergency_contact_phone VARCHAR(50),
    emergency_contact_address VARCHAR(255),
    payment_method VARCHAR(50),
    insurance_type VARCHAR(50),
    insurance_number VARCHAR(50),
    medical_card_number VARCHAR(50),
    -- 状态及需求
    is_vip_patient BOOLEAN,
    wristband_location VARCHAR(255),
    patient_pose VARCHAR(50),
    nursing_care_level VARCHAR(50),
    illness_severity_level VARCHAR(50),
    diet_type VARCHAR(50),
    isolation_precaution VARCHAR(50),
    chief_complaint VARCHAR(255),
    -- 医护信息
    dept_id VARCHAR(50),
    dept_name VARCHAR(50),
    ward_code VARCHAR(50),
    ward_name VARCHAR(255),
    attending_doctor_id VARCHAR(255),
    primary_care_doctor_id VARCHAR(255),
    admitting_doctor_id VARCHAR(255),
    responsible_nurse_id VARCHAR(255),
    -- 入科
    admission_source_dept_name VARCHAR(255),
    admission_source_dept_id VARCHAR(255),
    admission_type INTEGER,
    is_planned_admission BOOLEAN,
    unplanned_admission_reason VARCHAR(255),
    admission_status INTEGER,
    admission_time TIMESTAMP,
    admission_edit_time TIMESTAMP,
    admitting_account_id VARCHAR(255),

    -- 诊断
    diagnosis TEXT,

    -- 中医诊断
    diagnosis_tcm TEXT,

    -- 诊断类型, 较少使用，reserved
    diagnosis_type VARCHAR(100),

    -- 手术
    surgery_operation VARCHAR(255),
    surgery_operation_time TIMESTAMP,

    -- 出科
    discharged_type INTEGER,
    discharged_death_time TIMESTAMP,
    discharged_hospital_exit_time TIMESTAMP,
    discharged_diagnosis TEXT,
    discharged_diagnosis_code VARCHAR(1000),
    discharged_dept_name VARCHAR(255),
    discharged_dept_id VARCHAR(255),
    discharge_time TIMESTAMP,
    discharge_edit_time TIMESTAMP,
    discharging_account_id VARCHAR(255),
    created_at TIMESTAMP
    --FOREIGN KEY (icu_manual_entry_account_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (attending_doctor_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (primary_care_doctor_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (admitting_doctor_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (responsible_nurse_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (admitting_account_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (diagnosis_account_id) REFERENCES rbac_accounts(account_id),
    --FOREIGN KEY (discharging_account_id) REFERENCES rbac_accounts(account_id)
);
COMMENT ON TABLE patient_records IS '病人基本信息';
COMMENT ON COLUMN patient_records.id IS '自增主键';
COMMENT ON COLUMN patient_records.his_mrn IS '病历号, Medical Record Number';
COMMENT ON COLUMN patient_records.his_patient_id IS 'HIS系统中的病人记录表主键ID';
COMMENT ON COLUMN patient_records.his_index_id IS '病案首页ID';
COMMENT ON COLUMN patient_records.his_patient_serial_number IS 'HIS系统中的病人记录表中的病人流水号';
COMMENT ON COLUMN patient_records.his_admission_count IS 'HIS系统中的病人入院次数';
COMMENT ON COLUMN patient_records.his_admission_time IS 'HIS系统中的病人入院时间';
COMMENT ON COLUMN patient_records.his_admission_diagnosis IS 'HIS系统中的病人入院诊断';
COMMENT ON COLUMN patient_records.his_admission_diagnosis_code IS 'HIS系统中的病人入院诊断编码';
COMMENT ON COLUMN patient_records.his_bed_number IS 'HIS系统中的病人床位号';
COMMENT ON COLUMN patient_records.icu_manual_entry IS '本条数据是否由手动添加';
COMMENT ON COLUMN patient_records.icu_manual_entry_account_id IS '手动添加人';
COMMENT ON COLUMN patient_records.icu_name IS '姓名。该字段默认由HIS系统提供；断网等特殊情况下，可以由ICU系统提供，但是需要在HIS系统恢复后和HIS保持同步';
COMMENT ON COLUMN patient_records.icu_gender IS '性别。该字段默认由HIS系统提供；断网等特殊情况下，可以由ICU系统提供，但是需要在HIS系统恢复后和HIS保持同步';
COMMENT ON COLUMN patient_records.icu_date_of_birth IS '生日，儿科重症需要展示TIMESTAMP，DATE不够。该字段默认由HIS系统提供；断网等特殊情况下，可以由ICU系统提供，但是需要在HIS系统恢复后和HIS保持同步';
COMMENT ON COLUMN patient_records.height IS '身高，单位：厘米';
COMMENT ON COLUMN patient_records.weight IS '体重，单位：千克';
COMMENT ON COLUMN patient_records.weight_type IS '体重类型，如平车、卧床、轮椅等；如果为空，表示真实体重';
COMMENT ON COLUMN patient_records.weight_ibw IS '理想体重(IBW)：一个人在最佳健康状态下的理想体重范围';
COMMENT ON COLUMN patient_records.blood_type IS '血型, A, B, O, AB';
COMMENT ON COLUMN patient_records.blood_rh IS '血型Rh, +, -';
COMMENT ON COLUMN patient_records.bsa IS '体表面积，单位：m2';
COMMENT ON COLUMN patient_records.past_medical_history IS '既往病史，手术史、外伤史、家族史等';
COMMENT ON COLUMN patient_records.allergies IS '过敏史';
COMMENT ON COLUMN patient_records.phone IS '电话';
COMMENT ON COLUMN patient_records.home_phone IS '家庭电话';
COMMENT ON COLUMN patient_records.home_address IS '家庭地址';
COMMENT ON COLUMN patient_records.document_type IS '证件类型, 身份证、护照、军官证、驾驶证等';
COMMENT ON COLUMN patient_records.id_card_number IS '身份证号';
COMMENT ON COLUMN patient_records.nation IS '民族';
COMMENT ON COLUMN patient_records.native_place IS '籍贯';
COMMENT ON COLUMN patient_records.education_level IS '学历';
COMMENT ON COLUMN patient_records.occupation IS '职业';
COMMENT ON COLUMN patient_records.marital_status IS '婚姻状况，系统枚举值';
COMMENT ON COLUMN patient_records.emergency_contact_name IS '紧急联系人姓名';
COMMENT ON COLUMN patient_records.emergency_contact_relation IS '紧急联系人关系';
COMMENT ON COLUMN patient_records.emergency_contact_phone IS '紧急联系人电话';
COMMENT ON COLUMN patient_records.emergency_contact_address IS '紧急联系人地址';
COMMENT ON COLUMN patient_records.payment_method IS '结算方式';
COMMENT ON COLUMN patient_records.insurance_type IS '医保类型';
COMMENT ON COLUMN patient_records.insurance_number IS '医保号';
COMMENT ON COLUMN patient_records.medical_card_number IS '就诊卡号';
COMMENT ON COLUMN patient_records.is_vip_patient IS '是否VIP病人';
COMMENT ON COLUMN patient_records.wristband_location IS '腕带位置，左手、右手、左脚、右脚、其他';
COMMENT ON COLUMN patient_records.patient_pose IS '病人体位, 卧床/平车/轮椅/坐椅/半卧';
COMMENT ON COLUMN patient_records.nursing_care_level IS '护理等级';
COMMENT ON COLUMN patient_records.illness_severity_level IS '病情分级';
COMMENT ON COLUMN patient_records.diet_type IS '饮食类型';
COMMENT ON COLUMN patient_records.isolation_precaution IS '隔离措施';
COMMENT ON COLUMN patient_records.chief_complaint IS '主诉';
COMMENT ON COLUMN patient_records.dept_id IS '科室ID';
COMMENT ON COLUMN patient_records.dept_name IS '科室名称';
COMMENT ON COLUMN patient_records.ward_code IS '病区编码';
COMMENT ON COLUMN patient_records.ward_name IS '病区名称';
COMMENT ON COLUMN patient_records.attending_doctor_id IS '主治医生ID';
COMMENT ON COLUMN patient_records.primary_care_doctor_id IS '管床医生ID';
COMMENT ON COLUMN patient_records.admitting_doctor_id IS '收治医生ID';
COMMENT ON COLUMN patient_records.responsible_nurse_id IS '责任护士ID';
COMMENT ON COLUMN patient_records.admission_source_dept_name IS '入科来源科室名称，也可以是护士手动输入';
COMMENT ON COLUMN patient_records.admission_source_dept_id IS '入科来源科室ID，如果入科来源科室名称不是HIS系统中的名称，该id置空';
COMMENT ON COLUMN patient_records.admission_type IS '入科类型：入院、转入、手术、抢救、重症、外院、病危等';
COMMENT ON COLUMN patient_records.is_planned_admission IS '是否计划入科';
COMMENT ON COLUMN patient_records.unplanned_admission_reason IS '非计划入科原因';
COMMENT ON COLUMN patient_records.admission_status IS '入科状态';
COMMENT ON COLUMN patient_records.admission_time IS '入科时间';
COMMENT ON COLUMN patient_records.admission_edit_time IS '入科时间修改时间';
COMMENT ON COLUMN patient_records.admitting_account_id IS '入科操作员';

COMMENT ON COLUMN patient_records.diagnosis IS 'ICU诊断';
COMMENT ON COLUMN patient_records.diagnosis_tcm IS '中医诊断, Traditional Chinese Medicine';
COMMENT ON COLUMN patient_records.diagnosis_type IS '诊断类型，较少使用，reserved';

COMMENT ON COLUMN patient_records.surgery_operation IS '最近的手术名称';
COMMENT ON COLUMN patient_records.surgery_operation_time IS '最近的手术时间';

COMMENT ON COLUMN patient_records.discharged_type IS '出科类型：转出、死亡、出院';
COMMENT ON COLUMN patient_records.discharged_death_time IS '死亡时间。当出科类型为*死亡*时，该字段有效';
COMMENT ON COLUMN patient_records.discharged_hospital_exit_time IS '出院时间。当出科类型为*出院*时，该字段有效';
COMMENT ON COLUMN patient_records.discharged_diagnosis IS '出科诊断';
COMMENT ON COLUMN patient_records.discharged_diagnosis_code IS '出科诊断编码';
COMMENT ON COLUMN patient_records.discharged_dept_name IS '转出科室名称';
COMMENT ON COLUMN patient_records.discharged_dept_id IS '转出科室ID';
COMMENT ON COLUMN patient_records.discharge_time IS '出科时间';
COMMENT ON COLUMN patient_records.discharge_edit_time IS '出科时间修改时间';
COMMENT ON COLUMN patient_records.discharging_account_id IS '出科操作员';
COMMENT ON COLUMN patient_records.created_at IS '重症系统中本条记录的创建时间';
CREATE INDEX idx_patient_records_his_mrn ON patient_records (his_mrn);  -- 不是UNIQUE INDEX, 同一个mrn，不同id代表同一个病人的不同病例。
CREATE INDEX idx_patient_records_admission_status_his_bed_number ON patient_records (admission_status, his_bed_number);

CREATE TABLE diagnosis_history (
    id SERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL,

    diagnosis TEXT,
    diagnosis_code VARCHAR(1000),
    diagnosis_tcm TEXT,
    diagnosis_tcm_code VARCHAR(1000),

    diagnosis_time TIMESTAMP NOT NULL,
    diagnosis_account_id VARCHAR(255) NOT NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    modified_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255)
);
COMMENT ON TABLE diagnosis_history IS '记录病人诊断历史';
COMMENT ON COLUMN diagnosis_history.id IS '自增主键';
COMMENT ON COLUMN diagnosis_history.patient_id IS '病人ID';
COMMENT ON COLUMN diagnosis_history.diagnosis IS '诊断';
COMMENT ON COLUMN diagnosis_history.diagnosis_code IS '诊断编码';
COMMENT ON COLUMN diagnosis_history.diagnosis_tcm IS '中医诊断';
COMMENT ON COLUMN diagnosis_history.diagnosis_tcm_code IS '中医诊断编码';
COMMENT ON COLUMN diagnosis_history.diagnosis_time IS '诊断时间';
COMMENT ON COLUMN diagnosis_history.diagnosis_account_id IS '诊断医生id';
COMMENT ON COLUMN diagnosis_history.is_deleted IS '是否已删除';
COMMENT ON COLUMN diagnosis_history.deleted_at IS '删除时间';
COMMENT ON COLUMN diagnosis_history.deleted_by IS '删除人';
COMMENT ON COLUMN diagnosis_history.modified_at IS '修改时间';
COMMENT ON COLUMN diagnosis_history.modified_by IS '修改人';
COMMENT ON COLUMN diagnosis_history.modified_by_account_name IS '修改人姓名';
CREATE INDEX idx_diagnosis_history_patient_id ON diagnosis_history (patient_id);

CREATE TABLE surgery_history (
    id SERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    name VARCHAR(100),
    code VARCHAR(50),
    surgeon_doctor_name VARCHAR(255),
    anesthesiologist_name VARCHAR(255),
    operating_room_nurse_name VARCHAR(255)
);
COMMENT ON TABLE surgery_history IS '记录病人手术历史';
COMMENT ON COLUMN surgery_history.id IS '自增主键';
COMMENT ON COLUMN surgery_history.patient_id IS '病人ID';
COMMENT ON COLUMN surgery_history.start_time IS '手术开始时间';
COMMENT ON COLUMN surgery_history.end_time IS '手术结束时间';
COMMENT ON COLUMN surgery_history.name IS '手术名称';
COMMENT ON COLUMN surgery_history.code IS '手术编码';
COMMENT ON COLUMN surgery_history.surgeon_doctor_name IS '主刀医生';
COMMENT ON COLUMN surgery_history.anesthesiologist_name IS '麻醉医生';
COMMENT ON COLUMN surgery_history.operating_room_nurse_name IS '手术室护士';
CREATE INDEX idx_surgery_history_patient_id ON surgery_history (patient_id);

CREATE TABLE out_history (
    id SERIAL PRIMARY KEY,
    patient_id INTEGER,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    reason VARCHAR(255),
    FOREIGN KEY (patient_id) REFERENCES patient_records(id)
);
COMMENT ON TABLE out_history IS '记录病人外出历史';
COMMENT ON COLUMN out_history.id IS '自增主键';
COMMENT ON COLUMN out_history.patient_id IS '病人ID';
COMMENT ON COLUMN out_history.start_time IS '外出开始时间';
COMMENT ON COLUMN out_history.end_time IS '外出结束时间';
COMMENT ON COLUMN out_history.reason IS '外出原因';
CREATE INDEX idx_out_history_patient_id ON out_history (patient_id);

CREATE TABLE readmission_history (
    id SERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL,
    readmission_reason VARCHAR(255),
    icu_discharge_time TIMESTAMP,
    icu_discharge_edit_time TIMESTAMP,
    icu_discharging_account_id VARCHAR(255),
    icu_admission_time TIMESTAMP,
    icu_admission_edit_time TIMESTAMP,
    icu_admitting_account_id VARCHAR(255)
);
COMMENT ON TABLE readmission_history IS '记录患者因手术或结算等原因重返ICU的出入科情况';
COMMENT ON COLUMN readmission_history.id IS '自增主键';
COMMENT ON COLUMN readmission_history.patient_id IS '病人ID';
COMMENT ON COLUMN readmission_history.readmission_reason IS '重返ICU原因';
COMMENT ON COLUMN readmission_history.icu_admission_time IS 'ICU入科时间';
COMMENT ON COLUMN readmission_history.icu_admission_edit_time IS 'ICU入科时间修改时间';
COMMENT ON COLUMN readmission_history.icu_admitting_account_id IS 'ICU入科操作员';
COMMENT ON COLUMN readmission_history.icu_discharge_time IS 'ICU出科时间';
COMMENT ON COLUMN readmission_history.icu_discharge_edit_time IS 'ICU出科时间修改时间';
COMMENT ON COLUMN readmission_history.icu_discharging_account_id IS 'ICU出科操作员';
CREATE INDEX idx_readmission_history_patient_id ON readmission_history (patient_id);
CREATE INDEX idx_readmission_history_icu_discharging_account_id ON readmission_history (icu_discharging_account_id);
CREATE INDEX idx_readmission_history_icu_admitting_account_id ON readmission_history (icu_admitting_account_id);

-- 3. 医嘱模块
CREATE TABLE medication_types ( --- 药品类型，抗生素，消炎药...
    id INTEGER PRIMARY KEY,
    name VARCHAR(10)
);

CREATE TABLE administration_route_groups ( --- 给药途径分组
    id INTEGER PRIMARY KEY,
    name VARCHAR(10)
);

CREATE TABLE intake_types ( --- 入量类型
    id INTEGER PRIMARY KEY,
    name VARCHAR(255),
    monitoring_param_code VARCHAR(255)
);

CREATE TABLE order_duration_types ( --- 医嘱持续时间类型
    id INTEGER PRIMARY KEY,
    name VARCHAR(10)  -- 长期/临时
);

CREATE TABLE medication_channels ( --- 药物通道
    id INTEGER PRIMARY KEY,
    name VARCHAR(10)  -- 鼻肠管，静脉
);

CREATE TABLE medication_execution_action_types ( --- 药物通道
    id INTEGER PRIMARY KEY,
    name VARCHAR(10)  -- 开始，暂停，恢复，调速，快推，完成
);

CREATE TABLE medication_order_validity_types ( --- 药物通道
    id INTEGER PRIMARY KEY,
    name VARCHAR(10)  -- 正常，已停止，已取消
);

CREATE TABLE medications_history (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    spec VARCHAR(255) NOT NULL,
    dose DOUBLE PRECISION NOT NULL,
    dose_unit VARCHAR(255) NOT NULL,
    package_count INTEGER,
    package_unit VARCHAR(255),
    should_calculate_rate BOOLEAN,
    type INTEGER,
    company VARCHAR(255),
    price DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL,
    source VARCHAR(255) NOT NULL,
    medical_order_id VARCHAR(255)
);
COMMENT ON TABLE medications_history IS '药品字典';
COMMENT ON COLUMN medications_history.code IS '药品编码，对应于order.order_code';
COMMENT ON COLUMN medications_history.name IS '药品名称，对应于order.order_name';
COMMENT ON COLUMN medications_history.spec IS '规格 0.5g/支 500ml/瓶 1ml:2g 1ml:2g*10支 10ml:1g(10%)/支';
COMMENT ON COLUMN medications_history.dose IS '剂量';
COMMENT ON COLUMN medications_history.dose_unit IS '剂量单位（enum） 支 瓶 g mg';
COMMENT ON COLUMN medications_history.package_count IS '包装数量';
COMMENT ON COLUMN medications_history.package_unit IS '包装单位（enum） 支 袋 瓶';
COMMENT ON COLUMN medications_history.should_calculate_rate IS '是否需要计算药速 ug(药)/kg(体重)/min 实施配置';
COMMENT ON COLUMN medications_history.type IS '对应于medication_type 抗生素、毒麻药 后期配置';
COMMENT ON COLUMN medications_history.company IS '生产厂家';
COMMENT ON COLUMN medications_history.price IS '价格';
COMMENT ON COLUMN medications_history.created_at IS '创建时间';
COMMENT ON COLUMN medications_history.source IS '数据来源，如果是medical_orders，则medical_order_id字段需要更新';
COMMENT ON COLUMN medications_history.medical_order_id IS '医嘱ID，如果由medical_orders表中的记录更新，则更新这个字段';
CREATE INDEX idx_medications_history_code ON medications_history (code);

CREATE TABLE medications (
    code VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    spec VARCHAR(255) NOT NULL,
    dose DOUBLE PRECISION NOT NULL,
    dose_unit VARCHAR(255) NOT NULL,
    package_count INTEGER,
    package_unit VARCHAR(255),
    should_calculate_rate BOOLEAN,
    type INTEGER,
    company VARCHAR(255),
    price DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL,
    created_by VARCHAR(255),
    confirmed BOOLEAN NOT NULL,
    confirmed_by VARCHAR(255)
);
COMMENT ON TABLE medications IS '药品字典';
COMMENT ON COLUMN medications.code IS '药品编码，对应于order.order_code';
COMMENT ON COLUMN medications.name IS '药品名称，对应于order.order_name';
COMMENT ON COLUMN medications.spec IS '规格 0.5g/支 500ml/瓶 1ml:2g 1ml:2g*10支 10ml:1g(10%)/支';
COMMENT ON COLUMN medications.dose IS '剂量';
COMMENT ON COLUMN medications.dose_unit IS '剂量单位（enum） 支 瓶 g mg';
COMMENT ON COLUMN medications.package_count IS '包装数量';
COMMENT ON COLUMN medications.package_unit IS '包装单位（enum） 支 袋 瓶';
COMMENT ON COLUMN medications.should_calculate_rate IS '是否需要计算药速 ug(药)/kg(体重)/min 实施配置';
COMMENT ON COLUMN medications.type IS '对应于 medication_type 抗生素、毒麻药 后期配置';
COMMENT ON COLUMN medications.company IS '生产厂家';
COMMENT ON COLUMN medications.price IS '价格';
COMMENT ON COLUMN medications.created_at IS '创建时间，与medications_history.created_at保持一致';
COMMENT ON COLUMN medications.created_by IS '创建人';
COMMENT ON COLUMN medications.confirmed IS '本条药品信息是否被医护核准';
COMMENT ON COLUMN medications.confirmed_by IS '医嘱ID，如果由medical_orders表中的记录更新，则更新这个字段';

CREATE TABLE medication_frequencies (
    id SERIAL PRIMARY KEY,
    code VARCHAR(255),
    name VARCHAR(255),
    freq_spec TEXT,
    support_nursing_order BOOLEAN NOT NULL DEFAULT false,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255)
);
COMMENT ON TABLE medication_frequencies IS '展示字段设置';
COMMENT ON COLUMN medication_frequencies.id IS '自增主键';
COMMENT ON COLUMN medication_frequencies.code IS '频次编码';
COMMENT ON COLUMN medication_frequencies.name IS '频次名称';
COMMENT ON COLUMN medication_frequencies.freq_spec IS 'proto消息 MedicationFrequencySpec 实例序列化后的base64编码';
COMMENT ON COLUMN medication_frequencies.support_nursing_order IS '是否支持护理计划';
COMMENT ON COLUMN medication_frequencies.is_deleted IS '是否已删除';
COMMENT ON COLUMN medication_frequencies.deleted_at IS '删除时间';
COMMENT ON COLUMN medication_frequencies.deleted_by IS '删除人';
CREATE UNIQUE INDEX idx_medication_frequencies_code_id ON medication_frequencies (code) WHERE is_deleted = false;

CREATE TABLE administration_routes (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    code VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    is_continuous BOOLEAN NOT NULL,
    group_id INTEGER NOT NULL,
    intake_type_id INTEGER NOT NULL,
    is_valid BOOLEAN NOT NULL,

    FOREIGN KEY (intake_type_id) REFERENCES intake_types(id)
);
COMMENT ON TABLE administration_routes IS '给药途径';
COMMENT ON COLUMN administration_routes.id IS '自增主键';
COMMENT ON COLUMN administration_routes.dept_id IS '科室ID';
COMMENT ON COLUMN administration_routes.code IS '给药途径编码';
COMMENT ON COLUMN administration_routes.name IS '给药途径名称';
COMMENT ON COLUMN administration_routes.is_continuous IS '是否持续用药：如果是，护士需要配置相关的用药速度；如果不是，护士需要配置用药的总液体量';
COMMENT ON COLUMN administration_routes.group_id IS '对应于administration_route_groups的ID';
COMMENT ON COLUMN administration_routes.intake_type_id IS '对应于intake_types的ID';
COMMENT ON COLUMN administration_routes.is_valid IS '是否有效';
CREATE UNIQUE INDEX idx_administration_routes_deptid_code ON administration_routes (dept_id, code);

CREATE TABLE medical_orders (
    -- 关键实体
    order_id VARCHAR(255) NOT NULL PRIMARY KEY,
    his_patient_id VARCHAR(255) NOT NULL,
    group_id VARCHAR(255),
    his_mrn VARCHAR(255),
    patient_name VARCHAR(255),
    ordering_doctor VARCHAR(255),
    ordering_doctor_id VARCHAR(255),
    dept_id VARCHAR(255),

    -- 医嘱状态
    order_type VARCHAR(255),
    status VARCHAR(255),
    order_time TIMESTAMP,
    stop_time TIMESTAMP,
    cancel_time TIMESTAMP,

    -- 医嘱内容
    order_code VARCHAR(255),
    order_name VARCHAR(255),
    spec VARCHAR(255),
    dose DOUBLE PRECISION,
    dose_unit VARCHAR(255),
    recommend_speed VARCHAR(255),
    medication_note VARCHAR(255),
    medication_type VARCHAR(255),
    should_calculate_rate BOOLEAN,
    payment_type VARCHAR(255),

    -- 医嘱执行参数
    order_duration_type INTEGER,
    plan_time TIMESTAMP,
    freq_code VARCHAR(255),
    first_day_exe_count INTEGER,
    administration_route_code VARCHAR(255),
    administration_route_name VARCHAR(255),

    -- 审核信息
    reviewer VARCHAR(255),
    reviewer_id VARCHAR(255),
    review_time TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE medical_orders IS '医嘱中间表';
COMMENT ON COLUMN medical_orders.order_id IS 'HIS医嘱的主键';
COMMENT ON COLUMN medical_orders.his_patient_id IS 'HIS系统中的病人记录表主键ID';
COMMENT ON COLUMN medical_orders.group_id IS '医嘱分组id，同一组医嘱关联字段';
COMMENT ON COLUMN medical_orders.his_mrn IS '病历号, Medical Record Number';
COMMENT ON COLUMN medical_orders.patient_name IS '病人姓名';
COMMENT ON COLUMN medical_orders.ordering_doctor IS '下嘱医生';
COMMENT ON COLUMN medical_orders.ordering_doctor_id IS '下嘱医生ID';
COMMENT ON COLUMN medical_orders.dept_id IS '科室ID/编码';
COMMENT ON COLUMN medical_orders.order_type IS '医嘱类型 (中药, 西药, 检验等)';
COMMENT ON COLUMN medical_orders.status IS '医嘱状态 (已开立, 取消, 停止等)';
COMMENT ON COLUMN medical_orders.order_time IS '下嘱时间, 需要晚于patient_records.admission_time';
COMMENT ON COLUMN medical_orders.stop_time IS '停止时间';
COMMENT ON COLUMN medical_orders.cancel_time IS '作废时间';
COMMENT ON COLUMN medical_orders.order_code IS '医嘱编码';
COMMENT ON COLUMN medical_orders.order_name IS '医嘱名称';
COMMENT ON COLUMN medical_orders.spec IS '规格';
COMMENT ON COLUMN medical_orders.dose IS '剂量';
COMMENT ON COLUMN medical_orders.dose_unit IS '剂量单位';
COMMENT ON COLUMN medical_orders.medication_note IS '备注';
COMMENT ON COLUMN medical_orders.medication_type IS '毒麻药、抗生素等';
COMMENT ON COLUMN medical_orders.should_calculate_rate IS '是否需要计算药速 ug(药)/kg(体重)/min';
COMMENT ON COLUMN medical_orders.payment_type IS '支付方式';
COMMENT ON COLUMN medical_orders.order_duration_type IS '长期医嘱/临时医嘱, 对应order_duration_types.id';
COMMENT ON COLUMN medical_orders.plan_time IS '计划执行时间';
COMMENT ON COLUMN medical_orders.freq_code IS '频次编码, 对应medication_frequencies.id';
COMMENT ON COLUMN medical_orders.first_day_exe_count IS '首日执行医嘱次数';
COMMENT ON COLUMN medical_orders.administration_route_code IS '给药途径编码，对应administration_routes.code';
COMMENT ON COLUMN medical_orders.administration_route_name IS '给药途径名称，对应administration_routes.name';
COMMENT ON COLUMN medical_orders.reviewer IS '审核人';
COMMENT ON COLUMN medical_orders.reviewer_id IS '审核人ID';
COMMENT ON COLUMN medical_orders.review_time IS '审核时间';
COMMENT ON COLUMN medical_orders.created_at IS '创建时间';
CREATE INDEX idx_medical_orders_patient_id ON medical_orders (his_patient_id);
CREATE INDEX idx_medical_orders_patient_id_group_id ON medical_orders (his_patient_id, group_id);

CREATE TABLE medication_order_groups (
    -- 关键实体
    id BIGSERIAL PRIMARY KEY,
    his_patient_id VARCHAR(255) NOT NULL,
    patient_id BIGINT NOT NULL,
    group_id VARCHAR(255) NOT NULL,
    medical_order_ids TEXT,
    his_mrn VARCHAR(255),
    ordering_doctor VARCHAR(255),
    ordering_doctor_id VARCHAR(255),
    dept_id VARCHAR(255) NOT NULL,

    -- 医嘱状态
    order_type VARCHAR(255) NOT NULL,
    status VARCHAR(255) NOT NULL,
    order_time TIMESTAMP NOT NULL,
    stop_time TIMESTAMP,
    cancel_time TIMESTAMP,
    order_validity INTEGER NOT NULL,
    inconsistency_explanation VARCHAR(500),

    -- 医嘱内容
    medication_dosage_group TEXT,

    -- 医嘱执行参数
    order_duration_type INTEGER NOT NULL,
    plan_time TIMESTAMP NOT NULL,
    freq_code VARCHAR(255) NOT NULL,
    first_day_exe_count INTEGER,
    administration_route_code VARCHAR(255) NOT NULL,
    administration_route_name VARCHAR(255) NOT NULL,

    -- 其他
    note VARCHAR(1000),
    reviewer VARCHAR(255),
    reviewer_id VARCHAR(255),
    review_time TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE medication_order_groups IS '药物医嘱分组表';
COMMENT ON COLUMN medication_order_groups.id IS '药物医嘱分组表的主键';
COMMENT ON COLUMN medication_order_groups.his_patient_id IS 'HIS系统中的病人记录表主键ID';
COMMENT ON COLUMN medication_order_groups.patient_id IS '重症系统的病人ID, patient_records.id';
COMMENT ON COLUMN medication_order_groups.group_id IS '医嘱分组id，同一组医嘱的关联字段';
COMMENT ON COLUMN medication_order_groups.medical_order_ids IS '医嘱ID集合，包含该组医嘱中的所有医嘱，对应于MedicalOrderIdsPB的一个实例';
COMMENT ON COLUMN medication_order_groups.his_mrn IS '病历号，Medical Record Number';
COMMENT ON COLUMN medication_order_groups.ordering_doctor IS '下嘱医生';
COMMENT ON COLUMN medication_order_groups.ordering_doctor_id IS '下嘱医生的ID';
COMMENT ON COLUMN medication_order_groups.dept_id IS '科室ID/编码';
COMMENT ON COLUMN medication_order_groups.order_type IS '医嘱类型（例如：中药，西药，检验等）';
COMMENT ON COLUMN medication_order_groups.status IS '医嘱分组状态（例如：已开立，取消，停止等）';
COMMENT ON COLUMN medication_order_groups.order_time IS '下嘱时间, 需要晚于patient_records.admission_time';
COMMENT ON COLUMN medication_order_groups.stop_time IS '停止时间';
COMMENT ON COLUMN medication_order_groups.cancel_time IS '作废时间';
COMMENT ON COLUMN medication_order_groups.order_validity IS '医嘱是否可以继续产生执行记录，参见medication_order_validity_types表';
COMMENT ON COLUMN medication_order_groups.inconsistency_explanation IS '如果医嘱分组有不一致性，此字段用于解释。如果为'', 或null，表示数据无异常。';
COMMENT ON COLUMN medication_order_groups.medication_dosage_group IS '药物成分，MedicationDosageGroupPB的实例';
COMMENT ON COLUMN medication_order_groups.order_duration_type IS '长期医嘱/临时医嘱，对应order_duration_types.id';
COMMENT ON COLUMN medication_order_groups.plan_time IS '计划执行时间';
COMMENT ON COLUMN medication_order_groups.freq_code IS '频次编码，对应medication_frequencies.id';
COMMENT ON COLUMN medication_order_groups.first_day_exe_count IS '首日执行医嘱次数';
COMMENT ON COLUMN medication_order_groups.administration_route_code IS '给药途径编码，对应administration_routes.code';
COMMENT ON COLUMN medication_order_groups.administration_route_name IS '给药途径名称，对应administration_routes.name';
COMMENT ON COLUMN medication_order_groups.reviewer IS '审核人';
COMMENT ON COLUMN medication_order_groups.reviewer_id IS '审核人的ID';
COMMENT ON COLUMN medication_order_groups.review_time IS '审核时间';
COMMENT ON COLUMN medication_order_groups.created_at IS '记录创建时间';
CREATE INDEX idx_medication_order_groups_patient_id_group_id ON medication_order_groups (patient_id, group_id);

CREATE TABLE medication_execution_records (
    -- 关键实体
    id BIGSERIAL PRIMARY KEY,
    medication_order_group_id BIGINT NOT NULL,
    his_order_group_id VARCHAR(255) NOT NULL,
    patient_id BIGINT NOT NULL,
    plan_time TIMESTAMP NOT NULL,
    is_personal_medications BOOLEAN,
    should_calculate_rate BOOLEAN,

    -- 执行状态
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    is_deleted BOOLEAN NOT NULL,
    delete_account_id VARCHAR(255),
    delete_time TIMESTAMP,
    delete_reason VARCHAR(255),
    user_touched BOOLEAN NOT NULL,
    bar_code VARCHAR(255),
    his_execute_id VARCHAR(255),

    -- 药物成分
    medication_dosage_group TEXT,
    administration_route_code VARCHAR(255),
    administration_route_name VARCHAR(255),
    is_continuous BOOLEAN,
    medication_channel INTEGER,
    medication_channel_name VARCHAR(255),

    -- 其他
    comments VARCHAR(255),
    create_account_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,

    FOREIGN KEY (medication_order_group_id) REFERENCES medication_order_groups(id)
);
COMMENT ON TABLE medication_execution_records IS '药物执行记录表';
COMMENT ON COLUMN medication_execution_records.id IS '自增主键';
COMMENT ON COLUMN medication_execution_records.medication_order_group_id IS '外键：medication_order_groups.id';
COMMENT ON COLUMN medication_execution_records.his_order_group_id IS 'HIS系统中的医嘱分组ID，和medication_order_groups.group_id对应';
COMMENT ON COLUMN medication_execution_records.patient_id IS '重症系统的病人ID, patient_records.id';
COMMENT ON COLUMN medication_execution_records.plan_time IS '计划执行时间';
COMMENT ON COLUMN medication_execution_records.is_continuous IS '是否持续用药: true表示持续用药，false表示单次用药(isOnce = true)';
COMMENT ON COLUMN medication_execution_records.is_personal_medications IS '是否为个人药物';
COMMENT ON COLUMN medication_execution_records.should_calculate_rate IS '是否应计算药速 ug(药)/kg(体重)/min';
COMMENT ON COLUMN medication_execution_records.start_time IS '开始执行时间';
COMMENT ON COLUMN medication_execution_records.end_time IS '结束执行时间';
COMMENT ON COLUMN medication_execution_records.is_deleted IS '本条执行记录是否被删除';
COMMENT ON COLUMN medication_execution_records.delete_account_id IS '删除本条记录的用户';
COMMENT ON COLUMN medication_execution_records.delete_time IS '如果不为空，表示被人为删除';
COMMENT ON COLUMN medication_execution_records.delete_reason IS '删除理由';
COMMENT ON COLUMN medication_execution_records.user_touched IS '用户是否干预过，比如用户添加了开始/调速/快推等动作，用户修改了pda/his记录的消息';
COMMENT ON COLUMN medication_execution_records.bar_code IS '药物条形码，PDA扫码获取bar_code，bar_code对应具体的药物执行记录；PDA新增各种action，发送给重症后端，重症后端即可通过bar_code找到对应的执行记录，生成action【全流程分解】';
COMMENT ON COLUMN medication_execution_records.his_execute_id IS 'his执行记录，his产生新的开始action，重症同步该action，设置对应的his_execution_id; his产生新的结束action，附带his_execution_id, 重症根据his_execution_id找到执行记录，更新执行记录';
COMMENT ON COLUMN medication_execution_records.medication_dosage_group IS '药物成分，MedicationDosageGroupPB的实例。如果为空，用medication_order_groups.medication_dosage_group';
COMMENT ON COLUMN medication_execution_records.administration_route_code IS '给药途径编码，对应administration_routes.code';
COMMENT ON COLUMN medication_execution_records.administration_route_name IS '给药途径名称，对应administration_routes.name';
COMMENT ON COLUMN medication_execution_records.medication_channel IS '药物通道，对应于表medication_channels 鼻肠管';
COMMENT ON COLUMN medication_execution_records.comments IS '备注';
COMMENT ON COLUMN medication_execution_records.create_account_id IS '创建用户';
COMMENT ON COLUMN medication_execution_records.created_at IS '创建时间';
CREATE INDEX idx_medication_execution_records_his_order_group_id_plan_time ON medication_execution_records (his_order_group_id, plan_time);
CREATE INDEX idx_medication_execution_records_medication_order_group_id ON medication_execution_records (medication_order_group_id);
CREATE INDEX idx_medication_execution_records_patient_id ON medication_execution_records (patient_id);

CREATE TABLE medication_execution_actions (
    id BIGSERIAL PRIMARY KEY,
    medication_execution_record_id BIGINT NOT NULL,
    create_account_id VARCHAR(255) NOT NULL,
    create_account_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    action_type INTEGER NOT NULL,
    administration_rate DOUBLE PRECISION,
    medication_rate TEXT,
    intake_vol_ml DOUBLE PRECISION,
    is_deleted BOOLEAN NOT NULL,
    delete_account_id VARCHAR(255),
    delete_time TIMESTAMP,
    modified_at TIMESTAMP,
    FOREIGN KEY (medication_execution_record_id) REFERENCES medication_execution_records(id)
);
COMMENT ON TABLE medication_execution_actions IS '药物执行动作表';
COMMENT ON COLUMN medication_execution_actions.medication_execution_record_id IS '外键：medication_execution_records.id';
COMMENT ON COLUMN medication_execution_actions.create_account_id IS '创建用户';
COMMENT ON COLUMN medication_execution_actions.create_account_name IS '创建用户姓名';
COMMENT ON COLUMN medication_execution_actions.created_at IS '创建时间，action的开始时间';
COMMENT ON COLUMN medication_execution_actions.action_type IS '过程的类型, medication_execution_action_types.name';
COMMENT ON COLUMN medication_execution_actions.administration_rate IS '持续用药的药速，单位：ml/h, 对 action_type 暂停/完成/快推 不适用';
COMMENT ON COLUMN medication_execution_actions.medication_rate IS '药速，DosageGroupExtPB的实例序列化后的base64编码，只有当action_type为开始/调速时有效';
COMMENT ON COLUMN medication_execution_actions.intake_vol_ml IS '单次用药的药量，单位: ml, 对 action_type 快推/完成 适用';
COMMENT ON COLUMN medication_execution_actions.is_deleted IS '本条执行记录是否被人为删除';
COMMENT ON COLUMN medication_execution_actions.delete_account_id IS '删除本条记录的用户';
COMMENT ON COLUMN medication_execution_actions.delete_time IS '删除时间';
COMMENT ON COLUMN medication_execution_actions.modified_at IS '修改时间';
CREATE INDEX idx_medication_execution_actions_merid_id ON medication_execution_actions (medication_execution_record_id, id);

CREATE TABLE medication_execution_record_stats (
    id BIGSERIAL PRIMARY KEY,  -- 自增id
    group_id BIGINT NOT NULL,  -- 药物订单组id
    exe_record_id BIGINT NOT NULL,  -- 药物执行记录id, 外键nursing_execution_records.id
    stats_time TIMESTAMP NOT NULL,  -- 统计时间
    consumed_ml DOUBLE PRECISION NOT NULL,  -- 消耗液体量的毫升数
    is_final BOOLEAN NOT NULL,  -- 是否是对应的execution_record_id的最后一条统计记录
    remain_ml DOUBLE PRECISION,  -- 剩余液体量的毫升数
    FOREIGN KEY (group_id) REFERENCES medication_order_groups(id),  -- 外键约束，假设存在medication_order_groups表
    FOREIGN KEY (exe_record_id) REFERENCES medication_execution_records(id)  -- 外键约束，参照medication_execution_records表
);

COMMENT ON TABLE medication_execution_record_stats IS '药物执行记录统计表';

COMMENT ON COLUMN medication_execution_record_stats.id IS '自增id';
COMMENT ON COLUMN medication_execution_record_stats.group_id IS '药物订单组id';
COMMENT ON COLUMN medication_execution_record_stats.exe_record_id IS '药物执行记录id, 外键nursing_execution_records.id';
COMMENT ON COLUMN medication_execution_record_stats.stats_time IS '统计时间';
COMMENT ON COLUMN medication_execution_record_stats.is_final IS '是否是对应的execution_record_id的最后一条统计记录';
COMMENT ON COLUMN medication_execution_record_stats.remain_ml IS '剩余液体量的毫升数';

-- 创建唯一索引，确保每个medication_execution_record_id在stats_time上的唯一性
CREATE UNIQUE INDEX idx_med_execution_record_stats_record_time
    ON medication_execution_record_stats (exe_record_id, stats_time);

CREATE TABLE tube_types (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(255),
    is_common BOOLEAN,
    is_disabled BOOLEAN NOT NULL DEFAULT false,
    display_order INT NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP
);
COMMENT ON TABLE tube_types IS '管道设置表';
COMMENT ON COLUMN tube_types.dept_id IS '部门代码';
COMMENT ON COLUMN tube_types.type IS '管道类型';
COMMENT ON COLUMN tube_types.name IS '管道名称';
COMMENT ON COLUMN tube_types.category IS '管道分类, I类、II类、III类';
COMMENT ON COLUMN tube_types.is_common IS '是否为常用管道';
COMMENT ON COLUMN tube_types.is_disabled IS '是否禁用';
COMMENT ON COLUMN tube_types.display_order IS '管道展示顺序';
COMMENT ON COLUMN tube_types.is_deleted IS '是否被删除';
COMMENT ON COLUMN tube_types.modified_by IS '最后修改人';
COMMENT ON COLUMN tube_types.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_tube_types_dept_id_name ON tube_types (dept_id, name) WHERE is_deleted = false;

CREATE TABLE tube_type_attributes (
    id SERIAL PRIMARY KEY,
    tube_type_id INT NOT NULL,
    attribute VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    ui_type INT NOT NULL,
    opt_value VARCHAR(1000) NOT NULL,
    default_value VARCHAR(1000) NOT NULL,
    unit VARCHAR(100),
    display_order INT NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP,
    FOREIGN KEY (tube_type_id) REFERENCES tube_types(id)
);
COMMENT ON TABLE tube_type_attributes IS '管道类型属性表';
COMMENT ON COLUMN tube_type_attributes.tube_type_id IS 'tube_types.id';
COMMENT ON COLUMN tube_type_attributes.attribute IS '管道属性编码';
COMMENT ON COLUMN tube_type_attributes.name IS '管道属性名称（展示）';
COMMENT ON COLUMN tube_type_attributes.ui_type IS 'ui的类型，见config.tube.enums.tube_ui_type_*.id';
COMMENT ON COLUMN tube_type_attributes.opt_value IS '可选值，多个值用,(ascii的逗号)分割';
COMMENT ON COLUMN tube_type_attributes.default_value IS '默认值，多个值用,(ascii的逗号)分割';
COMMENT ON COLUMN tube_type_attributes.unit IS '单位';
COMMENT ON COLUMN tube_type_attributes.display_order IS '展示顺序';
COMMENT ON COLUMN tube_type_attributes.is_deleted IS '是否被删除';
COMMENT ON COLUMN tube_type_attributes.modified_by IS '最后修改人';
COMMENT ON COLUMN tube_type_attributes.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_tt_attributes_tube_type_id_attribute ON tube_type_attributes (tube_type_id, attribute) WHERE is_deleted = false;
CREATE UNIQUE INDEX idx_tt_attributes_tube_type_id_name ON tube_type_attributes (tube_type_id, name) WHERE is_deleted = false;

CREATE TABLE tube_type_statuses (
    id SERIAL PRIMARY KEY,
    tube_type_id INT NOT NULL,
    status VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    ui_type INT NOT NULL,
    opt_value VARCHAR(1000) NOT NULL,
    default_value VARCHAR(1000) NOT NULL,
    unit VARCHAR(100),
    display_order INT NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP,
    FOREIGN KEY (tube_type_id) REFERENCES tube_types(id)
);
COMMENT ON TABLE tube_type_statuses IS '管道类型状态表';
COMMENT ON COLUMN tube_type_statuses.tube_type_id IS 'tube_types.id';
COMMENT ON COLUMN tube_type_statuses.status IS '管道状态编码';
COMMENT ON COLUMN tube_type_statuses.name IS '管道状态名称（展示）';
COMMENT ON COLUMN tube_type_statuses.ui_type IS 'ui的类型，见config.tube.enums.tube_ui_type_*.id';
COMMENT ON COLUMN tube_type_statuses.opt_value IS '可选值，多个值用,(ascii的逗号)分割';
COMMENT ON COLUMN tube_type_statuses.default_value IS '默认值，多个值用,(ascii的逗号)分割';
COMMENT ON COLUMN tube_type_statuses.unit IS '单位';
COMMENT ON COLUMN tube_type_statuses.display_order IS '展示顺序';
COMMENT ON COLUMN tube_type_statuses.is_deleted IS '是否被删除';
COMMENT ON COLUMN tube_type_statuses.modified_by IS '最后修改人';
COMMENT ON COLUMN tube_type_statuses.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_tt_statuses_tube_type_id_status ON tube_type_statuses (tube_type_id, status) WHERE is_deleted = false;
CREATE UNIQUE INDEX idx_tt_statuses_tube_type_id_name ON tube_type_statuses (tube_type_id, name) WHERE is_deleted = false;

CREATE TABLE patient_tube_records (
    id BIGSERIAL PRIMARY KEY,
    pid BIGINT NOT NULL,
    tube_type_id INT NOT NULL,
    tube_name VARCHAR(255) NOT NULL,
    inserted_at TIMESTAMP NOT NULL,
    inserted_by VARCHAR(255) NOT NULL,
    inserted_by_account_name VARCHAR(255),
    planned_removal_at TIMESTAMP,
    is_unplanned_removal BOOLEAN,
    removal_reason VARCHAR(255),
    removed_at TIMESTAMP,
    removed_by VARCHAR(255),
    removed_by_account_name VARCHAR(255),
    prev_tube_record_id BIGINT,
    root_tube_record_id BIGINT,
    is_retained_on_discharge BOOLEAN,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    delete_reason VARCHAR(255),
    note TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (pid) REFERENCES patient_records(id),
    FOREIGN KEY (tube_type_id) REFERENCES tube_types(id)
);

COMMENT ON TABLE patient_tube_records IS '病人插管记录表';
COMMENT ON COLUMN patient_tube_records.pid IS 'patient_records.id';
COMMENT ON COLUMN patient_tube_records.tube_type_id IS '管道类型id';
COMMENT ON COLUMN patient_tube_records.tube_name IS '管道名称';
COMMENT ON COLUMN patient_tube_records.inserted_at IS '插管时间';
COMMENT ON COLUMN patient_tube_records.inserted_by IS '插管人/账户ID';
COMMENT ON COLUMN patient_tube_records.inserted_by_account_name IS '插管人姓名';
COMMENT ON COLUMN patient_tube_records.planned_removal_at IS '计划拔管时间';
COMMENT ON COLUMN patient_tube_records.is_unplanned_removal IS '是否非计划拔管';
COMMENT ON COLUMN patient_tube_records.removal_reason IS '拔管原因';
COMMENT ON COLUMN patient_tube_records.removed_at IS '拔管时间';
COMMENT ON COLUMN patient_tube_records.removed_by IS '拔管人/账户ID';
COMMENT ON COLUMN patient_tube_records.prev_tube_record_id IS '前一根管道ID（换管时用）';
COMMENT ON COLUMN patient_tube_records.root_tube_record_id IS '置换链的根管道ID';
COMMENT ON COLUMN patient_tube_records.is_retained_on_discharge IS '是否带管出科';
COMMENT ON COLUMN patient_tube_records.is_deleted IS '记录是否被人工删除';
COMMENT ON COLUMN patient_tube_records.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_tube_records.deleted_by IS '删除人/账户ID';
COMMENT ON COLUMN patient_tube_records.delete_reason IS '删除理由';
COMMENT ON COLUMN patient_tube_records.note IS '备注';
COMMENT ON COLUMN patient_tube_records.created_at IS '创建时间';
CREATE INDEX idx_pt_records_pid_tube_type_root ON patient_tube_records (pid, tube_type_id, root_tube_record_id) WHERE is_deleted = false;

CREATE TABLE patient_tube_attrs (
    id BIGSERIAL PRIMARY KEY,
    patient_tube_record_id BIGINT NOT NULL,
    tube_attr_id INT NOT NULL,
    value VARCHAR(255) NOT NULL,
    FOREIGN KEY (patient_tube_record_id) REFERENCES patient_tube_records(id),
    FOREIGN KEY (tube_attr_id) REFERENCES tube_type_attributes(id)
);

COMMENT ON TABLE patient_tube_attrs IS '病人管道属性表';
COMMENT ON COLUMN patient_tube_attrs.patient_tube_record_id IS 'patient_tube_records.id';
COMMENT ON COLUMN patient_tube_attrs.tube_attr_id IS 'tube_type_attributes.id';
COMMENT ON COLUMN patient_tube_attrs.value IS '管道属性值';
CREATE UNIQUE INDEX idx_pt_attrs_record_attr ON patient_tube_attrs (patient_tube_record_id, tube_attr_id);

CREATE TABLE patient_tube_status_records (
    id BIGSERIAL PRIMARY KEY,
    patient_tube_record_id BIGINT NOT NULL,
    tube_status_id INT NOT NULL,
    value VARCHAR(255) NOT NULL,
    recorded_by VARCHAR(255) NOT NULL,
    recorded_by_account_name VARCHAR(255),
    recorded_at TIMESTAMP NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    delete_reason VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (patient_tube_record_id) REFERENCES patient_tube_records(id),
    FOREIGN KEY (tube_status_id) REFERENCES tube_type_statuses(id)
);

COMMENT ON TABLE patient_tube_status_records IS '病人管道状态记录表';
COMMENT ON COLUMN patient_tube_status_records.patient_tube_record_id IS 'patient_tube_records.id';
COMMENT ON COLUMN patient_tube_status_records.tube_status_id IS '管道状态ID';
COMMENT ON COLUMN patient_tube_status_records.value IS '管道状态值';
COMMENT ON COLUMN patient_tube_status_records.recorded_by IS '记录人/账户ID';
COMMENT ON COLUMN patient_tube_status_records.recorded_by_account_name IS '记录人姓名';
COMMENT ON COLUMN patient_tube_status_records.recorded_at IS '记录时间';
COMMENT ON COLUMN patient_tube_status_records.is_deleted IS '记录是否被人工删除';
COMMENT ON COLUMN patient_tube_status_records.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_tube_status_records.deleted_by IS '删除人/账户ID';
COMMENT ON COLUMN patient_tube_status_records.delete_reason IS '删除理由';
COMMENT ON COLUMN patient_tube_status_records.created_at IS '创建时间';
CREATE UNIQUE INDEX idx_pt_status_records_record_status ON patient_tube_status_records (patient_tube_record_id, tube_status_id, recorded_at) WHERE is_deleted = false;

-- 全局观察参数表
CREATE TABLE monitoring_params (
    code VARCHAR(255) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    name_en VARCHAR(255),
    type_pb VARCHAR(2000) NOT NULL,
    balance_type INT NOT NULL,
    category INT,
    ui_modal_code VARCHAR(255),
    chart_sign VARCHAR(6),
    display_order INT NOT NULL
);
COMMENT ON TABLE monitoring_params IS '全局观察参数表';
COMMENT ON COLUMN monitoring_params.code IS '观察参数编码（含出入量）';
COMMENT ON COLUMN monitoring_params.name IS '观察参数名称，比如“静脉入量”，“尿量”等';
COMMENT ON COLUMN monitoring_params.name_en IS '观察参数英文名称';
COMMENT ON COLUMN monitoring_params.type_pb IS '将ValueMeta序列化并用base64编码的字符串';
COMMENT ON COLUMN monitoring_params.balance_type IS '1: 入量；2: 出量；其他不计入';
COMMENT ON COLUMN monitoring_params.category IS '观察参数分类，通常表示参数的直接来源，MonitoringEnums.param_category_xx.id';
COMMENT ON COLUMN monitoring_params.ui_modal_code IS '观察项UI模态框的编码，用于在观察项界面直接编辑评分';
COMMENT ON COLUMN monitoring_params.chart_sign IS '图表标识，用于观察项报表中的折线图的点的展示';

-- 科室观察参数表
CREATE TABLE dept_monitoring_params (
    code VARCHAR(255) NOT NULL,
    dept_id VARCHAR(255) NOT NULL,
    type_pb VARCHAR(2000) NOT NULL,
    PRIMARY KEY (code, dept_id),
    FOREIGN KEY (code) REFERENCES monitoring_params(code)
);
COMMENT ON TABLE dept_monitoring_params IS '科室观察参数表';
COMMENT ON COLUMN dept_monitoring_params.code IS '观察参数编码（含出入量）';
COMMENT ON COLUMN dept_monitoring_params.dept_id IS '科室ID';
COMMENT ON COLUMN dept_monitoring_params.type_pb IS '将ValueMeta序列化并用base64编码的字符串';

-- 观察参数历史表
CREATE TABLE monitoring_params_history (
    id SERIAL PRIMARY KEY,
    code VARCHAR(255) NOT NULL,
    dept_id VARCHAR(255),
    name VARCHAR(255),
    type_pb VARCHAR(2000),
    balance_type INT,
    ui_type INT,
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP
);
COMMENT ON TABLE monitoring_params_history IS '观察参数历史表';
COMMENT ON COLUMN monitoring_params_history.code IS '观察参数编码（含出入量）';
COMMENT ON COLUMN monitoring_params_history.dept_id IS '科室ID';
COMMENT ON COLUMN monitoring_params_history.name IS '观察参数名称，比如“静脉入量”，“尿量”等';
COMMENT ON COLUMN monitoring_params_history.type_pb IS '将ValueMeta序列化并用base64编码的字符串';
COMMENT ON COLUMN monitoring_params_history.balance_type IS '1: 入量；2: 出量；其他不计入';
COMMENT ON COLUMN monitoring_params_history.is_deleted IS '是否已删除';
COMMENT ON COLUMN monitoring_params_history.modified_by IS '最后修改人';
COMMENT ON COLUMN monitoring_params_history.modified_at IS '最后修改时间';

-- 科室观察(分组)表
CREATE TABLE dept_monitoring_groups (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    group_type INT,
    display_order INT NOT NULL,
    sum_type_pb VARCHAR(2000),
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP
);
COMMENT ON TABLE dept_monitoring_groups IS '科室观察分组表';
COMMENT ON COLUMN dept_monitoring_groups.dept_id IS '科室ID';
COMMENT ON COLUMN dept_monitoring_groups.name IS '观察量分组名称，比如"生命体征"';
COMMENT ON COLUMN dept_monitoring_groups.group_type IS '分组类型：0：出入量，1：观察项';
COMMENT ON COLUMN dept_monitoring_groups.display_order IS '分组间显示顺序';
COMMENT ON COLUMN dept_monitoring_groups.sum_type_pb IS '将ValueMeta序列化并用base64编码的字符串';
CREATE UNIQUE INDEX idx_dept_monitoring_groups_dept_id_name ON dept_monitoring_groups (dept_id, name) WHERE is_deleted = FALSE;

-- 病人观察(分组)表
CREATE TABLE patient_monitoring_groups (
    id SERIAL PRIMARY KEY,
    dept_monitoring_group_id INT NOT NULL,
    pid BIGINT NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255) NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (dept_monitoring_group_id) REFERENCES dept_monitoring_groups(id)
);
COMMENT ON TABLE patient_monitoring_groups IS '病人观察分组表';
CREATE UNIQUE INDEX idx_patient_monitoring_groups_dept_id_name ON patient_monitoring_groups (dept_monitoring_group_id, pid) WHERE is_deleted = FALSE;

-- 科室观察(分组-参数)表
CREATE TABLE dept_monitoring_group_params (
    id SERIAL PRIMARY KEY,
    dept_monitoring_group_id INT NOT NULL,
    monitoring_param_code VARCHAR(255) NOT NULL,
    display_order INT  NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255) NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (dept_monitoring_group_id) REFERENCES dept_monitoring_groups(id),
    FOREIGN KEY (monitoring_param_code) REFERENCES monitoring_params(code)
);
COMMENT ON TABLE dept_monitoring_group_params IS '科室观察分组-参数表';
CREATE UNIQUE INDEX idx_dept_monitoring_group_params_dept_id_param_code ON dept_monitoring_group_params (dept_monitoring_group_id, monitoring_param_code) WHERE is_deleted = FALSE;

-- 病人观察(分组-参数)表
CREATE TABLE patient_monitoring_group_params (
    id SERIAL PRIMARY KEY,
    patient_monitoring_group_id INT NOT NULL,
    monitoring_param_code VARCHAR(255) NOT NULL,
    display_order INT NOT NULL,
    is_deleted BOOLEAN  NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP,
    FOREIGN KEY (patient_monitoring_group_id) REFERENCES patient_monitoring_groups(id),
    FOREIGN KEY (monitoring_param_code) REFERENCES monitoring_params(code)
);
COMMENT ON TABLE patient_monitoring_group_params IS '病人观察分组-参数表';
CREATE UNIQUE INDEX idx_patient_monitoring_group_params_patient_id_param_code ON patient_monitoring_group_params (patient_monitoring_group_id, monitoring_param_code) WHERE is_deleted = FALSE;

-- 病人观察记录表
CREATE TABLE patient_monitoring_records (
    id BIGSERIAL PRIMARY KEY,
    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    monitoring_param_code VARCHAR(255) NOT NULL, -- 观察参数编码，关联到monitoring_params.code
    effective_time TIMESTAMP NOT NULL, -- param_value的有效时间
    param_value VARCHAR(1000) NOT NULL, -- 将 MonitoringValuePB 序列化字符串并进行base64编码后的字符串
    param_value_str VARCHAR(255), -- 记录时的值对应的字符串
    unit VARCHAR(100), -- 单位
    device_id INTEGER, -- 设备ID
    source VARCHAR(255) NOT NULL, -- 数据来源 (监护仪/人工/...)
    note VARCHAR(1000), -- 备注
    status VARCHAR(255), -- （备用字段）记录状态：已录入，已审核等
    modified_by VARCHAR(255), -- 记录人
    modified_at TIMESTAMP NOT NULL, -- 记录的时间
    is_deleted BOOLEAN DEFAULT FALSE, -- 是否已删除
    delete_reason VARCHAR(255), -- 删除理由
    deleted_by VARCHAR(255), -- 删除人
    deleted_at TIMESTAMP -- 删除时间
);
COMMENT ON TABLE patient_monitoring_records IS '病人观察参数记录表';
COMMENT ON COLUMN patient_monitoring_records.pid IS '病人ID';
COMMENT ON COLUMN patient_monitoring_records.dept_id IS '科室ID';
COMMENT ON COLUMN patient_monitoring_records.monitoring_param_code IS '观察参数编码，关联到monitoring_params.code';
COMMENT ON COLUMN patient_monitoring_records.effective_time IS 'param_value的有效时间';
COMMENT ON COLUMN patient_monitoring_records.param_value IS '将MonitoringValuePB序列化字符串并进行base64编码后的字符串';
COMMENT ON COLUMN patient_monitoring_records.param_value_str IS '记录时的值对应的字符串';
COMMENT ON COLUMN patient_monitoring_records.unit IS '单位';
COMMENT ON COLUMN patient_monitoring_records.device_id IS '设备ID';
COMMENT ON COLUMN patient_monitoring_records.source IS '数据来源 (监护仪/人工/...)';
COMMENT ON COLUMN patient_monitoring_records.note IS '备注';
COMMENT ON COLUMN patient_monitoring_records.status IS '记录状态：已录入，已审核等';
COMMENT ON COLUMN patient_monitoring_records.modified_by IS '记录人';
COMMENT ON COLUMN patient_monitoring_records.modified_at IS '记录的时间';
CREATE UNIQUE INDEX idx_pmr_pid_param_code_effective_time
ON patient_monitoring_records (pid, monitoring_param_code, effective_time)
WHERE is_deleted = FALSE;

-- 病人观察时点表
CREATE TABLE patient_monitoring_time_points (
    id BIGSERIAL PRIMARY KEY,
    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    time_point TIMESTAMP NOT NULL, -- 观察时刻
    modified_by VARCHAR(255), -- 记录人
    modified_at TIMESTAMP, -- 记录的时间
    is_deleted BOOLEAN NOT NULL, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人
    deleted_at TIMESTAMP -- 删除时间
);
COMMENT ON TABLE patient_monitoring_time_points IS '病人观察时点表';
COMMENT ON COLUMN patient_monitoring_time_points.pid IS '病人ID';
COMMENT ON COLUMN patient_monitoring_time_points.dept_id IS '科室ID';
COMMENT ON COLUMN patient_monitoring_time_points.time_point IS '观察时刻';
COMMENT ON COLUMN patient_monitoring_time_points.modified_by IS '记录人';
COMMENT ON COLUMN patient_monitoring_time_points.modified_at IS '记录的时间';
COMMENT ON COLUMN patient_monitoring_time_points.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_monitoring_time_points.deleted_by IS '删除人';
COMMENT ON COLUMN patient_monitoring_time_points.deleted_at IS '删除时间';
CREATE UNIQUE INDEX idx_patient_monitoring_time_points_pid_time_point 
ON patient_monitoring_time_points (pid, time_point) 
WHERE is_deleted = FALSE;

-- 病人观察记录统计日表
CREATE TABLE patient_monitoring_record_stats_daily (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    monitoring_param_code VARCHAR(255) NOT NULL, -- 观察参数编码，关联到monitoring_params.code
    effective_time TIMESTAMP NOT NULL, -- 本地班次日期0点（比如上海），对应的UTC时间
    param_value VARCHAR(1000), -- 将GenericValuePB序列化字符串并进行base64编码后的字符串
    param_value_str VARCHAR(255), -- 记录时的值对应的字符串
    unit VARCHAR(100), -- 单位
    note VARCHAR(1000), -- 备注
    status VARCHAR(255), -- （备用字段）记录状态：已录入，已审核等
    modified_by VARCHAR(255), -- 记录人
    modified_at TIMESTAMP NOT NULL, -- 记录的时间，为班次日期对应的整点
    is_deleted BOOLEAN NOT NULL, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人
    delete_reason VARCHAR(255), -- 删除理由
    deleted_at TIMESTAMP -- 删除时间
);
COMMENT ON TABLE patient_monitoring_record_stats_daily IS '病人观察记录统计日表';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.pid IS '病人ID';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.dept_id IS '科室ID';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.monitoring_param_code IS '观察参数编码，关联到monitoring_params.code';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.effective_time IS '本地班次日期0点（比如上海），对应的UTC时间';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.param_value IS '将GenericValuePB序列化字符串并进行base64编码后的字符串';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.param_value_str IS '记录时的值对应的字符串';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.unit IS '单位';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.note IS '备注';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.status IS '记录状态：已录入，已审核等';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.modified_by IS '记录人';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.modified_at IS '记录的时间，为班次日期对应的整点';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.deleted_by IS '删除人';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.delete_reason IS '删除理由';
COMMENT ON COLUMN patient_monitoring_record_stats_daily.deleted_at IS '删除时间';
CREATE INDEX idx_pmr_stats_daily_pid_param_code_effective_time
ON patient_monitoring_record_stats_daily (pid, monitoring_param_code, effective_time);

CREATE TABLE patient_target_daily_balances (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    pid BIGINT NOT NULL, -- 病人ID
    shift_start_time TIMESTAMP NOT NULL, -- 系统时区0点的日期，比如上海的2023-10-02 00:00:00，在这里存储为2023-10-01 16:00:00
    target_balance_ml DOUBLE PRECISION NOT NULL,  -- 目标平衡量
    modified_at TIMESTAMP NOT NULL, -- 记录的时间
    modified_by VARCHAR(255), -- 记录人
    modified_by_account_name VARCHAR(255), -- 记录人姓名
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人
    deleted_at TIMESTAMP -- 删除时间
);
COMMENT ON TABLE patient_target_daily_balances IS '病人目标平衡量表';
COMMENT ON COLUMN patient_target_daily_balances.id IS '自增ID';
COMMENT ON COLUMN patient_target_daily_balances.pid IS '病人ID';
COMMENT ON COLUMN patient_target_daily_balances.shift_start_time IS '系统时区0点的日期，比如上海的2023-10-02 00:00:00，在这里存储为2023-10-01 16:00:00';
COMMENT ON COLUMN patient_target_daily_balances.target_balance_ml IS '目标平衡量';
COMMENT ON COLUMN patient_target_daily_balances.modified_at IS '记录的时间';
COMMENT ON COLUMN patient_target_daily_balances.modified_by IS '记录人';
COMMENT ON COLUMN patient_target_daily_balances.modified_by_account_name IS '记录人姓名';
COMMENT ON COLUMN patient_target_daily_balances.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_target_daily_balances.deleted_by IS '删除人';

CREATE INDEX idx_patient_target_daily_balances_pid ON patient_target_daily_balances (pid) WHERE is_deleted = FALSE;

CREATE TABLE dept_score_groups (
    id SERIAL PRIMARY KEY, -- 自增ID
    dept_id VARCHAR(255) NOT NULL, -- 部门ID
    score_group_code VARCHAR(255) NOT NULL, -- 评分类型编码
    score_group_meta VARCHAR(4000) NOT NULL, -- 将 ScoreGroupMetaPB 序列化字符串并进行base64编码后的字符串
    display_order INT NOT NULL, -- 评分类型的显示顺序
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人
    modified_at TIMESTAMP -- 最后修改时间
);

COMMENT ON TABLE dept_score_groups IS '科室评分分组表';
COMMENT ON COLUMN dept_score_groups.id IS '自增ID';
COMMENT ON COLUMN dept_score_groups.dept_id IS '部门ID';
COMMENT ON COLUMN dept_score_groups.score_group_code IS '评分类型编码， 对应于icis_config.proto:Config.score.group.code';
COMMENT ON COLUMN dept_score_groups.score_group_meta IS '评分类型元数据， 对应于ScoreGroupMetaPB';
COMMENT ON COLUMN dept_score_groups.display_order IS '评分类型的显示顺序';
COMMENT ON COLUMN dept_score_groups.is_deleted IS '是否已删除';
COMMENT ON COLUMN dept_score_groups.deleted_by IS '删除人';
COMMENT ON COLUMN dept_score_groups.deleted_at IS '删除时间';
COMMENT ON COLUMN dept_score_groups.modified_by IS '最后修改人';
COMMENT ON COLUMN dept_score_groups.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_dept_score_groups_dept_id_score_group_code ON dept_score_groups (dept_id, score_group_code) WHERE is_deleted = FALSE;

CREATE TABLE patient_scores (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    pid BIGINT NOT NULL, -- 病人ID
    score_group_code VARCHAR(255) NOT NULL, -- 评分类型编码
    score VARCHAR(1000) NOT NULL, -- 将ScoreGroupPB序列化字符串并进行base64编码后的字符串
    score_str VARCHAR(255) NOT NULL, -- 评分的字符串表示
    effective_time TIMESTAMP NOT NULL, -- score的生效时间
    note VARCHAR(255), -- 备注
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 记录人
    modified_by_account_name VARCHAR(255), -- 记录人姓名
    modified_at TIMESTAMP NOT NULL -- 记录的时间
);

COMMENT ON TABLE patient_scores IS '病人评分表。可以从评分模块打分，也可以从观察项模块打分';
COMMENT ON COLUMN patient_scores.id IS '自增ID';
COMMENT ON COLUMN patient_scores.pid IS '病人ID';
COMMENT ON COLUMN patient_scores.score IS '将ScoreGroupPB序列化字符串并进行base64编码后的字符串';
COMMENT ON COLUMN patient_scores.score_str IS '评分的字符串表示';
COMMENT ON COLUMN patient_scores.effective_time IS 'score的生效时间';
COMMENT ON COLUMN patient_scores.note IS '备注';
COMMENT ON COLUMN patient_scores.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_scores.deleted_by IS '删除人';
COMMENT ON COLUMN patient_scores.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_scores.modified_by IS '记录人';
COMMENT ON COLUMN patient_scores.modified_by_account_name IS '记录人姓名';
COMMENT ON COLUMN patient_scores.modified_at IS '记录的时间';
CREATE UNIQUE INDEX idx_patient_scores_pid_score_group_code_effective_time ON patient_scores (pid, score_group_code, effective_time) WHERE is_deleted = FALSE;

CREATE TABLE nursing_record_template_groups (
    id SERIAL PRIMARY KEY,
    entity_type Integer NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    display_order INTEGER NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP
);

COMMENT ON TABLE nursing_record_template_groups IS '护理记录模板组';
COMMENT ON COLUMN nursing_record_template_groups.id IS '自增id';
COMMENT ON COLUMN nursing_record_template_groups.entity_type IS '模板类型： 1：部门模板；0： 个人模板；';
COMMENT ON COLUMN nursing_record_template_groups.entity_id IS '实体id：如果type为1，则为deptId；如果type为0，则为accountId';
COMMENT ON COLUMN nursing_record_template_groups.name IS '护理记录模板组名';
COMMENT ON COLUMN nursing_record_template_groups.display_order IS '评分类型的显示顺序';
COMMENT ON COLUMN nursing_record_template_groups.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_record_template_groups.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_record_template_groups.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_record_template_groups.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_record_template_groups.modified_at IS '最后修改时间';

-- 创建唯一索引，保证部门id和模板组名的唯一性（is_deleted为false时有效）
CREATE UNIQUE INDEX idx_nursing_record_template_groups_name
    ON nursing_record_template_groups (entity_type, entity_id, name)
    WHERE is_deleted = FALSE;

CREATE TABLE nursing_record_templates (
    id SERIAL PRIMARY KEY,
    entity_type Integer NOT NULL,
    entity_id VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    display_order INTEGER NOT NULL,
    is_common INTEGER NOT NULL,  -- 1: 常用模板 0：非常用模板
    group_id INTEGER NOT NULL,  -- 模板分组id, 外键nursing_record_template_groups.id
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP,
    FOREIGN KEY (group_id) REFERENCES nursing_record_template_groups(id)
);

COMMENT ON TABLE nursing_record_templates IS '护理记录部门模板';
COMMENT ON COLUMN nursing_record_templates.id IS '自增id';
COMMENT ON COLUMN nursing_record_templates.entity_type IS '模板类型： 1：部门模板；0： 个人模板；';
COMMENT ON COLUMN nursing_record_templates.entity_id IS '实体id：如果type为1，则为deptId；如果type为0，则为accountId';
COMMENT ON COLUMN nursing_record_templates.name IS '模板名称';
COMMENT ON COLUMN nursing_record_templates.content IS '模板内容';
COMMENT ON COLUMN nursing_record_templates.display_order IS '显示顺序';
COMMENT ON COLUMN nursing_record_templates.is_common IS '1: 常用模板 0：非常用模板';
COMMENT ON COLUMN nursing_record_templates.group_id IS '模板分组id, 外键nursing_record_template_groups.id';
COMMENT ON COLUMN nursing_record_templates.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_record_templates.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_record_templates.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_record_templates.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_record_templates.modified_at IS '最后修改时间';

-- 创建唯一索引，保证部门id和模板名的唯一性（is_deleted为false时有效）
CREATE UNIQUE INDEX idx_nursing_record_templates_name
    ON nursing_record_templates (entity_type, entity_id, name)
    WHERE is_deleted = FALSE;

CREATE TABLE nursing_records (
    id BIGSERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    effective_time TIMESTAMP NOT NULL,
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    source VARCHAR(1000),
    patient_critical_lis_handling_id INT,  -- 关联的病人危急值处理记录ID
    reviewed_by VARCHAR(255),
    reviewed_by_account_name VARCHAR(255),
    reviewed_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP,
    created_by VARCHAR(255),
    created_by_account_name VARCHAR(255),
    created_at TIMESTAMP
);

COMMENT ON TABLE nursing_records IS '护理记录';
COMMENT ON COLUMN nursing_records.id IS '自增id';
COMMENT ON COLUMN nursing_records.patient_id IS '患者id';
COMMENT ON COLUMN nursing_records.content IS '护理记录的内容';
COMMENT ON COLUMN nursing_records.effective_time IS '护理记录的记录时间';
COMMENT ON COLUMN nursing_records.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_records.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_records.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_records.source IS '数据来源，比如template_id&params，等';
COMMENT ON COLUMN nursing_records.patient_critical_lis_handling_id IS '关联的病人危急值处理记录ID';
COMMENT ON COLUMN nursing_records.reviewed_by IS '审核人的account_id';
COMMENT ON COLUMN nursing_records.reviewed_by_account_name IS '审核人姓名';
COMMENT ON COLUMN nursing_records.reviewed_at IS '审核时间';
COMMENT ON COLUMN nursing_records.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_records.modified_by_account_name IS '最后修改人姓名';
COMMENT ON COLUMN nursing_records.modified_at IS '最后修改时间';
COMMENT ON COLUMN nursing_records.created_by IS '创建人';
COMMENT ON COLUMN nursing_records.created_by_account_name IS '创建人姓名';
COMMENT ON COLUMN nursing_records.created_at IS '创建时间';

CREATE UNIQUE INDEX idx_nursing_records_patient_effective_time
    ON nursing_records (patient_id, effective_time, patient_critical_lis_handling_id)
    WHERE is_deleted = FALSE;

CREATE TABLE nursing_order_template_groups (
    id SERIAL PRIMARY KEY,  -- 自增id
    dept_id VARCHAR(255) NOT NULL,  -- 部门id
    name VARCHAR(255) NOT NULL,  -- 护理计划模板组名
    display_order INTEGER NOT NULL,  -- 显示顺序
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP,  -- 删除时间
    modified_by VARCHAR(255),  -- 最后修改人
    modified_at TIMESTAMP  -- 最后修改时间
);
COMMENT ON TABLE nursing_order_template_groups IS '护理计划模板分组表';
COMMENT ON COLUMN nursing_order_template_groups.id IS '自增id';
COMMENT ON COLUMN nursing_order_template_groups.dept_id IS '部门id';
COMMENT ON COLUMN nursing_order_template_groups.name IS '护理计划模板组名';
COMMENT ON COLUMN nursing_order_template_groups.display_order IS '显示顺序';
COMMENT ON COLUMN nursing_order_template_groups.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_order_template_groups.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_order_template_groups.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_order_template_groups.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_order_template_groups.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_nursing_order_template_groups_dept_name
    ON nursing_order_template_groups (dept_id, name)
    WHERE is_deleted = FALSE;

CREATE TABLE nursing_order_templates (
    id SERIAL PRIMARY KEY,  -- 自增id
    dept_id VARCHAR(255) NOT NULL,  -- 部门id
    group_id INTEGER NOT NULL,  -- 模板分组id, 外键nursing_order_template_groups.id
    name VARCHAR(255) NOT NULL,  -- 模板名称
    duration_type INTEGER NOT NULL,  -- 0: 临时护嘱 1: 长期护嘱
    medication_freq_code VARCHAR(255) NOT NULL,  -- 频次编码，外键：medication_frequencies.code
    display_order INTEGER NOT NULL,  -- 显示顺序
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP,  -- 删除时间
    modified_by VARCHAR(255),  -- 最后修改人
    modified_at TIMESTAMP,  -- 最后修改时间
    FOREIGN KEY (group_id) REFERENCES nursing_order_template_groups(id)  -- 外键约束
);
COMMENT ON TABLE nursing_order_templates IS '护理计划模板表';
COMMENT ON COLUMN nursing_order_templates.id IS '自增id';
COMMENT ON COLUMN nursing_order_templates.dept_id IS '部门id';
COMMENT ON COLUMN nursing_order_templates.group_id IS '模板分组id, 外键nursing_order_template_groups.id';
COMMENT ON COLUMN nursing_order_templates.name IS '模板名称';
COMMENT ON COLUMN nursing_order_templates.duration_type IS '0: 临时护嘱 1: 长期护嘱';
COMMENT ON COLUMN nursing_order_templates.medication_freq_code IS '频次编码，外键：medication_frequencies.code';
COMMENT ON COLUMN nursing_order_templates.display_order IS '显示顺序';
COMMENT ON COLUMN nursing_order_templates.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_order_templates.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_order_templates.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_order_templates.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_order_templates.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_nursing_order_templates_dept_name
    ON nursing_order_templates (dept_id, group_id, name)
    WHERE is_deleted = FALSE;

CREATE TABLE nursing_orders (
    id BIGSERIAL PRIMARY KEY,  -- 自增id
    pid BIGINT NOT NULL,  -- 患者id
    dept_id VARCHAR(255) NOT NULL,  -- 部门id
    order_template_id INT NOT NULL,  -- 模板id, 外键nursing_order_templates.id
    name VARCHAR(255) NOT NULL,  -- 护理计划名称
    duration_type INTEGER NOT NULL,  -- 0: 临时护嘱 1: 长期护嘱
    medication_freq_code VARCHAR(255) NOT NULL,  -- 频次编码，外键：medication_frequencies.code
    order_by VARCHAR(255),  -- 开立人
    order_time TIMESTAMP NOT NULL,  -- 开立时间
    stop_by VARCHAR(255),  -- 停止人
    stop_time TIMESTAMP,  -- 停止时间
    note TEXT,  -- 备注
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP,  -- 删除时间
    modified_by VARCHAR(255),  -- 最后修改人
    modified_at TIMESTAMP,  -- 最后修改时间
    FOREIGN KEY (order_template_id) REFERENCES nursing_order_templates(id)  -- 外键约束
);
COMMENT ON TABLE nursing_orders IS '护理计划表';
COMMENT ON COLUMN nursing_orders.id IS '自增id';
COMMENT ON COLUMN nursing_orders.pid IS '患者id';
COMMENT ON COLUMN nursing_orders.dept_id IS '部门id';
COMMENT ON COLUMN nursing_orders.order_template_id IS '模板id, 外键nursing_order_templates.id';
COMMENT ON COLUMN nursing_orders.name IS '护理计划名称';
COMMENT ON COLUMN nursing_orders.duration_type IS '0: 临时护嘱 1: 长期护嘱';
COMMENT ON COLUMN nursing_orders.medication_freq_code IS '频次编码，外键：medication_frequencies.code';
COMMENT ON COLUMN nursing_orders.order_by IS '开立人';
COMMENT ON COLUMN nursing_orders.order_time IS '开立时间';
COMMENT ON COLUMN nursing_orders.stop_by IS '停止人';
COMMENT ON COLUMN nursing_orders.stop_time IS '停止时间';
COMMENT ON COLUMN nursing_orders.note IS '备注';
COMMENT ON COLUMN nursing_orders.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_orders.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_orders.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_orders.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_orders.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_nursing_orders_pid_order_time 
    ON nursing_orders (pid, order_template_id, order_time)
    WHERE is_deleted = FALSE;

CREATE TABLE nursing_execution_records (
    id BIGSERIAL PRIMARY KEY,  -- 自增id
    pid BIGINT NOT NULL,  -- 患者id
    nursing_order_id BIGINT NOT NULL,  -- 护理计划id, 外键nursing_orders.id
    plan_time TIMESTAMP NOT NULL,  -- 计划执行时间
    completed_by VARCHAR(255),  -- 执行人
    completed_time TIMESTAMP,  -- 执行完成时间
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP,  -- 删除时间
    modified_by VARCHAR(255),  -- 最后修改人
    modified_at TIMESTAMP,  -- 最后修改时间
    FOREIGN KEY (nursing_order_id) REFERENCES nursing_orders(id)  -- 外键约束
);
COMMENT ON TABLE nursing_execution_records IS '护理计划执行记录表';
COMMENT ON COLUMN nursing_execution_records.id IS '自增id';
COMMENT ON COLUMN nursing_execution_records.pid IS '患者id';
COMMENT ON COLUMN nursing_execution_records.nursing_order_id IS '护理计划id, 外键nursing_orders.id';
COMMENT ON COLUMN nursing_execution_records.plan_time IS '计划执行时间';
COMMENT ON COLUMN nursing_execution_records.completed_by IS '执行人';
COMMENT ON COLUMN nursing_execution_records.completed_time IS '执行完成时间';
COMMENT ON COLUMN nursing_execution_records.is_deleted IS '是否已删除';
COMMENT ON COLUMN nursing_execution_records.deleted_by IS '删除人';
COMMENT ON COLUMN nursing_execution_records.deleted_at IS '删除时间';
COMMENT ON COLUMN nursing_execution_records.modified_by IS '最后修改人';
COMMENT ON COLUMN nursing_execution_records.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_nursing_execution_records_pid_nursing_order_id_plan_time 
    ON nursing_execution_records (pid, nursing_order_id, plan_time)
    WHERE is_deleted = FALSE;

CREATE TABLE patient_shift_records (
    id BIGSERIAL PRIMARY KEY,             -- 自增id
    pid BIGINT NOT NULL,                  -- 病人id
    content TEXT NOT NULL,                -- 交班内容
    shift_nurse_id VARCHAR(255) NOT NULL, -- 交班护士ID
    shift_nurse_name VARCHAR(255) NOT NULL,  -- 交班护士姓名
    shift_name VARCHAR(255) NOT NULL,        -- 班次名称
    shift_start TIMESTAMP NOT NULL,       -- 班次开始时间
    shift_end TIMESTAMP NOT NULL,         -- 班次结束时间
    modified_at TIMESTAMP NOT NULL,       -- 最后修改时间
    is_deleted BOOLEAN NOT NULL,          -- 是否已删除
    deleted_by VARCHAR(255),              -- 删除人
    deleted_at TIMESTAMP,                 -- 删除时间
    FOREIGN KEY (pid) REFERENCES patient_records(id)  -- patient_records.id
);

COMMENT ON TABLE patient_shift_records IS '病人交班记录表';
COMMENT ON COLUMN patient_shift_records.id IS '自增id';
COMMENT ON COLUMN patient_shift_records.pid IS '病人id';
COMMENT ON COLUMN patient_shift_records.content IS '交班内容';
COMMENT ON COLUMN patient_shift_records.shift_nurse_id IS '交班护士ID';
COMMENT ON COLUMN patient_shift_records.shift_nurse_name IS '交班护士姓名';
COMMENT ON COLUMN patient_shift_records.shift_name IS '班次名称';
COMMENT ON COLUMN patient_shift_records.shift_start IS '班次开始时间';
COMMENT ON COLUMN patient_shift_records.shift_end IS '班次结束时间';
COMMENT ON COLUMN patient_shift_records.modified_at IS '最后修改时间';
COMMENT ON COLUMN patient_shift_records.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_shift_records.deleted_by IS '删除人';
COMMENT ON COLUMN patient_shift_records.deleted_at IS '删除时间';

-- 创建唯一索引
CREATE UNIQUE INDEX idx_patient_shift_records_pid_shift_name_shift_start 
    ON patient_shift_records (pid, shift_name, shift_start)
    WHERE is_deleted = FALSE;

CREATE TABLE dragable_form_templates (
    id SERIAL PRIMARY KEY,  -- 自增id
    dept_id VARCHAR(255) NOT NULL,  -- 部门id
    name VARCHAR(255) NOT NULL,  -- 模板名称
    template_pb TEXT NOT NULL,  -- 模板内容, JfkTemplatePB 序列化后的字符串
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP  -- 删除时间
);
COMMENT ON TABLE dragable_form_templates IS '可拖拽表单模板表';
COMMENT ON COLUMN dragable_form_templates.id IS '自增id';
COMMENT ON COLUMN dragable_form_templates.dept_id IS '部门id';
COMMENT ON COLUMN dragable_form_templates.name IS '模板名称';
COMMENT ON COLUMN dragable_form_templates.template_pb IS '模板内容, JfkTemplatePB 序列化后的字符串';
COMMENT ON COLUMN dragable_form_templates.is_deleted IS '是否已删除';
COMMENT ON COLUMN dragable_form_templates.deleted_by IS '删除人';
COMMENT ON COLUMN dragable_form_templates.deleted_at IS '删除时间';

CREATE UNIQUE INDEX idx_dragable_form_templates_dept_id_name ON dragable_form_templates (dept_id, name)
WHERE is_deleted = FALSE;

CREATE TABLE dragable_forms (
    id BIGSERIAL PRIMARY KEY,  -- 自增id
    pid BIGINT NOT NULL,  -- 病人id
    dept_id VARCHAR(255) NOT NULL,  -- 部门id
    template_id INT NOT NULL,  -- 模板id, 外键dragable_form_templates.id
    form_pb TEXT NOT NULL,  -- 表单内容, JfkFormInstancePB 序列化后的字符串
    documented_at TIMESTAMP NOT NULL,  -- 报表时间
    modified_by VARCHAR(255),  -- 最后修改人
    modified_at TIMESTAMP NOT NULL,  -- 最后修改时间
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP,  -- 删除时间
    FOREIGN KEY (template_id) REFERENCES dragable_form_templates(id)  -- 外键约束
);
COMMENT ON TABLE dragable_forms IS '可拖拽表单记录表';
COMMENT ON COLUMN dragable_forms.id IS '自增id';
COMMENT ON COLUMN dragable_forms.pid IS '病人id';
COMMENT ON COLUMN dragable_forms.dept_id IS '部门id';
COMMENT ON COLUMN dragable_forms.template_id IS '模板id, 外键dragable_form_templates.id';
COMMENT ON COLUMN dragable_forms.form_pb IS '表单内容, JfkFormInstancePB 序列化后的字符串';
COMMENT ON COLUMN dragable_forms.documented_at IS '报表时间';
COMMENT ON COLUMN dragable_forms.modified_by IS '最后修改人';
COMMENT ON COLUMN dragable_forms.modified_at IS '最后修改时间';
COMMENT ON COLUMN dragable_forms.is_deleted IS '是否已删除';
COMMENT ON COLUMN dragable_forms.deleted_by IS '删除人';
COMMENT ON COLUMN dragable_forms.deleted_at IS '删除时间';

CREATE UNIQUE INDEX idx_dragable_forms_pid_tid_doctime ON dragable_forms (pid, template_id, documented_at) WHERE is_deleted = FALSE;

CREATE TABLE ward_reports (
    id BIGSERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    shift_start_time TIMESTAMP NOT NULL,
    ward_report_pb TEXT NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP
);

COMMENT ON TABLE ward_reports IS '病区报告表';
COMMENT ON COLUMN ward_reports.id IS '自增主键';
COMMENT ON COLUMN ward_reports.dept_id IS '科室ID';
COMMENT ON COLUMN ward_reports.shift_start_time IS '班次开始时间';
COMMENT ON COLUMN ward_reports.ward_report_pb IS '病区报告数据，WardReportPB的Base64字节码';
COMMENT ON COLUMN ward_reports.modified_at IS '最后修改时间';
COMMENT ON COLUMN ward_reports.modified_by IS '最后修改人';
COMMENT ON COLUMN ward_reports.is_deleted IS '是否已删除';
COMMENT ON COLUMN ward_reports.deleted_by IS '删除人';
COMMENT ON COLUMN ward_reports.deleted_at IS '删除时间';

CREATE UNIQUE INDEX idx_ward_reports_dept_shift_start
    ON ward_reports (dept_id, shift_start_time)
    WHERE is_deleted = false;

-- 床位配置表
CREATE TABLE bed_configs (
    id BIGSERIAL PRIMARY KEY,
    department_id VARCHAR(255) NOT NULL,
    his_bed_number VARCHAR(255) NOT NULL,
    device_bed_number VARCHAR(255) NOT NULL,
    display_bed_number VARCHAR(255) NOT NULL,
    bed_type INT NOT NULL,
    note VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP
);
COMMENT ON TABLE bed_configs IS '床位配置表';

COMMENT ON COLUMN bed_configs.id IS '自增id';
COMMENT ON COLUMN bed_configs.department_id IS '科室编码，NOT NULL';
COMMENT ON COLUMN bed_configs.his_bed_number IS 'his床位号，NOT NULL';
COMMENT ON COLUMN bed_configs.device_bed_number IS '设备床位号，NOT NULL';
COMMENT ON COLUMN bed_configs.display_bed_number IS '显示床位号，NOT NULL';
COMMENT ON COLUMN bed_configs.bed_type IS '床位类型，icis_device.proto:DeviceEnums.bed_type';
COMMENT ON COLUMN bed_configs.note IS '备注';
COMMENT ON COLUMN bed_configs.is_deleted IS '是否已删除';
COMMENT ON COLUMN bed_configs.deleted_by IS '删除人';
COMMENT ON COLUMN bed_configs.deleted_at IS '删除时间';
COMMENT ON COLUMN bed_configs.modified_by IS '最后修改人';
COMMENT ON COLUMN bed_configs.modified_by_account_name IS '修改人姓名';
COMMENT ON COLUMN bed_configs.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_bed_configs_deptid_hisbed
    ON bed_configs (department_id, his_bed_number)
    WHERE is_deleted = false;
CREATE UNIQUE INDEX idx_bed_configs_deptid_dispbed
    ON bed_configs (department_id, display_bed_number)
    WHERE is_deleted = false;

CREATE TABLE bed_counts (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    bed_count INT NOT NULL,
    effective_time TIMESTAMP NOT NULL,  -- 生效时间
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP
);
COMMENT ON TABLE bed_counts IS '床位统计表';

COMMENT ON COLUMN bed_counts.id IS '自增id';
COMMENT ON COLUMN bed_counts.dept_id IS '科室编码，NOT NULL';
COMMENT ON COLUMN bed_counts.bed_count IS '床位数量，NOT NULL';
COMMENT ON COLUMN bed_counts.effective_time IS '生效时间，NOT NULL';
COMMENT ON COLUMN bed_counts.is_deleted IS '是否已删除';
COMMENT ON COLUMN bed_counts.deleted_by IS '删除人';
COMMENT ON COLUMN bed_counts.deleted_at IS '删除时间';
COMMENT ON COLUMN bed_counts.modified_by IS '最后修改人';
COMMENT ON COLUMN bed_counts.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_bed_counts_deptid_effective_time
    ON bed_counts (dept_id, effective_time)
    WHERE is_deleted = false;

-- 病人床位历史表
CREATE TABLE patient_bed_history (
    id BIGSERIAL PRIMARY KEY,
    patient_id BIGINT,
    his_bed_number VARCHAR(255),
    device_bed_number VARCHAR(255),
    display_bed_number VARCHAR(255),
    switch_time TIMESTAMP NOT NULL,
    switch_type INT NOT NULL,  -- 换床类型：1-入科（重返），2-出科（重返），3-普通换床
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP,
    FOREIGN KEY (patient_id) REFERENCES patient_records(id)
);
COMMENT ON TABLE patient_bed_history IS '病人床位历史表（由his同步过来）';

COMMENT ON COLUMN patient_bed_history.id IS '自增id';
COMMENT ON COLUMN patient_bed_history.patient_id IS '病人id，外键patient_records.id';
COMMENT ON COLUMN patient_bed_history.his_bed_number IS 'his床位号';
COMMENT ON COLUMN patient_bed_history.device_bed_number IS '设备床位号';
COMMENT ON COLUMN patient_bed_history.display_bed_number IS '显示床位号';
COMMENT ON COLUMN patient_bed_history.switch_time IS '换床时间';
COMMENT ON COLUMN patient_bed_history.switch_type IS '换床类型：0-普通换床，1-重返出科，2-重返入科';
COMMENT ON COLUMN patient_bed_history.modified_by IS '最后修改人';
COMMENT ON COLUMN patient_bed_history.modified_by_account_name IS '修改人姓名';
COMMENT ON COLUMN patient_bed_history.modified_at IS '最后修改时间';

CREATE INDEX idx_patient_bed_history_patient_id
    ON patient_bed_history (patient_id);

-- 设备信息表
CREATE TABLE device_infos (
    id SERIAL PRIMARY KEY,
    department_id VARCHAR(255) NOT NULL,
    device_sn VARCHAR(255) NOT NULL,
    device_bed_number VARCHAR(255),
    device_type INTEGER NOT NULL,
    device_name VARCHAR(255) NOT NULL,
    device_ip VARCHAR(255),
    device_port VARCHAR(255),
    device_driver_code VARCHAR(255),
    network_protocol INTEGER,
    serial_protocol INTEGER,
    model VARCHAR(255),
    manufacturer VARCHAR(255),
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP
);
COMMENT ON TABLE device_infos IS '设备信息表';

COMMENT ON COLUMN device_infos.id IS '自增id';
COMMENT ON COLUMN device_infos.department_id IS '部门代码';
COMMENT ON COLUMN device_infos.device_sn IS '设备序列号';
COMMENT ON COLUMN device_infos.device_bed_number IS '设备默认床号';
COMMENT ON COLUMN device_infos.device_type IS '设备类型 icis_device.proto:DeviceEnums.device_type';
COMMENT ON COLUMN device_infos.device_name IS '设备名称';
COMMENT ON COLUMN device_infos.device_ip IS '设备的IP地址';
COMMENT ON COLUMN device_infos.device_port IS '设备端口';
COMMENT ON COLUMN device_infos.device_driver_code IS '驱动编码，内部编码（如：迈瑞-呼吸-code1，飞利浦-监护-code2）';
COMMENT ON COLUMN device_infos.network_protocol IS '网络协议（tcp/udp等），icis_device.proto:DeviceEnums.network_protocol';
COMMENT ON COLUMN device_infos.serial_protocol IS '串口端口（RS232, 485等），icis_device.proto:DeviceEnums.serial_protocol';
COMMENT ON COLUMN device_infos.model IS '型号';
COMMENT ON COLUMN device_infos.manufacturer IS '生产厂家';
COMMENT ON COLUMN device_infos.is_deleted IS '是否已删除';
COMMENT ON COLUMN device_infos.deleted_by IS '删除人';
COMMENT ON COLUMN device_infos.deleted_at IS '删除时间';
COMMENT ON COLUMN device_infos.modified_by IS '最后修改人';
COMMENT ON COLUMN device_infos.modified_by_account_name IS '修改人姓名';
COMMENT ON COLUMN device_infos.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_device_infos_device_name ON device_infos (department_id, device_name) WHERE is_deleted = false;

-- 设备绑定表
CREATE TABLE patient_devices (
    id BIGSERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL,
    device_id INTEGER NOT NULL,
    binding_time TIMESTAMP NOT NULL,
    unbinding_time TIMESTAMP,
    is_deleted BOOLEAN NOT NULL,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP,
    FOREIGN KEY (patient_id) REFERENCES patient_records(id),
    FOREIGN KEY (device_id) REFERENCES device_infos(id)
);
COMMENT ON TABLE patient_devices IS '病人设备绑定表';

COMMENT ON COLUMN patient_devices.id IS '自增id';
COMMENT ON COLUMN patient_devices.patient_id IS '病人id，外键 patient_records.id';
COMMENT ON COLUMN patient_devices.device_id IS '设备id，外键 device_infos.id';
COMMENT ON COLUMN patient_devices.binding_time IS '设备绑定时间';
COMMENT ON COLUMN patient_devices.unbinding_time IS '设备解绑时间';
COMMENT ON COLUMN patient_devices.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_devices.deleted_by IS '删除人';
COMMENT ON COLUMN patient_devices.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_devices.modified_by IS '最后修改人';
COMMENT ON COLUMN patient_devices.modified_by_account_name IS '修改人姓名';
COMMENT ON COLUMN patient_devices.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_patient_devices_pid_did_btime
    ON patient_devices (patient_id, device_id, binding_time)
    WHERE is_deleted = false;

-- 设备数据表
CREATE TABLE device_data (
    id BIGSERIAL PRIMARY KEY,
    department_id VARCHAR(255),
    device_id INTEGER,
    device_type INTEGER,
    device_bed_number VARCHAR(255),
    param_code VARCHAR(255),
    recorded_at TIMESTAMP,
    recorded_str VARCHAR(255)
);
COMMENT ON TABLE device_data IS '设备数据表，只保存最近X天的数据（例如15/30天）';

COMMENT ON COLUMN device_data.id IS '自增id';
COMMENT ON COLUMN device_data.department_id IS '部门编码';
COMMENT ON COLUMN device_data.device_id IS '设备id，外键 device_infos.id';
COMMENT ON COLUMN device_data.device_type IS '设备类型';
COMMENT ON COLUMN device_data.device_bed_number IS '设备床位号';
COMMENT ON COLUMN device_data.param_code IS '监测参数code，对应 monitoring_params.code';
COMMENT ON COLUMN device_data.recorded_at IS '数据记录时间';
COMMENT ON COLUMN device_data.recorded_str IS '设备数据原始值';

CREATE INDEX idx_device_data_device_id ON device_data(device_id);
CREATE INDEX idx_device_data_recorded_at ON device_data(recorded_at);

CREATE TABLE device_data_hourly (
    id BIGSERIAL PRIMARY KEY,
    department_id VARCHAR(255),
    device_id INTEGER,
    device_type INTEGER,
    device_bed_number VARCHAR(255),
    param_code VARCHAR(255),
    recorded_at TIMESTAMP,
    recorded_str VARCHAR(255)
);
COMMENT ON TABLE device_data_hourly IS '设备数据小时表，保存整点数据';

COMMENT ON COLUMN device_data_hourly.id IS '自增id';
COMMENT ON COLUMN device_data_hourly.department_id IS '部门编码';
COMMENT ON COLUMN device_data_hourly.device_id IS '设备id，外键 device_infos.id';
COMMENT ON COLUMN device_data_hourly.device_type IS '设备类型';
COMMENT ON COLUMN device_data_hourly.device_bed_number IS '设备床位号';
COMMENT ON COLUMN device_data_hourly.param_code IS '监测参数code，对应 monitoring_params.code';
COMMENT ON COLUMN device_data_hourly.recorded_at IS '数据记录时间';
COMMENT ON COLUMN device_data_hourly.recorded_str IS '设备数据原始值';

CREATE INDEX idx_device_data_hourly_device_id ON device_data_hourly(device_id);
CREATE INDEX idx_device_data_hourly_recorded_at ON device_data_hourly(recorded_at);

CREATE TABLE device_data_hourly_approx (
    id BIGSERIAL PRIMARY KEY,
    department_id VARCHAR(255),
    device_id INTEGER,
    device_type INTEGER,
    device_bed_number VARCHAR(255),
    param_code VARCHAR(255),
    recorded_at TIMESTAMP,
    recorded_str VARCHAR(255)
);
COMMENT ON TABLE device_data_hourly_approx IS '设备非整点数据表，保存 满足就近原则参数 的非整点数据';

COMMENT ON COLUMN device_data_hourly_approx.id IS '自增id';
COMMENT ON COLUMN device_data_hourly_approx.department_id IS '部门编码';
COMMENT ON COLUMN device_data_hourly_approx.device_id IS '设备id，外键 device_infos.id';
COMMENT ON COLUMN device_data_hourly_approx.device_type IS '设备类型';
COMMENT ON COLUMN device_data_hourly_approx.device_bed_number IS '设备床位号';
COMMENT ON COLUMN device_data_hourly_approx.param_code IS '监测参数code，对应 monitoring_params.code';
COMMENT ON COLUMN device_data_hourly_approx.recorded_at IS '数据记录时间';
COMMENT ON COLUMN device_data_hourly_approx.recorded_str IS '设备数据原始值';

CREATE INDEX idx_device_data_hourly_approx_device_id ON device_data_hourly_approx(device_id);
CREATE INDEX idx_device_data_hourly_approx_recorded_at ON device_data_hourly_approx(recorded_at);

CREATE TABLE disease_metas (
    code VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    disease_meta_pbtxt TEXT NOT NULL
);
COMMENT ON TABLE disease_metas IS '疾病元信息表';
COMMENT ON COLUMN disease_metas.code IS '疾病编码';
COMMENT ON COLUMN disease_metas.name IS '疾病名称';
COMMENT ON COLUMN disease_metas.description IS '疾病描述';
COMMENT ON COLUMN disease_metas.disease_meta_pbtxt IS '将DiseaseMetaPB序列化字符串并进行base64编码后的字符串';


CREATE TABLE patient_diagnoses (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    disease_code VARCHAR(255) NOT NULL, -- 疾病编码，外键disease_metas.code
    disease_pbtxt TEXT NOT NULL, -- 将DiseasePB序列化字符串并进行base64编码后的字符串
    eval_start_at TIMESTAMP NOT NULL, -- 评估开始时间
    eval_end_at TIMESTAMP NOT NULL,   -- 评估结束时间
    confirmed_by VARCHAR(255) NOT NULL, -- 确诊人
    confirmed_by_account_name VARCHAR(255), -- 确诊人姓名
    confirmed_at TIMESTAMP NOT NULL, -- 确诊时间
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE, -- 是否已删除
    deleted_by VARCHAR(255), -- 疾病排除人
    deleted_by_account_name VARCHAR(255), -- 疾病排除人姓名
    deleted_at TIMESTAMP, -- 排除时间
    modified_by VARCHAR(255), -- 记录人
    modified_at TIMESTAMP NOT NULL, -- 记录的时间
    FOREIGN KEY (pid) REFERENCES patient_records(id), -- 外键约束
    FOREIGN KEY (disease_code) REFERENCES disease_metas(code) -- 外键约束
);
COMMENT ON TABLE patient_diagnoses IS '病人诊断表';
COMMENT ON COLUMN patient_diagnoses.id IS '自增ID';
COMMENT ON COLUMN patient_diagnoses.dept_id IS '科室ID';
COMMENT ON COLUMN patient_diagnoses.pid IS '病人ID';
COMMENT ON COLUMN patient_diagnoses.disease_code IS '疾病编码，外键disease_metas.code';
COMMENT ON COLUMN patient_diagnoses.disease_pbtxt IS '将DiseasePB序列化字符串并进行base64编码后的字符串';
COMMENT ON COLUMN patient_diagnoses.eval_start_at IS '评估开始时间';
COMMENT ON COLUMN patient_diagnoses.eval_end_at IS '评估结束时间';
COMMENT ON COLUMN patient_diagnoses.confirmed_by IS '确诊人';
COMMENT ON COLUMN patient_diagnoses.confirmed_by_account_name IS '确诊人姓名';
COMMENT ON COLUMN patient_diagnoses.confirmed_at IS '确诊时间';
COMMENT ON COLUMN patient_diagnoses.modified_by IS '记录人';
COMMENT ON COLUMN patient_diagnoses.modified_at IS '记录的时间';
COMMENT ON COLUMN patient_diagnoses.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_diagnoses.deleted_by IS '删除人';
COMMENT ON COLUMN patient_diagnoses.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN patient_diagnoses.deleted_at IS '删除时间';

CREATE UNIQUE INDEX idx_patient_diagnoses_pid_disease_code 
    ON patient_diagnoses (pid, disease_code, confirmed_at);

CREATE TABLE dept_doctor_score_types (
    id SERIAL PRIMARY KEY, -- 自增ID
    dept_id VARCHAR(255) NOT NULL, -- 部门ID
    code VARCHAR(255) NOT NULL, -- 评分类型编码
    name VARCHAR(255) NOT NULL, -- 评分类型名称
    display_order INT NOT NULL, -- 评分类型的显示顺序
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人
    modified_at TIMESTAMP -- 最后修改时间
);

COMMENT ON TABLE dept_doctor_score_types IS '科室评分分组表';
COMMENT ON COLUMN dept_doctor_score_types.id IS '自增ID';
COMMENT ON COLUMN dept_doctor_score_types.dept_id IS '部门ID';
COMMENT ON COLUMN dept_doctor_score_types.code IS '评分类型编码， 对应于icis_config.proto:Config.doctor_score.doctor_score_type.key';
COMMENT ON COLUMN dept_doctor_score_types.name IS '评分类型名称， 对应于icis_config.proto:Config.doctor_score.doctor_score_type.val';
COMMENT ON COLUMN dept_doctor_score_types.display_order IS '评分类型的显示顺序';
COMMENT ON COLUMN dept_doctor_score_types.is_deleted IS '是否已删除';
COMMENT ON COLUMN dept_doctor_score_types.deleted_by IS '删除人';
COMMENT ON COLUMN dept_doctor_score_types.deleted_at IS '删除时间';
COMMENT ON COLUMN dept_doctor_score_types.modified_by IS '最后修改人';
COMMENT ON COLUMN dept_doctor_score_types.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_dept_doctor_score_types_dept_id_code ON dept_doctor_score_types (dept_id, code) WHERE is_deleted = FALSE;

CREATE TABLE apache_ii_scores (
    id BIGSERIAL PRIMARY KEY,            -- 自增主键ID
    pid BIGINT NOT NULL,                 -- 病人ID
    dept_id VARCHAR(255) NOT NULL,       -- 所属科室ID

    score_time TIMESTAMP NOT NULL,       -- 评分时间
    scored_by VARCHAR(255) NOT NULL,     -- 评分人账号
    scored_by_account_name VARCHAR(255), -- 评分人姓名
    eval_start_at TIMESTAMP,    -- 评估开始时间
    eval_end_at TIMESTAMP,      -- 评估结束时间

    -- === ApsParams 展开 ===
    body_temperature FLOAT,     -- 体温(°C)
    mean_arterial_pressure FLOAT,  -- 平均动脉压(MAP, mmHg)
    heart_rate FLOAT,            -- 心率(次/分钟)
    respiratory_rate FLOAT,      -- 呼吸频率(次/分钟)
    fio2 FLOAT,                 -- 吸入氧浓度(0.21 ~ 1.0)
    a_a_do2 FLOAT,             -- A-aDO2(在 FiO2 < 0.5 时使用)
    pao2 FLOAT,                -- PaO2(在 FiO2 >= 0.5 时使用)
    ph FLOAT,                  -- 动脉血 pH
    hco3 FLOAT,                -- 血碳酸氢根 HCO3(mmol/L, 无pH时使用)
    sodium FLOAT,              -- 血钠 Na+(mmol/L)
    potassium FLOAT,           -- 血钾 K+(mmol/L)
    creatinine FLOAT,          -- 血肌酐 Cr(mg/dL)
    has_acute_renal_failure BOOLEAN, -- 是否为急性肾衰竭
    hematocrit FLOAT,          -- 血球压积(如: %)
    white_blood_cell_count FLOAT,  -- 白细胞 WBC(10^9/L)
    glasgow_coma_scale INT,    -- GCS(3~15)

    -- 年龄
    age INT NOT NULL,

    -- === ChcParams 展开 ===
    has_chronic_conditions BOOLEAN, -- 是否存在慢性疾病
    chc_cardio BOOLEAN,        -- 心血管系统慢性病
    chc_resp BOOLEAN,          -- 呼吸系统慢性病
    chc_liver BOOLEAN,         -- 肝脏慢性病
    chc_kidney BOOLEAN,        -- 肾脏慢性病
    chc_immune BOOLEAN,        -- 免疫功能障碍
    non_operative_or_emergency_surgery BOOLEAN NOT NULL,     -- 非手术或急诊手术

    -- Apache II 评分结果
    aps_score INT NOT NULL,            -- Apache II Aps评分
    age_score INT NOT NULL,            -- Apache II 年龄评分
    chc_score INT NOT NULL,            -- Apache II Chc评分
    apache_ii_score INT NOT NULL,

    -- 预计病死率
    predicted_mortality_rate FLOAT NOT NULL,  -- 预测死亡率
    coeff TEXT,   -- proto消息ApacheIIScoreMetaPB(只包含系数*_coef)实例序列化后的base64编码
    is_operative BOOLEAN NOT NULL,        -- 是否为手术患者
    is_emergency_operation BOOLEAN,  -- 是否为急诊手术患者

    mortality_factor_code VARCHAR(255) NOT NULL,  -- （非）手术患者诊断主因编码
    mortality_factor_name VARCHAR(255),  -- （非）手术患者诊断主因名称

    -- 软删除及修改信息
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,  -- 是否已删除
    deleted_by VARCHAR(255),               -- 疾病排除人
    deleted_by_account_name VARCHAR(255),  -- 疾病排除人姓名
    deleted_at TIMESTAMP,                  -- 排除时间
    created_by VARCHAR(255) NOT NULL,     -- 创建人账号
    created_at TIMESTAMP NOT NULL,        -- 创建时间
    modified_by VARCHAR(255),             -- 记录人
    modified_at TIMESTAMP NOT NULL,       -- 记录时间

    FOREIGN KEY (pid) REFERENCES patient_records(id)  -- 外键约束
);

COMMENT ON TABLE apache_ii_scores IS 'Apache II 评分表';

COMMENT ON COLUMN apache_ii_scores.id IS '自增主键ID';
COMMENT ON COLUMN apache_ii_scores.pid IS '病人ID';
COMMENT ON COLUMN apache_ii_scores.dept_id IS '所属科室ID';
COMMENT ON COLUMN apache_ii_scores.score_time IS '评分时间';
COMMENT ON COLUMN apache_ii_scores.scored_by IS '评分人账号';
COMMENT ON COLUMN apache_ii_scores.scored_by_account_name IS '评分人姓名';
COMMENT ON COLUMN apache_ii_scores.eval_start_at IS '评估开始时间';
COMMENT ON COLUMN apache_ii_scores.eval_end_at IS '评估结束时间';

COMMENT ON COLUMN apache_ii_scores.body_temperature IS '体温(°C)';
COMMENT ON COLUMN apache_ii_scores.mean_arterial_pressure IS '平均动脉压(MAP, mmHg)';
COMMENT ON COLUMN apache_ii_scores.heart_rate IS '心率(次/分钟)';
COMMENT ON COLUMN apache_ii_scores.respiratory_rate IS '呼吸频率(次/分钟)';
COMMENT ON COLUMN apache_ii_scores.fio2 IS '吸入氧浓度(0.21 ~ 1.0)';
COMMENT ON COLUMN apache_ii_scores.a_a_do2 IS 'A-aDO2(在 FiO2 < 0.5 时使用)';
COMMENT ON COLUMN apache_ii_scores.pao2 IS 'PaO2(在 FiO2 >= 0.5 时使用)';
COMMENT ON COLUMN apache_ii_scores.ph IS '动脉血 pH';
COMMENT ON COLUMN apache_ii_scores.hco3 IS '血碳酸氢根 HCO3(mmol/L, 无pH时使用)';
COMMENT ON COLUMN apache_ii_scores.sodium IS '血钠 Na+(mmol/L)';
COMMENT ON COLUMN apache_ii_scores.potassium IS '血钾 K+(mmol/L)';
COMMENT ON COLUMN apache_ii_scores.creatinine IS '血肌酐 Cr(mg/dL)';
COMMENT ON COLUMN apache_ii_scores.has_acute_renal_failure IS '是否为急性肾衰竭';
COMMENT ON COLUMN apache_ii_scores.hematocrit IS '血球压积(如: %)';
COMMENT ON COLUMN apache_ii_scores.white_blood_cell_count IS '白细胞 WBC(10^9/L)';
COMMENT ON COLUMN apache_ii_scores.glasgow_coma_scale IS 'GCS(3~15)';
COMMENT ON COLUMN apache_ii_scores.age IS '年龄';

COMMENT ON COLUMN apache_ii_scores.has_chronic_conditions IS '是否存在慢性疾病';
COMMENT ON COLUMN apache_ii_scores.chc_cardio IS '心血管系统慢性病';
COMMENT ON COLUMN apache_ii_scores.chc_resp IS '呼吸系统慢性病';
COMMENT ON COLUMN apache_ii_scores.chc_liver IS '肝脏慢性病';
COMMENT ON COLUMN apache_ii_scores.chc_kidney IS '肾脏慢性病';
COMMENT ON COLUMN apache_ii_scores.chc_immune IS '免疫功能障碍';
COMMENT ON COLUMN apache_ii_scores.non_operative_or_emergency_surgery IS '非手术或急诊手术';

COMMENT ON COLUMN apache_ii_scores.aps_score IS 'Apache II Aps评分';
COMMENT ON COLUMN apache_ii_scores.age_score IS 'Apache II 年龄评分';
COMMENT ON COLUMN apache_ii_scores.chc_score IS 'Apache II Chc评分';
COMMENT ON COLUMN apache_ii_scores.apache_ii_score IS 'Apache II 总分';

COMMENT ON COLUMN apache_ii_scores.predicted_mortality_rate IS '预测死亡率';
COMMENT ON COLUMN apache_ii_scores.coeff IS 'proto消息ApacheIIScoreMetaPB(只包含系数*_coef)实例序列化后的base64编码';
COMMENT ON COLUMN apache_ii_scores.is_operative IS '是否为手术患者';
COMMENT ON COLUMN apache_ii_scores.is_emergency_operation IS '是否为急诊手术患者';
COMMENT ON COLUMN apache_ii_scores.mortality_factor_code IS '（非）手术患者诊断主因编码';
COMMENT ON COLUMN apache_ii_scores.mortality_factor_name IS '（非）手术患者诊断主因名称';

COMMENT ON COLUMN apache_ii_scores.is_deleted IS '是否已删除';
COMMENT ON COLUMN apache_ii_scores.deleted_by IS '删除人账号';
COMMENT ON COLUMN apache_ii_scores.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN apache_ii_scores.deleted_at IS '删除时间';
COMMENT ON COLUMN apache_ii_scores.created_by IS '创建人账号';
COMMENT ON COLUMN apache_ii_scores.created_at IS '创建时间';
COMMENT ON COLUMN apache_ii_scores.modified_by IS '最后修改人账号';
COMMENT ON COLUMN apache_ii_scores.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_apache_ii_scores_pid_time
    ON apache_ii_scores (pid, score_time)
    WHERE is_deleted = FALSE;

CREATE TABLE sofa_scores (
    id BIGSERIAL PRIMARY KEY,            -- 自增主键ID
    pid BIGINT NOT NULL,                 -- 病人ID
    dept_id VARCHAR(255) NOT NULL,       -- 所属科室ID

    score_time TIMESTAMP NOT NULL,       -- 评分时间
    scored_by VARCHAR(255) NOT NULL,     -- 评分人账号
    scored_by_account_name VARCHAR(255), -- 评分人姓名
    eval_start_at TIMESTAMP,             -- 评估开始时间
    eval_end_at TIMESTAMP,               -- 评估结束时间

    -- === 呼吸参数 ===
    pao2_fio2_ratio FLOAT,               -- PaO2/FiO2 比率 (mmHg)
    respiratory_support BOOLEAN,         -- 是否有呼吸支持

    -- === 凝血参数 ===
    platelet_count FLOAT,                -- 血小板计数 (x10^3/μL)

    -- === 肝脏参数 ===
    bilirubin FLOAT,                     -- 胆红素 (umol/L)

    -- === 循环参数 ===
    circulation_mean_arterial_pressure FLOAT,  -- 平均动脉压 (mmHg)
    circulation_dopamine_dose FLOAT,           -- 多巴胺 (μg/kg/min)
    circulation_epinephrine_dose FLOAT,        -- 肾上腺素 (μg/kg/min)
    circulation_norepinephrine_dose FLOAT,     -- 去甲肾上腺素 (μg/kg/min)
    circulation_dobutamine_is_used BOOLEAN,    -- 是否使用多巴酚丁胺

    -- === 神经参数 ===
    glasgow_coma_scale INT,              -- 格拉斯哥昏迷评分 (GCS, 3-15)

    -- === 肾脏参数 ===
    renal_creatinine FLOAT,                    -- 血肌酐 (umol/L)
    renal_urine_output FLOAT,                  -- 尿量 (mL/day)

    -- === 评分结果 ===
    sofa_score INT NOT NULL,             -- SOFA 总分

    -- 软删除及修改信息
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,  -- 是否已删除
    deleted_by VARCHAR(255),               -- 疾病排除人
    deleted_by_account_name VARCHAR(255),  -- 疾病排除人姓名
    deleted_at TIMESTAMP,                  -- 排除时间
    created_by VARCHAR(255) NOT NULL,     -- 创建人账号
    created_at TIMESTAMP NOT NULL,        -- 创建时间
    modified_by VARCHAR(255),             -- 记录人
    modified_at TIMESTAMP NOT NULL,       -- 记录时间

    FOREIGN KEY (pid) REFERENCES patient_records(id)  -- 外键约束
);

COMMENT ON TABLE sofa_scores IS 'SOFA 评分表';

COMMENT ON COLUMN sofa_scores.id IS '自增主键ID';
COMMENT ON COLUMN sofa_scores.pid IS '病人ID';
COMMENT ON COLUMN sofa_scores.dept_id IS '所属科室ID';
COMMENT ON COLUMN sofa_scores.score_time IS '评分时间';
COMMENT ON COLUMN sofa_scores.scored_by IS '评分人账号';
COMMENT ON COLUMN sofa_scores.scored_by_account_name IS '评分人姓名';
COMMENT ON COLUMN sofa_scores.eval_start_at IS '评估开始时间';
COMMENT ON COLUMN sofa_scores.eval_end_at IS '评估结束时间';

COMMENT ON COLUMN sofa_scores.pao2_fio2_ratio IS 'PaO2/FiO2 比率 (mmHg)';
COMMENT ON COLUMN sofa_scores.respiratory_support IS '是否有呼吸支持';
COMMENT ON COLUMN sofa_scores.platelet_count IS '血小板计数 (x10^3/μL)';
COMMENT ON COLUMN sofa_scores.bilirubin IS '胆红素 (umol/L)';
COMMENT ON COLUMN sofa_scores.circulation_mean_arterial_pressure IS '平均动脉压 (mmHg)';
COMMENT ON COLUMN sofa_scores.circulation_dopamine_dose IS '多巴胺 (μg/kg/min)';
COMMENT ON COLUMN sofa_scores.circulation_epinephrine_dose IS '肾上腺素 (μg/kg/min)';
COMMENT ON COLUMN sofa_scores.circulation_norepinephrine_dose IS '去甲肾上腺素 (μg/kg/min)';
COMMENT ON COLUMN sofa_scores.circulation_dobutamine_is_used IS '是否使用多巴酚丁胺';
COMMENT ON COLUMN sofa_scores.glasgow_coma_scale IS '格拉斯哥昏迷评分 (GCS, 3-15)';
COMMENT ON COLUMN sofa_scores.renal_creatinine IS '血肌酐 (umol/L)';
COMMENT ON COLUMN sofa_scores.renal_urine_output IS '尿量 (mL/day)';

COMMENT ON COLUMN sofa_scores.sofa_score IS 'SOFA 总分';

COMMENT ON COLUMN sofa_scores.is_deleted IS '是否已删除';
COMMENT ON COLUMN sofa_scores.deleted_by IS '删除人账号';
COMMENT ON COLUMN sofa_scores.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN sofa_scores.deleted_at IS '删除时间';
COMMENT ON COLUMN sofa_scores.created_by IS '创建人账号';
COMMENT ON COLUMN sofa_scores.created_at IS '创建时间';
COMMENT ON COLUMN sofa_scores.modified_by IS '最后修改人账号';
COMMENT ON COLUMN sofa_scores.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_sofa_scores_pid_time
    ON sofa_scores (pid, score_time)
    WHERE is_deleted = FALSE;

CREATE TABLE vte_caprini_scores (
    id BIGSERIAL PRIMARY KEY,            -- 自增主键ID
    pid BIGINT NOT NULL,                 -- 病人ID
    dept_id VARCHAR(255) NOT NULL,       -- 所属科室ID

    score_time TIMESTAMP NOT NULL,       -- 评分时间
    scored_by VARCHAR(255) NOT NULL,     -- 评分人账号
    scored_by_account_name VARCHAR(255), -- 评分人姓名
    eval_start_at TIMESTAMP,             -- 评估开始时间
    eval_end_at TIMESTAMP,               -- 评估结束时间

    -- 1 分风险因素
    age_41_60 BOOLEAN,                          -- 年龄为41-60岁
    lower_limb_edema BOOLEAN,                  -- 下肢水肿
    varicose_veins BOOLEAN,                    -- 静脉曲张
    obesity_bmi_25 BOOLEAN,                    -- 肥胖(BMI≥25)
    planned_minor_surgery BOOLEAN,             -- 计划小手术
    inflammatory_history BOOLEAN,              -- 炎症性病史
    oral_contraceptives_hrt BOOLEAN,           -- 口服避孕药或激素替代治疗
    pregnancy_or_postpartum BOOLEAN,           -- 妊娠期或产后(1个月内)
    acute_myocardial_infarction BOOLEAN,       -- 急性心肌梗塞
    congestive_heart_failure BOOLEAN,          -- 充血性心力衰竭
    bedridden_medical_patient BOOLEAN,         -- 卧床的内科患者
    pulmonary_dysfunction BOOLEAN,             -- 肺功能异常
    major_surgery_history BOOLEAN,             -- 大手术史(1个月内)
    sepsis BOOLEAN,                            -- 脓毒症
    severe_lung_disease_pneumonia BOOLEAN,     -- 严重肺部疾病，含肺炎(1个月内)
    unexplained_or_recurrent_miscarriage BOOLEAN, -- 不明原因或习惯性流产
    other_risk_factors TEXT,                   -- 其他风险因素（字符串输入）

    -- 2 分风险因素
    age_61_74 BOOLEAN,                         -- 年龄为61-74岁
    central_venous_catheter BOOLEAN,           -- 中心静脉置管
    arthroscopic_surgery BOOLEAN,              -- 关节镜手术
    major_surgery_over_45min BOOLEAN,          -- 大手术(＞45分钟)
    bed_rest_over_72h BOOLEAN,                 -- 患者需要卧床(>72小时)
    malignant_tumor BOOLEAN,                   -- 恶性肿瘤(既往或现患)
    laparoscopic_surgery_over_45min BOOLEAN,   -- 腹腔镜手术(＞45分钟)
    cast_immobilization BOOLEAN,               -- 石膏固定(1个月内)

    -- 3 分风险因素
        age_75_or_older BOOLEAN,                       -- 年龄≥75岁
    thrombosis_family_history BOOLEAN,             -- 血栓家族病史
    dvt_pe_history BOOLEAN,                        -- DVT/PE患者
    prothrombin_20210a_positive BOOLEAN,           -- 凝血酶原20210A阳性
    factor_v_leiden_positive BOOLEAN,              -- 因子V Leiden阳性
    lupus_anticoagulant_positive BOOLEAN,          -- 狼疮抗凝物阳性
    elevated_homocysteine BOOLEAN,                 -- 血清同型半胱氨酸升高
    antiphospholipid_antibodies BOOLEAN,           -- 抗心磷脂抗体升高
    heparin_induced_thrombocytopenia BOOLEAN,      -- 肝素引起的血小板减少(HIT)
    other_congenital_or_acquired_thrombosis TEXT,  -- 其他先天性或后天血栓疾病（字符串）

    -- 5 分风险因素
    stroke_within_month BOOLEAN,                    -- 脑卒中(1个月内)
    multiple_trauma_within_month BOOLEAN,           -- 多发性创伤(1个月内)
    acute_spinal_cord_injury BOOLEAN,               -- 急性骨髓损伤(瘫痪)(1个月内)
    hip_pelvis_lower_limb_fracture BOOLEAN,         -- 髋关节、骨盆或下肢骨折
    elective_lower_limb_joint_replacement BOOLEAN,  -- 选择性下肢关节置换术

    -- 总分
    total_score INT NOT NULL,  -- Caprini 评分总分

    -- 出血风险 - 常规危险因素
    active_bleeding BOOLEAN,                                -- 活动性出血
    bleeding_event_within_3months BOOLEAN,                  -- 3个月内有出血事件
    severe_renal_or_liver_failure BOOLEAN,                  -- 严重肾功能或肝功能衰竭
    platelet_count_below_50 BOOLEAN,                        -- 血小板计数<50*10^9/L
    uncontrolled_hypertension BOOLEAN,                      -- 未控制的高血压
    lumbar_epidural_or_spinal_anesthesia BOOLEAN,           -- 腰穿、硬膜外或椎管内麻醉术前4h-术后12h
    anticoagulant_antiplatelet_or_thrombolytic BOOLEAN,     -- 同时使用抗凝药、抗血小板治疗或溶栓药物
    coagulation_disorder BOOLEAN,                           -- 凝血功能障碍
    active_gi_ulcer BOOLEAN,                                -- 活动性消化道溃疡
    known_untreated_bleeding_disorder BOOLEAN,              -- 已知、未治疗的出血疾病

    -- 出血风险 - 手术相关危险因素
    abdominal_surgery_malignant_male_anemia_complex BOOLEAN,  -- 腹部手术：恶性肿瘤男性患者，术前贫血，复杂手术
    pancreaticoduodenectomy_sepsis_fistula_bleeding BOOLEAN,  -- 胰十二指肠切除术：败血症、胰瘘、手术部位出血
    liver_resection_pri_liver_cancer_low_hemoglobin_platelets BOOLEAN,  -- 肝切除术：原发性肝癌、术前血红蛋白和血小板计数低
    cardiac_surgery_long_cp_time BOOLEAN,                     -- 心脏手术：体外循环时间较长
    thoracic_surgery_pneumonectomy_or_extended BOOLEAN,       -- 胸部手术：全肺切除术或扩张切除术

    -- 出血风险 - 高风险手术
    craniotomy BOOLEAN,                   -- 开颅手术
    spinal_surgery BOOLEAN,               -- 脊柱手术
    spinal_trauma BOOLEAN,                -- 脊柱创伤
    free_flap_reconstruction BOOLEAN,     -- 游离皮瓣重建手术

    -- 出血风险 - 预防评估结果
    prevention_anticoagulant_only_assess BOOLEAN,       -- 评估：抗凝药物预防
    prevention_physical_only_assess BOOLEAN,            -- 评估：物理预防
    prevention_anticoagulant_physical_assess BOOLEAN,   -- 评估：抗凝药物+物理预防
    prevention_unavailable_assess BOOLEAN,              -- 评估：预防措施不可用

    -- 出血风险 - 预防执行结果
    prevention_anticoagulant_only_exec BOOLEAN,         -- 执行：抗凝药物预防
    prevention_physical_only_exec BOOLEAN,              -- 执行：物理预防
    prevention_anticoagulant_physical_exec BOOLEAN,     -- 执行：抗凝药物+物理预防
    prevention_unavailable_exec BOOLEAN,                -- 执行：预防措施不可用

    -- 护理措施 - 基础措施
    elevate_limbs BOOLEAN,                    -- 抬高患者肢体
    ankle_exercise BOOLEAN,                   -- 踝关节活动
    quadriceps_contraction BOOLEAN,           -- 股四头肌收缩
    deep_breathing_or_balloon BOOLEAN,        -- 深呼吸或吹气球
    quit_smoking_alcohol BOOLEAN,             -- 戒烟戒酒
    drink_more_water BOOLEAN,                 -- 多饮水
    maintain_bowel_regular BOOLEAN,           -- 保持大便通畅
    turn_every_2h_or_leg_movement BOOLEAN,    -- 每2小时翻身或主动屈伸下肢
    get_out_of_bed BOOLEAN,                   -- 下床活动
    other_basic_measures TEXT,                -- 其他护理措施（字符串）

    -- 护理措施 - 机械措施
    intermittent_pneumatic_compression BOOLEAN,     -- 使用间歇性气压装置
    graded_compression_stockings BOOLEAN,           -- 分级加压弹力袜
    foot_vein_pump BOOLEAN,                          -- 足底静脉泵

    -- 护理措施 - 药物措施
    low_molecular_heparin_injection BOOLEAN,         -- 低分子肝素注射
    rivaroxaban BOOLEAN,                             -- 利伐沙班
    warfarin BOOLEAN,                                -- 华法林
    other_pharmacological_measures TEXT,             -- 其他药物措施（字符串）

    -- 软删除及修改信息
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,  -- 是否已删除
    deleted_by VARCHAR(255),               -- 疾病排除人
    deleted_by_account_name VARCHAR(255),  -- 疾病排除人姓名
    deleted_at TIMESTAMP,                  -- 排除时间
    modified_by VARCHAR(255),             -- 记录人
    modified_at TIMESTAMP NOT NULL,       -- 记录时间

    FOREIGN KEY (pid) REFERENCES patient_records(id)  -- 外键约束
);
COMMENT ON TABLE vte_caprini_scores IS 'VTE Caprini 评分表';
COMMENT ON COLUMN vte_caprini_scores.id IS '自增主键ID';
COMMENT ON COLUMN vte_caprini_scores.pid IS '病人ID';
COMMENT ON COLUMN vte_caprini_scores.dept_id IS '所属科室ID';

-- Basic Information
COMMENT ON COLUMN vte_caprini_scores.score_time IS '评分时间';
COMMENT ON COLUMN vte_caprini_scores.scored_by IS '评分人账号';
COMMENT ON COLUMN vte_caprini_scores.scored_by_account_name IS '评分人姓名';
COMMENT ON COLUMN vte_caprini_scores.eval_start_at IS '评估开始时间';
COMMENT ON COLUMN vte_caprini_scores.eval_end_at IS '评估结束时间';

-- 1 Point Risk Factors
COMMENT ON COLUMN vte_caprini_scores.age_41_60 IS '年龄为41-60岁 (1分)';
COMMENT ON COLUMN vte_caprini_scores.lower_limb_edema IS '下肢水肿 (1分)';
COMMENT ON COLUMN vte_caprini_scores.varicose_veins IS '静脉曲张 (1分)';
COMMENT ON COLUMN vte_caprini_scores.obesity_bmi_25 IS '肥胖(BMI≥25) (1分)';
COMMENT ON COLUMN vte_caprini_scores.planned_minor_surgery IS '计划小手术 (1分)';
COMMENT ON COLUMN vte_caprini_scores.inflammatory_history IS '炎症性病史 (1分)';
COMMENT ON COLUMN vte_caprini_scores.oral_contraceptives_hrt IS '口服避孕药或激素替代治疗 (1分)';
COMMENT ON COLUMN vte_caprini_scores.pregnancy_or_postpartum IS '妊娠期或产后(1个月内) (1分)';
COMMENT ON COLUMN vte_caprini_scores.acute_myocardial_infarction IS '急性心肌梗塞 (1分)';
COMMENT ON COLUMN vte_caprini_scores.congestive_heart_failure IS '充血性心力衰竭 (1分)';
COMMENT ON COLUMN vte_caprini_scores.bedridden_medical_patient IS '卧床的内科患者 (1分)';
COMMENT ON COLUMN vte_caprini_scores.pulmonary_dysfunction IS '肺功能异常 (1分)';
COMMENT ON COLUMN vte_caprini_scores.major_surgery_history IS '大手术史(1个月内) (1分)';
COMMENT ON COLUMN vte_caprini_scores.sepsis IS '脓毒症 (1分)';
COMMENT ON COLUMN vte_caprini_scores.severe_lung_disease_pneumonia IS '严重肺部疾病，含肺炎(1个月内) (1分)';
COMMENT ON COLUMN vte_caprini_scores.unexplained_or_recurrent_miscarriage IS '不明原因或习惯性流产 (1分)';
COMMENT ON COLUMN vte_caprini_scores.other_risk_factors IS '其他风险因素（字符串输入） (1分)';

-- 2 Point Risk Factors
COMMENT ON COLUMN vte_caprini_scores.age_61_74 IS '年龄为61-74岁 (2分)';
COMMENT ON COLUMN vte_caprini_scores.central_venous_catheter IS '中心静脉置管 (2分)';
COMMENT ON COLUMN vte_caprini_scores.arthroscopic_surgery IS '关节镜手术 (2分)';
COMMENT ON COLUMN vte_caprini_scores.major_surgery_over_45min IS '大手术(＞45分钟) (2分)';
COMMENT ON COLUMN vte_caprini_scores.bed_rest_over_72h IS '患者需要卧床(>72小时) (2分)';
COMMENT ON COLUMN vte_caprini_scores.malignant_tumor IS '恶性肿瘤(既往或现患) (2分)';
COMMENT ON COLUMN vte_caprini_scores.laparoscopic_surgery_over_45min IS '腹腔镜手术(＞45分钟) (2分)';
COMMENT ON COLUMN vte_caprini_scores.cast_immobilization IS '石膏固定(1个月内) (2分)';

-- 3 Point Risk Factors
COMMENT ON COLUMN vte_caprini_scores.age_75_or_older IS '年龄≥75岁 (3分)';
COMMENT ON COLUMN vte_caprini_scores.thrombosis_family_history IS '血栓家族病史 (3分)';
COMMENT ON COLUMN vte_caprini_scores.dvt_pe_history IS 'DVT/PE患者 (3分)';
COMMENT ON COLUMN vte_caprini_scores.prothrombin_20210a_positive IS '凝血酶原20210A阳性 (3分)';
COMMENT ON COLUMN vte_caprini_scores.factor_v_leiden_positive IS '因子V Leiden阳性 (3分)';
COMMENT ON COLUMN vte_caprini_scores.lupus_anticoagulant_positive IS '狼疮抗凝物阳性 (3分)';
COMMENT ON COLUMN vte_caprini_scores.elevated_homocysteine IS '血清同型半胱氨酸升高 (3分)';
COMMENT ON COLUMN vte_caprini_scores.antiphospholipid_antibodies IS '抗心磷脂抗体升高 (3分)';
COMMENT ON COLUMN vte_caprini_scores.heparin_induced_thrombocytopenia IS '肝素引起的血小板减少(HIT) (3分)';
COMMENT ON COLUMN vte_caprini_scores.other_congenital_or_acquired_thrombosis IS '其他先天性或后天血栓疾病（字符串） (3分)';

-- 5 Point Risk Factors
COMMENT ON COLUMN vte_caprini_scores.stroke_within_month IS '脑卒中(1个月内) (5分)';
COMMENT ON COLUMN vte_caprini_scores.multiple_trauma_within_month IS '多发性创伤(1个月内) (5分)';
COMMENT ON COLUMN vte_caprini_scores.acute_spinal_cord_injury IS '急性骨髓损伤(瘫痪)(1个月内) (5分)';
COMMENT ON COLUMN vte_caprini_scores.hip_pelvis_lower_limb_fracture IS '髋关节、骨盆或下肢骨折 (5分)';
COMMENT ON COLUMN vte_caprini_scores.elective_lower_limb_joint_replacement IS '选择性下肢关节置换术 (5分)';

-- Total Score
COMMENT ON COLUMN vte_caprini_scores.total_score IS 'Caprini 评分总分';

-- Bleeding Risk - General Risk Factors
COMMENT ON COLUMN vte_caprini_scores.active_bleeding IS '活动性出血 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.bleeding_event_within_3months IS '3个月内有出血事件 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.severe_renal_or_liver_failure IS '严重肾功能或肝功能衰竭 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.platelet_count_below_50 IS '血小板计数<50*10^9/L (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.uncontrolled_hypertension IS '未控制的高血压 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.lumbar_epidural_or_spinal_anesthesia IS '腰穿、硬膜外或椎管内麻醉术前4h-术后12h (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.anticoagulant_antiplatelet_or_thrombolytic IS '同时使用抗凝药、抗血小板治疗或溶栓药物 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.coagulation_disorder IS '凝血功能障碍 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.active_gi_ulcer IS '活动性消化道溃疡 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.known_untreated_bleeding_disorder IS '已知、未治疗的出血疾病 (出血风险)';

-- Bleeding Risk - Surgery-Related Risk Factors
COMMENT ON COLUMN vte_caprini_scores.abdominal_surgery_malignant_male_anemia_complex IS '腹部手术：恶性肿瘤男性患者，术前贫血，复杂手术 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.pancreaticoduodenectomy_sepsis_fistula_bleeding IS '胰十二指肠切除术：败血症、胰瘘、手术部位出血 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.liver_resection_pri_liver_cancer_low_hemoglobin_platelets IS '肝切除术：原发性肝癌、术前血红蛋白和血小板计数低 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.cardiac_surgery_long_cp_time IS '心脏手术：体外循环时间较长 (出血风险)';
COMMENT ON COLUMN vte_caprini_scores.thoracic_surgery_pneumonectomy_or_extended IS '胸部手术：全肺切除术或扩张切除术 (出血风险)';

-- Bleeding Risk - High-Risk Surgeries
COMMENT ON COLUMN vte_caprini_scores.craniotomy IS '开颅手术 (高风险出血手术)';
COMMENT ON COLUMN vte_caprini_scores.spinal_surgery IS '脊柱手术 (高风险出血手术)';
COMMENT ON COLUMN vte_caprini_scores.spinal_trauma IS '脊柱创伤 (高风险出血手术)';
COMMENT ON COLUMN vte_caprini_scores.free_flap_reconstruction IS '游离皮瓣重建手术 (高风险出血手术)';

-- Bleeding Risk - Prevention Assessment Results
COMMENT ON COLUMN vte_caprini_scores.prevention_anticoagulant_only_assess IS '评估：抗凝药物预防';
COMMENT ON COLUMN vte_caprini_scores.prevention_physical_only_assess IS '评估：物理预防';
COMMENT ON COLUMN vte_caprini_scores.prevention_anticoagulant_physical_assess IS '评估：抗凝药物+物理预防';
COMMENT ON COLUMN vte_caprini_scores.prevention_unavailable_assess IS '评估：预防措施不可用';

-- Bleeding Risk - Prevention Execution Results
COMMENT ON COLUMN vte_caprini_scores.prevention_anticoagulant_only_exec IS '执行：抗凝药物预防';
COMMENT ON COLUMN vte_caprini_scores.prevention_physical_only_exec IS '执行：物理预防';
COMMENT ON COLUMN vte_caprini_scores.prevention_anticoagulant_physical_exec IS '执行：抗凝药物+物理预防';
COMMENT ON COLUMN vte_caprini_scores.prevention_unavailable_exec IS '执行：预防措施不可用';

-- Nursing Measures - Basic Measures
COMMENT ON COLUMN vte_caprini_scores.elevate_limbs IS '护理措施：抬高患者肢体';
COMMENT ON COLUMN vte_caprini_scores.ankle_exercise IS '护理措施：踝关节活动';
COMMENT ON COLUMN vte_caprini_scores.quadriceps_contraction IS '护理措施：股四头肌收缩';
COMMENT ON COLUMN vte_caprini_scores.deep_breathing_or_balloon IS '护理措施：深呼吸或吹气球';
COMMENT ON COLUMN vte_caprini_scores.quit_smoking_alcohol IS '护理措施：戒烟戒酒';
COMMENT ON COLUMN vte_caprini_scores.drink_more_water IS '护理措施：多饮水';
COMMENT ON COLUMN vte_caprini_scores.maintain_bowel_regular IS '护理措施：保持大便通畅';
COMMENT ON COLUMN vte_caprini_scores.turn_every_2h_or_leg_movement IS '护理措施：每2小时翻身或主动屈伸下肢';
COMMENT ON COLUMN vte_caprini_scores.get_out_of_bed IS '护理措施：下床活动';
COMMENT ON COLUMN vte_caprini_scores.other_basic_measures IS '护理措施：其他基础措施（字符串）';

-- Nursing Measures - Mechanical Measures
COMMENT ON COLUMN vte_caprini_scores.intermittent_pneumatic_compression IS '护理措施：使用间歇性气压装置';
COMMENT ON COLUMN vte_caprini_scores.graded_compression_stockings IS '护理措施：分级加压弹力袜';
COMMENT ON COLUMN vte_caprini_scores.foot_vein_pump IS '护理措施：足底静脉泵';

-- Nursing Measures - Pharmacological Measures
COMMENT ON COLUMN vte_caprini_scores.low_molecular_heparin_injection IS '护理措施：低分子肝素注射';
COMMENT ON COLUMN vte_caprini_scores.rivaroxaban IS '护理措施：利伐沙班';
COMMENT ON COLUMN vte_caprini_scores.warfarin IS '护理措施：华法林';
COMMENT ON COLUMN vte_caprini_scores.other_pharmacological_measures IS '护理措施：其他药物措施（字符串）';

-- Soft Delete and Modification Information
COMMENT ON COLUMN vte_caprini_scores.is_deleted IS '是否已删除';
COMMENT ON COLUMN vte_caprini_scores.deleted_by IS '疾病排除人';
COMMENT ON COLUMN vte_caprini_scores.deleted_by_account_name IS '疾病排除人姓名';
COMMENT ON COLUMN vte_caprini_scores.deleted_at IS '排除时间';
COMMENT ON COLUMN vte_caprini_scores.modified_by IS '记录人';
COMMENT ON COLUMN vte_caprini_scores.modified_at IS '记录时间';

CREATE UNIQUE INDEX idx_vte_caprini_scores_pid_time
    ON vte_caprini_scores (pid, score_time)
    WHERE is_deleted = FALSE;

CREATE TABLE vte_padua_scores (
    id BIGSERIAL PRIMARY KEY,            -- 自增主键ID
    pid BIGINT NOT NULL,                 -- 病人ID
    dept_id VARCHAR(255) NOT NULL,       -- 所属科室ID

    score_time TIMESTAMP NOT NULL,       -- 评分时间
    scored_by VARCHAR(255) NOT NULL,     -- 评分人账号
    scored_by_account_name VARCHAR(255), -- 评分人姓名
    eval_start_at TIMESTAMP,             -- 评估开始时间
    eval_end_at TIMESTAMP,               -- 评估结束时间

    -- 风险因素1分项（1分）
    age_70_or_older BOOLEAN,                              -- 年龄≥70岁
    obesity_bmi_30 BOOLEAN,                               -- 肥胖(体重指数≥30kg/m²)
    acute_infection_rheumatic BOOLEAN,                    -- 急性感染和(或)风湿性疾病
    acute_mi_or_stroke BOOLEAN,                           -- 急性心肌梗死和(或)缺血性脑卒中
    hormone_therapy BOOLEAN,                              -- 正在进行激素治疗
    heart_or_respiratory_failure BOOLEAN,                 -- 心脏和(或)呼吸衰竭

    -- 风险因素2分项（2分）
    recent_trauma_or_surgery BOOLEAN,                     -- 近期(≤1个月)创伤或外科手术

    -- 风险因素3分项（3分）
    thrombophilic_condition BOOLEAN,                      -- 有血栓形成倾向（易栓症筛查异常）
    active_malignancy BOOLEAN,                            -- 活动性恶性肿瘤
    prior_vte BOOLEAN,                                    -- 既往静脉血栓栓塞症
    immobilization BOOLEAN,                               -- 制动卧床≥3天

    total_score INT NOT NULL,                             -- Padua总分

    -- 出血高危（满足一项即为高危）
    active_gi_ulcer BOOLEAN,                              -- 活动性胃肠道溃疡
    bleeding_event_within_3months BOOLEAN,                -- 入院前3个月内有出血事件
    platelet_count_below_50 BOOLEAN,                      -- 血小板计数<50*10^9/L

    -- 出血高危（满足三项及以上为高危）
    age_85_or_older BOOLEAN,                              -- 年龄≥85岁
    liver_dysfunction_inr_15 BOOLEAN,                     -- 肝功能不全(INR > 1.5)
    severe_renal_failure BOOLEAN,                         -- 严重肾功能不全
    icu_or_ccu_admission BOOLEAN,                         -- 入住ICU或CCU
    central_venous_catheter BOOLEAN,                      -- 中心静脉置管
    has_active_malignancy BOOLEAN,                        -- 现患恶性肿瘤
    rheumatic_disease BOOLEAN,                            -- 风湿性疾病
    male_gender BOOLEAN,                                  -- 男性

    -- 出血风险 - 预防评估结果
    prevention_anticoagulant_only_assess BOOLEAN,       -- 评估：抗凝药物预防
    prevention_physical_only_assess BOOLEAN,            -- 评估：物理预防
    prevention_anticoagulant_physical_assess BOOLEAN,   -- 评估：抗凝药物+物理预防
    prevention_unavailable_assess BOOLEAN,              -- 评估：预防措施不可用

    -- 出血风险 - 预防执行结果
    prevention_anticoagulant_only_exec BOOLEAN,         -- 执行：抗凝药物预防
    prevention_physical_only_exec BOOLEAN,              -- 执行：物理预防
    prevention_anticoagulant_physical_exec BOOLEAN,     -- 执行：抗凝药物+物理预防
    prevention_unavailable_exec BOOLEAN,                -- 执行：预防措施不可用

    -- 护理措施
    elevate_limbs BOOLEAN,                                -- 抬高患者肢体
    ankle_exercise BOOLEAN,                               -- 踝关节活动
    quadriceps_contraction BOOLEAN,                       -- 股四头肌收缩
    deep_breathing_or_balloon BOOLEAN,                    -- 做深呼吸或吹气球
    quit_smoking_alcohol BOOLEAN,                         -- 戒烟戒酒
    drink_more_water BOOLEAN,                             -- 多饮水
    maintain_bowel_regular BOOLEAN,                       -- 保持大便通畅
    turn_every_2h_or_leg_movement BOOLEAN,                -- 每2小时翻身或主动屈伸下肢
    get_out_of_bed BOOLEAN,                               -- 下床活动
    other_basic_measures TEXT,                            -- 其他基础护理措施

    -- 物理设备
    intermittent_pneumatic_compression BOOLEAN,           -- 间歇性气压装置
    graded_compression_stockings BOOLEAN,                 -- 分级加压弹力袜
    foot_vein_pump BOOLEAN,                               -- 足底静脉泵

    -- 药物措施
    low_molecular_heparin_injection BOOLEAN,              -- 低分子肝素注射
    rivaroxaban BOOLEAN,                                  -- 利伐沙班
    warfarin BOOLEAN,                                     -- 华法林
    other_pharmacological_measures TEXT,                  -- 其他药物措施

    -- 软删除及修改信息
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,            -- 是否已删除
    deleted_by VARCHAR(255),                              -- 删除人账号
    deleted_by_account_name VARCHAR(255),                 -- 删除人姓名
    deleted_at TIMESTAMP,                                 -- 删除时间
    modified_by VARCHAR(255),                             -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL,                       -- 最后修改时间

    FOREIGN KEY (pid) REFERENCES patient_records(id)
);

COMMENT ON TABLE vte_padua_scores IS 'Padua VTE评分表';

-- Basic Information
COMMENT ON COLUMN vte_padua_scores.id IS '自增主键ID';
COMMENT ON COLUMN vte_padua_scores.pid IS '病人ID';
COMMENT ON COLUMN vte_padua_scores.dept_id IS '所属科室ID';
COMMENT ON COLUMN vte_padua_scores.score_time IS '评分时间';
COMMENT ON COLUMN vte_padua_scores.scored_by IS '评分人账号';
COMMENT ON COLUMN vte_padua_scores.scored_by_account_name IS '评分人姓名';
COMMENT ON COLUMN vte_padua_scores.eval_start_at IS '评估开始时间';
COMMENT ON COLUMN vte_padua_scores.eval_end_at IS '评估结束时间';

-- VTE Risk Factors (1 Point)
COMMENT ON COLUMN vte_padua_scores.age_70_or_older IS '年龄≥70岁 (1分)';
COMMENT ON COLUMN vte_padua_scores.obesity_bmi_30 IS '肥胖(体重指数≥30kg/m²) (1分)';
COMMENT ON COLUMN vte_padua_scores.acute_infection_rheumatic IS '急性感染和(或)风湿性疾病 (1分)';
COMMENT ON COLUMN vte_padua_scores.acute_mi_or_stroke IS '急性心肌梗死和(或)缺血性脑卒中 (1分)';
COMMENT ON COLUMN vte_padua_scores.hormone_therapy IS '正在进行激素治疗 (1分)';
COMMENT ON COLUMN vte_padua_scores.heart_or_respiratory_failure IS '心脏和(或)呼吸衰竭 (1分)';

-- VTE Risk Factors (2 Points)
COMMENT ON COLUMN vte_padua_scores.recent_trauma_or_surgery IS '近期(≤1个月)创伤或外科手术 (2分)';

-- VTE Risk Factors (3 Points)
COMMENT ON COLUMN vte_padua_scores.thrombophilic_condition IS '有血栓形成倾向（易栓症筛查异常） (3分)';
COMMENT ON COLUMN vte_padua_scores.active_malignancy IS '活动性恶性肿瘤 (3分)';
COMMENT ON COLUMN vte_padua_scores.prior_vte IS '既往静脉血栓栓塞症 (3分)';
COMMENT ON COLUMN vte_padua_scores.immobilization IS '制动卧床≥3天 (3分)';

-- Total Score
COMMENT ON COLUMN vte_padua_scores.total_score IS 'Padua总分';

-- High Bleeding Risk (Single Factor)
COMMENT ON COLUMN vte_padua_scores.active_gi_ulcer IS '活动性胃肠道溃疡 (出血高危: 满足一项)';
COMMENT ON COLUMN vte_padua_scores.bleeding_event_within_3months IS '入院前3个月内有出血事件 (出血高危: 满足一项)';
COMMENT ON COLUMN vte_padua_scores.platelet_count_below_50 IS '血小板计数<50*10^9/L (出血高危: 满足一项)';

-- High Bleeding Risk (≥3 Factors Required)
COMMENT ON COLUMN vte_padua_scores.age_85_or_older IS '年龄≥85岁 (出血高危: 3项及以上)';
COMMENT ON COLUMN vte_padua_scores.liver_dysfunction_inr_15 IS '肝功能不全(INR > 1.5) (出血高危: 3项及以上)';
COMMENT ON COLUMN vte_padua_scores.severe_renal_failure IS '严重肾功能不全 (出血高危: 3项及以上)';
COMMENT ON COLUMN vte_padua_scores.icu_or_ccu_admission IS '入住ICU或CCU (出血高危: 3项及以上)';
COMMENT ON COLUMN vte_padua_scores.central_venous_catheter IS '中心静脉置管 (出血高危: 3项及以上)';
COMMENT ON COLUMN vte_padua_scores.has_active_malignancy IS '现患恶性肿瘤 (出血高危: 3项及以上)'; -- This line assumes the duplicate column exists. You MUST fix the table DDL first.
COMMENT ON COLUMN vte_padua_scores.rheumatic_disease IS '风湿性疾病 (出血高危: 3项及以上)';
COMMENT ON COLUMN vte_padua_scores.male_gender IS '男性 (出血高危: 3项及以上)';

-- Bleeding Risk - Prevention Assessment Results
COMMENT ON COLUMN vte_padua_scores.prevention_anticoagulant_only_assess IS '评估：抗凝药物预防';
COMMENT ON COLUMN vte_padua_scores.prevention_physical_only_assess IS '评估：物理预防';
COMMENT ON COLUMN vte_padua_scores.prevention_anticoagulant_physical_assess IS '评估：抗凝药物+物理预防';
COMMENT ON COLUMN vte_padua_scores.prevention_unavailable_assess IS '评估：预防措施不可用';

-- Bleeding Risk - Prevention Execution Results
COMMENT ON COLUMN vte_padua_scores.prevention_anticoagulant_only_exec IS '执行：抗凝药物预防';
COMMENT ON COLUMN vte_padua_scores.prevention_physical_only_exec IS '执行：物理预防';
COMMENT ON COLUMN vte_padua_scores.prevention_anticoagulant_physical_exec IS '执行：抗凝药物+物理预防';
COMMENT ON COLUMN vte_padua_scores.prevention_unavailable_exec IS '执行：预防措施不可用';

-- Nursing Measures (Basic)
COMMENT ON COLUMN vte_padua_scores.elevate_limbs IS '护理措施：抬高患者肢体';
COMMENT ON COLUMN vte_padua_scores.ankle_exercise IS '护理措施：踝关节活动';
COMMENT ON COLUMN vte_padua_scores.quadriceps_contraction IS '护理措施：股四头肌收缩';
COMMENT ON COLUMN vte_padua_scores.deep_breathing_or_balloon IS '护理措施：做深呼吸或吹气球';
COMMENT ON COLUMN vte_padua_scores.quit_smoking_alcohol IS '护理措施：戒烟戒酒';
COMMENT ON COLUMN vte_padua_scores.drink_more_water IS '护理措施：多饮水';
COMMENT ON COLUMN vte_padua_scores.maintain_bowel_regular IS '护理措施：保持大便通畅';
COMMENT ON COLUMN vte_padua_scores.turn_every_2h_or_leg_movement IS '护理措施：每2小时翻身或主动屈伸下肢';
COMMENT ON COLUMN vte_padua_scores.get_out_of_bed IS '护理措施：下床活动';
COMMENT ON COLUMN vte_padua_scores.other_basic_measures IS '护理措施：其他基础护理措施';

-- Nursing Measures (Physical Devices)
COMMENT ON COLUMN vte_padua_scores.intermittent_pneumatic_compression IS '物理设备：间歇性气压装置';
COMMENT ON COLUMN vte_padua_scores.graded_compression_stockings IS '物理设备：分级加压弹力袜';
COMMENT ON COLUMN vte_padua_scores.foot_vein_pump IS '物理设备：足底静脉泵';

-- Nursing Measures (Pharmacological)
COMMENT ON COLUMN vte_padua_scores.low_molecular_heparin_injection IS '药物措施：低分子肝素注射';
COMMENT ON COLUMN vte_padua_scores.rivaroxaban IS '药物措施：利伐沙班';
COMMENT ON COLUMN vte_padua_scores.warfarin IS '药物措施：华法林';
COMMENT ON COLUMN vte_padua_scores.other_pharmacological_measures IS '药物措施：其他药物措施';

-- Soft Delete and Modification Information
COMMENT ON COLUMN vte_padua_scores.is_deleted IS '是否已删除';
COMMENT ON COLUMN vte_padua_scores.deleted_by IS '删除人账号';
COMMENT ON COLUMN vte_padua_scores.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN vte_padua_scores.deleted_at IS '删除时间';
COMMENT ON COLUMN vte_padua_scores.modified_by IS '最后修改人账号';
COMMENT ON COLUMN vte_padua_scores.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_vte_padua_scores_pid_time
    ON vte_padua_scores (pid, score_time)
    WHERE is_deleted = FALSE;

CREATE TABLE padua_risk_scores (
    id BIGSERIAL PRIMARY KEY,        -- 自增主键ID

    pid BIGINT NOT NULL,             -- 病人ID
    dept_id VARCHAR(255) NOT NULL,   -- 科室ID

    score_time TIMESTAMP NOT NULL,   -- 评估时间
    scored_by VARCHAR(255) NOT NULL, -- 评估人账号
    scored_by_account_name VARCHAR(255), -- 评估人姓名

    -- ============= PaduaRiskFactors展开 =============
    -- 风险因素(1分项) => risk_factor_one_point
    one_age_70_or_older BOOLEAN NOT NULL,       -- 年龄≥70岁
    one_obesity_bmi_30 BOOLEAN NOT NULL,        -- 肥胖(BMI≥30)
    one_acute_infection_rheumatic BOOLEAN NOT NULL, -- 急性感染/风湿性疾病
    one_acute_mi_or_stroke BOOLEAN NOT NULL,    -- 急性心肌梗死/缺血性卒中
    one_hormone_therapy BOOLEAN NOT NULL,       -- 激素治疗
    one_heart_or_respiratory_failure BOOLEAN NOT NULL, -- 心/呼吸衰竭

    -- 风险因素(2分项) => risk_factor_two_points
    two_recent_trauma_or_surgery BOOLEAN NOT NULL,  -- 近期(≤1个月)创伤或手术

    -- 风险因素(3分项) => risk_factor_three_points
    three_thrombophilic_condition BOOLEAN NOT NULL, -- 血栓倾向(易栓症筛查)
    three_active_malignancy BOOLEAN NOT NULL,       -- 活动性恶性肿瘤
    three_prior_vte BOOLEAN NOT NULL,              -- 既往VTE
    three_immobilization BOOLEAN NOT NULL,         -- 制动(卧床≥3天)

    -- 分数与分级
    total_score INT NOT NULL,    -- 总分(1×(1分项) + 2×(2分项) + 3×(3分项))
    risk_level INT NOT NULL,     -- 风险分级(0=LOW,1=HIGH)

    -- 逻辑删除 & 审计字段
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by VARCHAR(255),
    deleted_by_account_name VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE padua_risk_scores IS 'Padua VTE风险评分表(内科)';
COMMENT ON COLUMN padua_risk_scores.id IS '自增主键ID';
COMMENT ON COLUMN padua_risk_scores.pid IS '病人ID';
COMMENT ON COLUMN padua_risk_scores.dept_id IS '科室ID';
COMMENT ON COLUMN padua_risk_scores.score_time IS '评估时间';
COMMENT ON COLUMN padua_risk_scores.scored_by IS '评估人账号';
COMMENT ON COLUMN padua_risk_scores.scored_by_account_name IS '评估人姓名';

COMMENT ON COLUMN padua_risk_scores.one_age_70_or_older IS '≥70岁(1分)';
COMMENT ON COLUMN padua_risk_scores.one_obesity_bmi_30 IS '肥胖BMI≥30(1分)';
COMMENT ON COLUMN padua_risk_scores.one_acute_infection_rheumatic IS '急性感染/风湿(1分)';
COMMENT ON COLUMN padua_risk_scores.one_acute_mi_or_stroke IS '急性心梗/缺血性卒中(1分)';
COMMENT ON COLUMN padua_risk_scores.one_hormone_therapy IS '激素治疗(1分)';
COMMENT ON COLUMN padua_risk_scores.one_heart_or_respiratory_failure IS '心/呼吸衰竭(1分)';

COMMENT ON COLUMN padua_risk_scores.two_recent_trauma_or_surgery IS '近期创伤/手术(2分)';

COMMENT ON COLUMN padua_risk_scores.three_thrombophilic_condition IS '血栓倾向(3分)';
COMMENT ON COLUMN padua_risk_scores.three_active_malignancy IS '活动性恶性肿瘤(3分)';
COMMENT ON COLUMN padua_risk_scores.three_prior_vte IS '既往VTE(3分)';
COMMENT ON COLUMN padua_risk_scores.three_immobilization IS '制动卧床≥3天(3分)';

COMMENT ON COLUMN padua_risk_scores.total_score IS 'Padua总分';
COMMENT ON COLUMN padua_risk_scores.risk_level IS '风险等级(0=LOW<4分,1=HIGH≥4分)';

COMMENT ON COLUMN padua_risk_scores.is_deleted IS '逻辑删除标记';
COMMENT ON COLUMN padua_risk_scores.deleted_by IS '删除人';
COMMENT ON COLUMN padua_risk_scores.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN padua_risk_scores.deleted_at IS '删除时间';
COMMENT ON COLUMN padua_risk_scores.modified_by IS '记录人账号';
COMMENT ON COLUMN padua_risk_scores.modified_at IS '记录时间';

CREATE UNIQUE INDEX idx_padua_risk_scores_pid_time
    ON padua_risk_scores(pid, score_time)
    WHERE is_deleted = false;

CREATE TABLE padua_bleeding_risk_assessments (
    id BIGSERIAL PRIMARY KEY,        -- 自增ID

    pid BIGINT NOT NULL,             -- 病人ID
    dept_id VARCHAR(255) NOT NULL,   -- 科室ID

    score_time TIMESTAMP NOT NULL,   -- 评估时间
    scored_by VARCHAR(255) NOT NULL, -- 评估人账号
    scored_by_account_name VARCHAR(255), -- 评估人姓名

    -- ============== 高危单项(满足1项则高危) ==============
    hr1_active_gi_ulcer BOOLEAN NOT NULL,             -- 活动性胃肠道溃疡
    hr1_bleeding_event_within_3months BOOLEAN NOT NULL, -- 3个月内有出血事件
    hr1_platelet_count_below_50 BOOLEAN NOT NULL,     -- 血小板<50*10^9/L

    -- ============== 高危三项(满足3项则高危) ==============
    hr3_age_85_or_older BOOLEAN NOT NULL,             -- 年龄≥85岁
    hr3_liver_dysfunction_inr_15 BOOLEAN NOT NULL,    -- 肝功能不全(INR>1.5)
    hr3_severe_renal_failure BOOLEAN NOT NULL,        -- 严重肾功能不全
    hr3_icu_or_ccu_admission BOOLEAN NOT NULL,        -- 入ICU或CCU
    hr3_central_venous_catheter BOOLEAN NOT NULL,     -- 中心静脉置管
    hr3_active_malignancy BOOLEAN NOT NULL,           -- 恶性肿瘤
    hr3_rheumatic_disease BOOLEAN NOT NULL,           -- 风湿性疾病
    hr3_male_gender BOOLEAN NOT NULL,                 -- 男性

    -- 是否出血高风险
    is_high_bleeding_risk BOOLEAN NOT NULL,           -- 是否高危(布尔)

-- ======================
    --  出血评估结果(BleedingAssessmentResult)
    -- 预防建议(枚举) -> 用 INT 存储
    -- ======================
    assessment_result INT NOT NULL,  -- 评估结果(0=ANTICOAGULANT_ONLY,1=PHYSICAL_ONLY,2=ANTICOAGULANT_PHYSICAL,3=PREVENTION_UNAVAILABLE)
    execution_result INT NOT NULL,   -- 执行结果(同枚举)

    -- ======================
    --  出血其他护理措施(BleedingCareMeasures)
    --   1) BasicMeasures
    -- ======================
    basic_elevate_limbs BOOLEAN NOT NULL,
    basic_ankle_exercise BOOLEAN NOT NULL,
    basic_quadriceps_contraction BOOLEAN NOT NULL,
    basic_deep_breathing_or_balloon BOOLEAN NOT NULL,
    basic_quit_smoking_alcohol BOOLEAN NOT NULL,
    basic_drink_more_water BOOLEAN NOT NULL,
    basic_maintain_bowel_regular BOOLEAN NOT NULL,
    basic_turn_every_2h_or_leg_movement BOOLEAN NOT NULL,
    basic_get_out_of_bed BOOLEAN NOT NULL,
    basic_other_basic_measures VARCHAR(255), -- 其他措施

    --   2) MechanicalMeasures
    mechanical_intermittent_pneumatic_compression BOOLEAN NOT NULL,
    mechanical_graded_compression_stockings BOOLEAN NOT NULL,
    mechanical_foot_vein_pump BOOLEAN NOT NULL,

    --   3) PharmacologicalMeasures
    pharmacological_low_molecular_heparin_injection BOOLEAN NOT NULL,
    pharmacological_rivaroxaban BOOLEAN NOT NULL,
    pharmacological_warfarin BOOLEAN NOT NULL,
    pharmacological_other_pharmacological_measures VARCHAR(255),

    -- 逻辑删除 & 审计字段
    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by VARCHAR(255),
    deleted_by_account_name VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL
);

COMMENT ON TABLE padua_bleeding_risk_assessments IS 'Padua内科患者出血风险评估表';
COMMENT ON COLUMN padua_bleeding_risk_assessments.id IS '自增ID';
COMMENT ON COLUMN padua_bleeding_risk_assessments.pid IS '病人ID';
COMMENT ON COLUMN padua_bleeding_risk_assessments.dept_id IS '科室ID';
COMMENT ON COLUMN padua_bleeding_risk_assessments.score_time IS '评估时间';
COMMENT ON COLUMN padua_bleeding_risk_assessments.scored_by IS '评估人账号';
COMMENT ON COLUMN padua_bleeding_risk_assessments.scored_by_account_name IS '评估人姓名';

COMMENT ON COLUMN padua_bleeding_risk_assessments.hr1_active_gi_ulcer IS '活性GI溃疡(高危单项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr1_bleeding_event_within_3months IS '3个月内出血(高危单项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr1_platelet_count_below_50 IS '血小板<50(高危单项)';

COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_age_85_or_older IS '≥85岁(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_liver_dysfunction_inr_15 IS '肝功能不全,INR>1.5(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_severe_renal_failure IS '严重肾功能不全(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_icu_or_ccu_admission IS '入住ICU/CCU(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_central_venous_catheter IS '中心静脉置管(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_active_malignancy IS '恶性肿瘤(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_rheumatic_disease IS '风湿性疾病(高危三项)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.hr3_male_gender IS '男性(高危三项)';

COMMENT ON COLUMN padua_bleeding_risk_assessments.is_high_bleeding_risk IS '是否出血高危';

COMMENT ON COLUMN padua_bleeding_risk_assessments.assessment_result IS '评估结果(0=抗凝,1=物理,2=抗凝+物理,3=无可用预防)';
COMMENT ON COLUMN padua_bleeding_risk_assessments.execution_result IS '执行结果(同上枚举)';

COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_elevate_limbs IS '抬高肢体';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_ankle_exercise IS '踝关节活动';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_quadriceps_contraction IS '股四头肌收缩';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_deep_breathing_or_balloon IS '深呼吸或吹气球';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_quit_smoking_alcohol IS '戒烟酒';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_drink_more_water IS '多饮水';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_maintain_bowel_regular IS '保持大便通畅';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_turn_every_2h_or_leg_movement IS '2h翻身或屈伸下肢';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_get_out_of_bed IS '下床活动';
COMMENT ON COLUMN padua_bleeding_risk_assessments.basic_other_basic_measures IS '其他基础措施';

COMMENT ON COLUMN padua_bleeding_risk_assessments.mechanical_intermittent_pneumatic_compression IS '间歇充气加压泵';
COMMENT ON COLUMN padua_bleeding_risk_assessments.mechanical_graded_compression_stockings IS '分级加压弹力袜';
COMMENT ON COLUMN padua_bleeding_risk_assessments.mechanical_foot_vein_pump IS '足底静脉泵';

COMMENT ON COLUMN padua_bleeding_risk_assessments.pharmacological_low_molecular_heparin_injection IS '低分子肝素皮下注射';
COMMENT ON COLUMN padua_bleeding_risk_assessments.pharmacological_rivaroxaban IS '利伐沙班';
COMMENT ON COLUMN padua_bleeding_risk_assessments.pharmacological_warfarin IS '华法林';
COMMENT ON COLUMN padua_bleeding_risk_assessments.pharmacological_other_pharmacological_measures IS '其他药物预防措施';

COMMENT ON COLUMN padua_bleeding_risk_assessments.is_deleted IS '逻辑删除标记';
COMMENT ON COLUMN padua_bleeding_risk_assessments.deleted_by IS '删除人';
COMMENT ON COLUMN padua_bleeding_risk_assessments.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN padua_bleeding_risk_assessments.deleted_at IS '删除时间';
COMMENT ON COLUMN padua_bleeding_risk_assessments.modified_by IS '记录人账号';
COMMENT ON COLUMN padua_bleeding_risk_assessments.modified_at IS '记录时间';

CREATE UNIQUE INDEX idx_padua_bleeding_risk_assessments_pid_time
    ON padua_bleeding_risk_assessments(pid, score_time)
    WHERE is_deleted = false;

CREATE TABLE bga_params (
    id BIGSERIAL PRIMARY KEY,        -- 自增ID
    dept_id VARCHAR(255) NOT NULL,
    monitoring_param_code VARCHAR(255) NOT NULL,
    display_order INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,

    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_by VARCHAR(255),
    deleted_by_account_name VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (monitoring_param_code) REFERENCES monitoring_params(code)
);
COMMENT ON TABLE bga_params IS '血气参数配置表';
COMMENT ON COLUMN bga_params.dept_id IS '科室ID';
COMMENT ON COLUMN bga_params.monitoring_param_code IS '监测参数编码，关联到monitoring_params.code';
COMMENT ON COLUMN bga_params.display_order IS '显示顺序';
COMMENT ON COLUMN bga_params.enabled IS '是否启用';
COMMENT ON COLUMN bga_params.is_deleted IS '是否已删除';
COMMENT ON COLUMN bga_params.deleted_by IS '删除人账号';
COMMENT ON COLUMN bga_params.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN bga_params.deleted_at IS '删除时间';
COMMENT ON COLUMN bga_params.modified_by IS '最后修改人账号';
COMMENT ON COLUMN bga_params.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_bga_params_dept_param 
    ON bga_params (dept_id, monitoring_param_code) 
    WHERE is_deleted = false;

CREATE TABLE bga_category_mappings (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    bga_category_id INT NOT NULL, -- 血气类别ID
    lis_category_code VARCHAR(255), -- 检验类别编码

    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_by_account_name VARCHAR(255), -- 删除人姓名
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL -- 最后修改时间
);

COMMENT ON TABLE bga_category_mappings IS '血气检验类别映射表';
COMMENT ON COLUMN bga_category_mappings.dept_id IS '科室ID';
COMMENT ON COLUMN bga_category_mappings.bga_category_id IS '血气类别ID';
COMMENT ON COLUMN bga_category_mappings.lis_category_code IS '检验类别编码';
COMMENT ON COLUMN bga_category_mappings.is_deleted IS '是否已删除';
COMMENT ON COLUMN bga_category_mappings.deleted_by IS '删除人账号';
COMMENT ON COLUMN bga_category_mappings.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN bga_category_mappings.deleted_at IS '删除时间';
COMMENT ON COLUMN bga_category_mappings.modified_by IS '最后修改人账号';
COMMENT ON COLUMN bga_category_mappings.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_bga_category_mappings_dept_bga_lis 
    ON bga_category_mappings (dept_id, bga_category_id, lis_category_code) 
    WHERE is_deleted = false;

CREATE TABLE bga_param_mappings (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    bga_code VARCHAR(255) NOT NULL, -- 血气参数编码，和bga_params.monitoring_param_code相同
    lis_result_code VARCHAR(255), -- LIS结果编码

    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_by_account_name VARCHAR(255), -- 删除人姓名
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL -- 最后修改时间
);

COMMENT ON TABLE bga_param_mappings IS '血气参数映射表';
COMMENT ON COLUMN bga_param_mappings.dept_id IS '科室ID';
COMMENT ON COLUMN bga_param_mappings.bga_code IS '血气参数编码，和bga_params.monitoring_param_code相同';
COMMENT ON COLUMN bga_param_mappings.lis_result_code IS 'LIS结果编码';
COMMENT ON COLUMN bga_param_mappings.is_deleted IS '是否已删除';
COMMENT ON COLUMN bga_param_mappings.deleted_by IS '删除人账号';
COMMENT ON COLUMN bga_param_mappings.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN bga_param_mappings.deleted_at IS '删除时间';
COMMENT ON COLUMN bga_param_mappings.modified_by IS '最后修改人账号';
COMMENT ON COLUMN bga_param_mappings.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_bga_param_mappings_dept_bga_lis 
    ON bga_param_mappings (dept_id, bga_code, lis_result_code) 
    WHERE is_deleted = false;

CREATE TABLE patient_bga_records (
    id BIGSERIAL PRIMARY KEY, -- 自增ID

    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    bga_category_id INT NOT NULL, -- 血气类别ID
    bga_category_name VARCHAR(255), -- 血气类别名称
    lis_category_code VARCHAR(255), -- LIS类别编码

    recorded_by VARCHAR(255), -- 首次记录人账号
    recorded_by_account_name VARCHAR(255), -- 首次记录人姓名
    recorded_at TIMESTAMP, -- 首次记录时间

    effective_time TIMESTAMP NOT NULL, -- 检测时间
    raw_record_id BIGINT, -- 原始记录ID，关联到raw_bga_records.id

    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_by_account_name VARCHAR(255), -- 删除人姓名
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_by_account_name VARCHAR(255), -- 最后修改人姓名
    modified_at TIMESTAMP NOT NULL, -- 最后修改时间
    reviewed_by VARCHAR(255), -- 审核人账号
    reviewed_by_account_name VARCHAR(255), -- 审核人姓名
    reviewed_at TIMESTAMP, -- 审核时间

    FOREIGN KEY (pid) REFERENCES patient_records(id)
);

COMMENT ON TABLE patient_bga_records IS '病人血气记录表';
COMMENT ON COLUMN patient_bga_records.id IS '自增ID';

COMMENT ON COLUMN patient_bga_records.pid IS '病人ID';
COMMENT ON COLUMN patient_bga_records.dept_id IS '科室ID';
COMMENT ON COLUMN patient_bga_records.bga_category_id IS '血气类别ID';
COMMENT ON COLUMN patient_bga_records.bga_category_name IS '血气类别名称';
COMMENT ON COLUMN patient_bga_records.lis_category_code IS 'LIS类别编码';

COMMENT ON COLUMN patient_bga_records.recorded_by IS '记录人账号';
COMMENT ON COLUMN patient_bga_records.recorded_by_account_name IS '记录人姓名';
COMMENT ON COLUMN patient_bga_records.recorded_at IS '记录时间';

COMMENT ON COLUMN patient_bga_records.effective_time IS '检测时间';
COMMENT ON COLUMN patient_bga_records.raw_record_id IS '原始记录ID，关联到raw_bga_records.id';

COMMENT ON COLUMN patient_bga_records.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_bga_records.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_bga_records.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN patient_bga_records.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_bga_records.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_bga_records.modified_by_account_name IS '最后修改人姓名';
COMMENT ON COLUMN patient_bga_records.modified_at IS '最后修改时间';
COMMENT ON COLUMN patient_bga_records.reviewed_by IS '审核人账号';
COMMENT ON COLUMN patient_bga_records.reviewed_by_account_name IS '审核人姓名';
COMMENT ON COLUMN patient_bga_records.reviewed_at IS '审核时间';

CREATE UNIQUE INDEX idx_patient_bga_records_pid_cateid_time
    ON patient_bga_records (pid, bga_category_id, effective_time)
    WHERE is_deleted = false;

CREATE TABLE patient_bga_record_details (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    record_id BIGINT NOT NULL, -- 外键patient_bga_records.id
    monitoring_param_code VARCHAR(255) NOT NULL, -- 监测参数编码
    param_value VARCHAR(255) NOT NULL, -- proto消息GenericValuePB实例序列化后的base64编码
    param_value_str VARCHAR(255), -- 记录时的值对应的字符串

    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL, -- 最后修改时间

    FOREIGN KEY (monitoring_param_code) REFERENCES monitoring_params(code)
);

COMMENT ON TABLE patient_bga_record_details IS '病人血气记录明细表';
COMMENT ON COLUMN patient_bga_record_details.id IS '自增ID';
COMMENT ON COLUMN patient_bga_record_details.record_id IS '外键patient_bga_records.id';
COMMENT ON COLUMN patient_bga_record_details.monitoring_param_code IS '监测参数编码';
COMMENT ON COLUMN patient_bga_record_details.param_value IS 'proto消息GenericValuePB实例序列化后的base64编码';
COMMENT ON COLUMN patient_bga_record_details.param_value_str IS '记录时的值对应的字符串';

COMMENT ON COLUMN patient_bga_record_details.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_bga_record_details.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_bga_record_details.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_bga_record_details.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_bga_record_details.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_patient_bga_record_details_record_param 
    ON patient_bga_record_details (record_id, monitoring_param_code)
    WHERE is_deleted = false;

CREATE TABLE raw_bga_records (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    mrn_bednum VARCHAR(255) NOT NULL, -- 病人住院号或床号
    bga_category_id INT NOT NULL, -- 血气类别ID
    effective_time TIMESTAMP NOT NULL -- 检测时间
);
COMMENT ON TABLE raw_bga_records IS '原始血气记录表';
COMMENT ON COLUMN raw_bga_records.id IS '自增ID';
COMMENT ON COLUMN raw_bga_records.mrn_bednum IS '病人住院号或床号';
COMMENT ON COLUMN raw_bga_records.bga_category_id IS '血气类别ID';
COMMENT ON COLUMN raw_bga_records.effective_time IS '检测时间';

CREATE INDEX idx_raw_bga_records_mrn ON raw_bga_records (mrn_bednum);

CREATE TABLE raw_bga_record_details (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    record_id BIGINT NOT NULL, -- 外键raw_bga_records.id
    monitoring_param_code VARCHAR(255) NOT NULL, -- 监测参数编码
    param_value_str VARCHAR(255) NOT NULL  -- 记录时的值对应的字符串
);
COMMENT ON TABLE raw_bga_record_details IS '原始血气记录明细表';
COMMENT ON COLUMN raw_bga_record_details.id IS '自增ID';
COMMENT ON COLUMN raw_bga_record_details.record_id IS '外键raw_bga_records.id';
COMMENT ON COLUMN raw_bga_record_details.monitoring_param_code IS '监测参数编码';
COMMENT ON COLUMN raw_bga_record_details.param_value_str IS '记录时的值对应的字符串';

-- 检验
CREATE TABLE lis_params (
    param_code VARCHAR(255) PRIMARY KEY, -- 检验参数编码
    param_name VARCHAR(255) NOT NULL, -- 检验参数名称
    param_description VARCHAR(255), -- 检验参数描述
    external_param_code VARCHAR(255), -- 外部检验参数编码，以逗号分隔
    display_order INT NOT NULL -- 显示顺序
);
COMMENT ON TABLE lis_params IS '检验参数表';
COMMENT ON COLUMN lis_params.param_code IS '检验参数编码';
COMMENT ON COLUMN lis_params.param_name IS '检验参数名称';
COMMENT ON COLUMN lis_params.param_description IS '检验参数描述';
COMMENT ON COLUMN lis_params.external_param_code IS '外部检验参数编码，以逗号分隔';
COMMENT ON COLUMN lis_params.display_order IS '显示顺序';

CREATE TABLE external_lis_params (
    param_code VARCHAR(255) PRIMARY KEY, -- 外部检验参数编码
    param_name VARCHAR(255) NOT NULL, -- 外部检验参数名称
    type_pb VARCHAR(255), -- proto消息ValueMetaPB实例序列化后的base64编码
    danger_max VARCHAR(255), -- 危险上阈值，proto消息GenericValuePB实例序列化后的base64编码
    danger_min VARCHAR(255), -- 危险下阈值，proto消息GenericValuePB实例序列化后的base64编码
    display_order INT NOT NULL -- 显示顺序
);
COMMENT ON TABLE external_lis_params IS '外部检验参数表';
COMMENT ON COLUMN external_lis_params.param_code IS '外部检验参数编码';
COMMENT ON COLUMN external_lis_params.param_name IS '外部检验参数名称';
COMMENT ON COLUMN external_lis_params.type_pb IS 'proto消息ValueMetaPB实例序列化后的base64编码';
COMMENT ON COLUMN external_lis_params.danger_max IS '危险上阈值，proto消息GenericValuePB实例序列化后的base64编码';
COMMENT ON COLUMN external_lis_params.danger_min IS '危险下阈值，proto消息GenericValuePB实例序列化后的base64编码';
COMMENT ON COLUMN external_lis_params.display_order IS '显示顺序';

CREATE TABLE patient_lis_items (
    report_id VARCHAR(255) PRIMARY KEY, -- 检验项流水号
    mrn VARCHAR(255), -- 病人住院号
    his_pid VARCHAR(255) NOT NULL, -- 病人ID
    lis_item_name VARCHAR(255), -- 检验项目名称，血常规、尿常规、肝功等
    lis_item_short_name VARCHAR(255), -- 检验项目简称
    lis_item_code VARCHAR(255), -- 检验项目编码
    order_id VARCHAR(255), -- 申请单号
    order_dept VARCHAR(255), -- 申请科室
    order_dept_id VARCHAR(255), -- 申请科室编码(code)
    order_doctor VARCHAR(255), -- 申请医生
    order_doctor_id VARCHAR(255), -- 申请医生编码
    sample_id VARCHAR(255), -- 标本号
    sample_name VARCHAR(255), -- 标本名称，静脉血、痰液、胃液等
    collect_time TIMESTAMP, -- 标本采集时间
    receive_time TIMESTAMP, -- 标本接收时间
    auth_time TIMESTAMP, -- 结果批准时间
    auth_doctor VARCHAR(255), -- 结果批准者
    status VARCHAR(255) -- 检验状态，申请、接收、审核等
);

COMMENT ON TABLE patient_lis_items IS '检验项目中间表';
COMMENT ON COLUMN patient_lis_items.report_id IS '检验项流水号';
COMMENT ON COLUMN patient_lis_items.mrn IS '病人住院号';
COMMENT ON COLUMN patient_lis_items.his_pid IS 'HIS病人ID';
COMMENT ON COLUMN patient_lis_items.lis_item_name IS '检验项目名称，血常规、尿常规、肝功等';
COMMENT ON COLUMN patient_lis_items.lis_item_short_name IS '检验项目简称';
COMMENT ON COLUMN patient_lis_items.lis_item_code IS '检验项目编码';
COMMENT ON COLUMN patient_lis_items.order_id IS '申请单号';
COMMENT ON COLUMN patient_lis_items.order_dept IS '申请科室';
COMMENT ON COLUMN patient_lis_items.order_dept_id IS '申请科室编码(code)';
COMMENT ON COLUMN patient_lis_items.order_doctor IS '申请医生';
COMMENT ON COLUMN patient_lis_items.order_doctor_id IS '申请医生编码';
COMMENT ON COLUMN patient_lis_items.sample_id IS '标本号';
COMMENT ON COLUMN patient_lis_items.sample_name IS '标本名称，静脉血、痰液、胃液等';
COMMENT ON COLUMN patient_lis_items.collect_time IS '标本采集时间';
COMMENT ON COLUMN patient_lis_items.receive_time IS '标本接收时间';
COMMENT ON COLUMN patient_lis_items.auth_time IS '结果批准时间';
COMMENT ON COLUMN patient_lis_items.auth_doctor IS '结果批准者';
COMMENT ON COLUMN patient_lis_items.status IS '检验状态，申请、接收、审核等';

CREATE TABLE patient_lis_results (
    id BIGSERIAL PRIMARY KEY, -- 自增ID
    report_id VARCHAR(255) NOT NULL, -- 检验项流水号，外键
    external_param_code VARCHAR(255) NOT NULL, -- 外部检验参数编码
    external_param_name VARCHAR(255), -- 外部检验参数名称
    unit VARCHAR(255), -- 单位
    result_str VARCHAR(255), -- 结果值对应的字符串
    auth_time TIMESTAMP, -- 结果批准时间
    auth_doctor VARCHAR(255), -- 结果批准者
    notes VARCHAR(255), -- 备注

    alarm_flag VARCHAR(255), -- 报警标志，正常、偏高、偏低（null或空表示正常）
    danger_flag VARCHAR(255), -- 危险标志，正常、危险（null或空表示正常）

    normal_min_str VARCHAR(255), -- 正常下阈值
    normal_max_str VARCHAR(255), -- 正常上阈值
    danger_min_str VARCHAR(255), -- 危险下阈值对应的字符串
    danger_max_str VARCHAR(255), -- 危险上阈值对应的字符串

    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_by_account_name VARCHAR(255), -- 删除人姓名
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL -- 最后修改时间
);
COMMENT ON TABLE patient_lis_results IS '检验结果中间表';
COMMENT ON COLUMN patient_lis_results.id IS '主键';
COMMENT ON COLUMN patient_lis_results.report_id IS '检验项流水号，外键';
COMMENT ON COLUMN patient_lis_results.external_param_code IS '外部检验参数编码';
COMMENT ON COLUMN patient_lis_results.external_param_name IS '外部检验参数名称';
COMMENT ON COLUMN patient_lis_results.unit IS '单位';
COMMENT ON COLUMN patient_lis_results.result_str IS '结果值对应的字符串';
COMMENT ON COLUMN patient_lis_results.auth_time IS '结果批准时间';
COMMENT ON COLUMN patient_lis_results.auth_doctor IS '结果批准者';
COMMENT ON COLUMN patient_lis_results.notes IS '备注';

COMMENT ON COLUMN patient_lis_results.alarm_flag IS '报警标志，正常、偏高、偏低（null或空表示正常）';
COMMENT ON COLUMN patient_lis_results.danger_flag IS '危险标志，正常、危险（null或空表示正常）';
COMMENT ON COLUMN patient_lis_results.normal_min_str IS '正常下阈值';
COMMENT ON COLUMN patient_lis_results.normal_max_str IS '正常上阈值';
COMMENT ON COLUMN patient_lis_results.danger_min_str IS '危险下阈值对应的字符串';
COMMENT ON COLUMN patient_lis_results.danger_max_str IS '危险上阈值对应的字符串';

COMMENT ON COLUMN patient_lis_results.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_lis_results.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_lis_results.deleted_by_account_name IS '删除人姓名';
COMMENT ON COLUMN patient_lis_results.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_lis_results.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_lis_results.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_patient_lis_results_report_id_result_code
    ON patient_lis_results (report_id, external_param_code, auth_time)
    WHERE is_deleted = false;

CREATE TABLE patient_critical_lis_results (
    id SERIAL PRIMARY KEY, -- 自增ID
    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID

    -- 检验大项信息
    report_id VARCHAR(255), -- 检验项流水号
    auth_time TIMESTAMP NOT NULL, -- 结果批准时间
    order_doctor VARCHAR(255) NOT NULL, -- 申请医生
    order_doctor_id VARCHAR(255), -- 申请医生编码
    lis_item_name VARCHAR(255) NOT NULL, -- 检验项目名称，血常规、尿常规、肝功等
    lis_item_short_name VARCHAR(255), -- 检验项目简称
    lis_item_code VARCHAR(255), -- 检验项目编码

    -- 检验小项信息
    patient_lis_result_id BIGINT, -- 外键，关联到 patient_lis_results.id
    external_param_code VARCHAR(255) NOT NULL, -- 外部检验参数编码
    external_param_name VARCHAR(255) NOT NULL, -- 外部检验参数名称

    unit VARCHAR(255), -- 单位
    result_str VARCHAR(255) NOT NULL, -- 结果值对应的字符串
    normal_min_str VARCHAR(255), -- 正常下阈值
    normal_max_str VARCHAR(255), -- 正常上阈值

    alarm_flag VARCHAR(255), -- 报警标志，正常、偏高、偏低（null或空表示正常）
    danger_flag VARCHAR(255), -- 危险标志，正常、危险（null或空表示正常）
    notes VARCHAR(255), -- 备注

    -- 处理结果
    handling_id INT, -- 处理ID, 对应于 patient_critical_lis_handlings.id，如果为空，表示未处理；如果不为空，表示已处理

    -- 用户修改信息
    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL -- 最后修改时间
);
COMMENT ON TABLE patient_critical_lis_results IS '病人危急检验结果表';
COMMENT ON COLUMN patient_critical_lis_results.id IS '自增ID';
COMMENT ON COLUMN patient_critical_lis_results.pid IS '病人ID';
COMMENT ON COLUMN patient_critical_lis_results.dept_id IS '科室ID';
COMMENT ON COLUMN patient_critical_lis_results.report_id IS '检验项流水号';
COMMENT ON COLUMN patient_critical_lis_results.auth_time IS '结果批准时间';
COMMENT ON COLUMN patient_critical_lis_results.order_doctor IS '申请医生';
COMMENT ON COLUMN patient_critical_lis_results.order_doctor_id IS '申请医生编码';
COMMENT ON COLUMN patient_critical_lis_results.lis_item_name IS '检验项目名称，血常规、尿常规、肝功等';
COMMENT ON COLUMN patient_critical_lis_results.lis_item_short_name IS '检验项目简称';
COMMENT ON COLUMN patient_critical_lis_results.lis_item_code IS '检验项目编码';
COMMENT ON COLUMN patient_critical_lis_results.patient_lis_result_id IS '外键，关联到 patient_lis_results.id';
COMMENT ON COLUMN patient_critical_lis_results.external_param_code IS '外部检验参数编码';
COMMENT ON COLUMN patient_critical_lis_results.external_param_name IS '外部检验参数名称';
COMMENT ON COLUMN patient_critical_lis_results.unit IS '单位';
COMMENT ON COLUMN patient_critical_lis_results.result_str IS '结果值对应的字符串';
COMMENT ON COLUMN patient_critical_lis_results.normal_min_str IS '正常下阈值';
COMMENT ON COLUMN patient_critical_lis_results.normal_max_str IS '正常上阈值';
COMMENT ON COLUMN patient_critical_lis_results.alarm_flag IS '报警标志，正常、偏高、偏低（null或空表示正常）';
COMMENT ON COLUMN patient_critical_lis_results.danger_flag IS '危险标志，正常、危险（null或空表示正常）';
COMMENT ON COLUMN patient_critical_lis_results.notes IS '备注';
COMMENT ON COLUMN patient_critical_lis_results.handling_id IS '处理ID, 对应于 patient_critical_lis_handlings.id，如果为空，表示未处理；如果不为空，表示已处理';
COMMENT ON COLUMN patient_critical_lis_results.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_critical_lis_results.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_critical_lis_results.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_critical_lis_results.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_critical_lis_results.modified_at IS '最后修改时间';
CREATE INDEX idx_patient_critical_lis_results_pid_auth_time ON patient_critical_lis_results (pid, auth_time);

CREATE TABLE patient_critical_lis_handlings (
    id SERIAL PRIMARY KEY, -- 自增ID
    pid BIGINT NOT NULL, -- 病人ID
    dept_id VARCHAR(255) NOT NULL, -- 科室ID
    actions VARCHAR(1000) NOT NULL, -- 处理动作，如"已处理"、"已忽略"
    effective_time TIMESTAMP NOT NULL, -- 处理时间

    -- 用户修改信息
    is_deleted BOOLEAN NOT NULL DEFAULT false, -- 是否已删除
    deleted_by VARCHAR(255), -- 删除人账号
    deleted_at TIMESTAMP, -- 删除时间
    modified_by VARCHAR(255), -- 最后修改人账号
    modified_at TIMESTAMP NOT NULL -- 最后修改时间
);
COMMENT ON TABLE patient_critical_lis_handlings IS '病人危急检验结果处理表';
COMMENT ON COLUMN patient_critical_lis_handlings.id IS '自增ID';
COMMENT ON COLUMN patient_critical_lis_handlings.pid IS '病人ID';
COMMENT ON COLUMN patient_critical_lis_handlings.dept_id IS '科室ID';
COMMENT ON COLUMN patient_critical_lis_handlings.actions IS '处理动作，如"已处理"、"已忽略"';
COMMENT ON COLUMN patient_critical_lis_handlings.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_critical_lis_handlings.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_critical_lis_handlings.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_critical_lis_handlings.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_critical_lis_handlings.modified_at IS '最后修改时间';

CREATE TABLE overview_templates (
    id BIGSERIAL PRIMARY KEY,
    dept_id VARCHAR(255),
    account_id VARCHAR(255),
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    template_name VARCHAR(255) NOT NULL,
    notes TEXT,
    display_order INTEGER NOT NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP
);

COMMENT ON TABLE overview_templates IS '数据视图模板表';
COMMENT ON COLUMN overview_templates.id IS '自增主键';
COMMENT ON COLUMN overview_templates.dept_id IS '所属科室ID';
COMMENT ON COLUMN overview_templates.account_id IS '所属用户账号ID';
COMMENT ON COLUMN overview_templates.created_by IS '创建人账号';
COMMENT ON COLUMN overview_templates.created_at IS '创建时间';
COMMENT ON COLUMN overview_templates.template_name IS '模板名称';
COMMENT ON COLUMN overview_templates.notes IS '备注';
COMMENT ON COLUMN overview_templates.display_order IS '模板显示顺序';
COMMENT ON COLUMN overview_templates.is_deleted IS '是否删除';
COMMENT ON COLUMN overview_templates.deleted_by IS '删除人账号';
COMMENT ON COLUMN overview_templates.deleted_at IS '删除时间';
COMMENT ON COLUMN overview_templates.modified_by IS '最后修改人账号';
COMMENT ON COLUMN overview_templates.modified_by_account_name IS '最后修改人姓名';
COMMENT ON COLUMN overview_templates.modified_at IS '最后修改时间';

CREATE UNIQUE INDEX idx_overview_templates_name
    ON overview_templates (dept_id, account_id, template_name)
    WHERE is_deleted = FALSE;

CREATE TABLE overview_groups (
    id BIGSERIAL PRIMARY KEY,
    template_id BIGINT NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    is_balance_group BOOLEAN NOT NULL,
    display_order INTEGER NOT NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP,

    FOREIGN KEY (template_id) REFERENCES overview_templates(id)
);

COMMENT ON TABLE overview_groups IS '数据视图模板-分组表';
COMMENT ON COLUMN overview_groups.id IS '自增主键';
COMMENT ON COLUMN overview_groups.template_id IS '所属模板ID，外键 overview_templates.id';
COMMENT ON COLUMN overview_groups.group_name IS '分组名称';
COMMENT ON COLUMN overview_groups.is_balance_group IS '是否平衡组(出入量组)';
COMMENT ON COLUMN overview_groups.display_order IS '分组显示顺序';
COMMENT ON COLUMN overview_groups.is_deleted IS '是否删除';
COMMENT ON COLUMN overview_groups.deleted_by IS '删除人账号';
COMMENT ON COLUMN overview_groups.deleted_at IS '删除时间';
COMMENT ON COLUMN overview_groups.modified_by IS '最后修改人账号';
COMMENT ON COLUMN overview_groups.modified_by_account_name IS '最后修改人姓名';
COMMENT ON COLUMN overview_groups.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_overview_groups_name
    ON overview_groups (template_id, group_name)
    WHERE is_deleted = FALSE;

CREATE TABLE overview_params (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    param_name VARCHAR(255) NOT NULL,
    graph_type INTEGER NOT NULL,
    color VARCHAR(64),
    point_icon VARCHAR(64),
    param_type INTEGER NOT NULL,
    bga_category_id INTEGER,
    param_code VARCHAR(255) NOT NULL,
    value_meta VARCHAR(1000),  -- proto消息ValueMetaPB实例序列化后的base64编码
    balance_type_id INTEGER NOT NULL,
    display_order INTEGER NOT NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP,

    FOREIGN KEY (group_id) REFERENCES overview_groups(id)
);

COMMENT ON TABLE overview_params IS '数据视图模板-参数表';
COMMENT ON COLUMN overview_params.id IS '自增主键';
COMMENT ON COLUMN overview_params.group_id IS '所属分组ID，外键 overview_groups.id';
COMMENT ON COLUMN overview_params.param_name IS '参数名称';
COMMENT ON COLUMN overview_params.graph_type IS '图表类型：如柱状图、折线图';
COMMENT ON COLUMN overview_params.color IS '图表颜色';
COMMENT ON COLUMN overview_params.point_icon IS '点图标样式（后端枚举）';
COMMENT ON COLUMN overview_params.param_type IS '参数类型：1-血气，2-检验，3-观察项';
COMMENT ON COLUMN overview_params.bga_category_id IS '血气类别ID，仅当param_type为1时有效';
COMMENT ON COLUMN overview_params.param_code IS '参数编码';
COMMENT ON COLUMN overview_params.value_meta IS 'proto消息ValueMetaPB实例序列化后的base64编码';
COMMENT ON COLUMN overview_params.balance_type_id IS '平衡类型ID，对应于MonitoringPB.enums.balance_xx.id; 0: nan; 1: 入量; 2: 出量; 3: 平衡量';
COMMENT ON COLUMN overview_params.display_order IS '参数显示顺序';
COMMENT ON COLUMN overview_params.is_deleted IS '是否删除';
COMMENT ON COLUMN overview_params.deleted_by IS '删除人账号';
COMMENT ON COLUMN overview_params.deleted_at IS '删除时间';
COMMENT ON COLUMN overview_params.modified_by IS '最后修改人账号';
COMMENT ON COLUMN overview_params.modified_by_account_name IS '最后修改人姓名';
COMMENT ON COLUMN overview_params.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_overview_params_name
    ON overview_params (group_id, param_name)
    WHERE is_deleted = FALSE;
CREATE INDEX idx_overview_params_code
    ON overview_params (param_code)
    WHERE is_deleted = FALSE;

CREATE TABLE balance_stats_shifts (
    id BIGSERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    start_hour INTEGER NOT NULL,
    effective_time TIMESTAMP NOT NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255),
    modified_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE balance_stats_shifts IS '出入量统计班次表';
COMMENT ON COLUMN balance_stats_shifts.id IS '自增主键';
COMMENT ON COLUMN balance_stats_shifts.dept_id IS '科室ID';
COMMENT ON COLUMN balance_stats_shifts.start_hour IS '班次开始小时';
COMMENT ON COLUMN balance_stats_shifts.effective_time IS '统计时间';
COMMENT ON COLUMN balance_stats_shifts.is_deleted IS '是否删除';
COMMENT ON COLUMN balance_stats_shifts.deleted_by IS '删除人账号';
COMMENT ON COLUMN balance_stats_shifts.deleted_at IS '删除时间';
COMMENT ON COLUMN balance_stats_shifts.modified_by IS '最后修改人账号';
COMMENT ON COLUMN balance_stats_shifts.modified_by_account_name IS '最后修改人姓名';
COMMENT ON COLUMN balance_stats_shifts.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_balance_stats_shifts_dept_start_hour
    ON balance_stats_shifts (dept_id, effective_time)
    WHERE is_deleted = FALSE;

-- 组长质控（checklist）

-- 可以增删，不可以修改
CREATE TABLE dept_checklist_groups (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    group_name VARCHAR(255) NOT NULL,
    comments VARCHAR(255),
    display_order INTEGER NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL
);
COMMENT ON TABLE dept_checklist_groups IS '科室质控分组表';
COMMENT ON COLUMN dept_checklist_groups.id IS '自增ID';
COMMENT ON COLUMN dept_checklist_groups.dept_id IS '科室ID';
COMMENT ON COLUMN dept_checklist_groups.group_name IS '分组名称';
COMMENT ON COLUMN dept_checklist_groups.comments IS '备注';
COMMENT ON COLUMN dept_checklist_groups.display_order IS '显示顺序';
COMMENT ON COLUMN dept_checklist_groups.is_deleted IS '是否已删除';
COMMENT ON COLUMN dept_checklist_groups.deleted_by IS '删除人账号';
COMMENT ON COLUMN dept_checklist_groups.deleted_at IS '删除时间';
COMMENT ON COLUMN dept_checklist_groups.modified_by IS '最后修改人账号';
COMMENT ON COLUMN dept_checklist_groups.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_dept_checklist_groups_name
    ON dept_checklist_groups (dept_id, group_name)
    WHERE is_deleted = FALSE;

-- 可以增删，不可以修改
CREATE TABLE dept_checklist_items (
    id SERIAL PRIMARY KEY,
    group_id INT NOT NULL,
    item_name VARCHAR(255) NOT NULL,
    is_critical BOOLEAN NOT NULL DEFAULT FALSE,
    comments VARCHAR(255),  -- 备注
    has_note BOOLEAN NOT NULL DEFAULT FALSE,
    default_note VARCHAR(255),
    display_order INTEGER NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (group_id) REFERENCES dept_checklist_groups(id)
);
COMMENT ON TABLE dept_checklist_items IS '科室质控项表';
COMMENT ON COLUMN dept_checklist_items.id IS '自增ID';
COMMENT ON COLUMN dept_checklist_items.group_id IS '所属分组ID，外键 dept_checklist_groups.id';
COMMENT ON COLUMN dept_checklist_items.item_name IS '质控项名称';
COMMENT ON COLUMN dept_checklist_items.is_critical IS '是否为关键质控项';
COMMENT ON COLUMN dept_checklist_items.comments IS '质控项备注';
COMMENT ON COLUMN dept_checklist_items.has_note IS '启用输入框';
COMMENT ON COLUMN dept_checklist_items.default_note IS '输入框默认内容';
COMMENT ON COLUMN dept_checklist_items.display_order IS '显示顺序';
COMMENT ON COLUMN dept_checklist_items.is_deleted IS '是否已删除';
COMMENT ON COLUMN dept_checklist_items.deleted_by IS '删除人账号';
COMMENT ON COLUMN dept_checklist_items.deleted_at IS '删除时间';
COMMENT ON COLUMN dept_checklist_items.modified_by IS '最后修改人账号';
COMMENT ON COLUMN dept_checklist_items.modified_at IS '最后修改时间';
CREATE UNIQUE INDEX idx_dept_checklist_items_name
    ON dept_checklist_items (group_id, item_name)
    WHERE is_deleted = FALSE;

CREATE TABLE patient_checklist_records (
    id SERIAL PRIMARY KEY,
    pid BIGINT NOT NULL,
    dept_id VARCHAR(255) NOT NULL,
    created_by VARCHAR(255) NOT NULL,  -- 质控人
    effective_time TIMESTAMP NOT NULL,  -- 质控时间
    created_at TIMESTAMP NOT NULL,  -- 创建时间
    modified_by VARCHAR(255) NOT NULL,  -- 最后修改人
    modified_at TIMESTAMP NOT NULL,  -- 最后修改人
    reviewed_by VARCHAR(255),  -- 审核人
    reviewed_at TIMESTAMP,  -- 审核时间
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP
);
COMMENT ON TABLE patient_checklist_records IS '病人质控记录表';
COMMENT ON COLUMN patient_checklist_records.id IS '自增ID';
COMMENT ON COLUMN patient_checklist_records.pid IS '病人ID';
COMMENT ON COLUMN patient_checklist_records.dept_id IS '科室ID';
COMMENT ON COLUMN patient_checklist_records.created_by IS '质控人账号';
COMMENT ON COLUMN patient_checklist_records.effective_time IS '质控时间';
COMMENT ON COLUMN patient_checklist_records.created_at IS '创建时间';
COMMENT ON COLUMN patient_checklist_records.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_checklist_records.modified_at IS '最后修改时间';
COMMENT ON COLUMN patient_checklist_records.reviewed_by IS '审核人账号';
COMMENT ON COLUMN patient_checklist_records.reviewed_at IS '审核时间';
COMMENT ON COLUMN patient_checklist_records.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_checklist_records.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_checklist_records.deleted_at IS '删除时间';
CREATE UNIQUE INDEX idx_patient_checklist_records_pid_effective_time
    ON patient_checklist_records (pid, effective_time)
    WHERE is_deleted = FALSE;

CREATE TABLE patient_checklist_groups (
    id BIGSERIAL PRIMARY KEY,
    record_id INT NOT NULL,  -- 外键 patient_checklist_records.id
    group_id INT NOT NULL,  -- 外键 dept_checklist_groups.id
    display_order INTEGER NOT NULL,  -- 显示顺序
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (record_id) REFERENCES patient_checklist_records(id),
    FOREIGN KEY (group_id) REFERENCES dept_checklist_groups(id)
);
COMMENT ON TABLE patient_checklist_groups IS '病人质控分组记录表';
COMMENT ON COLUMN patient_checklist_groups.id IS '自增ID';
COMMENT ON COLUMN patient_checklist_groups.record_id IS '外键 patient_checklist_records.id';
COMMENT ON COLUMN patient_checklist_groups.group_id IS '外键 dept_checklist_groups.id';
COMMENT ON COLUMN patient_checklist_groups.display_order IS '显示顺序';
COMMENT ON COLUMN patient_checklist_groups.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_checklist_groups.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_checklist_groups.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_checklist_groups.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_checklist_groups.modified_at IS '最后修改时间';
CREATE INDEX idx_patient_checklist_groups_group
    ON patient_checklist_groups (group_id)
    WHERE is_deleted = FALSE;

CREATE TABLE patient_checklist_items (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,  -- 外键 patient_checklist_groups.id
    item_id INT NOT NULL,  -- 外键 dept_checklist_items.id
    is_checked BOOLEAN NOT NULL DEFAULT FALSE,  -- 是否已勾选
    note VARCHAR(255),  -- 输入框内容
    display_order INTEGER NOT NULL,  -- 显示顺序
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL,
    FOREIGN KEY (group_id) REFERENCES patient_checklist_groups(id),
    FOREIGN KEY (item_id) REFERENCES dept_checklist_items(id)
);
COMMENT ON TABLE patient_checklist_items IS '病人质控项记录表';
COMMENT ON COLUMN patient_checklist_items.id IS '自增ID';
COMMENT ON COLUMN patient_checklist_items.group_id IS '外键 patient_checklist_groups.id';
COMMENT ON COLUMN patient_checklist_items.item_id IS '外键 dept_checklist_items.id';
COMMENT ON COLUMN patient_checklist_items.is_checked IS '是否已勾选';
COMMENT ON COLUMN patient_checklist_items.note IS '输入框内容';
COMMENT ON COLUMN patient_checklist_items.display_order IS '显示顺序';
COMMENT ON COLUMN patient_checklist_items.is_deleted IS '是否已删除';
COMMENT ON COLUMN patient_checklist_items.deleted_by IS '删除人账号';
COMMENT ON COLUMN patient_checklist_items.deleted_at IS '删除时间';
COMMENT ON COLUMN patient_checklist_items.modified_by IS '最后修改人账号';
COMMENT ON COLUMN patient_checklist_items.modified_at IS '最后修改时间';

CREATE TABLE ext_url_configs (
    id SERIAL PRIMARY KEY,
    dept_id VARCHAR(255) NOT NULL,
    display_name VARCHAR(255) NOT NULL,
    pattern VARCHAR(1000),
    ext_url_pb TEXT,

    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_by VARCHAR(255),
    deleted_at TIMESTAMP,

    modified_at TIMESTAMP NOT NULL,
    modified_by VARCHAR(255) NOT NULL
);

COMMENT ON TABLE ext_url_configs IS '外部URL配置表';
COMMENT ON COLUMN ext_url_configs.id IS '自增主键';
COMMENT ON COLUMN ext_url_configs.dept_id IS '科室ID';
COMMENT ON COLUMN ext_url_configs.display_name IS '显示名称';
COMMENT ON COLUMN ext_url_configs.pattern IS '匹配模式';
COMMENT ON COLUMN ext_url_configs.ext_url_pb IS '外部URL配置pb数据';
COMMENT ON COLUMN ext_url_configs.is_deleted IS '是否已删除';
COMMENT ON COLUMN ext_url_configs.deleted_by IS '删除人';
COMMENT ON COLUMN ext_url_configs.deleted_at IS '删除时间';
COMMENT ON COLUMN ext_url_configs.modified_at IS '最后修改时间';
COMMENT ON COLUMN ext_url_configs.modified_by IS '最后修改人';

CREATE UNIQUE INDEX idx_ext_url_configs_dept_display_name 
    ON ext_url_configs (dept_id, display_name) 
    WHERE is_deleted = FALSE;

CREATE TABLE patient_nursing_reports (
    id BIGSERIAL PRIMARY KEY,
    pid BIGINT NOT NULL,
    effective_time_midnight TIMESTAMP NOT NULL,
    data_pb TEXT,
    last_processed_at TIMESTAMP,
    latest_data_time TIMESTAMP
);
COMMENT ON TABLE patient_nursing_reports IS '病人护理单数据表';
COMMENT ON COLUMN patient_nursing_reports.id IS '自增主键';
COMMENT ON COLUMN patient_nursing_reports.pid IS '病人ID';
COMMENT ON COLUMN patient_nursing_reports.effective_time_midnight IS '护理单对应的时间（本地时间当天0点对应的UTC时间）';
COMMENT ON COLUMN patient_nursing_reports.data_pb IS '护理单数据， Ah2PageDataPB 的Base64字节码';
COMMENT ON COLUMN patient_nursing_reports.last_processed_at IS '最后处理时间';
COMMENT ON COLUMN patient_nursing_reports.latest_data_time IS '最新数据时间，结合last_processed_at用于判断data_pb是否需要更新';
CREATE UNIQUE INDEX idx_patient_nursing_reports_pid_effective_time 
    ON patient_nursing_reports (pid, effective_time_midnight);

-- 10. 配置表
CREATE TABLE system_settings (
    function_id INT NOT NULL PRIMARY KEY,
    function_name VARCHAR(255),
    settings_pb TEXT NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    modified_by VARCHAR(255) NOT NULL
);
COMMENT ON TABLE system_settings IS '系统设置';
COMMENT ON COLUMN system_settings.function_id IS '功能ID';
COMMENT ON COLUMN system_settings.function_name IS '功能名称';
COMMENT ON COLUMN system_settings.settings_pb IS '不同的功能ID，有不同的PB的Base64字节码，比如显示入科状态用的是icis_config.proto:DisplayFieldSettingsPB';
COMMENT ON COLUMN system_settings.modified_at IS '最后修改时间';
COMMENT ON COLUMN system_settings.modified_by IS '最后修改人';

CREATE TABLE dept_system_settings (
    dept_id VARCHAR(255) NOT NULL,
    function_id INT NOT NULL,
    settings_pb TEXT NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    modified_by VARCHAR(255) NOT NULL,
    PRIMARY KEY (dept_id, function_id),
    FOREIGN KEY (dept_id) REFERENCES rbac_departments(dept_id)
);
COMMENT ON TABLE dept_system_settings IS '系统设置';
COMMENT ON COLUMN dept_system_settings.dept_id IS '科室ID';
COMMENT ON COLUMN dept_system_settings.function_id IS '功能ID';
COMMENT ON COLUMN dept_system_settings.settings_pb IS '不同的功能ID，有不同的PB的Base64字节码，比如显示入科状态用的是icis_config.proto:DisplayFieldSettingsPB';
COMMENT ON COLUMN dept_system_settings.modified_at IS '最后修改时间';
COMMENT ON COLUMN dept_system_settings.modified_by IS '最后修改人';

CREATE TABLE patient_settings (
    pid BIGINT NOT NULL PRIMARY KEY,
    report_cfg TEXT NOT NULL,
    modified_at TIMESTAMP NOT NULL,
    modified_by VARCHAR(255) NOT NULL
);
COMMENT ON TABLE patient_settings IS '病人设置';
COMMENT ON COLUMN patient_settings.pid IS '病人ID';
COMMENT ON COLUMN patient_settings.report_cfg IS 'PatientReportConfigPB 的 Base64字节码';
COMMENT ON COLUMN patient_settings.modified_at IS '最后修改时间';
COMMENT ON COLUMN patient_settings.modified_by IS '最后修改人';
