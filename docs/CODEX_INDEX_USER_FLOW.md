# User 模块流程与关键点（Codex 索引版）

## 1. 作用范围
- 文件范围：
  - `src/main/java/com/jingyicare/jingyi_icis_engine/service/users/UserService.java`
  - `src/main/java/com/jingyicare/jingyi_icis_engine/service/users/UserCommand.java`
  - `src/main/java/com/jingyicare/jingyi_icis_engine/service/users/UserMapper.java`
  - `src/main/java/com/jingyicare/jingyi_icis_engine/service/users/UserQuery.java`
- 模块分层：
  - `UserService`：接口编排层（JSON 转 Proto、登录态、权限判断、返回码封装）。
  - `UserCommand`：命令层（RBAC/业务表写入、事务管理、状态码返回）。
  - `UserQuery`：查询层（账号、科室、角色、权限读取及结果组装）。

账号配置与接口统一使用 `config/icis_user.proto` 的 `AccountPB`、`AccountDepartmentPB`：
- 配置账号字段使用 `account_id`、`account_name`，不再配置账号顶层的 `role_id`。
- 科室归属与主要角色放在 `account.department`；旧 `department_account` 字段已删除并保留编号。
- 初始化顺序为科室、账号、额外科室角色；初始化失败会停止启动。
- 管理员编码统一为 `UserQuery.ADMIN_ACCOUNT_ID`（`admin`），系统审计标识为
  `UserQuery.SYSTEM_ACCOUNT_ID`（`system`）。

## 2. 总体调用流程（高频模式）
1. `UserService` 入口方法接收 `xxxReqJson`。
2. `ProtoUtils.parseJsonToProto(...)` 解析请求，失败返回 `PARSE_JSON_FAILED`。
3. `getCurrentAccount()` 获取当前账号，缺失时统一返回 `ACCOUNT_NOT_FOUND`（未找到当前用户信息）。鉴权和密码操作使用 `rbacAcctId()`，审计字段使用 `autoAcctId()`。
4. 按功能执行授权校验：
   - 管理员校验：`account.rbacAcctId().equals(ADMIN_ACCOUNT_ID)`。
   - 权限校验：`hasPermission(rbacAcctId, opDeptId, Consts.PERM_ID_CONFIG_CHECKLIST)`。
5. 查询调用 `UserQuery`，写入调用 `UserCommand`。
6. `ReturnCodeUtils.getReturnCode(statusCodeMsgList, statusCode)` 统一包装响应。

## 3. 入口方法 -> 核心能力映射

### 3.1 用户与账号
- `getUserInfo` -> `getCurrentAccount` / `getAllDeptInfoByAccountId` / `getPermissionIds` / `getPrimaryRoleName`
  - 额外依赖：`menuService.getMenuGroups`、`certificateService.checkBedAvailable`。
- `getAllAccounts` -> `getAllAccounts(queryDeptId, queryAccountId, queryAccountName, zoneId)`
  - 结果会过滤管理员账号（`ADMIN_ACCOUNT_ID`）。
- `addAccount` -> `addAccount(AccountPB, modifiedByAutoAcctId)`
  - 需要 `PERM_ID_CONFIG_CHECKLIST`。
- `updateAccount` -> `updateAccount(AccountPB, modifiedByAutoAcctId)`
  - 合并目标账号现有科室与请求科室并去重，统一通过 `canConfigAccount` 检查账号管理权限；
  - 修改本人也需权限，失败时返回首个无权限科室。
- `deleteAccount` -> `deleteAccount(rbacAcctId, deptId, modifiedByAutoAcctId)`
  - 禁止删除管理员或自己；普通操作者需具备目标现有科室与请求科室的账号管理权限。
- `changePassword` -> `changePassword(rbacAcctId, oldPassword, newPassword)`，仅修改当前登录账号。
- `resetPassword` -> `resetPassword(rbacAcctId, operatorRbacAcctId)`
  - Service 与命令层都仅允许固定管理员 `admin` 重置，不依赖科室权限或全局角色绑定。

### 3.2 部门
- `getAllDeptNames` -> `getAllDeptInfo`（按 `Department.id` 排序）。
- `getAllDepts` -> `getAllDepartments`（返回 `IcisDepartmentPB`）。
- `addDept` -> `addDepartment(...)` -> `getDepartmentId(deptId)`。
  - 仅管理员可操作。
