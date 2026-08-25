#!/usr/bin/env bash
# ============================================================================
#  SnSclaw 三容器部署脚本（全新部署 · 纯镜像拉取）
#
#  用法：  ./deploy.sh
#  幂等：  可重复执行；已运行的服务会被重建为当前配置
# ============================================================================
set -euo pipefail

REGISTRY="221.237.179.2:5000"
RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; RST=$'\033[0m'

info() { echo "${GRN}[ok]${RST}   $*"; }
warn() { echo "${YEL}[warn]${RST} $*"; }
die()  { echo "${RED}[fail]${RST} $*" >&2; exit 1; }

cd "$(dirname "$0")"

# 宿主架构 → 镜像 tag 后缀。registry 里同一版本按架构分开存放
# （server:v1.0.2-amd64 / server:v1.0.2-arm64），compose 里的 image 写成
# ...:v1.0.2${IMAGE_ARCH_SUFFIX:-}，靠这里导出后拼接。
case "$(uname -m)" in
    x86_64|amd64)  IMAGE_ARCH_SUFFIX="-amd64" ;;
    aarch64|arm64) IMAGE_ARCH_SUFFIX="-arm64" ;;
    *) die "未支持的宿主架构：$(uname -m)
     registry 中只有 -amd64 / -arm64 两种镜像。" ;;
esac
export IMAGE_ARCH_SUFFIX
info "宿主架构 $(uname -m) → 使用 ${IMAGE_ARCH_SUFFIX} 镜像"

# --- 1. 前置检查 ---------------------------------------------------------
command -v docker >/dev/null || die "未安装 docker"
docker compose version >/dev/null 2>&1 || die "docker compose (v2) 不可用。
     Ubuntu 的 docker.io 包不含 compose 插件，安装：
         sudo apt update && sudo apt install -y docker-compose-v2"

docker info >/dev/null 2>&1 || die "无权访问 docker daemon。执行：sudo usermod -aG docker \$USER && newgrp docker"

if ! docker info 2>/dev/null | grep -q "$REGISTRY"; then
    die "registry $REGISTRY 不在 insecure-registries 中。
     请先配置 /etc/docker/daemon.json 并重启 docker，详见 README.md"
fi
info "insecure-registry 已生效"

[ -f .env ] || die ".env 不存在。执行： cp .env.template .env  然后填写其中的 <CHANGE_ME>"

if grep -vE '^\s*#' .env | grep -q "CHANGE_ME"; then
    grep -nvE '^\s*#' .env | grep "CHANGE_ME" >&2
    die ".env 中仍有未填写的 <CHANGE_ME> 项（见上）"
fi

# .env 含明文密码和密钥，收紧权限（幂等，每次运行都确保）
chmod 600 .env

# 关键项不能为空 —— compose 的 :? 只在变量"未定义"时报错，
# 写成 KEY= 空值仍会通过，但 JWT_SECRET 为空会导致每次重启登录态全失效。
# 去掉首尾引号和空白再判断，避免 KEY="" 或 KEY=  这类写法漏网。
for _k in DB_PASSWORD DB_ADMIN_PASSWORD JWT_SECRET SEARXNG_SECRET MATECLAW_PUBLIC_BASE_URL; do
    _v=$(grep -E "^${_k}=" .env | head -1 | cut -d= -f2- \
         | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' -e 's/^"\(.*\)"$/\1/' -e "s/^'\(.*\)'$/\1/")
    [ -n "$_v" ] || die ".env 中 ${_k} 为空值，必须填写"
done
info ".env 检查通过（权限 600，关键项非空）"

[ -f docker/postgres/init/10-app-role.sh ] \
    || die "缺少 docker/postgres/init/10-app-role.sh —— 数据库将无法初始化"
chmod +x docker/postgres/init/10-app-role.sh
info "数据库初始化脚本就位"

# 端口占用检查 —— 从 compose 解析实际映射，避免脚本与 compose 各写一份而失配。
# 解析失败时回落到默认值，不因此中断部署。
_ports=$(docker compose config 2>/dev/null \
    | grep -E '^\s+published:' | tr -dc '0-9\n' | tr '\n' ' ')
[ -n "${_ports// /}" ] || _ports="19600 19695 1455"
for p in $_ports; do
    if ss -tln 2>/dev/null | grep -qE "[:.]${p}\s"; then
        die "端口 $p 已被占用。改 docker-compose.yml 中冒号左侧的宿主端口，
     或停掉占用它的服务（ss -tlnp | grep :$p 可查）"
    fi
done
info "端口空闲：${_ports}"

# 应用对外端口，供后续就绪探测和提示使用
APP_PORT=$(docker compose config 2>/dev/null \
    | grep -B2 'target: 18088' | grep 'published:' | tr -dc '0-9')
[ -n "$APP_PORT" ] || APP_PORT=19600

# 磁盘空间：镜像解压后约 4.3G + 运行时数据，留 15G 余量
_avail=$(df -BG --output=avail /var/lib/docker 2>/dev/null | tail -1 | tr -dc '0-9')
if [ -n "$_avail" ] && [ "$_avail" -lt 15 ]; then
    die "/var/lib/docker 可用空间不足：${_avail}G（建议 ≥15G）"
fi
info "磁盘空间充足（${_avail:-?}G 可用）"

# --- 2. 检查 registry 可达 + 已登录 --------------------------------------
# 用 manifest 查询做连通性测试，而不是 docker pull —— pull 会真的下载几百 MB。
# 未登录时给出明确指引而不是唤起交互式 login（脚本可能在非交互环境运行）。
#
# curl 并非所有精简系统都预装（Jetson 的 Ubuntu 20.04 就没有），缺失时回落到
# wget，再退一步用 python3。三者都没有就跳过这项探测 —— 后面的
# docker manifest inspect 同样能发现 registry 不通，不值得为此中断部署。
_http_probe() {
    local url="$1"
    if command -v curl >/dev/null 2>&1; then
        curl -s -o /dev/null -w "%{http_code}" --max-time 10 "$url" 2>/dev/null || echo 000
    elif command -v wget >/dev/null 2>&1; then
        # wget 对 401 返回非零退出码，需要从 stderr 里抓状态行
        wget -q -S --timeout=10 -O /dev/null "$url" 2>&1 \
            | awk '/^  HTTP\//{c=$2} END{print (c?c:"000")}'
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$url" <<'PYEOF' 2>/dev/null || echo 000
import sys,urllib.request,urllib.error
try:
    print(urllib.request.urlopen(sys.argv[1],timeout=10).status)
except urllib.error.HTTPError as e:
    print(e.code)
except Exception:
    print("000")
PYEOF
    else
        echo skip
    fi
}
_probe=$(_http_probe "http://$REGISTRY/v2/")
case "$_probe" in
    401)
        # registry 活着且要求认证 —— 检查本机是否已存有凭据。
        # 用 sudo 跑 docker 时凭据在 /root/.docker，不在 $HOME。
        if ! grep -qs "$REGISTRY" "$HOME/.docker/config.json" \
           && ! sudo -n grep -qs "$REGISTRY" /root/.docker/config.json 2>/dev/null; then
            die "registry 可达但尚未登录。先执行：
         docker login $REGISTRY -u admin"
        fi
        info "registry 可达且已登录"
        ;;
    200)
        info "registry 可达（未启用认证）"
        ;;
    skip)
        warn "无 curl/wget/python3，跳过 registry 连通性探测"
        ;;
    000)
        die "无法连接 registry $REGISTRY。
     检查网络，以及 insecure-registries 是否已配置生效。"
        ;;
    *)
        die "registry 返回异常状态码：$_probe"
        ;;
