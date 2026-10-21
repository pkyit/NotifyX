#!/bin/bash

# 脚本：get-issue-info.sh
# 用途：自动获取当前分支关联的 Issue 编号，辅助 AI 生成合规的提交信息

# 1. 获取当前分支名
CURRENT_BRANCH=$(git branch --show-current)

# 2. 尝试从分支名提取 Issue ID (支持格式: feat/issue-123, fix/1024-xxx)
# 正则匹配数字
ISSUE_ID=$(echo "$CURRENT_BRANCH" | grep -oE '[0-9]+' | head -n 1)

if [ -n "$ISSUE_ID" ]; then
    # 输出 ID 供 AI 读取
    echo "DETECTED_ISSUE_ID=$ISSUE_ID"
else
    # 如果没有检测到 ID，输出提示
    echo "NO_ISSUE_DETECTED"
fi
