# 对话助手意图理解与任务编排架构调研提纲

> 文档状态：调研与方案评审中
>
> 最后整理：2026-07-17
>
> 适用范围：当前四类技能及未来银行业务技能扩展

## 1. 文档目的

本文整理当前项目在意图识别、多轮对话和技能切换方面已经暴露的问题，分析问题为何不能通过继续增加正则或单独优化提示词彻底解决，并汇总业界产品与相关论文采用的主要方案。

本文不是最终技术选型结论。下一步需要结合项目规模、模型成本、响应延迟、数据安全、可测试性和银行业务风险进一步验证。

## 2. 当前系统概况

当前助手主要包含以下能力：

- 行内知识问答/RAG。
- 客户资产及 AUM 查询。
- 黄金价格等外部信息查询。
- 客户消息生成、确认和模拟发送。

当前处理链路大致为：

```text
用户输入
  → Java 确定性路由与实体提取
  → 技能状态机尝试处理
  → Python 意图识别与技能执行
  → 低置信度时进行意图澄清
```

Java/Redis 已经保存部分对话状态，消息触达和客户资产查询也已经开始使用状态机。但是，目前的全局路由、技能内部状态机和 Python 意图识别还没有建立统一的会话语义模型。

## 3. 已经出现的实际问题

### 3.1 每轮重新识别意图导致循环

典型场景：

1. 用户询问“帮我查一下客户等级”。
2. 系统让用户选择“客户资产查询”或“行内知识问答”。
3. 用户选择客户资产查询。
4. 系统要求提供客户姓名。
5. 用户回复“张伟”。
6. 系统把“张伟”当成新的独立问题，再次识别意图或重复询问。

根因不是姓名正则本身，而是系统没有稳定地区分：

- 顶层业务选择；
- 当前任务的参数补充；
- 当前任务操作；
- 新任务请求。

### 3.2 当前任务容易吞掉新问题

消息触达进入待确认阶段后，状态机主要识别：

- 确认发送；
- 取消；
- 修改消息。

当用户说“帮我查一下”“更换技能”或提出一个未被 Java 规则覆盖的新问题时，消息状态机可能把它当作无效确认回复，继续要求用户“确认、取消或修改为……”。

这虽然避免了误发送，但交互表现为当前任务被强锁定，用户难以自然切换话题。

### 3.3 “修改当前任务”和“切换任务”边界模糊

例如：

- “换成李明”可能是在修改收件人。
- “换个问题”是在切换业务。
- “帮我查一下他的 AUM”既包含新任务，又引用当前任务中的客户。
- “先查一下黄金价格，等会再发”包含暂停、启动新任务和保留旧任务三个动作。

依赖“修改、换、重新”等关键词无法可靠区分这些情况。

### 3.4 单标签意图无法描述真实对话动作

传统输出通常是：

```json
{
  "intent": "CUSTOMER_AUM"
}
```

但用户一轮话语可能同时包含多个动作：

```text
先别发，帮我查一下张伟的 AUM。
```

它至少包含：

1. 暂停消息发送；
2. 启动客户资产查询；
3. 设置客户姓名为张伟。

单一 intent 标签无法完整表达。

### 3.5 技能越多，中心路由越难维护

如果继续使用全局正则和技能特例，未来每增加一个银行业务技能，都可能需要修改：

- 全局路由规则；
- 实体提取规则；
- 意图提示词；
- 当前技能的打断判断；
- 其他技能的防误触逻辑；
- 前端澄清选项；
- 多轮测试用例。

技能之间会形成组合爆炸，最终变成“遇到一个问题修一个分支”。

### 3.6 安全性与自然交互存在张力

只读查询可以较自由地切换；消息发送、交易和资料修改等有外部副作用的业务不能仅凭模型推断执行。

因此系统必须同时满足：

- 用户可以自然插话、纠正和切换；
- 高风险动作必须确定性校验和明确确认；
- 不能因为追求自然语言体验而绕过权限、确认和审计。

## 4. 根因归纳

目前的问题可以归纳为四个架构缺口：

### 4.1 意图识别缺少完整上下文

系统部分组件只看当前文本，没有统一使用当前任务、阶段、已填参数、待填参数和最近对话。

### 4.2 对话状态模型不统一

已有 `activeSkill` 和技能状态，但缺少统一的任务栈、暂停、恢复、切换和跨任务参数传递模型。

### 4.3 对话理解与业务执行边界不清晰

正则、LLM、状态机都在不同程度上尝试决定“下一步做什么”，容易产生优先级冲突。

### 4.4 技能定义主要体现在代码中

