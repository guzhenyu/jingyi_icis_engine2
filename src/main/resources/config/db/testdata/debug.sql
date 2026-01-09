-- PostgreSQL database dump & restore example
pg_dump -U jingyi_dev jingyi_icis_db > ah2db.sql
psql jingyi_icis_db < ah2db.sql

-- 重置菜单
delete from dept_system_settings where dept_id='xxx' and function_id=7;

-- 查看数据库锁
SELECT pid, usename, application_name, client_addr, backend_start, state, query
FROM pg_stat_activity
WHERE state != 'idle';

SELECT pg_terminate_backend(<pid>);