# iter-26 · Kafka Consumer 独立服务

- **日期**：2026-10-22
- **分支**：`feature/kafka-consumer` → `master`
- **类型**：feat（P3：consumer）
- **关联**：D-023（新增）、§11.4、iter-25

## 1. 背景

iter-25 的 `mode=mq` 只负责发布；设计 §11.4 要求独立 consumer 承担
「消费 → 幂等 → 完整责任链 → 渠道发送 → ACK」。本轮新增 `notifyx-consumer` 服务，
用真实 Kafka 验证「发布两次、只发一次通知」。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 需要幂等，但没有持久化存储 | §11.4 三档：Redis / DB / 内存 | 先实现内存 TTL + 容量上限（T0），Redis/JDBC 实现留到后续；文档警告重启会失效 |
| 2 | 内存幂等不能无界增长 | 公开仓库红线：缓存必须有上限 | `max-entries` + 惰性 TTL + 队列淘汰，超过容量淘汰最旧 10% |
| 3 | 单消费者内异步会打乱顺序 | 本地 `notifyx.async.enabled` 默认 true | consumer 配置 `async.enabled=false`，按分区同步处理 |
| 4 | 坏报文不能卡死分区 | 解码 JSON 可能失败 | 解码失败只 ERROR 日志并返回（ACK），不重试毒消息 |
| 5 | 重复消息需要 ACK 丢弃 | Kafka at-least-once | 幂等命中直接 return，容器提交 offset |
| 6 | consumer 需要完整管线配置 | 本地门面依赖场景/渠道 | `application.yml` 提供 `SCENE_LIVE`（log）示例；生产由部署配置覆盖 |

## 3. 改动点

新增模块 `notifyx-consumer`（16 个模块）：

- `NotifyxConsumerApplication`：Spring Boot 应用；
- `KafkaEnvelopeConsumer`：`@KafkaListener` 解码 `NotifyEnvelope` → 幂等 → `notifyKit.send(toEvent())`；
- `IdempotencyStore` + `MemoryIdempotencyStore`：TTL 24h、容量上限、惰性淘汰；
- `ConsumerProperties`：topic / idempotency-ttl / max-entries；
- `application.yml`：Kafka 连接、`group-id`、`auto-offset-reset=earliest`、`mode=local`、
  `async=false`、场景与 log 渠道示例；
- 打包为可执行 fat jar（`spring-boot-maven-plugin`）。

**测试（6）**：

| 测试 | 覆盖 |
| --- | --- |
| `MemoryIdempotencyStoreTest`（3） | 首次/重复、空键、容量淘汰 |
| `KafkaEnvelopeConsumerTest`（3） | 正常解码投递、重复只投一次、坏 JSON 不抛 |

## 4. 验证（真实 Kafka）

```text
k8s Kafka 4.3.1 + port-forward 19092
java -jar notifyx-consumer-1.0.0-SNAPSHOT.jar --notifyx.consumer.topic=notifyx-consumer-live
→ Started NotifyxConsumerApplication，订阅 3 个分区

向 topic 发送两条完全相同的 envelope（idempotencyKey=idem-live-consumer）：
→ NOTIFYX_AUDIT ... eventId=evt_live_consumer scene=SCENE_LIVE level=ERROR channel=log status=SUCCESS
→ notifyx consumer duplicate message, ack: eventId=evt_live_consumer

即：真实 MQ 链路上「重复投递只发一次通知」成立。
```

## 5. 回滚

删除 `notifyx-consumer` 模块与父 POM 条目即可；不影响 SDK / producer。

## 6. 下一步

- Redis / JDBC 幂等实现（`nx_idempotency` 表）与 consumer 多实例/分区消费。
- RabbitMQ bus；死信队列与重试策略。
- consumer 的 Dockerfile / k8s Deployment。
