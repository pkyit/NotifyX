# iter-33 · Webhook 契约与异常分类修复

- **日期**：2026-10-22
- **分支**：`bugfix/iter-33-webhook-contract` → `master`
- **类型**：fix（渠道契约 + 错误分类 + 回归测试）
- **触发**：夜间自动化第二轮；继续处理 `docs/architecture-review-2026-10-22.md` 的 A09。

## 1. 问题

| # | 问题 | 证据/影响 |
| --- | --- | --- |
| 1 | `jsonMap()` 对所有 value 执行 `String.valueOf()`，嵌套 Map/List 被拍平成字符串 | `WebhookChannel.java` 原 `jsonMap`；接收方拿到的是 `"{id=42}"` 而不是 JSON object，嵌套参数语义丢失 |
| 2 | 用户 `headers` 在框架头之后设置，可覆盖 `Content-Type` / `X-Notifyx-Signature` / 幂等键 | 原 `properties.getHeaders().forEach(request::header)`；可伪造签名头或让签名与实际 Content-Type 不一致 |
| 3 | `URI.create()` / `HttpRequest.Builder.header()` 的 `IllegalArgumentException` 直接外抛 | 未进入 `ChannelException` 体系，管线只能按“意外异常”处理；错误分类不明确 |
| 4 | `sign()` 在 HMAC 不可用时抛 `IllegalStateException` | 同上，且被当成可重试 |
| 5 | URL scheme 未校验、host 白名单大小写敏感 | `file://` 等非 HTTP scheme 直到发送才失败；`HOOKS.EXAMPLE.COM` 会被白名单误拒 |

## 2. 修复

- 新增递归 `jsonValue(Object)`：支持 `String` / `Number` / `Boolean` / `Map` / `Iterable` / 数组 / `null`；
  嵌套结构保持 JSON object/array，不再 `String.valueOf`。
- 框架保留头改为**先过滤用户头、后写框架头**：`Content-Type`、`User-Agent`、`Host`、`Content-Length`、
  `Connection`、`Transfer-Encoding` 与全部 `X-Notifyx-*` 不允许业务覆盖（大小写不敏感）。
- `URI.create`、非法 header 名/值、`HttpClient.send` 的 `IllegalArgumentException` 统一转
  `ChannelProtocolException`；HMAC 不可用同样转 `ChannelProtocolException`（不可重试）。
- URL 只允许 `http` / `https`，必须有 host；白名单比较改为 `equalsIgnoreCase`。

## 3. 验证

```text
$env:JDK_JAVA_OPTIONS='-Djdk.attach.allowAttachSelf=true'
mvn -B -ntp -pl notifyx-channel-webhook -am test -Dtest=WebhookChannelTest
→ 17/17 通过（原 12 + 新增 5）
```

新增用例：

- 嵌套 Map/List 输出为 JSON 结构；
- 业务 header 不能覆盖 `Content-Type` / `X-Notifyx-Event-Id` / `X-Notifyx-Signature`，自定义 `X-Tenant` 仍透传；
- 非法 URL → `ChannelProtocolException("invalid webhook url")`；
- 非 http/https scheme → `ChannelProtocolException("unsupported webhook scheme")`；
- 非法 header 值 → `ChannelProtocolException("invalid webhook request")`。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，269 用例（256 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 正常字符串参数、顶层字段、签名算法、状态码分类不变。
- 嵌套参数从“字符串化”变为“真实 JSON 结构”，属于修复；接收方若依赖旧的字符串形态需要同步调整
  （这是契约 bug，旧形态本身不是合法语义）。
- 业务 header 中与框架保留头同名的配置现在被忽略；其他自定义 header 不受影响。
- 非法 URL/header 从 `IllegalArgumentException` 变为 `ChannelProtocolException`，管线会按不可重试处理。

## 5. 未纳入

- A05 的模板渲染异常仍在 `sendOne()` 的 try 之外，下一轮处理；
- A02/A03/A04/A07 涉及契约变更，待用户确认；
- DLX/死信、consumer 多实例、信创 DDL、Admin 未动。

## 6. 回滚

单提交回滚；无 schema / 配置项变化。
