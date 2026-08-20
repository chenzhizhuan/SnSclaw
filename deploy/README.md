# SnSclaw 部署包

三容器私有化部署：应用 + PostgreSQL + SearXNG。镜像从私有 registry 拉取，
不在目标机构建。

---

## 快速开始

新机器从零到可用，三条命令：

```bash
bash prepare-host.sh    # 准备宿主机环境，生成 .env
vi .env                 # 填 3 项配置
./deploy.sh             # 部署
```

已部署过的机器，日常操作用 `./manage.sh`。

---

## 目录结构

```
.
├── docker-compose.yml           编排定义（服务、端口、卷、网络）
├── .env.template                配置模板
├── .env                         实际配置（本地生成，含密钥，权限 600）
│
├── prepare-host.sh              ① 宿主机准备，新机跑一次
├── deploy.sh                    ② 首次部署
├── manage.sh                    ③ 日常运维
│
├── docker/postgres/init/
│   └── 10-app-role.sh           数据库初始化脚本（必需，勿删）
│
└── backups/                     manage.sh backup 的输出目录（自动创建）
```

**本包自洽** —— 不依赖包外文件，整个目录上传即可部署。

> SearXNG 定制镜像的构建源码在仓库 `docs/searxng-custom-image/`，
> 部署不需要，仅在要改搜索行为并重建镜像时查阅。

---

## 服务与端口

| 服务 | 镜像 | 宿主端口 | 容器端口 |
|---|---|---|---|
| snsclaw-server | `snsclaw/server:v1.0.2` | 19600 | 18088 |
| | | 1455 | 1455 |
| postgres | `snsclaw/postgres:16` | 19695 | 5432 |
| searxng | `snsclaw/searxng:latest` | 不暴露 | 8080 |

镜像仓库地址 `221.237.179.2:5000`。

**容器端口不可改**（由镜像固定），只改 compose 里冒号左侧的宿主端口。
例外：**1455 必须宿主与容器同号** —— OpenAI OAuth 回调 URL 写死了
`localhost:1455`，改宿主端口会导致回调失败。

---

## 部署流程

### 0. 上传部署包

```bash
# 目标机：先建目录（新机通常没有 /www）
ssh -p <端口> <用户>@<新机IP> \
  'sudo mkdir -p /www/wwwroot/SnSclaw && sudo chown -R $USER:$USER /www/wwwroot/SnSclaw'

# 本机：上传（源写 deploy/. 带点，否则会多套一层目录）
scp -P <端口> -r deploy/. <用户>@<新机IP>:/www/wwwroot/SnSclaw/
```

目录名 `SnSclaw` 会被 compose 转成小写项目名 `snsclaw`，数据卷即
`snsclaw_postgres_data` / `snsclaw_server_data`。换目录名，卷名跟着变。

### 1. 准备宿主机环境

```bash
cd /www/wwwroot/SnSclaw
bash prepare-host.sh
```

该脚本幂等，做五件事：

1. 安装 `docker-compose-v2` —— Ubuntu 的 `docker.io` 包不含 compose 插件
2. 配置 insecure-registry 并重启 docker（合并写入，备份原文件）
3. 将当前用户加入 docker 组
4. 登录 registry（会提示输密码）
5. 从模板生成 `.env`，自动填充 `JWT_SECRET` / `SEARXNG_SECRET`

> 若第 3 步刚把你加进 docker 组，需**重新登录**后再跑一次 ——
> 组权限不重登不生效，这是 Linux 机制。

### 2. 填写配置

```bash
vi .env
```

只需填三项，其余已有合理默认：

| 变量 | 说明 |
|---|---|
| `DB_PASSWORD` | 应用连接数据库的密码 |
| `DB_ADMIN_PASSWORD` | 数据库超级用户密码 |
| `MATECLAW_PUBLIC_BASE_URL` | 对外访问地址，如 `http://<IP>:19600` |

