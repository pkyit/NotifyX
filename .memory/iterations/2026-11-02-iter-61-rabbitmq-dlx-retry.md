# iter-61 · RabbitMQ 死信队列（DLX + 多级 TTL 退避重试，producer 侧）

- **日期**：2026-11-02
- **分支**：`feat/rabbitmq-dlx-retry`
- **类型**：feat（设计书 §11.2「RabbitMQ DLX + 多级 TTL 队列」落地，producer 侧）
- **触发**：STATE.md 未实现清单未勾选项「RabbitMQ 死信队列（DLX + 多级 TTL）—— §11.2」；
  D-025 第 4 点曾把 DLX 声明推迟到 consumer 落地，本次兑现。

## 1. 问题

| # | 问题 | 证据 |
| --- | --- | --- |
| 1 | producer 只声明主队列，无死信/退避拓扑 | `RabbitMqNotifyBus.ensureChannel` 仅 `queueDeclare(queue, durable, excl, autodelete, null)` |
| 2 | §11.2 要求 `x-dead-letter-exchange` + `retry.{5s,1m,5m}` 多级 TTL，尚未落地 | D-025 第 4 点「producer 暂不声明 DLX」 |
| 3 | 退避计数机制缺失，两侧无法对齐重试次数 | 无 `x-retry-count` 头 |

## 2. 方案

**配置（RabbitMqBusProperties）**

- 新增嵌套类 `Retry`：`boolean enabled=false`、`String exchange="notifyx.retry"`；
  前缀 `notifyx.bus.rabbitmq.retry.*`。

**拓扑声明（RabbitMqNotifyBus.declareTopology）**

- 主队列：`queueDeclare(queue, true, false, false, args)`，启用重试时 `args` 含
  `x-dead-letter-exchange=<retry.exchange>`；
- 重试交换机：`exchangeDeclare(exchange, "direct", true)`；
- 3 级 TTL 队列（`RETRY_LEVELS = 5s/1m/5m`，固定 3 级不动态建队列）：
  每级 `queueDeclare(exchange + "." + label, durable, ...)`，args 含
  `x-message-ttl=<label 毫秒>` + `x-dead-letter-exchange=""` + `x-dead-letter-routing-key=<主队列>`，
  再 `queueBind(queue, exchange, label)`；
- 常量 `RETRY_COUNT_HEADER = "x-retry-count"`（producer/consumer 两侧一致，producer 发布不带头＝0）。

## 3. 验证

| 测试 | 用例 | 覆盖 |
| --- | --- | --- |
| `RabbitMqNotifyBusTest.shouldDeclareRetryTopology`（新） | 启用 retry 后 publish | 主队列 DLX 绑定、3 级 TTL 队列 args、exchange/binding 声明 |

模块级验证：

```text
mvn -B -ntp -pl notifyx-bus-rabbitmq -am test -Dtest=RabbitMqNotifyBusTest → BUILD SUCCESS（3 用例）
```

## 4. 兼容性

- 新增 `notifyx.bus.rabbitmq.retry.*` 配置，默认 `enabled=false`，行为与 1.0.x 一致；
- 幂等 `queueDeclare`/`exchangeDeclare`，重复声明不改既有资源。

## 5. 文档与门禁

- `docs/design.md`：§11.2 补「RabbitMQ 退避重试落地」段落；
- `README.md`：producer RabbitMQ 配置补 `retry.*` 与拓扑说明；
- `.memory/decisions.md`：新增 D-030（supersedes D-025 第 4 点）；
- `.memory/STATE.md`：勾选项 + §15。

## 6. 未纳入

- consumer 侧消费死信/重试（`RabbitMqEnvelopeConsumer` 重写、`IdempotencyStore.release`、
  `RabbitMqTopologyConfiguration`）：iter-62。

## 7. 回滚

单提交回滚；拓扑声明随 producer 禁用重试后自然回到「只声明主队列」。