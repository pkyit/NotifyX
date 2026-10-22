# 在 Kubernetes 中验证邮件告警

目标：用 `registry.aliyuncs.com/pkyit/java:jdk17-font`（国内可拉取）运行 sample，
SMTP 凭据由 Secret 注入，配置由 ConfigMap 注入，从集群内触发一次真实邮件。

> 本目录的 `pod.yaml` 用 `kubectl cp` 把 fat jar 拷进容器，是**冒烟验证**的捷径；
> 生产请用 `Dockerfile` 把 jar 打进镜像后部署 Deployment（见文末）。

## 1. 构建可执行 jar

```bash
mvn -B -pl notifyx-sample -am package -DskipTests
# 产物：notifyx-sample/target/notifyx-sample-1.0.0-SNAPSHOT.jar（约 26 MB，含依赖）
```

## 2. 创建命名空间、配置与凭据

```bash
kubectl apply -f deploy/k8s/namespace.yaml
kubectl apply -f deploy/k8s/configmap.yaml

# 凭据不要写进文件；用命令创建（或由 CI Secret 注入）
kubectl -n notifyx-demo create secret generic notifyx-smtp \
  --from-literal=user='<邮箱地址>' \
  --from-literal=pass='<SMTP 授权码>'
```

`configmap.yaml` 中的 `username/password/from` 使用 `${NOTIFYX_SMTP_USER}`/`${NOTIFYX_SMTP_PASS}`，
由 Pod 的环境变量（来自 Secret）解析。

## 3. 启动 Pod 并拷入 jar

```bash
kubectl apply -f deploy/k8s/pod.yaml
kubectl -n notifyx-demo wait --for=condition=Ready pod/notifyx-sample --timeout=180s

kubectl -n notifyx-demo exec notifyx-sample -- mkdir -p /app
kubectl -n notifyx-demo cp \
  notifyx-sample/target/notifyx-sample-1.0.0-SNAPSHOT.jar \
  notifyx-sample:/app/app.jar

kubectl -n notifyx-demo exec notifyx-sample -- \
  sh -c "nohup java -jar /app/app.jar --spring.config.additional-location=file:/config/ > /tmp/notifyx.log 2>&1 &"
```

等待日志出现 `Started SampleApplication`：

```bash
kubectl -n notifyx-demo exec notifyx-sample -- sh -c "grep 'Started SampleApplication' /tmp/notifyx.log"
```

## 4. 触发并验证

```bash
kubectl -n notifyx-demo port-forward pod/notifyx-sample 18080:8080
# 另一个终端：
curl "http://127.0.0.1:18080/demo/notify?orderNo=K8S-MAIL-001"

kubectl -n notifyx-demo exec notifyx-sample -- sh -c "grep NOTIFYX_AUDIT /tmp/notifyx.log | tail"
```

预期两条审计（场景 `ORDER_CREATE_FAIL` 配了两个收件人）：

```text
NOTIFYX_AUDIT ... scene=ORDER_CREATE_FAIL level=ERROR channel=email receiver=<收件人1> status=SUCCESS
NOTIFYX_AUDIT ... scene=ORDER_CREATE_FAIL level=ERROR channel=email receiver=<收件人2> status=SUCCESS
```

`level=ERROR` 来自场景配置（生效级别），不是事件默认的 WARN。

## 5. 清理

```bash
kubectl delete namespace notifyx-demo
```

## 6. 生产镜像（建议）

```bash
docker build -f deploy/k8s/Dockerfile -t <registry>/<namespace>/notifyx-sample:1.0.0 .
docker push <registry>/<namespace>/notifyx-sample:1.0.0
```

然后以相同 ConfigMap/Secret 部署 Deployment；`terminationGracePeriodSeconds` 需大于
`notifyx.async.shutdown-await`（默认 10s，建议 30s），确保优雅关闭能排空队列。
