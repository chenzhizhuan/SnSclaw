# 结构化决策层

决策层提供可替换的结构化判断接口，不执行工具，也不替代现有权限、完成验收或状态机。请求使用 Choice、Boolean 或 Score，Provider 输出建议后仍须通过原有业务约束。

## 模式与配置

`mate.decision.mode` 默认 `SHADOW`：

| 模式 | 行为 |
|---|---|
| OFF | 直接使用旧判断，不调用新的 Provider 或决策记录存储 |
| SHADOW | 原判断继续生效；有界后台队列计算新建议并记录比较结果 |
| ACTIVE | 使用通过硬约束、类型和置信度检查的建议；实际状态提交仍由领域服务控制 |

下面是仅启用 Goal 观察的分阶段配置示例；未配置场景覆盖时，三个场景均继承全局 SHADOW。

```yaml
mate:
  decision:
    mode: SHADOW
    provider: rule
    confidence-threshold: 0.8
    timeout-ms: 250
    provider-threads: 4
    shadow-threads: 2
    queue-capacity: 64
    retention-days: 30
    retention-batch-size: 500
    retention-interval-ms: 3600000
    scenarios:
      GOAL_CONTINUATION: SHADOW
      WORKER_RESULT: OFF
      AGENT_ROUTING: OFF
```

场景配置优先于全局模式。完全关闭时，将全局模式设为 OFF，并清除或关闭各场景覆盖项。当前内置 `rule` 返回原判断且不伪造置信度，`llm` 为明确返回不可用的占位 Provider；本阶段不调用新的模型服务。

## 处理流程

1. Deterministic Guard 先检查硬限制，可直接跳过 Provider。
2. Provider 在有界线程池和超时约束下产生类型化建议。
3. 类型非法、候选不存在、超时、异常、弃权或置信度不足时回到原判断。
4. Policy Override 应用领域策略，不能突破 Guard。
5. 保存原判断、Provider 建议、策略后的有效建议；实际提交结果另外保存。

SHADOW 永远返回原判断；队列满或后台记录失败只影响观察覆盖率。ACTIVE 在返回建议前持久化记录，实际效果参与调用方业务事务。建议持久化成功不代表业务已提交。

## 场景边界

- Goal 续写保留完成证据、预算和租约约束；ACTIVE 建议只能对原本允许的继续动作进行保守的延后或重试选择。
- Worker 判断保留确定性的无效结果检查、checkpoint 与审批要求；ACTIVE 语义拒绝通过既有状态机令任务失败，不增加执行重试次数。提交时在锁内重验原 owner、派发次数和会话。
- Agent Routing 只覆盖非 Team 的计划步骤；Team Lead、渠道绑定和显式工具委派仍保持原实现。

## 可观测与隐私

记录使用 `mate_decision_record` 和 `mate_decision_outcome`。字段包括场景、模式、Provider/版本、策略/问题版本、工作区和执行标识、管线计算耗时（不含后续业务提交）、置信度、回退原因和 Override 原因。结果只保存结构化值，不保存原始 Prompt、回答、SQL、工具参数、证据正文或异常消息。

| 结果状态 | 含义 |
|---|---|
| PENDING | 已记录建议，但没有可确认的提交结果；由缺少 outcome 行推导 |
| OBSERVED | 观察到原业务路径的实际结果 |
| APPLIED | 调用方在明确应用边界记录了实际结果 |
| NOT_APPLIED | 建议因并发状态、次数限制等原因未应用 |

图内 `FOLLOWUP_SUBMITTED` 仅表示已构建并提交续写输出；`FOLLOWUP_SUPPRESSED` 表示 ACTIVE DEFER/RETRY 建议阻止了当前续写，不代表已经安排持久重试。这些结果不表示后续节点已经执行，也不提供数据库与图执行之间的原子提交保证。

Micrometer 指标包括 `mate.decision.total`、`mate.decision.duration`、`mate.decision.comparison`、`mate.decision.failure`。业务 ID 不进入指标标签。比较指标区分 Provider 原始建议与策略后的建议；Guard 和不可用 Provider 不应被算作有效模型比较。

Routing v2 的持久记录通过父 Agent、会话和 PLAN_STEP 阶段关联；步骤序号仅是瞬时事实，尚未保存 plan/subplan ID，因此不能从审计记录精确重建每个子计划的对应关系。实际指派结果仍与计划插入同事务保存。

查询记录必须限定工作区和时间范围；当前不新增公开查询接口。分析一致率时还应查看丢弃、失败和缺少 outcome 的数量。Rule 的一致率不能说明新模型质量提高。

## 验证与后续接入

测试通过受控 Provider 验证新行为，不需要配置真实模型。数据库测试使用 H2，并执行 MySQL/PostgreSQL 兼容模式；这不能代替真实 MySQL/Kingbase 验证。

未来 Provider 可通过 Java SPI 实现规则、远程模型或本地推理。上线前需要协议和版本固定、输入最小化、中文领域数据评测、置信度校准、超时/熔断和逐场景灰度。语言能力元数据目前不等于语言识别或校准能力。工业写操作、未知副作用和重试幂等性仍由原执行与审批机制控制。

## 场景策略与路由输入（第二轮）

模式继续使用 `scenarios`；新增 `scenario-policies` 只覆盖 Provider、置信度阈值和超时。每个省略的字段继承全局值，原有配置无需迁移。以下配置仍使用内置 Rule，不接入新模型：

```yaml
mate:
  decision:
    mode: SHADOW
    provider: rule
    confidence-threshold: 0.8
    timeout-ms: 250
    scenario-policies:
      WORKER_RESULT:
        confidence-threshold: 0.95
        timeout-ms: 500
      AGENT_ROUTING:
        provider: rule
        timeout-ms: 150
```

覆盖项在启动时验证；超时范围为 1～60000 毫秒，阈值为有限的 0～1 数值。不存在的 Provider 走 UNAVAILABLE 回退，不偷偷使用全局 Provider。一次请求固定本次策略，SHADOW 排队不改变其 Provider、阈值或超时；不提供配置热更新功能。阈值示例不是经过模型校准的生产建议，Rule 返回基线时不使用置信度阈值。

Routing v2 将当前步骤文本作为瞬时 evidence，将同工作区有效候选和父 Agent 的名称、描述、标签、类型作为选项描述。不传完整会话、原始完整目标、系统提示词、runtime config 或模型凭据；原始目标仅在本地用于保留显式指定 Agent 的约束。描述是未经验证的能力声明，不代表实际工具权限。父 Agent 元数据不可用时使用通用 LOCAL 描述。

当前步骤最多 2048 字符，每个候选描述最多 1024 字符，步骤与全部选项描述合计最多 8192 字符。空步骤或超限走 Guard 并保留原指派，不截断后交给 Provider；旧候选失效时仍优先执行原有 LOCAL 保护。字符预算不等于 tokenizer token 预算。审计只保存结构化结果和问题版本，不保存新增的步骤与候选文本。未来远程 Provider 上线仍须评估这些瞬时文本的数据外发策略。

本轮不增加候选召回、工具能力实时校验或真实语义 Provider；默认 SHADOW/Rule 的一致性不能证明准确率或执行效率提升。