`MATECLAW_PUBLIC_BASE_URL` 用于生成分享链接和回调 URL，填成浏览器**实际
访问**这台服务的地址。注意 SSH 登录地址未必等于业务访问地址。

LLM 供应商的 API Key **不在这里配置** —— 服务启动后在后台
`设置 → 模型 → 添加供应商` 录入，存于数据库并热加载。

### 3. 部署

```bash
./deploy.sh
```

依次执行 8 项前置检查（任一不过即中止，不留半成品）：

```
docker compose 可用 → daemon 可访问 → insecure-registry 生效
→ .env 完整（含空值检测，权限收紧至 600）→ 初始化脚本就位
→ 端口空闲 → 磁盘 ≥15G → registry 可达且 tag 均存在
```

通过后拉取镜像、按依赖顺序启动、轮询直到 HTTP 200。
容器中途退出会立即打印日志并中止，不干等。

---

## 数据库初始化

全新部署时三阶段自动完成，无需人工介入：

**阶段 1 — 创建角色与 schema**
`docker/postgres/init/10-app-role.sh` 由 Postgres 官方镜像的 initdb 机制执行，
**仅在数据卷为空时运行一次**。创建低权限应用角色、授予 `CONNECT, CREATE`、
建立 `snsclaw` schema。

PostgreSQL 不会自动创建非 public 的 schema，缺了这一步 Flyway 会因权限不足失败。

**阶段 2 — Flyway 建表**
应用启动时执行 181 个迁移至 v187。注意 PostgreSQL 复用 `db/migration/kingbase`
目录（两者 SQL 方言相同）。

**阶段 3 — 灌入种子数据**
`DatabaseBootstrapRunner` 检查 `user` 表是否有行，空则按方言和语言选择脚本
（PostgreSQL + 中文 → `data-kingbase-zh.sql`），灌入管理员账号、菜单权限、
内置智能体、供应商定义。

三阶段均幂等，重启不会重复执行。预期日志：

```
[init] application role 'snsclaw' and schema 'snsclaw' ready
Successfully applied 181 migrations to schema "snsclaw", now at version v187
Auto-initializing database with default locale: zh-CN
Started MateClawApplication
```

界面语言默认中文，改英文在 `.env` 加 `MATECLAW_SETUP_DEFAULT_LOCALE=en-US`。

---

## 日常运维

```bash
./manage.sh status                 # 容器/镜像/卷/健康/近期错误/磁盘
./manage.sh update                 # 拉镜像并重建（数据保留）
./manage.sh update server:v1.0.3   # 改 tag 再更新，先校验 tag 存在性
./manage.sh restart [服务]         # 重启，不拉镜像
./manage.sh start | stop           # 启停
./manage.sh logs [服务]            # 跟踪日志，默认 snsclaw-server
./manage.sh backup                 # 备份数据库 + 文件卷到 ./backups/
./manage.sh reset                  # 删除全部数据，需输入 DELETE 确认
```

服务名：`snsclaw-server` | `postgres` | `searxng`

**除 `reset` 外，所有命令都不会删除数据卷。**

`update <服务>:<tag>` 会在改动 compose **之前**校验 tag 是否存在，避免改坏
文件才在拉取阶段失败；改动前自动备份为 `docker-compose.yml.bak.<时间戳>`。

---

## 更新镜像版本

服务端重新推送镜像后，**必须同步 compose 里的 tag** —— 这是最容易漏的一步。

```bash
# 查看 registry 实际内容
curl -u admin:<密码> http://221.237.179.2:5000/v2/_catalog
curl -u admin:<密码> http://221.237.179.2:5000/v2/snsclaw/server/tags/list

# 更新到指定版本
./manage.sh update server:v1.0.3
```

> 服务端推送时可用 `127.0.0.1:5000`（Docker 默认信任 localhost，无需改
> daemon.json），但 **compose 里必须写 `221.237.179.2:5000`** —— 那是客户端
> 视角的地址。

---

## 数据持久化

