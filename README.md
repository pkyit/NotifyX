# NotifyX

业务语义层告警通知平台 —— 一个可插拔、松耦合的 Spring Boot 3 告警 starter。

业务代码只描述「发生了什么」，投递到哪里、怎么投、失败了怎么办，全部交给可替换的组件决定。

## 特性

- **事件与通道解耦**：`AlertEvent` 是唯一数据契约，`AlertChannel` 是唯一出口，二者互不知晓实现。
- **SPI 扩展**：实现 `AlertChannel` 并注册成 Bean，自动被发现，无需改一行核心代码。
- **全量可替换**：所有自动装配的 Bean 都带 `@ConditionalOnMissingBean`，业务方可以接管调度器、限流器、模板。
- **内置通道**：日志通道（默认开）、通用 Webhook 通道（按需开）。
- **异步 + 背压**：独立线程池投递，队列满时回退到调用方线程，不静默丢告警。
- **故障隔离**：单个通道抛异常不影响其它通道，也不影响业务线程。
- **限流去重**：基于 `fingerprint` 的滑动窗口限流，抑制同一错误刷屏。
- **零侵入**：不依赖 AOP、不扫描注解，业务里显式调用 `AlertTemplate` 即可。

## 架构

```
业务代码 ──► AlertTemplate ──► AlertEvent
                                   │
                                   ▼
                           AlertDispatcher
                    过滤(minLevel) ─► 限流(fingerprint) ─► 线程池
                                   │
              ┌────────────────────┼────────────────────┐
              ▼                    ▼                    ▼
      LoggingAlertChannel   WebhookAlertChannel   你的通道(SPI)
```

## 模块

| 模块 | 说明 |
| --- | --- |
| `notifyx-core` | 事件模型、通道 SPI、调度器、限流器；不依赖 Spring |
| `notifyx-spring-boot-autoconfigure` | 配置属性、自动装配、内置通道 |
| `notifyx-spring-boot-starter` | 业务方唯一需要引入的依赖 |
| `notifyx-demo` | 最小可运行示例 |

## 快速开始

```xml
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>notifyx-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@RequiredArgsConstructor
@RestController
public class PayController {

    private final AlertTemplate alertTemplate;

    @PostMapping("/pay/callback")
    public void callback(@RequestBody PayCallback callback) {
        try {
            payService.handle(callback);
        } catch (Exception ex) {
            alertTemplate.error("支付回调失败")
                    .content(ex.getMessage())
                    .tag("biz", "payment")
                    .attribute("orderId", callback.orderId())
                    .send();
            throw ex;
        }
    }
}
```

## 配置

```yaml
notifyx:
  enabled: true
  source: ${spring.application.name}   # 事件来源
  min-level: WARN                      # 低于该级别直接丢弃
  async:
    enabled: true
    core-pool-size: 2
    max-pool-size: 8
    queue-capacity: 1024
    keep-alive: 60s
    thread-name-prefix: notifyx-
  rate-limit:
    enabled: true
    max-events: 10                     # 窗口内最多投递次数
    window: 1m                         # 按 fingerprint 计数
  channels:
    log:
      enabled: true
    webhook:
      enabled: false
      url: https://your-alert-gateway/hook
      connect-timeout: 2s
      timeout: 3s
      headers:
        X-Alert-Token: xxx
```

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `notifyx.enabled` | `true` | 总开关 |
| `notifyx.min-level` | `WARN` | 最低投递级别 |
| `notifyx.source` | `application` | 默认事件来源 |
| `notifyx.async.enabled` | `true` | 关闭后同步投递 |
| `notifyx.rate-limit.enabled` | `false` | 滑动窗口限流 |
| `notifyx.channels.log.enabled` | `true` | 日志通道 |
| `notifyx.channels.webhook.enabled` | `false` | Webhook 通道 |

## 扩展：自定义通道

```java
@Component
public class DingTalkChannel implements AlertChannel {

    @Override
    public String name() {
        return "dingtalk";
    }

    @Override
    public boolean supports(AlertEvent event) {
        return event.level().atLeast(AlertLevel.ERROR);   // 只接收 ERROR 及以上
    }

    @Override
    public void send(AlertEvent event) throws Exception {
        // 调用钉钉机器人；抛异常会被调度器隔离
    }
}
```

自定义通道会被 `ObjectProvider<AlertChannel>` 自动收集，按 `order()` 排序执行。

## 运行示例

```bash
mvn clean package
mvn -pl notifyx-demo spring-boot:run

curl "http://localhost:18080/demo/alert?level=ERROR&message=磁盘快满了"
curl "http://localhost:18080/demo/business-error"
```

日志通道的 logger 名称固定为 `ALERT`，可以单独配置输出文件、采集规则与告警。

## Roadmap

- [ ] 失败重试与死信队列
- [ ] Micrometer 指标（投递次数、失败次数、耗时）
- [ ] 钉钉 / 飞书 / 企业微信开箱通道
- [ ] 分布式限流（Redis）
- [ ] 基于注解的声明式上报（可选模块）

## License

[Apache License 2.0](LICENSE)
