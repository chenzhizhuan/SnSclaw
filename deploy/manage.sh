#!/usr/bin/env bash
# ============================================================================
#  SnSclaw 运维脚本 —— 日常更新 / 重启 / 排障
#
#  用法：  ./manage.sh <命令> [参数]
#
#  常用：
#    ./manage.sh status              查看状态（容器 / 端口 / 磁盘 / 数据卷）
#    ./manage.sh update              拉最新镜像并重建（保留数据）
#    ./manage.sh update server:v1.0.3   指定 tag 更新单个服务
#    ./manage.sh restart [服务名]    重启（不重建，不拉镜像）
#    ./manage.sh logs [服务名]       跟踪日志
#    ./manage.sh backup              备份数据库 + 文件卷
#
#  设计原则：默认不销毁数据。会删除数据的操作（reset）要求显式二次确认。
# ============================================================================
set -euo pipefail

REGISTRY="221.237.179.2:5000"
RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYA=$'\033[36m'; RST=$'\033[0m'

info() { echo "${GRN}[ok]${RST}   $*"; }
warn() { echo "${YEL}[warn]${RST} $*"; }
step() { echo; echo "${CYA}==>${RST} $*"; }
die()  { echo "${RED}[fail]${RST} $*" >&2; exit 1; }

# 宿主架构 → 镜像 tag 后缀。registry 里同一版本按架构分开存放
# （server:v1.0.3-amd64 / server:v1.0.3-arm64），compose 用
# ${IMAGE_ARCH_SUFFIX} 拼到 tag 末尾，靠这里导出。
#
# 不用 docker 的 multi-arch manifest 是因为该 registry 上的镜像是单架构
# 分别 push 的，没有 manifest list 可供自动选择。
detect_arch_suffix() {
    local m; m=$(uname -m)
    case "$m" in
        x86_64|amd64)  echo "-amd64" ;;
        aarch64|arm64) echo "-arm64" ;;
        *) die "未支持的宿主架构：${m}
     registry 中只有 -amd64 / -arm64 两种镜像。" ;;
    esac
}
IMAGE_ARCH_SUFFIX=$(detect_arch_suffix)
export IMAGE_ARCH_SUFFIX

# HTTP 状态码探测。curl 并非所有精简系统都预装（Jetson 的 Ubuntu 20.04 就没有），
# 缺失时回落到 wget，再退一步用 python3。都没有则返回 000。
_http_probe() {
    local url="$1"
    if command -v curl >/dev/null 2>&1; then
        curl -s -o /dev/null -w "%{http_code}" --max-time 5 "$url" 2>/dev/null || echo 000
    elif command -v wget >/dev/null 2>&1; then
        wget -q -S --timeout=5 -O /dev/null "$url" 2>&1 \
            | awk '/^  HTTP\//{c=$2} END{print (c?c:"000")}'
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$url" <<'PYEOF' 2>/dev/null || echo 000
import sys,urllib.request,urllib.error
try:
    print(urllib.request.urlopen(sys.argv[1],timeout=5).status)
except urllib.error.HTTPError as e:
    print(e.code)
except Exception:
    print("000")
PYEOF
    else
        echo 000
    fi
}

cd "$(dirname "$0")"
[ -f docker-compose.yml ] || die "当前目录没有 docker-compose.yml"
docker compose version >/dev/null 2>&1 || die "docker compose (v2) 不可用"

