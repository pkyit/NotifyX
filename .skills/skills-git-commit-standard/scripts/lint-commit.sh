#!/bin/bash

# 脚本：lint-commit.sh
# 用途：校验提交信息是否符合 [Emoji + 云效分类 + Issue编号 + 详情列表] 的结构化格式
# 触发时机：commit-msg 钩子 (在 git commit 完成后，推送前拦截)

COMMIT_MSG_FILE=$1
COMMIT_MSG=$(cat $COMMIT_MSG_FILE)

# 提取第一行（标题行）
TITLE_LINE=$(echo "$COMMIT_MSG" | head -n 1)

# 提取正文（去除第一行后的内容）
BODY_CONTENT=$(echo "$COMMIT_MSG" | tail -n +2)

# ==========================================
# 1. 校验标题行格式
# ==========================================
# 正则说明：
# ^(...)                        : 必须以...开头
# ✨|🐛|...                     : 允许的 Gitmoji 列表
# (feat|fix|...)                : 允许的云效分类 Type
# \([a-zA-Z0-9_-]+\)            : 必须是 (scope) 格式
# : .+                          : 冒号空格后接描述
# (\(#\d+\))?$                  : 必须以 (#数字) 结尾 (强制 Issue 关联)
REGEX_TITLE="^(✨|🐛|🚑|✅|♻️|⚡|📝|🔧|🔒|🔥|🎉) (feat|fix|hotfix|test|refactor|perf|docs|chore|security|remove|release)\([a-zA-Z0-9_-]+\): .+ \(\#\d+\)$"

if [[ ! $TITLE_LINE =~ $REGEX_TITLE ]]; then
    echo "❌ 提交信息标题格式错误！"
    echo "------------------------------------------------"
    echo "标题必须严格遵循以下格式："
    echo "<Emoji> <Type>(<Scope>): <描述> (#<IssueID>)"
    echo ""
    echo "常见错误："
    echo "1. 缺少 Emoji (如 ✨)"
    echo "2. Type 拼写错误 (必须是 feat, fix 等)"
    echo "3. 缺少 Issue 编号 (必须以 (#123) 结尾)"
    echo ""
    echo "正确示例："
    echo "✨ feat(user): 新增用户注册接口 (#1024)"
    echo "🐛 fix(order): 修复金额计算错误 (#501)"
    echo "------------------------------------------------"
    exit 1
fi

# ==========================================
# 2. 校验正文（详情列表）
# ==========================================
# 规则：如果有正文内容（非空行），必须包含以 "- " 开头的列表项
if [ -n "$(echo "$BODY_CONTENT" | grep -v '^$')" ]; then
    if ! echo "$BODY_CONTENT" | grep -q "^- "; then
        echo "⚠️ 警告：提交包含详情描述，但格式不规范。"
        echo "详情列表应使用 '- ' 开头，例如："
        echo "- UserService 新增注册逻辑"
        echo "- 新增单元测试"
        # 这里设置为警告，不强制退出 (exit 1)，以免阻塞紧急提交
        # 如果需要强制，请取消下面这行的注释
        # exit 1
    fi
fi

echo "✅ 提交信息格式校验通过"
exit 0
