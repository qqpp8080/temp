#!/bin/bash

# ==============================================================================
# 配置区域（请根据你的实际情况修改这三个变量）
# ==============================================================================

# 1. 你的 Kubernetes 命名空间（如果要监控所有命名空间，请参考下方优化提示）
NAMESPACE="default" 

# 2. 当前节点上（外面）存放的、需要复制进去的源文件路径
TARGET_FILE="/path/to/local/file.txt" 

# 3. 目标容器内部的绝对路径（包含文件名）
CONTAINER_PATH="/app/config/file.txt" 

# ==============================================================================
# 核心逻辑区域
# ==============================================================================

echo "=================================================="
echo "🚀 脚本启动：开始实时监控命名空间 [ $NAMESPACE ] 中以 ars 开头的 Pod..."
echo "📅 启动时间: $(date)"
echo "=================================================="

# 使用 kubectl get pods -w 启动实时流式监控。
# --no-headers : 隐藏表格头部（如 NAME、READY 等），方便按行解析。
# | while read -r line : 只要集群有 Pod 状态变化，k8s 就会输出一行，这里用循环实时捕获每一行。
kubectl get pods -n "$NAMESPACE" -w --no-headers | while read -r line; do

    # 1. 从当前这行输出中，提取出 Pod 的名称（第一列）
    # 例如：ars-service-7f89b-abcde
    pod_name=$(echo "$line" | awk '{print $1}')
    
    # 2. 提取出当前这一行显示的 Pod 状态（第三列）
    # 例如：Running, Pending, ContainerCreating, Terminating
    status=$(echo "$line" | awk '{print $3}')
    
    # 3. 核心条件判断：
    # 核心判断一：[[ "$pod_name" =~ ^ars ]] -> 使用正则检查 Pod 名字是否以 "ars" 开头
    # 核心判断二：[ "$status" == "Running" ] -> 检查状态是否刚好变为 "Running"（运行中）
    if [[ "$pod_name" =~ ^ars ]] && [ "$status" == "Running" ]; then
        
        echo "--------------------------------------------------"
        echo "🔔 捕获到目标 Pod 启动: $pod_name (状态: $status)"
        echo "⏳ 等待 3 秒，确保容器内网络和文件系统完全就绪..."
        sleep 3
        
        echo "➡️ 开始推送文件到容器..."
        # 执行 k8s 远程复制命令
        # 格式：kubectl cp [本地文件] [命名空间]/[Pod名]:[容器内路径]
        kubectl cp "$TARGET_FILE" "${NAMESPACE}/${pod_name}:${CONTAINER_PATH}"
        
        # $? 是上一个命令（kubectl cp）的退出状态码，0 表示执行成功
        if [ $? -eq 0 ]; then
            echo "✅ 成功！文件已安全送达: $pod_name"
            echo "⏰ 完成时间: $(date)"
        else
            echo "❌ 失败！未能复制文件到 $pod_name，请检查容器路径是否存在，或容器内是否有 tar 命令。"
        fi
        echo "--------------------------------------------------"
    fi

done