技能需要的参数、风险等级、打断策略和执行条件没有形成统一的声明式定义，因此通用引擎无法根据配置处理。

## 5. 业界主要方案

### 5.1 传统方案：Intent + Entity/Slot + Dialogue State

传统任务型对话通常拆成：

- 意图分类（Intent Classification）；
- 实体或槽位抽取（Entity/Slot Filling）；
- 对话状态跟踪（Dialogue State Tracking，DST）；
- 对话策略（Dialogue Policy）；
- 业务执行（Fulfillment）。

这种方案并未过时。它的优点是可解释、可测试、执行确定，缺点是训练语料和领域配置成本较高，对复杂插话、指代、纠正和多动作表达适应较弱。

相关综述：

- [Recent Neural Methods on Slot Filling and Intent Classification for Task-Oriented Dialogue Systems](https://aclanthology.org/2020.coling-main.42/)
- [Transferable Multi-Domain State Generator for Task-Oriented Dialogue Systems](https://aclanthology.org/P19-1078/)
- [CrossWOZ: A Large-Scale Chinese Cross-Domain Task-Oriented Dialogue Dataset](https://aclanthology.org/2020.tacl-1.19/)

### 5.2 Google Dialogflow CX：Flow、Page、Form 和分层路由

Dialogflow CX 将一次会话显式描述为状态机：

- Flow 表示一类业务流程；
- Page 表示流程中的当前阶段；
- Form Parameters 表示需要多轮收集的参数；
- Page/Flow/Agent 级 Route 处理不同范围的意图和事件；
- Session Parameters 保存跨轮参数。

值得借鉴的不是具体产品，而是“局部流程规则 + 全局公共路由”的分层作用域。帮助、退出、转人工等可以作为全局模式，当前参数收集作为局部模式。

官方资料：

- [Dialogflow CX Pages](https://docs.cloud.google.com/dialogflow/cx/docs/concept/page)
- [Dialogflow CX Flows](https://docs.cloud.google.com/dialogflow/cx/docs/concept/flow)
- [Dialogflow CX Parameters and Form Filling](https://docs.cloud.google.com/dialogflow/cx/docs/concept/parameter)
- [Dialogflow CX State Handlers and Route Scope](https://docs.cloud.google.com/dialogflow/cx/docs/concept/handler)

### 5.3 Amazon Lex V2：Session State、意图切换与恢复

Amazon Lex V2 使用 Session State 保存当前 intent、slot 和 dialog action，并支持：

- 切换到另一个意图；
- 修改新意图的槽位；
- 完成插入任务后恢复旧意图；
- 在参数收集或确认期间，通过策略决定是否允许意图切换；
- 使用 Session Attributes 或 Context 在意图之间共享数据。

这说明“用户能不能在当前阶段切换”不是统一答案，而应该是每类业务可配置的策略。

官方资料：

- [Understanding Amazon Lex V2 Bot Sessions](https://docs.aws.amazon.com/lexv2/latest/dg/managing-sessions.html)
- [Disabling Intent Switches](https://docs.aws.amazon.com/lexv2/latest/dg/context-mgmt-request-attribs.html)
- [Sharing Information Between Intents](https://docs.aws.amazon.com/lexv2/latest/dg/context-mgmt-cross-intent.html)
- [Intent Confidence Scores](https://docs.aws.amazon.com/lexv2/latest/dg/using-intent-confidence-scores.html)

### 5.4 Rasa CALM：从单标签 Intent 转向结构化 Command

Rasa CALM 是与本项目问题最接近的现代设计。它让语言模型读取：

- 当前激活的 Flow；
- 对话栈；
- 已填槽位；
- 相关 Flow 定义；
- 对话历史。

模型不直接执行工具，而是生成结构化命令，例如：

- 启动 Flow；
- 终止或暂停当前 Flow；
- 设置槽位；
- 请求澄清；
- 回答知识问题；
- 转人工。

随后由确定性的 Dialogue Manager 执行命令。这种方式支持一轮话语生成多个命令，也能处理用户回答当前问题的同时开启新任务。

官方资料：

- [Rasa CALM Overview](https://rasa.com/docs/learn/concepts/calm/)
- [Rasa Command Generator](https://rasa.com/docs/pro/customize/command-generator/)
- [Rasa Dialogue Management](https://rasa.com/docs/learn/concepts/dialogue-management/)
- [Rasa Writing Flows and Dialogue Stack](https://rasa.com/docs/pro/build/writing-flows/)
- [Rasa Business Logic with Flows](https://rasa.com/docs/reference/primitives/flows/)

### 5.5 LLM 与确定性业务逻辑结合

研究方向正在尝试用大模型减少传统 NLU 对训练语料和固定 ontology 的依赖，但并不意味着让模型直接控制业务系统。一种主流思路是由 LLM 理解复杂对话，由确定性逻辑完成参数校验和业务执行。

参考资料：

- [Task-Oriented Dialogue with In-Context Learning](https://arxiv.org/abs/2402.12234)
- [Intent-driven In-context Learning for Few-shot Dialogue State Tracking](https://arxiv.org/abs/2412.03270)
- [Beyond Ontology in Dialogue State Tracking for Goal-Oriented Chatbot](https://arxiv.org/abs/2410.22767)
- [Multi-Domain Dialogue State Tracking with Disentangled Domain-Slot Attention](https://aclanthology.org/2023.findings-acl.304/)

这些论文主要证明技术可行性，不等同于银行生产系统的工程和合规方案，需要单独评估数据、稳定性和安全性。

## 6. 调研结论

### 6.1 没有单一模型能够解决全部问题

无论传统 NLU 还是 LLM，都不能独立承担完整的业务对话管理。成熟方案仍然需要：

- 对话状态；
- 参数/槽位定义；
- 业务流程；
- 切换和打断策略；
- 确定性校验；
- 高风险确认；
- 评估数据集。

### 6.2 不应继续以“每句话一个意图”为中心

意图仍然有价值，但应该被包含在更完整的“对话动作/命令”中。系统需要理解用户如何推进当前会话，而不仅是给当前句子贴标签。

### 6.3 配置不会消失，但配置内容应该改变

不推荐继续配置大量同义句和正则。更有价值的配置是：

- 技能用途和边界；
- 必填和选填参数；
- 参数类型及校验方式；
- 风险等级；
- 是否允许打断；
- 打断时暂停还是取消；
- 是否需要确认；
- 执行器和权限要求。

### 6.4 推荐混合架构

适合当前项目的方向是：

```text
候选技能检索
  + 上下文 LLM 命令理解
  + 确定性对话状态与策略
  + 声明式 Flow
  + 受控技能执行器
```

## 7. 拟议的目标架构

```text
用户输入
   │
   ▼
全局输入预处理
鉴权、脱敏、显式确认/取消等高精度命令
   │
   ▼
候选技能检索
根据文本、当前状态、权限和领域筛选 Top-K
   │
   ▼
上下文对话理解器
输入当前对话栈、Flow、槽位和候选技能
输出结构化 DialogCommand 列表
   │
   ▼
策略与状态管理器
校验命令、处理暂停/恢复/切换/澄清
   │
   ▼
通用 Flow 引擎
收集参数、校验、确认、执行、完成
   │
   ▼
技能执行器
调用 Java 内部服务、RAG 或外部 API
```

### 7.1 技能定义

建议每个技能通过统一配置注册：

```yaml
id: MESSAGE_SEND
name: 客户消息触达
description: 生成客户消息并在明确确认后发送
riskLevel: EXTERNAL_SIDE_EFFECT
interruptPolicy: CONFIRM_OR_SUSPEND
confirmationRequired: true
slots:
  customer:
    type: customer_reference
    required: true
  messagePurpose:
    type: text
    required: true
executor: messageSendExecutor
```

### 7.2 对话命令

建议统一支持：

```text
START_FLOW
SUSPEND_FLOW
RESUME_FLOW
CANCEL_FLOW
COMPLETE_FLOW
SET_SLOT
CLEAR_SLOT
CONFIRM
REJECT
REQUEST_CLARIFICATION
ANSWER_KNOWLEDGE
HANDOFF_HUMAN
NO_OP
```

一轮用户输入可以产生多个命令。

### 7.3 对话栈

建议从单一 `activeSkill` 升级为可暂停和恢复的 Flow Stack：

```json
{
  "flowStack": [
    {
      "flow": "MESSAGE_SEND",
      "status": "SUSPENDED",
      "stage": "WAITING_CONFIRMATION",
      "slots": {
        "customerName": "张伟"
      }
    },
    {
      "flow": "CUSTOMER_AUM",
      "status": "ACTIVE",
      "stage": "COLLECTING_SLOTS",
      "slots": {}
    }
  ]
}
```

### 7.4 策略引擎

策略引擎必须确定性决定：

- 当前 Flow 是否允许打断；
- 打断时暂停、取消还是必须确认；
- 新 Flow 是否允许启动；
- 是否能复用前一 Flow 的参数；
- 操作是否需要授权和二次确认；
- LLM 输出的命令是否合法。

LLM 只提出命令，不直接调用高风险业务接口。

## 8. 规则、模型和配置的职责边界

| 能力 | 推荐责任方 |
| --- | --- |
| 明确的确认、取消、退出 | 高精度规则与全局模式 |
| 客户编号、金额、日期等格式值 | 确定性解析器/实体服务 |
| 修改、插话、切换、指代、多动作理解 | 上下文 LLM 命令理解器 |
| 技能所需参数 | Flow 配置 |
| 是否允许打断及如何恢复 | 策略配置 |
| 参数真实性和业务合法性 | Java 业务服务 |
| 外部操作执行 | 受控 Java Executor |
| 回复自然度 | 模板或 LLM 表达层 |
| 低置信度和冲突 | 结构化澄清 |

## 9. 银行业务必须保留的安全边界

未来即使采用更强的 LLM，也必须保留：

- 用户身份、角色和数据权限校验；
- 高风险操作明确确认；
- 参数白名单和业务合法性校验；
- 幂等键，避免重复发送或重复交易；
- 操作状态机，禁止非法回退；
- 完整审计日志；
- 敏感字段脱敏；
- 会话状态 TTL；
- 模型输出 Schema 校验；
- 模型不可直接访问生产执行凭据；
- 失败降级和人工接管。

## 10. 建议实施路径

### 阶段一：统一概念和数据结构（已建立定义层）

- `已完成`：定义 `SkillDefinition`、`FlowDefinition`、`SlotDefinition`、风险等级和打断策略。
- `已完成`：将现有四个技能注册到声明式运行时配置，并增加启动校验和查询注册表。
- `待后续阶段`：定义运行时 `FlowInstance`、`DialogCommand` 和 `ConversationPolicy`。
- 保留现有执行器，不立即重写业务服务。

当前定义配置位于 `bank-chat-middle-platform/src/main/resources/config/skill-definitions.json`。本阶段只建立统一定义层，尚未用通用 Flow Engine 替换现有路由和技能状态机。

### 阶段二：引入对话栈（客户 AUM 试点已完成）

- `已完成`：`DialogState` 从单一 `activeSkill` 扩展出持久化 `flowStack`，并保留旧状态字段兼容投影。
- `已完成`：实现由声明式阶段驱动的通用 Flow Engine，当前支持参数收集、校验、执行、完成和取消。
- `已完成`：客户 AUM 已迁移为首个 `FlowSkillHandler`，清晰的 AUM 请求进入 Java Flow 试点链路。
- `待后续阶段`：实现通用暂停、恢复、确认和跨 Flow 切换策略。
- `待后续阶段`：迁移知识问答、黄金查询和消息触达。

当前 Flow Engine 只负责确定性阶段推进，不承担语义意图判断。复杂切换和多动作输入仍留给后续 `DialogCommand` 理解层。

### 阶段三：实现上下文命令理解器（已接入主链路，可配置回滚）

- `已完成`：定义统一 `DialogCommand`，支持开始、暂停、恢复、取消、设置/清除槽位、确认、拒绝、澄清和空操作。
- `已完成`：Java `ConversationPolicy` 根据风险等级、打断策略、当前阶段和置信度裁决命令。
- `已完成`：Java `DialogCommandDispatcher` 支持确定性的开始、暂停、恢复、取消及槽位变更。
- `已完成`：Python 新增 `/ai/dialogue/commands` 结构化命令接口，输入当前 Flow、阶段、槽位、候选技能和必要历史。
- `已完成`：Java 新增命令理解调用服务；发送模型前默认脱敏敏感槽位值。
- `已完成`：命令理解、Policy、Dispatcher 和 Flow 推进已编排进 `/api/chat`；`NO_OP`、命令服务不可用及前端显式选定技能时降级到原路由。
- `已完成`：同一轮支持 `CONFIRM -> EXECUTE`，避免消息流程只推进状态而未执行；仍由业务服务校验确认标记和操作幂等性。
- `回滚开关`：通过 `AI_DIALOGUE_COMMAND_ENABLED=false` 可关闭主链路命令编排。
- `待后续迁移`：黄金查询、知识问答和消息触达迁入 Flow Engine 后，再启用主链路命令路由。

当前规则只处理确认、取消、恢复、明确 AUM/黄金请求和无目标切换等高精度动作；复杂语义由结构化 LLM 输出补充，所有输出仍需经过 Java Policy 校验。

### 阶段四：迁移现有技能（现有四个技能已完成）

建议顺序：

1. `已完成`：客户资产查询，只读且流程简单。
2. `已完成`：行内知识问答和外部黄金行情查询已接入通用 Flow；保留原始问题槽位并复用现有执行服务。
3. `已完成`：消息触达已迁入通用 Flow，覆盖客户与用途收集、客户校验、预览、修改、明确确认和模拟发送；未确认时不会调用发送服务。
4. 再增加新的银行技能。

主链路编排已完成。下一步进入完整 transcript 评测，覆盖插话、暂停恢复、多动作、模糊表达、权限与执行失败等场景，并根据评测结果调整命令置信度和澄清策略。

在进入批量评测前，已补充安全的跨 Flow 槽位引用：发送给 LLM 的可共享敏感值使用 `flow-slot://<flowInstanceId>/<slotId>` 表示，模型只复制引用，不接触真实客户姓名或编号。Java Dispatcher 仅在来源 Flow 属于当前会话、来源与目标槽位均声明为 `shareable` 且类型一致时解析真实值；非法、不可共享或已取消 Flow 的引用会在状态变更前拒绝。

### 阶段五：建立完整对话评测（基础框架已完成，持续扩充场景）

评测单位从单句升级为完整 transcript，并校验每轮命令和状态：

```yaml
- user: 给张伟生成到期提醒
  expected:
    - START_FLOW: MESSAGE_SEND

- user: 先查一下他的AUM
  expected:
    - SUSPEND_FLOW: MESSAGE_SEND
    - START_FLOW: CUSTOMER_AUM
    - SET_SLOT:
        customerName: 张伟

- user: 查完继续刚才的消息
  expected:
    - RESUME_FLOW: MESSAGE_SEND
```

重点覆盖：

- 参数补充；
- 修改和纠正；
- 插话与切换；
- 暂停和恢复；
- 指代；
- 一句话多动作；
- 模糊表达；
- 连续无关回答；
- 高风险确认；
- 权限不足；
- 执行超时、失败和重试。

当前已完成：

- 建立 JSON Transcript 数据格式和数据驱动运行器，首批覆盖消息插话 AUM、安全客户指代、恢复消息、确认、裸姓名补槽、模糊切换和黄金原问题保留。
- Dispatcher 对整组命令先在状态副本中预演；存在拒绝或澄清时不修改原始会话状态，避免部分执行。
- 建立恢复策略：被暂停的只读 Flow 可配置自动恢复；消息发送等外部副作用 Flow 只提示用户明确恢复，不自动推进。
- 增加命令理解、模型使用、应用、拒绝、澄清、降级和 Flow 完成计数，并输出不包含槽位值的结构化审计日志。
- 将重复的技能名称归一化和高精度旧意图切换规则集中到 `LegacyIntentFallback`；旧链路当前仅作为命令服务不可用或 `NO_OP` 时的降级保护，待 Transcript 覆盖率和线上指标达标后再删除。

## 11. 需要进一步调研和验证的问题

### 11.1 技术选型

- 自研轻量 Flow/Command 引擎，还是引入 Rasa 等框架？
- 现有 Java 状态机扩展为通用引擎的成本是多少？
- 是否需要工作流框架，还是当前 Redis + Java 足够？

### 11.2 模型和成本

- 命令理解使用通用大模型、轻量模型还是微调模型？
- Top-K 技能检索能否把延迟和 Token 控制在可接受范围？
- 中文银行话术和内部术语的识别效果如何？

### 11.3 准确率和可控性

- 多命令输出的准确率如何评价？
- 低置信度阈值和澄清策略如何确定？
- 模型输出与状态机冲突时如何裁决？
- 如何防止历史上下文污染当前槽位？

### 11.4 数据与合规

- 哪些上下文允许发送给模型？
- 客户姓名、编号和资产信息是否需要替换为临时引用？
- 是否需要部署行内模型或专有环境？
- 审计日志应保存原文、脱敏文本还是结构化命令？

### 11.5 产品交互

- 新任务打断旧任务时，默认暂停还是询问？
- 只读任务与有副作用任务是否使用不同策略？
- 前端如何展示“当前办理事项”和“已暂停事项”？
- 用户如何明确返回先前任务？

## 12. 当前建议

在完成上述调研前，不建议继续大规模增加技能内部的意图切换正则。

短期修复仍可以用于保证现有演示链路，但应遵循两个原则：

1. 只增加高精度、安全相关的临时规则。
2. 新逻辑尽量落在通用状态、命令或策略层，不继续扩大单个技能的特殊分支。

当前优先级应从“继续增加技能”暂时调整为“建立统一对话语义和状态模型”。如果这一层稳定，后续新增银行技能的成本和相互影响才可能得到控制。
