# iter-62 · consumer 多实例分区消费与死信/重试（RabbitMQ）

- **日期**：2026-11-02
- **分支**：`feat/consumer-rabbitmq-retry-dead-letter`
- **类型**：feat（设计书 §11.4「consumer 幂等 + 死信/重试」、§11.2「DLX 多级 TTL」消费侧落地）
- **触发**：STATE.md 未实现清单未勾选项「consumer 多实例分区消费与死信/重试 —— §11.4」；
  D-026 第 4/5 点曾把「队列假定已存在」与「DLX/重试不在本轮」推迟，本次兑现。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| 1 | `@RabbitListener` 自动声明队列 args 与 producer 声明不一致，触发 `PRECONDITION_FAILED` | spring-amqp 默认声明无 DLX args，与 producer 的 `x-dead-letter-exchange` 冲突 |
| 2 | consumer 失败后直接抛异常，无退避重试，也无死信收敛 | `RabbitMqEnvelopeConsumer` 无 `x-retry-count` 分支 |
| 3 | 幂等键在 dispatch 前即 `firstSeen` 记录，失败重投会被当重复 ACK 丢弃 | `IdempotencyStore` 无 `release` |
| 4 | consumer 无 RabbitMQ 重试配置项 | `ConsumerProperties` 缺 `rabbitmq.*` |

## 2. 方案

**配置（ConsumerProperties）**

- 新增嵌套类 `Rabbitmq`：`String queue="notifyx-events"`、`boolean retryEnabled=false`、
  `String retryExchange="notifyx.retry"`；前缀 `notifyx.consumer.rabbitmq.*`。

**幂等 release（IdempotencyStore + 实现）**

- SPI 新增 `default void release(String key) {}`；
- `MemoryIdempotencyStore`：`seen.remove(key)`；
- `RedisIdempotencyStore`：`template.delete(PREFIX + key)`（异常吞掉告警）；
- `JdbcIdempotencyStore`：`delete from nx_idempotency where idempotency_key = ?`。

**拓扑声明（RabbitMqTopologyConfiguration，新建）**

- `@Configuration @ConditionalOnProperty(prefix="notifyx.consumer", name="transport", havingValue="rabbitmq")`；
- `@Bean("notifyxRabbitMainQueue") Queue`：`QueueBuilder.durable(queue)`，retry 开启时
  `.deadLetterExchange(retryExchange)`；
- `@Bean @ConditionalOnProperty(prefix="notifyx.consumer.rabbitmq", name="retry-enabled", havingValue="true") Declarables`：
  1 个 `DirectExchange` + 3 级 TTL 队列（`.ttl(ms).deadLetterExchange("").deadLetterRoutingKey(主队列)`）+ 3 绑定；
- 与 producer（iter-61）声明等价拓扑，规避 `PRECONDITION_FAILED`。

**死信/重试（RabbitMqEnvelopeConsumer 重写）**

- `@RabbitListener(queues = "#{notifyxRabbitMainQueue.name}")`，方法 `onMessage(Message, Channel)`；
- 解码失败 → 记录并 ACK 丢弃；`firstSeen` 失败 → 重复 ACK 丢弃；
- `send` 成功 → 正常 ACK；
- 失败且 `retryEnabled && retryCount < 3` → `release(key)` + `basicPublish` 回重试交换机
  （routing key = `5s/1m/5m`，`x-retry-count=retryCount+1`，`deliveryMode=2`）并 ACK；
- 否则抛 `AmqpRejectAndDontRequeueException`，进入死信。

## 3. 验证

| 测试 | 用例 | 覆盖 |
| --- | --- | --- |
| `RabbitMqEnvelopeConsumerTest`（重写） | dispatch 一次 / 坏报文 / 关重试拒绝 / 回投重试 / 达上限拒绝 | 死信/重试核心路径 + 幂等 release |
| `RabbitMqTopologyConfigurationTest`（新） | 主队列 DLX、retry 拓扑（1 exchange + 3 queue + 3 binding、TTL args） | 拓扑等价声明 |

模块级验证：

```text
mvn -B -ntp -pl notifyx-consumer -am test → BUILD SUCCESS（21 用例 + 1 live 跳过）
```

## 4. 兼容性

- `retryEnabled=false`（默认）时 consumer 行为与 1.0.x 一致（失败即拒绝不重排队）；
- `IdempotencyStore.release` 为 default 方法，自定义实现无需改动；
- `@RabbitListener` 改为引用 Queue bean，用户自定义队列名仍经 `notifyx.consumer.rabbitmq.queue` 生效。

## 5. 文档与门禁

- `docs/design.md`：§11.4 补「RabbitMQ 死信/重试语义」，`notifyx.consumer.lag` 标注未实现；
- `README.md`：Consumer 补 `retry-*` 配置与死信/重试说明；
- `.memory/decisions.md`：D-030（supersedes D-026 第 4/5 点）；
- `.memory/STATE.md`：勾选项 + §15。

## 6. 未纳入

- `notifyx.consumer.lag` 堆积指标：consumer 无 micrometer 依赖，lag 需真实 broker + 管理插件统计，
  原因见 STATE.md §15；多传输桥接（Kafka→Rabbit）：D-026 复审条件，暂无需求。

## 7. 回滚

单提交回滚；拓扑声明按 `retry-enabled` 开关自动收敛为「仅主队列」。