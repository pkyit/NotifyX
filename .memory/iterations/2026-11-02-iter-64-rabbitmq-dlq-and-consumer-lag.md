# iter-64 · RabbitMQ 死信路由修复与 consumer 堆积指标

- **日期**：2026-11-02
- **分支**：`fix/rabbitmq-dead-letter-and-consumer-lag`
- **类型**：fix + feat（修复 iter-61/62 的死信缺陷，兑现设计书 §11.4 `notifyx.consumer.lag`）
- **触发**：`mvn -B -ntp clean verify` 在 `notifyx-consumer` Spotless 阶段失败；复查 iter-61/62
  拓扑发现「进入死信」实际不可路由；STATE.md §15 仍挂账 `notifyx.consumer.lag`。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| 1 | 主队列只有 `x-dead-letter-exchange`，无 `x-dead-letter-routing-key`，无死信队列；重试耗尽后死信投递无匹配绑定，消息被 broker 丢弃 | `RabbitMqNotifyBus.declareTopology` 原 args 仅 1 项；重试 exchange 仅绑定 `5s`/`1m`/`5m` |
| 2 | 退避重投只带 `x-retry-count`，丢掉 `messageId` 与自定义头，链路不可追溯 | `RabbitMqEnvelopeConsumer.republishForRetry` 原 `headers(Map.of(...))` |
| 3 | `notifyx.consumer.lag`（§11.4 要求）未实现 | STATE.md §15「未纳入」；D-030 第 6 点 |
| 4 | `notifyx.consumer.transport` 只作为条件键，未进入配置模型 | `ConsumerProperties` 无 `transport` 字段 |
| 5 | 6 个新增 Java 文件缺少文件末尾换行，Spotless 失败 | `mvn -B -ntp clean verify` → `notifyx-consumer` Spotless `-}`/`+}` |
| 6 | `JdbcAuditRetention` Javadoc 仍称「DM/Kingbase 待适配」 | iter-60 已交付 dm/kingbase/opengauss DDL |
| 7 | README consumer 启动示例仍写 `notifyx-consumer-1.0.0.jar` | 当前版本 1.0.1 |

## 2. 方案

**死信收敛（producer + consumer 双侧等价拓扑）**

- 主队列（启用重试时）追加 `x-dead-letter-routing-key=dead`；
- 新增 durable 死信队列 `{retry.exchange}.dead`（默认 `notifyx.retry.dead`），以 routing key `dead`
  绑定到重试交换机；
- `RabbitMqNotifyBus.declareTopology` 与 `RabbitMqTopologyConfiguration` 同步声明；
- `retry-enabled=false` 时不声明任何死信/重试资源，保持 1.0.0 行为。

**重投保真**

- `republishForRetry` 复制原 headers 后覆盖 `x-retry-count`，保留 `messageId`；
  `contentType` 为空时回退 `application/json`，仍 `contentEncoding=UTF-8`、`deliveryMode=2`。

**堆积指标（D-031）**

- `ConsumerProperties` 新增 `transport`（默认 `kafka`）；
- 新增 `NotifyxConsumerLagMetrics`（`@Component`）：存在 `MeterRegistry` 时注册
  `notifyx.consumer.lag`，标签仅 `transport`/`queue`；
- RabbitMQ：`RabbitAdmin.getQueueProperties(queue)` 的 `QUEUE_MESSAGE_COUNT`（不依赖管理插件）；
- Kafka：AdminClient 懒创建，`describeTopics` → `listOffsets(latest)` → `listConsumerGroupOffsets`，
  按分区 `max(0, end - committed)` 求和，单次调用 2s 超时，组件销毁时 1s 关闭；
- 采样失败只 WARN 并沿用上次成功值（初始 0），不影响 `onMessage`；
- `notifyx-consumer` 增加 `io.micrometer:micrometer-core`（版本由 Boot BOM 管理）。

## 3. 验证

| 测试 | 用例 | 覆盖 |
| --- | --- | --- |
| `RabbitMqNotifyBusTest.shouldDeclareRetryTopology` | 主队列 2 个死信参数、4 队列、4 绑定、死信队列 | producer 拓扑 |
| `RabbitMqTopologyConfigurationTest` | 重试关闭无死信参数 / 重试开启死信参数 / 4 队列 4 绑定含 `dead` | consumer 拓扑 |
| `RabbitMqEnvelopeConsumerTest.shouldRepublishForRetry` | 保留 `messageId`、自定义头、`x-retry-count=1` | 重投保真 |
| `NotifyxConsumerLagMetricsTest`（7 个） | gauge 注册与标签、采样失败降级、RabbitMQ 消息数、`computeLag`、无 registry | lag 指标 |

模块级：`mvn -B -ntp -pl notifyx-bus-rabbitmq -am test`、`mvn -B -ntp -pl notifyx-consumer -am test`
均 BUILD SUCCESS。

全量：`mvn -B -ntp clean verify` → **BUILD SUCCESS**（17/17 模块，372 用例 / 13 跳过，
Spotless + Checkstyle + JaCoCo 全绿；consumer 28 用例、bus-rabbitmq 6 用例）。

## 4. 兼容性

- 默认（重试关闭）拓扑与 1.0.0 一致；
- 已按旧拓扑建过队列的环境（`x-dead-letter-exchange` 已存在）在 1.0.1 启用重试时，主队列参数新增
  `x-dead-letter-routing-key`，需要重建主队列（或先删除空队列），否则 `PRECONDITION_FAILED`；
  该场景仅影响 1.0.0 → 1.0.1 之间启用过重试的实验环境；
- `notifyx.consumer.lag` 需要部署方提供 `MeterRegistry`（如加 `spring-boot-starter-actuator`）。

## 5. 文档与门禁

- `docs/design.md` §11.2 拓扑补死信队列与死信路由键；§11.4 lag 指标改为已实现；
- `README.md` 死信队列 / lag 指标说明、jar 版本号修正；
- `.memory/decisions.md`：D-030 补充、新增 D-031；`.memory/issues.md`：ISSUE-053（CLOSED）；
- `.memory/STATE.md`：新增 §16。

## 6. 未纳入

- 真实 RabbitMQ 的 DLX 端到端冒烟：仍属 live 测试（需真实 broker）；
- Kafka lag 的真实集群校准（含 SSL bundle 场景）：当前 `buildAdminProperties(null)`，启用 SSL bundle 时
  采样降级为上次值而不影响消费，待有环境时补 `SslBundles` 注入。

## 7. 回滚

单提交回滚；拓扑按 `retry-enabled` 自动收敛为「仅主队列」，lag 指标在无 `MeterRegistry` 时不注册。
