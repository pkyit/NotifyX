# Git Commit 标准规范

## 📝 编写细则

### 标题行规范
- **必须以 Emoji 开头**
- **Scope 必须是微服务模块名**（如 `auth`, `order`, `gateway`）
- **必须包含 Issue 编号**：如果当前任务关联了 Issue（如 `#1024`），必须在标题末尾标注 `(#1024)`
- **标题长度限制**：概括核心问题，不要超过 50 个字符

### 详情列表规范
- **必须使用 `-` 作为列表符号**
- **拒绝模糊描述**：不要写"修改了代码"，要写"修改了 UserService 中的 login 方法"
- **包含上下文**：说明修改的具体类、接口或配置项
- **包含测试**：如果涉及测试，必须列出新增的测试场景

---

## 🔗 Issue 关联与原子性提交铁律

### Issue 强制关联
- 如果当前开发任务是关联了 GitLab Issue 或云效任务，**必须在提交信息中体现该编号**
- **自动检测**：在提交前，请调用 `scripts/get-issue-info.sh` 脚本尝试从分支名中提取 Issue 编号

### 原子性提交（绝对禁止大杂烩）

#### 原则
**一个 Issue/任务 = 一个 Commit**

#### 禁止事项
❌ 绝对禁止一次性修复几十个 Bug 或完成多个功能后，打包成一个大 Commit 提交

#### 执行标准

1. **单一职责**：每次 `git commit` 只能解决一个具体的 Issue 或完成一个微小的功能点

2. **频繁暂存**：如果同时修复了 3 个 Bug（#101, #102, #103），必须分三次提交：
   ```bash
   git add file_A && git commit -m "... (#101)"
   git add file_B && git commit -m "... (#102)"
   git add file_C && git commit -m "... (#103)"
   ```

3. **上下文隔离**：如果代码改动涉及完全不相关的模块（例如既改了 `user` 又改了 `payment`），除非它们属于同一个 Issue，否则必须拆分为不同的 Commit

---

## ✅ 优秀示例（参考标准）

### 场景 1：修复 Auth 模块 Bug（关联 Issue #1088）

```commit
:bug: fix(auth): 修复登录认证模块 IP 锁定后刷新令牌仍可通过的问题 (#1088)

- RefreshTokenGranter 增加 IP 锁定状态校验
- LoginAttemptService 补充刷新场景下的失败计数复位逻辑
- 新增单元测试覆盖刷新 Token 的 IP 锁定分支
```

### 场景 2：新增用户功能（关联 Issue #501）

```commit
:sparkles: feat(user): 实现用户头像上传与裁剪功能 (#501)

- UserController 新增 /avatar/upload 接口
- 引入 Thumbnailator 库处理图片压缩
- 配置 OSS 存储路径策略
- 新增 AvatarControllerTest 测试文件上传流
```

### 场景 3：重构订单模块（无关联 Issue，纯技术优化）

```commit
:recycle: refactor(order): 剥离订单状态机逻辑至独立组件

- 移除 OrderService 中臃肿的 if-else 状态判断
- 新增 OrderStateMachine 类管理状态流转
- 使用策略模式重构支付回调处理
```

---

## 🔄 执行流程

1. **分析变更**：使用 GitLab MCP 工具或 `git diff` 分析变更文件
2. **检测 Issue**：运行 `scripts/get-issue-info.sh` 获取可能的 Issue 编号
3. **提取细节**：识别具体的类名、方法名和逻辑变动
4. **组装文档**：按照模板生成多行提交信息，确保包含：
   - Emoji
   - Type
   - Scope
   - Issue ID
   - 详情列表
5. **执行提交**：调用工具执行提交