# iter-28 · RabbitMQ Producer（MQ 模式）

- **日期**：2026-10-22
- **分支**：`feature/rabbitmq-bus` → `master`
- **类型**：feat（P3：MQ 总线第二实现）
- **关联**：D-025（新增）、§11.2、iter-25

## 1. 背景

ADR-006 的 `NotifyBus` SPI 已有 Kafka 实现；设计 §11.2 要求 RabbitMQ 也支持
（durable queue + persistent message + publisher confirm）。本轮补齐 producer 侧。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | RabbitMQ `Channel` 非线程安全 | 多线程并发 publish 会报 `Already closed`/帧交错 | `publish` 串行化（synchronized），保证 producer 正确性 |
| 2 | 需要知道消息是否真正落 broker | `basicPublish` 是异步的 | `confirmSelect` + `waitForConfirmsOrDie(timeout)`，失败抛 `BusException` |
| 3 | 不能因 broker 暂时不可用导致应用启动失败 | 设计允许 Redis/MQ 缺失时降级 | **懒连接**：首次 publish 才建连接；auto-config 测试不需要 broker |
| 4 | 消息需要可恢复 | 队列/消息默认非持久 | durable queue + `deliveryMode=2` + `messageId=dedupeKey` |
| 5 | 失败后连接可能半开 | 网络中断/confirm 超时 | 任何异常都关闭 channel/connection，下次 publish 重连 |
| 6 | 集群 advertised 地址与宿主机不同 | k8s 内 DNS 宿主机不可达 | 测试用 port-forward（宿主机 15672→容器 5672） |

## 3. 改动点

新增模块 `notifyx-bus-rabbitmq`（17 个模块）：

- `RabbitMqNotifyBus`：懒连接、durable queue、persistent JSON、publisher confirm、
  channel 串行化、失败关闭重连；
- `RabbitMqBusProperties`：host/port/username/password/vhost/queue/timeouts；
- `RabbitMqBusAutoConfiguration`：`mode=mq` + `notifyx.bus=rabbitmq` 条件装配；
- `deploy/k8s/rabbitmq.yaml`（阿里云 `rabbitmq:3.13.0`）；
- 测试（+5）：发布属性/confirm、失败包装、自动装配 2、真实 RabbitMQ live 1（默认跳过）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，17 模块，240 tests（13 个 live 默认跳过）
→ Spotless / Checkstyle / JaCoCo 全绿

k8s RabbitMQ 3.13.0（namespace notifyx-infra，k8s-node2）：
NOTIFYX_RABBITMQ_HOST=127.0.0.1 NOTIFYX_RABBITMQ_PORT=15672 \
  mvn -B -pl notifyx-bus-rabbitmq -am test -Dtest=RabbitMqBusLiveTest
→ Tests run: 1, Failures: 0
  publish 后 basicGet 到 messageId=sha256:rabbit-live、body scene=SCENE_LIVE
```

## 5. 回滚

删除 `notifyx-bus-rabbitmq` 模块、BOM/POM 条目与 k8s 清单即可；只影响选择 rabbitmq 的用户。

## 6. 下一步

- RabbitMQ consumer / 死信队列（`x-dead-letter-exchange` + 多级 TTL 队列，§11.2）。
- consumer 多实例分区消费与死信/重试。
- 信创 DDL、审计容量治理、k8s 生产化。
