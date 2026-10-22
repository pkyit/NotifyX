# iter-18 · 架构审计与仓库工作说明

- **日期**：2026-10-22
- **分支**：`master`
- **类型**：docs（无业务代码改动）

## 1. 变更内容

1. 新增 `AGENTS.md`：沉淀仓库定位、构建命令、模块依赖纪律、实现红线、质量门禁、文档与 Git 规则。
2. 新增 `docs/architecture-review-2026-10-22.md`：从架构师视角审计 P0/P1 SDK 的可靠性语义、失败补偿、安全脱敏、配置契约、观测性与生命周期问题，并给出 P0/P1/P2 修复路线。
3. 新增 `.editorconfig`，补充 `.gitignore` 的 `*.log.gz` 与 `/.workbuddy/` 忽略规则。
4. 更新 `.memory/issues.md`，登记 ISSUE-018 / ISSUE-019。

## 2. 验证

- `mvn -pl notifyx-spring-boot3-autoconfigure -am -Dtest=NotifyxHealthIndicatorTest "-Dsurefire.failIfNoSpecifiedTests=false" test`
  - 结果：6 / 6 通过。
- 此前在同一 HEAD 上执行 `mvn -B -ntp verify`
  - 结果：12 个模块、186 个测试全部通过，Spotless / Checkstyle / JaCoCo 全绿。
- 本迭代未修改 Java / POM 代码，无需重新跑全量构建。

## 3. 回滚方式

- 直接 revert 本提交即可；不影响运行时行为。