# 应用对外端口，从 compose 解析（避免与 compose 各写一份而失配）。
#
# 三级回落：
#   1. docker compose config 的 published/target 结构（最准，已展开变量）
#   2. .env 里的 APP_EXPOSED_PORT —— compose 把宿主端口写成
#      "${APP_EXPOSED_PORT:-10000}:18088" 时，若 compose config 因故不可用
#      （daemon 不通、compose 文件报错）第 1 步会拿到空值
#   3. compose 文件里该变量的默认值
# 全部失败才回落到 10000。曾经硬编码 19600 —— 那是旧版 compose 的端口，
# 端口改成变量驱动后会导致就绪探测打错端口、把已就绪的服务误报为失败。
app_port() {
    local p
    p=$(docker compose config 2>/dev/null \
        | grep -B2 'target: 18088' | grep 'published:' | tr -dc '0-9')
    if [ -z "$p" ] && [ -f .env ]; then
        p=$(grep -E '^APP_EXPOSED_PORT=' .env 2>/dev/null | head -1 \
            | cut -d= -f2- | tr -dc '0-9')
    fi
    if [ -z "$p" ]; then
        p=$(grep -oE '\$\{APP_EXPOSED_PORT:-[0-9]+\}' docker-compose.yml 2>/dev/null \
            | head -1 | tr -dc '0-9')
    fi
    echo "${p:-10000}"
}

# 等待 HTTP 就绪；容器中途退出则立即报错，不干等
wait_ready() {
    local port="$1" max="${2:-90}" i code
    echo -n "等待 snsclaw-server 就绪"
    for i in $(seq 1 "$max"); do
        code=$(_http_probe "http://localhost:${port}/")
        if [ "$code" = "200" ]; then
            echo; info "服务已就绪 → http://$(hostname -I 2>/dev/null | awk '{print $1}'):${port}"
            return 0
        fi
        if ! docker compose ps --status running 2>/dev/null | grep -q snsclaw-server; then
            echo
            warn "snsclaw-server 已退出，最后 40 行日志："
            docker compose logs --tail=40 snsclaw-server 2>&1 | sed 's/^/    /'
            return 1
        fi
        echo -n "."; sleep 2
    done
    echo
    warn "$((max * 2)) 秒内未就绪，查看： ./manage.sh logs snsclaw-server"
    return 1
}

cmd_status() {
    step "容器"
    docker compose ps --format "table {{.Name}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null \
        || warn "compose 项目未启动"

    step "镜像版本"
    echo "   宿主架构 $(uname -m) → tag 后缀 ${IMAGE_ARCH_SUFFIX}"
    docker compose config 2>/dev/null | grep -E '^\s+image:' | awk '{print "   " $2}'

    step "数据卷"
    local proj vols v sz
    proj=$(docker compose config --format json 2>/dev/null \
        | grep -oE '"name": *"[^"]+"' | head -1 | cut -d'"' -f4) || true
    [ -n "$proj" ] || proj=$(basename "$(pwd)" | tr '[:upper:]' '[:lower:]')
    vols=$(docker volume ls --format "{{.Name}}" 2>/dev/null | grep "^${proj}_" || true)
    if [ -z "$vols" ]; then
        echo "   （未找到 ${proj}_* 卷）"
    else
        for v in $vols; do
            # 卷目录属 root，普通用户 du 不到；有免密 sudo 才显示大小，
            # 否则只列卷名 —— 不因权限问题让 status 整体失败。
            sz=$(sudo -n du -sh "$(docker volume inspect "$v" --format '{{.Mountpoint}}' 2>/dev/null)" 2>/dev/null | cut -f1) || true
            printf "   %-32s %s\n" "$v" "${sz:-(需 sudo 查看大小)}"
        done
    fi

    step "健康检查"
    local port code
    port=$(app_port)
    code=$(_http_probe "http://localhost:${port}/")
    if [ "$code" = "200" ]; then
        info "HTTP ${port} → 200"
    else
        warn "HTTP ${port} → ${code}（服务可能未就绪）"
    fi

    step "近期错误（最多 5 条）"
    local errs
    errs=$(docker compose logs --tail=500 snsclaw-server 2>/dev/null \
        | grep -E "ERROR|Exception" | tail -5 | cut -c1-140 || true)
    if [ -n "$errs" ]; then
        echo "$errs" | sed 's/^/   /'
    else
        echo "   （无）"
    fi

    step "磁盘"
    df -h /var/lib/docker 2>/dev/null | tail -1 | sed 's/^/   /' || true
    echo
}

