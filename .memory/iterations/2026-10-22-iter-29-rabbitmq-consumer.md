# iter-29 · RabbitMQ Consumer 与传输切换

- **日期**：2026-10-22
- **分支**：`feature/rabbitmq-consumer` → `master`
- **类型**：feat（P3：consumer 双传输）
- **关联**：D-026（新增）、§11.4、iter-26/27/28

## 1. 背景

iter-26 的 consumer 只消费 Kafka；iter-28 补齐 RabbitMQ producer 后，
consumer 需要能按部署选择 `kafka` 或 `rabbitmq`，并复用同一套幂等/责任链。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | Kafka/Rabbit 监听器不能同时启动 | 两个 `@KafkaListener`/`@RabbitListener` 会同时尝试连接 | 新增 `notifyx.consumer.transport=kafka\|rabbitmq`，监听器按条件装配（Kafka matchIfMissing） |
| 2 | 引入 `spring-boot-starter-jdbc` 后应用启动失败 | 日志：`Failed to configure a DataSource: 'url' attribute is not specified` | 移除 starter-jdbc；JDBC 幂等改用 `DriverManager` + `jdbc-url/user/password`，不触发 Boot DataSource 自动配置 |
| 3 | RabbitMQ JSON 消息体转换为 String 依赖 converter | `SimpleMessageConverter` 对 `application/json` 不保证 String | 监听器参数用 `Message`，显式 UTF-8 解码 body |
| 4 | 队列不存在时监听器起不来 | `@RabbitListener` 不会自动声明队列 | 约定 producer 先声明 durable 队列；文档写明启动顺序，后续补 `RabbitAdmin` 自动声明 |
| 5 | 重复消息仍要 ACK 丢弃 | at-least-once | 复用 `IdempotencyStore`，重复直接 return |

## 3. 改动点

- `RabbitMqEnvelopeConsumer`：`@RabbitListener` + `@ConditionalOnProperty(transport=rabbitmq)`，
  解码 → 幂等 → `NotifyKit.send`；
- `KafkaEnvelopeConsumer`：增加 `transport=kafka`（matchIfMissing）条件；
- `NotifyxConsumerApplication`：`@EnableRabbit`；
- `ConsumerProperties`：`transport`、`rabbitmq.queue`、`jdbc-url/user/password`；
- `JdbcIdempotencyStore`：改为 `DriverManager`，避免 DataSource 自动配置副作用；
- `application.yml`：transport/queue/jdbc 配置项；
- 测试（+2）：Rabbit 消费者正常/重复/坏报文；JDBC 测试改用 URL 构造。

## 4. 验证（真实 RabbitMQ）

```text
先由 producer 声明队列并投递两条相同 envelope（idempotencyKey=idem-rmq-consumer）
再启动 consumer：
  --notifyx.consumer.transport=rabbitmq
  --notifyx.consumer.rabbitmq.queue=notifyx-consumer-rmq
  --spring.rabbitmq.host=127.0.0.1 --spring.rabbitmq.port=15672

日志：
  NOTIFYX_AUDIT ... eventId=evt_rmq_consumer scene=SCENE_LIVE level=ERROR channel=log status=SUCCESS
  notifyx consumer duplicate rabbit message, ack: eventId=evt_rmq_consumer
```

即 RabbitMQ 链路也满足「重复投递只发一次」。

```text
mvn -B clean verify → BUILD SUCCESS，17 模块，242 tests（13 live 跳过）
Spotless / Checkstyle / JaCoCo 全绿
```

## 5. 回滚

删除 Rabbit consumer 与 transport 条件、恢复 JDBC 使用 DataSource 即可；
默认 `transport=kafka` 行为不变。

## 6. 下一步

- RabbitMQ 死信队列（`x-dead-letter-exchange` + 多级 TTL）与重试；
- consumer 多实例/分区消费；队列自动声明（`RabbitAdmin`）；
- 信创 DDL、审计容量治理、k8s 生产化。
