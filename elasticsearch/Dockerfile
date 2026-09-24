# ============================================================
# Elasticsearch 镜像：基于官方 8.18.1（与 Spring Boot 3.5 的 client 8.18.1 匹配）
# 安装 IK 中文分词插件，用于小说搜索
# ============================================================
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.1

# 安装 IK 中文分词插件（版本需与 ES 严格一致）
RUN bin/elasticsearch-plugin install --batch \
    https://get.infini.cloud/elasticsearch/analysis-ik/8.18.1