esac

# 校验 compose 引用的每个 tag 在 registry 中确实存在 —— tag 改名/漏推是
# 最常见的部署失败原因，提前查比拉到一半失败清楚得多。
# 用 docker manifest inspect 而非 curl：registry 需认证，匿名 curl 只会拿到
# 401 而无法判定，而 docker 会复用 ~/.docker/config.json 里的凭据。
_missing=""
for _ref in $(docker compose config 2>/dev/null \
        | grep -E '^\s+image:' | awk '{print $2}' | grep "^$REGISTRY/" | sort -u); do
    docker manifest inspect --insecure "$_ref" >/dev/null 2>&1 \
        || _missing="${_missing}
     · ${_ref}"
done
if [ -n "$_missing" ]; then
    die "以下镜像在 registry 中不存在：${_missing}

     核对实际内容：
         curl -u admin:<密码> http://$REGISTRY/v2/_catalog
         curl -u admin:<密码> http://$REGISTRY/v2/snsclaw/server/tags/list
     然后修改 docker-compose.yml 里的 image tag。"
fi
info "compose 引用的镜像 tag 均存在"

# --- 3. 拉取并启动 -------------------------------------------------------
echo
echo "拉取镜像（server 约 3.5GB，首次较慢）..."
if ! docker compose pull; then
    die "镜像拉取失败。常见原因：
     · 网络中断 —— 重跑本脚本即可，已下载的层会复用
     · tag 不存在 —— 核对 docker-compose.yml 里的 image tag 与 registry 实际内容：
         curl -u admin:<密码> http://$REGISTRY/v2/_catalog
         curl -u admin:<密码> http://$REGISTRY/v2/snsclaw/server/tags/list"
fi

echo
echo "启动服务..."
docker compose up -d

# --- 4. 等待就绪 ---------------------------------------------------------
echo
echo -n "等待 snsclaw-server 就绪（首次启动要跑 Flyway 建表，约 1-2 分钟）"
for i in $(seq 1 90); do
    code=$(_http_probe "http://localhost:${APP_PORT}/")
    if [ "$code" = "200" ]; then
        echo
        info "服务已就绪"
        echo
        docker compose ps
        _ip=$(hostname -I 2>/dev/null | awk '{print $1}')
        echo
        echo "  访问地址： http://${_ip:-<本机IP>}:${APP_PORT}"
        echo "  默认账号： admin / admin123   ${YEL}(登录后请立即修改)${RST}"
        echo
        echo "  下一步：设置 → 模型 → 添加供应商，录入云端 LLM 的 API Key"
        exit 0
    fi
    # 容器已退出就没必要再等满 3 分钟
    if ! docker compose ps --status running 2>/dev/null | grep -q snsclaw-server; then
        echo
        warn "snsclaw-server 未在运行，最后 40 行日志："
        docker compose logs --tail=40 snsclaw-server 2>&1 | sed 's/^/    /'
        die "容器启动失败，见上方日志"
    fi
    echo -n "."
    sleep 2
done

echo
warn "180 秒内未就绪。服务可能仍在启动（Flyway 迁移较慢），检查："
echo "  docker compose logs --tail=80 snsclaw-server"
echo "  curl -v http://localhost:${APP_PORT}/"
exit 1
