# 生产镜像示例：在项目根目录构建
#   docker build -f deploy/k8s/Dockerfile -t <registry>/<namespace>/notifyx-sample:1.0.0 .
FROM registry.aliyuncs.com/pkyit/java:jdk17-font

WORKDIR /app
COPY notifyx-sample/target/notifyx-sample-1.0.0-SNAPSHOT.jar /app/app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=file:/config/"]
