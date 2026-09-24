"""从运行中的容器读取口令。

口令只应存在于两处：容器环境变量（由 docker-compose 从 .env 注入）与 .env 文件本身。
脚本不保存口令副本 —— 一旦写进代码，它就会随仓库一起公开。

取不到时返回空串，由调用方决定如何处理（通常是拿到一个明确的连接失败，
而不是悄悄用一个「本地默认口令」连上去）。
"""
import os
import subprocess

_CACHE = {}


def container_secret(container, var):
    """读容器内的环境变量。"""
    key = (container, var)
    if key not in _CACHE:
        try:
            _CACHE[key] = subprocess.check_output(
                ["docker", "exec", container, "sh", "-c", f'printf %s "${var}"'],
                text=True, timeout=15, stderr=subprocess.DEVNULL).strip()
        except (subprocess.SubprocessError, OSError):
            _CACHE[key] = ""
    return _CACHE[key]


def mysql_password(container="ai-novel-mysql"):
    """MySQL root 口令：环境变量优先（compose 注入时可用），否则读容器。"""
    return os.environ.get("MYSQL_PWD") or container_secret(container, "MYSQL_ROOT_PASSWORD")


def redis_password(container="ai-novel-redis"):
    """Redis 口令：环境变量优先，否则读容器。"""
    return os.environ.get("REDIS_PASSWORD") or container_secret(container, "REDIS_PASSWORD")
