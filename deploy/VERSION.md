# 版本记录

记录部署包与镜像版本的对应关系。每次更新镜像 tag 后在此追加一行，
便于回滚时知道该退到哪个版本。

## 当前

| 项 | 值 |
|---|---|
| 部署包版本 | 1.0.0 |
| 更新日期 | 2026-08-21 |
| registry | `221.237.179.2:5000` |

| 服务 | 镜像 | tag |
|---|---|---|
| snsclaw-server | `snsclaw/server` | `v1.0.2` |
| postgres | `snsclaw/postgres` | `16` |
| searxng | `snsclaw/searxng` | `latest` |

数据库 schema 版本：Flyway v187（181 个迁移）

## 变更历史

### 1.0.0 — 2026-08-21

首个版本。

- 三容器编排：server / postgres / searxng
- 端口：19600（应用）、19695（数据库）、1455（OAuth 回调）
- 脚本三件套：`prepare-host.sh` / `deploy.sh` / `manage.sh`
- 镜像 tag：`server:v1.0.2`、`searxng:latest`、`postgres:16`

已知问题见 README「已知问题」一节。

---

## 回滚

```bash
./manage.sh update server:<旧tag>
```

若数据库 schema 也需回退，先恢复对应时间点的备份 —— Flyway 不支持向下迁移，
新版本迁移过的库直接跑旧版本应用可能失败。
