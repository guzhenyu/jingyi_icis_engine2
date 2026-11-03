# 开发说明

## 项目结构

- 后端使用 Java 21、Spring Boot 3.3.7 和 Maven。
- 业务代码位于 `src/main/java`，配置位于 `src/main/resources`，测试位于 `src/test`。
- Protobuf 定义位于 `src/main/proto`；通过 Maven 生成代码，不手工修改 `target` 中的生成文件。

## 开发约定

- 遵循现有代码风格与分层，围绕当前任务做最小必要修改。
- Java 代码每行不超过 100 个字符。
- 注释用中文，变量名保持英文。
- 遵守用户指定的改动范围，需要扩大范围时先与用户讨论。
- 修改数据库访问逻辑时，验证 PostgreSQL 的实际行为，并运行相关测试。

### 公共工具复用

- 时间处理优先复用 `TimeUtils`，字符串处理优先复用 `StrUtils`。
- 复用前确认时区、精度和空值约定；已有工具无法满足需求时，优先扩展公共工具类。

### 业务锁与数据库访问

- 锁资源可直接确定时使用 `LockExecutor.execute`；需要预读数据确定时，使用
  `LockExecutor.executePlanned`。
- 动态锁的预读 Supplier 和锁后复查统一调用 `XXXJdbc`，通过 `JdbcTemplate`
  读取数据库，避免 JPA 一级缓存影响锁计划校验。
- `XXXJdbc` 放在 `repository/<业务模块>/` 下，提供静态查询方法；
  Service 负责锁与业务流程编排，实际业务数据访问使用 `JpaRepository`。
- 事务由 `LockExecutor` 统一管理，调用入口不添加外层 `@Transactional`。

## 测试

- 运行数据库测试前启动 Docker；测试使用 Testcontainers 自动管理 PostgreSQL 17 实例。
- 数据库测试继承 `TestsBase`，或在独立测试上下文中导入 `PostgreSqlTestConfiguration` 并启用 `test` 配置。
- 全量测试：`mvn test`；指定测试：`mvn -Dtest=测试类名 test`。

## 提交

- 提交标题和正文使用中文，说明改动及验证结果。
- 不提交 `target`、运行日志、临时文件、凭据或本地环境专用配置。
