#!/usr/bin/env bash
#
# CI 执行内容说明：本地执行本脚本，等价于流水线中执行的那一条。
#
# 抽成脚本而非将命令写在流水线 yaml 中的原因：
#   1) 更换 CI 平台（更换执行器 / 厂商）只需修改适配层，构建逻辑无需重写；
#   2) 推送代码前可先在本机执行一遍，失败时无需等待流水线反馈；
#   3) 多条命令散落在 yaml 中，修改出错时不易被发现。
#
# 包含：干净构建 + 单测 + 打包 jar（即 mvn clean package）。
# 不包含：集成测试。该批测试需要 Docker 与四个真实中间件（MySQL / Redis / RabbitMQ / ES），
#          单次执行一两分钟，且要求机器上 Docker 处于运行状态；放入 CI 主流程会使该
#          流水线耗时与稳定性同时下降。需要执行时：mvn test -Pintegration（见 pom 的 integration profile）。
#
# 用法（仓库根目录）：bash ci/run-ci.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# Windows 的 Git Bash 中需使用 mvn.cmd：maven 自带的 POSIX 包装脚本无法读取
# MAVEN_HOME 中的反斜杠路径（C:\apache-maven-3.9.12），会报
# 「找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher」；
# 该报错表现为 classpath 异常，实际原因只是路径分隔符。CI（Linux）上仅有 mvn。
MVN=mvn
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) MVN=mvn.cmd ;;
esac

echo "==================== 环境 ===================="
java -version
"$MVN" -v
echo

echo "==================== 构建 + 单测 + 打包 ===================="
# -B 批处理模式（日志中不输出进度条，便于阅读 CI 日志）
# -ntp 不输出下载进度
# -s backend/settings.xml：与 Dockerfile 中使用同一份（阿里云镜像），CI 与镜像构建行为一致
"$MVN" -B -ntp -f backend/pom.xml -s backend/settings.xml clean package

echo
echo "==================== 产物 ===================="
ls -l backend/target/ai-novel-backend.jar
echo "单测报告在 backend/target/surefire-reports/"
