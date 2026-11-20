BEGIN;

ALTER TABLE dept_monitoring_groups ADD COLUMN IF NOT EXISTS balance_role INT;

COMMENT ON COLUMN dept_monitoring_groups.balance_role IS '出入量角色：0：无角色，1：入量，2：出量，3：平衡量';

COMMIT;

select * from dept_monitoring_groups where group_type = 1 and is_deleted = false order by dept_id, group_type;

update dept_monitoring_groups set balance_role = 1 where name = '入量' and is_deleted = false;
update dept_monitoring_groups set balance_role = 2 where name = '出量' and is_deleted = false;
update dept_monitoring_groups set balance_role = 3 where name = '平衡量' and is_deleted = false;
