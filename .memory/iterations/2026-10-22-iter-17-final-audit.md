# iter-17 · 交付审计：P0/P1 SDK 范围收口

- **日期**：2026-10-22
- **分支**：`chore/final-audit` → `master`
- **类型**：docs（只更新记忆与状态，无代码改动）

## 1. 审计结论

设计书 **P0 + P1 的 SDK 部分**已完成且可验证；P2 起的范围（Redis/JDBC store、MQ 总线与
consumer、Admin 控制台、IM 渠道、sms4j、信创适配）不在本次交付内。

| 维度 | 证据 |
| --- | --- |
| 构建 | `mvn -B clean verify` → 12 模块 / **186 tests** / 0 failures |
| 质量门禁 | Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标 |
| 覆盖率 | core 79.8%（门禁 76%）、spring 92.2%（90%）、autoconfigure ≥62%（60%）、logback 85.2%（80%）、webhook 84.7%（80%）、email 79%（75%） |
| 发布产物 | `mvn -Prelease -Dgpg.skip=true package` → 双 starter 的 jar/sources/javadoc 齐全 |
| 双坐标 | `-boot3-` relocation 实测解析到 `notifyx-spring-boot-starter` |
| 仓库 | 工作区干净；`origin/master` = `dbd50f5`（iter-16 合并提交） |

## 2. 交付清单（按设计书章节）

| 章节 | 能力 | 迭代 |
| --- | --- | --- |
| §6 模型 / §9.1 指纹 | NotifyEvent、Level、SendResult、异常体系、ULID、SHA-256 指纹 | 基线（iter-00 前）+ iter-14 边界测试 |
| §9.2 漏斗 | 全局静默→场景路由→免打扰→去重→限流→用户 Filter | 基线 + iter-03 测试 |
| §9.5 聚合 | T0 实例内窗口聚合（默认关闭，跨实例诚实标注） | iter-08 |
| §9.6 升级 | 连续 N 次/窗口/upgrade-to/恢复场景/成功清零 | iter-09 |
| §9.7/9.8 熔断/重试 | 半开探针、指数退避、不可重试分类 | 基线 + iter-14 测试 |
| §9.9 失败落盘/补偿 | local-file JSON 存储 + 乐观锁 + 磁盘上限 + 自动补偿状态机 | iter-10 / iter-15 / iter-16 |
| §9.10 优雅关闭 | SmartLifecycle 排空 → 聚合汇总 → 渠道关闭 | iter-05 |
| §9.11 队列 | 优先级队列、drop-lowest、关闭拒绝 | 基线 + iter-13 测试 |
| §8.1 L0 | Logback Appender（T7 防自递归） | iter-01 |
| §8.3 L2 | @AlarmProtect/@NotifyOn + SpEL 安全默认 | 基线 + iter-12 测试 |
| §8.4 L3 | @NotifyxScene / 配置映射桥接 | iter-04 |
| §10 渠道 | log / email / webhook（签名/白名单/错误分类） | 基线 + iter-06/iter-11 契约测试 |
| §14 配置/诊断 | 全量 Properties + 启动能力表 | 基线 + iter-05 |
| §15.2 健康 | Actuator HealthIndicator（默认不探活） | iter-05 |
| §15.3 Trace | MDC → 模板/审计/渠道上下文 | iter-04 |
| §17.3 质量门禁 | Spotless/Checkstyle/JaCoCo/CI | iter-03 + 后续每轮抬升 |
| §19.1 发布链路 | release profile + GPG + central-publishing + 发布手册 | iter-02 |
| ADR-001 双坐标 | 主坐标 + alias relocation | iter-07 |

## 3. 记忆索引

- 决策：D-001 ~ D-018（`decisions.md`）
- 问题：ISSUE-001 ~ ISSUE-015（关闭/遗留状态见 `issues.md`）
- 迭代：iter-00 ~ iter-17（`iterations/`）
- 状态：`STATE.md`；环境：`env.md`

## 4. 遗留（不属于本次 P0/P1 SDK 范围）

| 项 | 状态 | 说明 |
| --- | --- | --- |
| 真实 Central 发布 | 阻塞 | 需交付方提供 Central 账号 + GPG 私钥；配置与本地验证已完成 |
| Redis store / 跨实例聚合 | P2 | T1 能力，依赖 Redis |
| JDBC store | P2 | 随信创库适配（MySQL/DM/Kingbase/openGauss） |
| MQ 三总线 + consumer | P3 | producer-only/consumer 模式 |
| Admin 控制台 | P4 | Vue3 + 配置 pull |
| IM 渠道 / sms4j | P2/P1 扩展 | 钉钉/企微/飞书、sms4j 适配 |
| 覆盖率目标 | OPEN | core 目标 90%（当前 79.8%）；T1–T24 剩余用例 |
| 文档去重 | OPEN | `docs/design.md` 与 `docs/NotifyX架构设计书.md` 内容重复（ISSUE-008） |

## 5. 复验命令

```bash
mvn -B clean verify
mvn -B -Prelease -Dgpg.skip=true -Dpackage clean package
git status --short && git log --oneline -5
```