- `updateDept` -> `updateDepartment(...)`。
- `deleteDept` -> `deleteDepartment(deptId, modifiedBy)`。
  - 仅管理员可操作，且至少保留一个部门（`DEPARTMENT_AT_LEAST_ONE`）。

### 3.3 角色与权限
- `getRoles` -> `getPrimaryRoles()`（过滤 `ADMIN_ROLE_ID`）。
- `getPermissions` -> `departmentExists` -> `getNonPrimaryRolePermissions(deptId)`。
- `addRolePermission` -> `addRolePermission(roleId, permissionId, modifiedBy)`。
- `revokeRolePermission` -> `revokeRolePermission(roleId, permissionId, modifiedBy)`。
- `addAccountPermission` -> `addAccountPermission(accountId, deptId, permissionId, modifiedBy)`。
- `revokeAccountPermission` -> `revokeAccountPermission(accountId, deptId, permissionId, modifiedBy)`。

## 4. 用户数据层关键流程

### 4.1 账号新增 `addAccount(AccountPB, modifiedByAutoAcctId)`
1. API 入口拒绝空科室列表；命令层在锁前检查请求内重复科室。
   系统初始化可创建无科室的管理员账号。
2. 从请求确定 `LockKeys.account(rbacAcctId)` 和全部 `LockKeys.department(deptId)`，
   通过 `LockExecutor.execute` 一次取得锁并开启事务；执行器统一去重、排序。
3. 在锁内校验账号不重复、科室与主角色存在、角色是主角色、账号科室关系不存在。
4. 通过 `UserMapper` 转换账号实体，设置密码哈希和审计字段，并写入两张账号表；
   随后直接建立 RBAC 科室关系、业务科室关系和主角色关系。
5. 业务校验在写入前返回错误码；持久化异常由执行器整批回滚。

### 4.2 账号更新 `updateAccount(AccountPB, modifiedByAutoAcctId)`
1. 在锁前检查请求内重复科室；空科室列表保留移除全部科室归属的语义。
2. 通过 `RbacDepartmentAccountJdbc` 预读现有科室，使用 `LockExecutor.executePlanned`
   锁定账号及新旧科室并集；锁后通过同一 JDBC 查询复查，归属变化时重建计划。
3. 在修改任何托管实体前，校验两张账号表、请求科室及主角色，以及角色变更的
   原主角色关系存在、新主角色关系不存在。
4. 更新账号资料与 RBAC 姓名，再依次新增科室关系、修改主角色、移除旧科室关系。
   移除科室同时删除角色和权限关系，业务科室关系软删除并记录审计信息。
5. 全部写入在执行器事务内完成，入口无外层 `@Transactional`。

### 4.3 账号删除 `deleteAccount(rbacAcctId, deptId, modifiedByAutoAcctId)`
1. 使用 `LockExecutor.executePlanned` 锁定账号，以及 RBAC、有效业务归属与请求指定科室的并集。
2. 锁前与锁后都通过 JDBC 读取 RBAC 科室集合和有效 `accounts_departments` 记录快照；
   业务记录的 ID、角色、时间或审计字段变化都会使计划失效并重试。
3. 指定 `deptId` 时删除该科室归属；为空或全空白时删除两侧全部归属。
   每个科室统一调用 `deleteDepartmentAccount`，硬删除 RBAC 权限、角色和科室关系，
   软删除有效业务关系；已软删除的历史记录不改动。
4. 两侧都没有剩余科室时，清理历史全局角色和权限，硬删除 `rbac_accounts`，
   软删除 `accounts` 并写入删除审计。删除最后一个科室时也执行账号清理。
5. 无科室账号同样可以删除；重复删除已清理账号返回 `ACCOUNT_NOT_FOUND`。

### 4.4 部门新增/更新/删除
- 新增 `addDepartment`：
  - 校验 `deptId` 和 `deptName` 唯一（在 `Department` 业务表中）。
  - 写 `Department`，同步写/更新 `RbacDepartment`，并触发 `bgaMetaService.initBgaParams(deptId)` 初始化。
