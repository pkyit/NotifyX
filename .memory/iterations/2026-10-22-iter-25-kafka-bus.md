# iter-25 · Kafka producer（MQ 模式 producer-only）

- **日期**：2026-10-22
- **分支**：`feature/mq-kafka` → `master`
- **类型**：feat（P3：MQ 总线）
- **关联**：D-022（新增）、§11、§6.5、iter-22/23/24

## 1. 背景

设计 ADR-006 要求 `notifyx.mode=mq` 时 SDK 退化为 producer-only：序列化 `NotifyEnvelope` →
发 MQ → 失败本地落盘；消费、去重、限流、聚合、渲染全部由独立 consumer 承担。
本轮先做 Kafka producer 与模式切换，consumer 留到下一轮。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | MQ 模式不能走本地管线 | 设计 §11.3：producer 只做序列化/发送/落盘 | 新增 `MqNotifyKit`，接管 `NotifyKit`，完全绕过 pipeline/渠道 |
| 2 | 本地与 MQ 门面 Bean 冲突 | `notifyxNotifyKit` 是无条件 `@ConditionalOnMissingBean` | 本地 Bean 增加 `mode=local`（matchIfMissing），MQ Bean 用 `mode=mq`，条件互斥 |
| 3 | 回调无法跨进程 | `onSuccess/onError` 是 `transient` | MQ 门面检测到回调只 warn，不阻断；文档写明 |
| 4 | Kafka producer 配置非法 | 首次建 Bean 报 `delivery.timeout.ms should be equal to or larger than linger.ms + request.timeout.ms` | 显式 `request.timeout.ms=5000`、`retries=3`，delivery 默认 10s |
| 5 | 孤立模块跑测试用了旧 core jar | `-pl notifyx-bus-kafka` 编译找不到 `NotifyBus` | 用 `-am` 或先 install（记录到 env 注意事项） |
| 6 | 本机 port-forward 访问 Kafka | advertised 若写集群 DNS，宿主机无法解析 | 测试部署 advertised=`localhost:19092`，port-forward 19092→9092 |

## 3. 改动点

**core**：

- `NotifyBus` SPI（name/publish/publishBatch/close）+ `BusException`；
- `MqNotifyKit`：`mode=mq` 的门面，构造 `NotifyEnvelope`（`idempotencyKey=eventId`、
  `dedupeKey=指纹`、trace/instance），`bus.publish`，失败写 `FailureStore` 并回调 `onError`；
  `send/sendAsync/alarm` 全部支持；
- `NotifyEnvelope.of(...)` 增加显式幂等键重载（MQ 用 eventId，重投稳定）。

**autoconfigure**：

- 本地门面加 `mode=local`（matchIfMissing=true）；
- 新增 `mode=mq` 的 `MqNotifyKit` Bean（注入 `NotifyBus`/`FailureStore`/`NotifyMetrics`）。

**新模块 `notifyx-bus-kafka`**：

- `KafkaNotifyBus`：key=`dedupeKey` 保序，value=报文 JSON；`acks=all` + `enable.idempotence=true`；
  `send().get(timeout)`，失败抛 `BusException`；
- `KafkaBusProperties`：bootstrap-servers/topic/acks/idempotence/delivery-timeout；
- 自动装配条件：`notifyx.mode=mq` + `notifyx.bus=kafka` + classpath 有 `KafkaProducer`；
- `deploy/k8s/kafka.yaml`（KRaft 单节点，阿里云镜像）。

**测试（+9）**：

| 测试 | 覆盖 |
| --- | --- |
| `MqNotifyKitTest`（3） | 发布 envelope、失败落盘、alarm |
| `KafkaNotifyBusTest`（3） | key/value、失败包装、close |
| `KafkaBusAutoConfigurationTest`（2） | mq+kafka 装配、local/其他 bus 回退 |
| `KafkaBusLiveTest`（1，默认跳过） | 真实 Kafka 发布→消费 |

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，15 模块，226 tests（11 个 live 默认跳过）
→ Spotless / Checkstyle / JaCoCo 全绿

k8s Kafka 4.3.1（namespace notifyx-infra，k8s-node3）：
NOTIFYX_KAFKA_BOOTSTRAP=127.0.0.1:19092 \
  mvn -B -pl notifyx-bus-kafka -am test -Dtest=KafkaBusLiveTest
→ Tests run: 1, Failures: 0
  发布 key=sha256:live、value 含 scene=SCENE_LIVE，consumer 按 earliest 消费到同一报文
```

## 5. 回滚

删除 `notifyx-bus-kafka`、core 的 `NotifyBus`/`MqNotifyKit`、autoconfigure 的 MQ Bean 即可；
默认 `mode=local` 行为不变。

## 6. 下一步

- `notifyx-consumer`：消费 Kafka、幂等（Redis `SET NX` / `nx_idempotency` / 内存）、
  重建事件走完整管线、ACK/死信。
- RabbitMQ bus（镜像 `rabbitmq:3.13.0` 已具备）。
- `nx_idempotency` DDL 与 retention。
