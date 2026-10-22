# iter-18 · 真实 SMTP 联通与 EmailChannel 认证缺陷修复

- **日期**：2026-10-22
- **分支**：`bugfix/email-smtp-auth` → `master`
- **类型**：fix（真实环境验证发现的缺陷）
- **关联**：ISSUE-016（新增并关闭）、journal/2026-10-22

## 1. 背景

用户提供真实 163 邮箱（SMTP SSL）要求验证「最基本的邮件提醒」。
验证方式：先用临时程序直连 `EmailChannel`，再通过 sample 应用走完整管线。

## 2. 问题点（含证据）

| # | 问题 | 证据 | 处理 |
| --- | --- | --- | --- |
| 1 | **EmailChannel 从未向 SMTP 提交凭据** | 首次真实发送：`ChannelAuthException: SMTP authentication failed`；Python `smtplib` 同一账号密码 `235 Authentication successful` | `Session.getInstance(props, authenticator(properties))`，有用户名时提供 `PasswordAuthentication` |
| 2 | 现有契约测试用静态 mock 拦截 `Transport.send`，覆盖不到认证 | `EmailChannelTest` 6 个用例全绿但真实环境失败 | 新增 2 个用例，通过 `requestPasswordAuthentication` 断言有/无用户名时的凭据行为 |
| 3 | 无用户名的匿名中继不能被迫带凭据 | 部分内网 SMTP 允许匿名 | `username` 为空时 `authenticator` 返回 null（保持原行为） |

**根因**：JavaMail 的 `Transport.send(message)` 不接收用户名/密码，凭据只能来自
`Session` 的 `Authenticator`；只设 `mail.smtp.auth=true` 只会让服务器要求认证，却没人应答。

## 3. 改动点

- `EmailChannel`：
  - 新增 `authenticator(EmailChannelProperties)`；
  - 构造 `Session` 时传入（用户名非空才传）；
  - 新增包级 `session()` 访问器，仅用于测试断言。
- `EmailChannelTest`：新增 2 个用例（有凭据 / 无凭据）。
- 测试数：email 6 → 8；全量 186 → 188。

## 4. 验证（真实环境）

**直连渠道**（临时程序，凭据走环境变量，未入库）：

```text
OK   pky1999@qq.com status=SUCCESS messageId=<32017212.0.1792674241345@DESKTOP-HQ0NUVI> cost=PT2.0305251S
OK   pkyit@qq.com   status=SUCCESS messageId=<370475881.1.1792674243223@DESKTOP-HQ0NUVI> cost=PT0.5656353S
RESULT=ALL_SENT
```

**完整链路**（sample + 真实管线，`GET /demo/notify`）：

```text
NOTIFYX_AUDIT ... scene=ORDER_CREATE_FAIL level=WARN channel=email receiver=pky1999@qq.com status=SUCCESS attempts=1 costMs=1269
NOTIFYX_AUDIT ... scene=ORDER_CREATE_FAIL level=WARN channel=email receiver=pkyit@qq.com   status=SUCCESS attempts=1 costMs=393
/actuator/health → UP
```

> 未验证：QQ 侧收件箱/垃圾箱是否可见（本机无法读取对方邮箱）。

## 5. 回滚

还原 `EmailChannel` 的 `Session` 构造（Authenticator → null）并删除 2 个测试即可；
建议保留修复。

## 6. 遗留

- 审计 level 为 WARN 而 YAML 场景配置为 ERROR，需单独复现（可能是命令行列表覆盖导致）。
- k8s 部署验证尚未进行。
