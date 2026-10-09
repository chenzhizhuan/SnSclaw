---
title: DeepSeek Harness 集成
description: 配置、验证、升级和恢复受管理的 DeepSeek Harness SDK 运行时。
---

# DeepSeek Harness 集成

DSH 是数字员工运行时。SnSclaw 启动官方 `dsh --profile sdk` 进程，把 JSON-RPC 会话事件投影到聊天界面。先在 **设置 → 模型** 配置 DeepSeek 提供方，再为有工作空间的数字员工选择 **DSH / DeepSeek Harness**。

## 环境与安装

受管理安装要求 **Node.js 22**、npm 和 npm 仓库网络访问。安装器接受 macOS arm64 与 Linux x64 候选环境。实际 npm 安装、Java 适配器握手和真实模型任务已在 macOS arm64、Node 22.20.0、npm 11.6.2 上通过；Linux x64 仍待实机验证，Windows/WSL 未验证。

在 DSH 管理控制台安装固定目标 **`0.2.0-rc.1`**，另一个受审查版本为 **`0.1.7-rc.2`**。两者均为预发布版本。安装使用仓库内依赖锁文件，在独立候选目录运行 `npm ci`，不会追踪移动的 `latest` 标签，也不会修改全局 npm 包。

依次运行 **校验**、**测试连接**、**测试任务**：分别检查配置、执行 SDK `initialize` 握手、调用真实模型并要求精确回答 `OK`。握手成功不等于模型可用。协议中的服务版本 `0.0.1` 不是 npm 包版本；受管理安装单独记录固定包的来源。外部可执行文件需要另行验证版本。

## SDK 配置

可执行文件必须是 `node_modules/.bin/dsh` 的绝对路径，profile 必须为 `sdk`，工作目录必须存在，并设置专用 home 根目录。可选 patch 列表是按顺序传入的绝对文件路径 JSON 数组。SnSclaw 按工作空间/员工分配独立 `DSH_HOME`，直接传递参数，不经过 shell。

| 配置键 | 用途 |
| --- | --- |
| `dsh.executable_path` | SDK CLI 可执行文件绝对路径 |
| `dsh.profile` | 固定为 `sdk` |
| `dsh.working_directory` | 默认工作目录 |
| `dsh.home_root` | 隔离运行时 home 的根目录 |
| `dsh.patch_paths` | 有序 patch 路径 JSON 数组，例如 `[]` |

旧 `dsh-jsonrpc-agent`/Cordis 入口配置必须迁移。清空 `dsh.cordis_config_path`，移除旧 `DSH_CORDIS_CONFIG` 环境变量，改用 SDK CLI。可执行文件字段不能包含 shell 命令或附加参数。`DSH_JSONRPC_AGENT` 仍作为兼容环境变量名接受可执行文件路径，并不表示继续使用旧二进制。

凭证保存在模型提供方或受保护的运行时配置中，由 SnSclaw 注入子进程环境。对于 DeepSeek 官方域名，适配器将普通 API 根地址映射到这些包要求的 Messages API `/anthropic` 路径；其他自定义地址仍须提供兼容的 Messages API。

SnSclaw 通过 `initialize.maxTokens` 显式设置输出上限：使用模型配置的正数值，缺省为 4096，并限制为已知上下文窗口的一半。这不替代 DSH 自身的上下文管理。

## 升级与回滚

受管理升级先准备独立包版本，阻止新 DSH 回合进入，最多等待 60 秒让现有回合结束；随后复制运行时 home 和配置的 patch 文件，在各受影响 home 中执行候选握手与新模型任务，通过后原子切换可执行文件、配置及 home 所属代次。原 home 和旧代次保留；激活前失败继续使用旧选择。原生运行时不受 DSH 准入门控制。

home 复制拒绝符号链接与特殊文件，不会跟随链接。包含这些文件的自定义 home 需要显式迁移。代次记录和包目录保留，但活动 home 在正常运行时仍会变化。

只有已完成操作的候选代次仍处于活动状态时，才能对该操作回滚。回滚复制并检查保留的旧 home/配置后再切换，不会合并升级后产生的会话，也不会逆向修改数据库。验收完成前保留旧代次和备份。

候选检查创建新会话，不能证明每个历史 DSH 会话均可重新打开或迁移。**真实持久化 V3 → V4 历史迁移尚未验证。**

DSH 使用 profile 管理的工具，不接入 SnSclaw 宿主审批；启用成员文件隔离时禁止运行此运行时。回滚不会撤销工具对工作目录文件的写入，也不会删除 SnSclaw 聊天记录。

## 会话与诊断

每个 SnSclaw 回合使用新的 SDK 会话 ID，并回放有界的已完成用户/助手文本历史；这不是恢复 DSH 工具状态。不要用已存在的持久化 SDK ID 创建新会话。是否完成由 SDK 终止事件决定，不能只依赖入队回执或 idle 状态。

管理 API 前缀为 `/api/v1/admin/dsh`：`GET /status`、`POST /verify`、`/test-connection`、`/test-task`、`/upgrades`，以及 `GET /upgrades/{id}`。升级请求包含精确 `targetVersion`、当前 `expectedRevision`、`idempotencyKey`；回滚接口为 `POST /upgrades/{id}/rollback`，携带版本修订号与幂等键。通过操作记录查询进度。日志及状态不得输出凭证。

## 孤儿运行时恢复

`dsh.orphaned_runtime_requires_recovery` 表示某个租约所属 JVM 已退出。系统继续拒绝准入，因为遗留 DSH 进程或工具子孙进程仍可能写入 home。

1. 停止 DSH 流量，定位受管理安装根目录下 `leases/` 中的租约；默认根目录为 `~/.mateclaw/runtimes/deepseek-harness`。
2. 确认记录的 JVM 已退出，识别并停止其孤儿 DSH 进程及所有工具子孙进程，确认受影响 home 已无写入进程。
3. 备份状态后，仅删除已确认过期的租约。不要删除活动租约或直接清空租约目录。
4. 检查持久化升级操作与活动代次，重新验证后再恢复任务。

仅重启后端不能完成孤儿进程清理。不要为绕过错误直接删除租约。

> AI生成
