# iter-38 · JsonCodec 边界健壮性修复

- **日期**：2026-10-23
- **分支**：`bugfix/iter-38-json-codec` → `master`
- **类型**：fix（零依赖 JSON 解析边界 + 回归测试）
- **触发**：20:00 夜间自动化巡检；审计 `JsonCodec` 的失败落盘/补偿解码路径。

## 1. 问题

`JsonCodec.read()` 是失败落盘、`NotifyEnvelope`、补偿器共用的解析入口，契约声明
“解析失败抛 `IllegalArgumentException`”，但两个边界不成立：

1. `\u` 转义不足 4 位时，`text.substring(index, index + 4)` 抛
   `StringIndexOutOfBoundsException`，不是 `IllegalArgumentException`；
2. 嵌套数组/对象没有深度上限，恶意或损坏的 envelope 可导致 `StackOverflowError`，
   这不是 `RuntimeException` 能安全兜住的错误（线程直接终止）。

## 2. 修复

- `\u` 解析前检查剩余长度，非法十六进制统一转
  `IllegalArgumentException("bad unicode escape ...")`；
- 解析器增加 `MAX_DEPTH = 64`，`parseValue(depth)` / `parseObject(depth)` /
  `parseArray(depth)` 逐层传递，超限抛 `IllegalArgumentException("json nesting too deep")`；
- 正常 JSON 的读写行为不变。

## 3. 验证

新增 2 个用例：

- `"\\u12"` → `truncated unicode escape`；`"\\uZZZZ"` → `bad unicode escape`；
  `"\\u4e2d"` 仍解析为 `中`；
- 100 层嵌套数组 → `json nesting too deep`，不会 `StackOverflowError`。

`JsonCodecTest` 6/6 通过（原 4 + 新增 2）。

全量验证：

```text
mvn -B -ntp clean verify
→ BUILD SUCCESS，17 模块，279 用例（266 执行 + 13 live 跳过），0 failure / 0 error
→ Spotless 0 / Checkstyle 0 / JaCoCo 全模块达标
```

## 4. 兼容性

- 合法 JSON 的解析结果不变；
- 非法 `\u` 与超深嵌套从“可能抛非预期异常/Error”变为统一
  `IllegalArgumentException`，符合 `JsonCodec.read` 的 Javadoc 契约；
- 无 public API 签名变更。

## 5. 未纳入

- A02/A03、A04、A06、A07、A12、A14、A15；
- 信创 DDL、DLX/死信、consumer 多实例、Admin。

## 6. 回滚

单提交回滚；无 schema/配置变化。