cmd_update() {
    local target="${1:-}"

    if [ -n "$target" ]; then
        # 形如 server:v1.0.3 —— 就地改 compose 里对应服务的 tag。
        # tag 不要带 -amd64/-arm64 后缀，架构由 IMAGE_ARCH_SUFFIX 自动补。
        local svc="${target%%:*}" tag="${target##*:}"
        [ "$svc" != "$tag" ] || die "格式应为 <服务>:<tag>，例如 server:v1.0.3"
        case "$tag" in
            *-amd64|*-arm64)
                die "tag 不要带架构后缀：写 ${svc}:${tag%-*} 即可，
     架构后缀（${IMAGE_ARCH_SUFFIX}）由脚本按宿主 $(uname -m) 自动附加。" ;;
        esac
        grep -q "${REGISTRY}/snsclaw/${svc}:" docker-compose.yml \
            || die "compose 中找不到镜像 ${REGISTRY}/snsclaw/${svc}"

        step "校验 tag 是否存在（${svc}:${tag}${IMAGE_ARCH_SUFFIX}）"
        # registry 需认证，匿名 curl 只会拿到 401，无法判定 tag 真伪。
        # 改用 docker manifest inspect —— 它会复用 ~/.docker/config.json 里的凭据。
        if docker manifest inspect --insecure \
                "${REGISTRY}/snsclaw/${svc}:${tag}${IMAGE_ARCH_SUFFIX}" >/dev/null 2>&1; then
            info "tag ${svc}:${tag}${IMAGE_ARCH_SUFFIX} 存在"
        else
            die "registry 中查不到 snsclaw/${svc}:${tag}${IMAGE_ARCH_SUFFIX}
     本机架构 $(uname -m) 需要 ${IMAGE_ARCH_SUFFIX} 后缀的镜像。
     核对可用 tag：
         curl -u admin:<密码> http://${REGISTRY}/v2/snsclaw/${svc}/tags/list
     （未登录请先： docker login ${REGISTRY} -u admin）"
        fi

        cp docker-compose.yml "docker-compose.yml.bak.$(date +%Y%m%d-%H%M%S)"
        # 只替换版本号部分，保留末尾的 ${IMAGE_ARCH_SUFFIX:-} 占位符不动
        sed -i -E "s|(${REGISTRY}/snsclaw/${svc}):[^\$[:space:]]+|\1:${tag}|" docker-compose.yml
        info "已更新 compose 中的 tag → ${svc}:${tag}（原文件已备份）"
    fi

    step "拉取镜像（架构 $(uname -m) → ${IMAGE_ARCH_SUFFIX}）"
    docker compose pull || die "拉取失败。网络中断可直接重跑（已下载的层会复用）；
     若是 tag 不存在，核对： curl -u admin:<密码> http://${REGISTRY}/v2/_catalog
     本机需要带 ${IMAGE_ARCH_SUFFIX} 后缀的镜像。"

    step "重建容器（数据卷保留）"
    docker compose up -d

    step "等待就绪"
    wait_ready "$(app_port)" 90 || die "更新后服务未就绪"

    step "清理悬空镜像"
    local freed; freed=$(docker image prune -f 2>/dev/null | tail -1)
    info "${freed:-已清理}"
}

cmd_restart() {
    local svc="${1:-}"
    if [ -n "$svc" ]; then
        step "重启 $svc"
        docker compose restart "$svc"
    else
        step "重启全部服务"
        docker compose restart
    fi
    wait_ready "$(app_port)" 60 || true
}

cmd_stop()  { step "停止服务（数据保留）"; docker compose stop; info "已停止"; }
cmd_start() { step "启动服务"; docker compose up -d; wait_ready "$(app_port)" 90 || true; }

cmd_logs() {
    local svc="${1:-snsclaw-server}"
    echo "跟踪 $svc 日志，Ctrl-C 退出"
    docker compose logs -f --tail=100 "$svc"
}

