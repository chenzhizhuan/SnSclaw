#!/usr/bin/env bash
# ============================================================================
#  SnSclaw 新机环境准备脚本
#
#  在 deploy.sh 之前运行一次，把「宿主机层面」的前置条件准备好：
#    1. 安装 docker compose v2（Ubuntu 的 docker.io 包不含此插件）
#    2. 配置 insecure-registry（registry 走 HTTP）
#    3. 把当前用户加入 docker 组
#    4. 登录 registry
#    5. 从模板生成 .env 并自动生成两个密钥
#
#  用法：  bash prepare-host.sh
#  需要：  sudo 权限（会提示输密码）
#  幂等：  可重复执行，已完成的步骤会跳过
# ============================================================================
set -euo pipefail

REGISTRY="221.237.179.2:5000"
REG_USER="admin"
RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; RST=$'\033[0m'

info() { echo "${GRN}[ok]${RST}   $*"; }
warn() { echo "${YEL}[warn]${RST} $*"; }
step() { echo; echo "${YEL}==>${RST} $*"; }
die()  { echo "${RED}[fail]${RST} $*" >&2; exit 1; }

cd "$(dirname "$0")"

# --- 1. docker 本体 ------------------------------------------------------
command -v docker >/dev/null || die "未安装 docker。先执行：sudo apt install -y docker.io"

# --- 2. docker compose v2 ------------------------------------------------
step "检查 docker compose v2"
if docker compose version >/dev/null 2>&1; then
    info "已安装：$(docker compose version | head -1)"
else
    warn "缺少 compose 插件，正在安装 docker-compose-v2"
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq docker-compose-v2
    docker compose version >/dev/null 2>&1 \
        || die "安装后仍不可用，请手动检查 apt 源"
    info "已安装：$(docker compose version | head -1)"
fi

# --- 3. insecure-registry ------------------------------------------------
step "配置 insecure-registry（$REGISTRY 走 HTTP）"
if docker info 2>/dev/null | grep -q "$REGISTRY"; then
    info "已生效，跳过"
else
    if [ -f /etc/docker/daemon.json ]; then
        sudo cp /etc/docker/daemon.json "/etc/docker/daemon.json.bak.$(date +%s)"
        warn "已备份原 daemon.json"
        # 合并而非覆盖，保留原有配置（如镜像加速器）
        sudo python3 - "$REGISTRY" <<'PYEOF'
import json, sys
p = "/etc/docker/daemon.json"
reg = sys.argv[1]
try:
    d = json.load(open(p))
except Exception:
    d = {}
lst = d.setdefault("insecure-registries", [])
if reg not in lst:
    lst.append(reg)
json.dump(d, open(p, "w"), indent=2)
print("written:", json.dumps(d))
PYEOF
    else
        printf '{\n  "insecure-registries": ["%s"]\n}\n' "$REGISTRY" \
            | sudo tee /etc/docker/daemon.json >/dev/null
    fi
    sudo systemctl restart docker
    # daemon 重启有延迟，命令返回 != 就绪
    for i in $(seq 1 20); do
        docker info >/dev/null 2>&1 && break
        sleep 1
    done
    docker info 2>/dev/null | grep -q "$REGISTRY" \
        || die "配置写入了但未生效，检查 /etc/docker/daemon.json 语法"
    info "已生效"
fi

# --- 4. docker 组 --------------------------------------------------------
step "检查 docker 组权限"
if docker info >/dev/null 2>&1; then
    info "当前用户可直接访问 docker daemon"
else
    warn "当前用户无权访问 docker，正在加入 docker 组"
    sudo usermod -aG docker "$USER"
    die "已加入 docker 组，但需要重新登录才生效。
     请执行： exit  然后重新 ssh 登录，再跑一次本脚本。
     （或立即执行： newgrp docker）"
fi

# --- 5. registry 登录 ----------------------------------------------------
step "检查 registry 登录状态"
if grep -qs "$REGISTRY" "$HOME/.docker/config.json"; then
    info "已登录"
else
    warn "尚未登录，即将提示输入密码"
    docker login "$REGISTRY" -u "$REG_USER" || die "登录失败"
    info "登录成功"
fi

# --- 6. .env -------------------------------------------------------------
step "准备 .env"
if [ -f .env ]; then
    info ".env 已存在，跳过（不覆盖既有配置）"
else
    [ -f .env.template ] || die "缺少 .env.template"
    cp .env.template .env
    chmod 600 .env

    # 自动生成两个密钥，省去手工 openssl
    _jwt=$(openssl rand -base64 48 | tr -d '\n')
    _sxng=$(openssl rand -hex 32)
    # 用 | 作分隔符，避免 base64 中的 / 破坏 sed
    sed -i "s|^JWT_SECRET=.*|JWT_SECRET=${_jwt}|"      .env
    sed -i "s|^SEARXNG_SECRET=.*|SEARXNG_SECRET=${_sxng}|" .env
    info "已生成 .env，JWT_SECRET / SEARXNG_SECRET 已自动填充"

    echo
    warn "仍需手工填写以下三项，然后再运行 ./deploy.sh："
    echo "    DB_PASSWORD                 应用库密码"
    echo "    DB_ADMIN_PASSWORD           超管库密码"
    echo "    MATECLAW_PUBLIC_BASE_URL    对外访问地址，如 http://<本机IP>:19600"
    echo
    echo "    编辑： vi .env"
    exit 0
fi

echo
info "宿主机环境准备完成，现在可以运行： ./deploy.sh"