- 更新 `updateDepartment`：
  - 更新 `Department` 后同步 `RbacDepartment` 名称与标识。
- 删除 `deleteDepartment`：
  - 仅对 `Department` 做软删除；并限制至少保留一个部门。

### 4.5 权限计算 `UserQuery.getPermissionIds(accountId, deptId)`
1. 先取账号在部门内的直接权限（`RbacDepartmentAccountPermission`）。
2. 再取账号在部门内绑定角色（`RbacDepartmentAccountRole`）。
3. 用 BFS 沿 `RbacRoleRole(parent->child)` 递归扩展子角色。
4. 汇总所有角色的 `RbacRolePermission`，与直接权限取并集。

## 5. 事务与回滚关键点
- `@Transactional` 覆盖部门更新与删除、角色权限等写操作。
- `addAccount` 通过 `LockExecutor.execute` 管理创建事务。
- `changePassword`、`resetPassword` 通过 `LockExecutor.execute` 取得目标账号锁，
  在同一事务中读取当前密码哈希、校验并写入，与账号增删改共享锁。
- `updateAccount`、`deleteAccount` 通过 `LockExecutor.executePlanned` 管理动态科室锁与事务。
- `addDepartment` 通过 `LockExecutor` 执行科室创建事务，成功后再初始化血气参数。
- 账号新增和更新均先完成业务校验，再修改实体；持久化异常触发整个事务回滚。

## 6. 跨表一致性与数据模型要点
- 账号信息双表：
  - `RbacAccount`：鉴权核心（账号名、密码哈希）。
  - `Account`：业务资料（性别、生日、电话等，软删除字段）。
- 部门关系双表：
  - `RbacDepartmentAccount`：RBAC 主关系。
  - `DepartmentAccount`：业务关系（软删除、primaryRoleId、startDate）。
- 部门主角色关系：
  - `RbacDepartmentAccount.primaryRoleId` + `RbacDepartmentAccountRole` 联动维护。
- 删除策略混合：
  - RBAC 多为硬删。
  - `Account` / `Department` / `DepartmentAccount` 多为软删。

## 7. 索引建议关键词（给 Codex）
- 账号管理：`addAccount`, `updateAccount`, `deleteAccount`, `UserMapper.toRbacAccount`, `UserMapper.toAccount`
- 部门管理：`addDepartment`, `updateDepartment`, `deleteDepartment`, `getDepartmentId`
- 权限计算：`getPermissionIds`, `getNonPrimaryRolePermissions`, `hasPermission`
- 角色权限：`addRolePermission`, `revokeRolePermission`, `addAccountPermission`, `revokeAccountPermission`
- 关键约束：`ONLY_ADMIN_IS_AUTHORIZED`, `PERMISSION_DENIED`, `CANNOT_DELETE_SELF_ACCOUNT`, `DEPARTMENT_AT_LEAST_ONE`

## 8. 快速定位（行号）
- `UserService`：
  - `getUserInfo`：66
  - `addDept`：168
  - `addAccount`：343
  - `updateAccount`：388
  - `deleteAccount`：431
  - `resetPassword`：509
  - `getPermissions`：546
- `UserCommand`：
  - `addAccount`：73
  - `updateAccount`：166
  - `deleteAccount`：319
  - `addDepartment`：424
  - `resetPassword`：400
  - `addAccountPermission`：632
- `UserMapper`：
  - `toRbacAccount`：15
  - `toAccount`：22
- `UserQuery`：
  - `getAllAccounts`：138
  - `getAllDeptInfo`：303
  - `getNonPrimaryRolePermissions`：331
  - `getPermissionIds`：109

## 9. 实现注意点（索引风险提示）
- `UserService` 中 `addRolePermission` / `revokeRolePermission` / `addAccountPermission` / `revokeAccountPermission` 仅校验登录态，未在该层显式校验业务权限。
- `resetPassword` 的 Service 和命令层均按 `UserQuery.ADMIN_ACCOUNT_ID` 校验操作者。
- 改密保留现有的客户端编码输入和历史原文输入兼容；重置密码仍恢复为账号编码。
- 账号与部门删除是“软硬混合”策略，检索删除问题时需同时看 RBAC 表和业务表。