两个 named volume。`docker compose down` 不删除，`down -v` 会删除。

| 卷 | 内容 |
|---|---|
| `snsclaw_postgres_data` | 数据库 |
| `snsclaw_server_data` | `/app/data` —— wiki 上传、chat 上传、生成文件、workspace、skills |

备份：

```bash
./manage.sh backup      # 输出到 ./backups/
```

手工恢复：

```bash
# 数据库
docker compose exec -T postgres pg_restore -U snsclaw_admin -d snsclaw --clean < backups/db-<时间戳>.dump

# 文件卷
docker run --rm -v snsclaw_server_data:/data -v $PWD/backups:/backup alpine \
  sh -c 'cd /data && tar xzf /backup/server_data-<时间戳>.tar.gz'
```

备份文件含明文业务数据，注意存放位置与访问权限。

---

## 验证部署

```bash
./manage.sh status                                    # 三个容器应为 Up
curl -o /dev/null -w "%{http_code}\n" http://localhost:19600/   # 期望 200
```

浏览器访问 `http://<IP>:19600`，默认账号 `admin` / `admin123`，
**登录后立即修改密码**。

随后配置 LLM 供应商：`设置 → 模型 → 添加供应商`，填入云端供应商的 Key
并测试连接。本部署不使用本地 GPU 推理。

---

## 常见问题

**`DB_PASSWORD is required in .env`**
`.env` 未创建或未填。compose 用 `:?` 语法强制校验，是有意设计。

**`http: server gave HTTP response to HTTPS client`**
insecure-registry 未生效。用 `docker info | grep -A3 insecure` 确认。
注意 `systemctl restart docker` 命令返回不代表 daemon 已就绪，等几秒再试。
另注意 `docker login` 的地址**不要加 `http://` 前缀**，Docker 不接受 scheme。

**`unknown command: docker compose`**
Ubuntu 的 `docker.io` 包不含 compose 插件：
`sudo apt install -y docker-compose-v2`

**镜像拉取中途 `connection reset by peer`**
网络不稳定。重跑即可，已下载的层会复用。若反复失败，先用 curl 验证链路：
`curl -u admin:<密码> http://221.237.179.2:5000/v2/_catalog`

**应用连不上数据库 / Flyway 报权限错误**
`docker/postgres/init/10-app-role.sh` 未随包上传，或在数据卷已存在后才补上。
该脚本仅在**首次初始化空数据卷**时执行。全新部署阶段可重来：

```bash
docker compose down -v      # 删除数据卷，仅限尚无有效数据时使用
./deploy.sh
```

**端口被占用**
`ss -tlnp | grep -E ':(19600|19695|1455) '` 查冲突，改 compose 里冒号左侧的值
（1455 除外，必须与容器同号）。

**服务起来了但搜索无结果**
SearXNG 需要 JSON 输出且禁用 Limiter，定制镜像已处理。验证：

```bash
docker compose exec snsclaw-server \
  wget -qO- 'http://searxng:8080/search?q=test&format=json' | head -c 200
```

---

## 已知问题

**内置 skill 部分缺失（PostgreSQL 环境）**

若干内置 skill 携带二进制文件（PDF 模板、PNG 图标），被当作文本写入
PostgreSQL 的 `text` 字段时，因含 `0x00` 字节被拒绝，导致事务回滚，
受影响的 skill 只有元数据而无文件内容。

H2 / MySQL 允许 `0x00`，故该问题仅在 PostgreSQL 部署时出现。

首次启动会看到 `Application run failed`，随后容器自动重启并成功启动 ——
服务可用，但内置 skill 不完整。属上游代码缺陷，修复需改动
`SkillFileService` 并重建镜像。

排查命令：

```bash
docker compose exec -T postgres psql -U snsclaw_admin -d snsclaw -c \
  "select count(*) from (select s.id from snsclaw.mate_skill s
   left join snsclaw.mate_skill_file f on f.skill_id=s.id
   group by s.id having count(f.id)=0) t;"
```