cmd_backup() {
    local dir="backups" ts; ts=$(date +%Y%m%d-%H%M%S)
    mkdir -p "$dir"

    step "备份数据库"
    local dbuser dbname
    dbuser=$(grep -E '^DB_ADMIN_USERNAME=' .env 2>/dev/null | cut -d= -f2- | tr -d '"' || echo snsclaw_admin)
    dbname=$(grep -E '^DB_NAME=' .env 2>/dev/null | cut -d= -f2- | tr -d '"' || echo snsclaw)
    docker compose exec -T postgres pg_dump -U "${dbuser:-snsclaw_admin}" \
        -d "${dbname:-snsclaw}" -Fc > "${dir}/db-${ts}.dump" \
        || die "数据库备份失败（服务未运行？）"
    info "$(ls -lh "${dir}/db-${ts}.dump" | awk '{print $5}')  →  ${dir}/db-${ts}.dump"

    step "备份文件卷"
    local proj; proj=$(docker compose config --format json 2>/dev/null \
        | grep -oE '"name": *"[^"]+"' | head -1 | cut -d'"' -f4)
    [ -n "$proj" ] || proj=$(basename "$(pwd)" | tr '[:upper:]' '[:lower:]')
    docker volume inspect "${proj}_server_data" >/dev/null 2>&1 \
        || die "找不到数据卷 ${proj}_server_data，用 ./manage.sh status 查看实际卷名"
    docker run --rm -v "${proj}_server_data":/data -v "$(pwd)/${dir}":/backup alpine \
        tar czf "/backup/server_data-${ts}.tar.gz" -C /data . 2>/dev/null \
        || die "文件卷备份失败"
    info "$(ls -lh "${dir}/server_data-${ts}.tar.gz" | awk '{print $5}')  →  ${dir}/server_data-${ts}.tar.gz"

    echo
    warn "备份含明文数据，注意存放位置。恢复步骤见 README.md"
}

cmd_reset() {
    echo
    warn "此操作会删除容器和【所有数据卷】——数据库、上传文件、skill 全部丢失。"
    echo "如需保留数据请先运行： ./manage.sh backup"
    echo
    printf "确认请输入大写 %sDELETE%s： " "$RED" "$RST"
    local ans; read -r ans
    [ "$ans" = "DELETE" ] || { info "已取消"; return 0; }
    step "销毁容器与数据卷"
    docker compose down -v
    info "已清空。重新部署： ./deploy.sh"
}

usage() {
    cat <<'EOF'
SnSclaw 运维脚本

  ./manage.sh status                 查看状态（容器/镜像/卷/健康/错误/磁盘）
  ./manage.sh update                 按当前 compose 拉镜像并重建（保留数据）
  ./manage.sh update server:v1.0.3   改 tag 后再更新（自动校验 tag 是否存在）
  ./manage.sh restart [服务]         重启，不拉镜像
  ./manage.sh start | stop           启停（数据保留）
  ./manage.sh logs [服务]            跟踪日志，默认 snsclaw-server
  ./manage.sh backup                 备份数据库 + 文件卷到 ./backups/
  ./manage.sh reset                  ⚠️ 删除全部数据，需输入 DELETE 确认

服务名： snsclaw-server | postgres | searxng

多架构：镜像 tag 自动按宿主架构补 -amd64 / -arm64 后缀，
        x86_64 与 aarch64 机器执行同样的命令即可，无需改配置。
        update 指定 tag 时不要带架构后缀（写 server:v1.0.3）。
EOF
}

case "${1:-}" in
    status)  cmd_status ;;
    update)  shift; cmd_update "${1:-}" ;;
    restart) shift; cmd_restart "${1:-}" ;;
    start)   cmd_start ;;
    stop)    cmd_stop ;;
    logs)    shift; cmd_logs "${1:-}" ;;
    backup)  cmd_backup ;;
    reset)   cmd_reset ;;
    ""|-h|--help|help) usage ;;
    *) die "未知命令：$1（运行 ./manage.sh --help 查看用法）" ;;
esac
