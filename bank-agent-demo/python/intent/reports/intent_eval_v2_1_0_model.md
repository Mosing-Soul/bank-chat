# 意图识别评估报告（v2.1.0）

## 运行信息

- 运行时间（UTC）：`2026-08-04T06:32:29.154961+00:00`
- 评估模式：`model`
- 模型/识别器：`deepseek-v4-pro`
- 置信度阈值：`0.6`
- Temperature：`0`
- 并发数：`4`
- 样本量：`35`
- 数据集 SHA-256：`f00c1097fe2aaa7d62b9932a38e78fc9778ef2d016bf9ae43547f2c2eb67061f`

## 汇总

| 指标 | 正确/总数 | 准确率 |
| --- | ---: | ---: |
| 总体 | 35/35 | 100.00% |
| 意图判定 | 35/35 | 100.00% |
| UNKNOWN/澄清判断 | 11/11 | 100.00% |
| UNKNOWN/澄清召回 | 6/6 | 100.00% |

## 按期望意图

| 意图 | 正确/总数 | 准确率 |
| --- | ---: | ---: |
| CUSTOMER_AUM_QUERY | 5/5 | 100.00% |
| EXTERNAL_API_QUERY | 6/6 | 100.00% |
| GENERAL_CHAT | 5/5 | 100.00% |
| KNOWLEDGE_QA | 5/5 | 100.00% |
| MESSAGE_SEND | 6/6 | 100.00% |
| MODEL_TOP1 | 2/2 | 100.00% |
| UNKNOWN | 6/6 | 100.00% |

## 按样本类型

| 类型 | 正确/总数 | 准确率 |
| --- | ---: | ---: |
| boundary | 2/2 | 100.00% |
| clarification | 4/4 | 100.00% |
| clear | 21/21 | 100.00% |
| compound_top1 | 2/2 | 100.00% |
| missing_slot | 2/2 | 100.00% |
| underspecified | 4/4 | 100.00% |

## 失败样本

无。

## 判分说明

- 意图：普通样本要求 expectedIntent 精确匹配；MODEL_TOP1 样本要求输出属于 allowedIntents 且只返回一个非 UNKNOWN 意图。
- 澄清：本评估聚焦 Python 意图层；actualIntent=UNKNOWN 视为应进入澄清。
- 候选：配置 expectedCandidateIntents 时，期望候选必须全部出现在实际候选中。
- `offline` 报告是可重复的规则降级基线，不代表线上大模型效果；模型效果需使用 `--mode model` 单独运行并保留报告。
