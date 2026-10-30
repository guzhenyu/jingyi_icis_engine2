# 开发说明

## 项目结构

- 后端使用 Java 21、Spring Boot 3.3.7 和 Maven。
- 业务代码位于 `src/main/java`，配置位于 `src/main/resources`，测试位于 `src/test`。
- Protobuf 定义位于 `src/main/proto`；通过 Maven 生成代码，不手工修改 `target` 中的生成文件。

## 开发约定

- 遵循现有代码风格与分层，围绕当前任务做最小必要修改。
- Java 代码每行不超过 100 个字符。
- 遵守用户指定的改动范围，需要扩大范围时先与用户讨论。
- 修改数据库访问逻辑时，验证 PostgreSQL 的实际行为，并运行相关测试。

## 测试

- 运行数据库测试前启动 Docker；测试使用 Testcontainers 自动管理 PostgreSQL 17 实例。
- 数据库测试继承 `TestsBase`，或在独立测试上下文中导入 `PostgreSqlTestConfiguration` 并启用 `test` 配置。
- 全量测试：`mvn test`；指定测试：`mvn -Dtest=测试类名 test`。

## 提交

- 提交标题和正文使用中文，说明改动及验证结果。
- 不提交 `target`、运行日志、临时文件、凭据或本地环境专用配置。
