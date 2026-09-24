# ============================================================
# 前端镜像：多阶段构建
# Stage1 node build；Stage2 nginx 静态托管 + 反代后端
# ============================================================

# ---------- Stage 1: Vite 构建 ----------
FROM node:20-alpine AS builder
WORKDIR /build
COPY package.json package-lock.json ./
# 使用国内镜像加速（可选，删掉这两行也能跑）
RUN npm config set registry https://registry.npmmirror.com && \
    npm ci
COPY . .
RUN npm run build

# ---------- Stage 2: nginx ----------
FROM nginx:alpine
# 时区
RUN apk add --no-cache tzdata && \
    cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /build/dist /usr/share/nginx/html
EXPOSE 80
# 用 127.0.0.1 而非 localhost：busybox wget 会先试 IPv6 ::1，nginx 只监听 IPv4 导致误报 unhealthy
HEALTHCHECK --interval=30s --timeout=2s --retries=3 \
  CMD wget -qO- http://127.0.0.1/ || exit 1
