# iter-06 · Webhook 渠道契约测试（§18.3 / §10.4）

- **日期**：2026-10-22
- **分支**：`test/webhook-contract` → `master`
- **类型**：test（补 §21.2 第 8 步验收）
- **关联**：ISSUE-010（部分关闭：webhook 完成，email 待补）

## 1. 背景

设计书要求「WireMock 契约测试固化为渠道行为」（§18.3、§21.2 第 8 步），
但 webhook 渠道此前只有实现，没有一行测试：HMAC 签名、状态码分类、白名单、超时全靠人眼。
签名一旦改错是**静默的安全失效**——通知照样发出去，只是接收方无法验真。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | 无任何渠道契约测试 | `git log -- notifyx-channel-webhook/src/test` 为空；JaCoCo 无该模块数据 | 新增 12 个契约测试 |
| 2 | 429 异常的 message 不含状态码 | 测试断言 `hasMessageContaining("429")` 失败：实际 `"webhook rate limited: ok"` | 生产代码改为 `webhook rate limited (429): ...` |
| 3 | 引 WireMock 会新增测试依赖 | — | 用 JDK 自带 `com.sun.net.httpserver.HttpServer` + 随机端口，零新增依赖 |
| 4 | 签名验证不能只断言「有 header」 | 需要独立复算 HMAC | 测试侧独立用 `Mac` 复算 `HMAC_SHA256(timestamp + "." + body)` 并逐字节比对 |

## 3. 改动点

**新增 `WebhookChannelTest`（12 个用例）**：

| 用例 | 覆盖点 |
| --- | --- |
| 发送签名报文 | JSON 字段（scene/level/title/receiver/attempt/params）、Content-Type/User-Agent/EventId/Attempt/幂等键、时间戳为 10 位、签名独立复算一致 |
| sign-enabled=false | 不出现签名头 |
| 自定义 header | 配置的 header 透传 |
| 429 / 5xx / 4xx | 分别映射 `ChannelRateLimitException` / `ChannelException` / `ChannelProtocolException` |
| expect-status 可配置 | 202 视为成功 |
| host 白名单 | 非白名单 host 拒绝 |
| 空 URL | 协议异常 |
| 读超时 | `ChannelTimeoutException` |
| 超长响应 | `rawResponse` 截断到 1024 |
| 无接收人/无 tags | JSON 里是 `null` 与 `{}`，不产生非法报文 |

**生产代码**：`WebhookChannel` 429 错误信息补状态码。

**POM**：webhook 模块 `jacoco.minimum=0.80`（实测 84.7%）。

## 4. 验证

```text
mvn -B clean verify
→ BUILD SUCCESS，11 模块，118 tests，0 failures
  webhook 12 个契约测试全绿
  JaCoCo：notifyx-channel-webhook 84.7%（门禁 80%）
  Spotless 0 / Checkstyle 0 / 其它模块门禁全部通过
```

## 5. 回滚

删除 `notifyx-channel-webhook/src/test/.../WebhookChannelTest.java` 与 POM 里的
`jacoco.minimum` 属性即可；生产代码的 message 改动可保留（纯可观测性改进）。

## 6. 下一步

- email 渠道契约测试：需要伪 SMTP（或只测配置绑定/报文组装），排在后续迭代。
- P0 收尾：ADR-001 alias artifact。
- P1：聚合/升级、失败落盘补偿。
