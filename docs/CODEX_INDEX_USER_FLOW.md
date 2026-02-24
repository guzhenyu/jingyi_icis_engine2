# User 模块流程与关键点（Codex 索引版）

## 1. 作用范围
- 文件范围：
  - `src/main/java/com/jingyicare/jingyi_icis_engine/service/users/UserService.java`
  - `src/main/java/com/jingyicare/jingyi_icis_engine/service/users/UserBasicOperator.java`
- 模块分层：
  - `UserService`：接口编排层（JSON 转 Proto、登录态、权限判断、返回码封装）。
  - `UserBasicOperator`：事务与数据层（RBAC/业务表读写、跨表一致性、状态码返回）。

## 2. 总体调用流程（高频模式）
1. `UserService` 入口方法接收 `xxxReqJson`。
2. `ProtoUtils.parseJsonToProto(...)` 解析请求，失败返回 `PARSE_JSON_FAILED`。
3. `getCtxAccountId()` 获取当前登录账号，空则返回 `NOT_LOGGED_IN`。
4. 按功能执行授权校验：
   - 管理员校验：`accountId.equals(ADMIN_ACCOUNT_ID)`。
   - 权限校验：`hasPermission(opDeptId, Consts.PERM_ID_CONFIG_CHECKLIST)`。
5. 调用 `UserBasicOperator` 执行核心逻辑。
6. `protoService.getReturnCode(statusCode)` 统一包装响应。

## 3. 入口方法 -> 核心能力映射

### 3.1 用户与账号
- `getUserInfo` -> `getRbacAccount` / `getAllDeptInfoByAccountId` / `getPermissionIds` / `getPrimaryRoleName`
  - 额外依赖：`menuService.getMenuGroups`、`certificateService.checkBedAvailable`。
- `getAllAccounts` -> `getAllAccounts(queryDeptId, queryAccountId, queryAccountName, zoneId)`
  - 结果会过滤管理员账号（`ADMIN_ACCOUNT_ID`）。
- `addAccount` -> `addAccount(IcisAccountPB, modifiedBy)`
  - 需要 `PERM_ID_CONFIG_CHECKLIST`。
- `updateAccount` -> `updateAccount(IcisAccountPB, modifiedBy)`
  - 更新自己时可跳过权限校验；更新他人需权限。
- `deleteAccount` -> `deleteAccount(accountId, deptId, modifiedBy)`
  - 需要权限，且禁止删除自己（`CANNOT_DELETE_SELF_ACCOUNT`）。
- `changePassword` -> `changePassword(accountId, oldPassword, newPassword)`。
- `resetPassword` -> `resetPassword(targetAccountId, operatorAccountId, ADMIN_ROLE_ID)`
  - 需权限，底层再次校验操作者是否拥有管理员角色。

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

## 4. `UserBasicOperator` 关键流程

### 4.1 账号新增 `addAccount(IcisAccountPB, modifiedBy)`
1. `checkAddAccount`：校验 `RbacAccount` 与 `Account` 不重复。
2. 校验每个 `department(role)` 存在性（`checkDepartmentAndRole`）。
3. `addAccountImpl` 同时写入：
   - `RbacAccount`（含密码哈希）。
   - `Account`（基础资料，`isDeleted=false`）。
4. 遍历部门调用 `addDepartmentAccount` 建立账号-部门-角色关系。
5. 任一步失败设置事务回滚并返回失败码。

### 4.2 账号更新 `updateAccount(IcisAccountPB, modifiedBy)`
1. 更新 `Account` 基础资料与 `RbacAccount.accountName`。
2. 对比请求部门列表与当前部门关系，拆分三类：
   - 新增部门关系。
   - 主角色变更。
   - 需删除的部门关系。
3. 分别执行新增/更新/删除，任一步失败回滚。

### 4.3 账号删除 `deleteAccount(accountId, deptId, modifiedBy)`
- 指定 `deptId`：仅删除该部门下的账号关联（不删账号主体）。
- `deptId` 为空：执行整账号删除流程：
  - 删除 `RbacDepartmentAccountRole` / `RbacDepartmentAccountPermission` / `RbacDepartmentAccount` / `RbacAccountRole`。
  - 业务关联 `DepartmentAccount` 做软删除。
  - `Account` 软删除；`RbacAccount` 实体删除。

### 4.4 部门新增/更新/删除
- 新增 `addDepartment`：
  - 校验 `deptId` 和 `deptName` 唯一（在 `Department` 业务表中）。
  - 写 `Department`，同步写/更新 `RbacDepartment`，并触发 `monitoringConfig.getBgaParamList(deptId)` 初始化。
- 更新 `updateDepartment`：
  - 更新 `Department` 后同步 `RbacDepartment` 名称与标识。
- 删除 `deleteDepartment`：
  - 仅对 `Department` 做软删除；并限制至少保留一个部门。

### 4.5 权限计算 `getPermissionIds(accountId, deptId)`
1. 先取账号在部门内的直接权限（`RbacDepartmentAccountPermission`）。
2. 再取账号在部门内绑定角色（`RbacDepartmentAccountRole`）。
3. 用 BFS 沿 `RbacRoleRole(parent->child)` 递归扩展子角色。
4. 汇总所有角色的 `RbacRolePermission`，与直接权限取并集。

## 5. 事务与回滚关键点
- `@Transactional` 覆盖大多数写操作（账号、部门、角色权限管理）。
- 显式回滚点集中在账号新增/更新流程：
  - `addAccount(IcisAccountPB, ...)` 中部门关联失败会 `setRollbackOnly()`。
  - `updateAccount(...)` 中账号实体缺失、部门新增失败、角色更新失败会回滚。

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
- 账号管理：`addAccount`, `updateAccount`, `deleteAccount`, `checkAddAccount`, `addAccountImpl`
- 部门管理：`addDepartment`, `updateDepartment`, `deleteDepartment`, `getDepartmentId`
- 权限计算：`getPermissionIds`, `getNonPrimaryRolePermissions`, `hasPermission`
- 角色权限：`addRolePermission`, `revokeRolePermission`, `addAccountPermission`, `revokeAccountPermission`
- 关键约束：`ONLY_ADMIN_IS_AUTHORIZED`, `PERMISSION_DENIED`, `CANNOT_DELETE_SELF_ACCOUNT`, `DEPARTMENT_AT_LEAST_ONE`

## 8. 快速定位（行号）
- `UserService`：
  - `getUserInfo`：110
  - `addDept`：208
  - `addAccount`：354
  - `updateAccount`：389
  - `deleteAccount`：426
  - `resetPassword`：495
  - `getPermissions`：538
- `UserBasicOperator`：
  - `getAllAccounts`：74
  - `addAccount(IcisAccountPB, ...)`：217
  - `updateAccount`：244
  - `deleteAccount`：336
  - `addDepartment`：471
  - `addDepartmentAccount`：579
  - `getNonPrimaryRolePermissions`：769
  - `getPermissionIds`：1014

## 9. 实现注意点（索引风险提示）
- `UserService` 中 `addRolePermission` / `revokeRolePermission` / `addAccountPermission` / `revokeAccountPermission` 仅校验登录态，未在该层显式校验业务权限。
- `resetPassword` 是双重门禁：
  - `UserService` 先做 `PERM_ID_CONFIG_CHECKLIST` 权限校验。
  - `UserBasicOperator.resetPassword` 再校验操作者是否具备 `ADMIN_ROLE_ID`。
- 账号与部门删除是“软硬混合”策略，检索删除问题时需同时看 RBAC 表和业务表。
