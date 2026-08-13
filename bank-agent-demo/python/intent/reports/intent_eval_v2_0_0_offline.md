# 意图识别评估报告（v2.0.0）

## 运行信息

- 运行时间（UTC）：`2026-08-03T03:42:51.425803+00:00`
- 评估模式：`offline`
- 模型/识别器：`deterministic-fallback`
- 置信度阈值：`0.6`
- Temperature：`N/A`
- 并发数：`1`
- 样本量：`33`
- 数据集 SHA-256：`059209718eabae40fdc2279bf13da5042dfd9a793c52bc267d4c6a9f23e8166a`

## 汇总

| 指标 | 正确/总数 | 准确率 |
| --- | ---: | ---: |
| 总体 | 33/33 | 100.00% |
| 意图精确匹配 | 33/33 | 100.00% |
| UNKNOWN/澄清判断 | 8/8 | 100.00% |

## 按期望意图

| 意图 | 正确/总数 | 准确率 |
| --- | ---: | ---: |
| CUSTOMER_AUM_QUERY | 5/5 | 100.00% |
| EXTERNAL_API_QUERY | 6/6 | 100.00% |
| GENERAL_CHAT | 4/4 | 100.00% |
| KNOWLEDGE_QA | 6/6 | 100.00% |
| MESSAGE_SEND | 6/6 | 100.00% |
| UNKNOWN | 6/6 | 100.00% |

## 按样本类型

| 类型 | 正确/总数 | 准确率 |
| --- | ---: | ---: |
| boundary | 2/2 | 100.00% |
| clarification | 4/4 | 100.00% |
| clear | 21/21 | 100.00% |
| missing_slot | 2/2 | 100.00% |
| underspecified | 4/4 | 100.00% |

## 失败样本

无。

## 判分说明

- 意图：expectedIntent 与结构化输出 intent 精确匹配。
- 澄清：本评估聚焦 Python 意图层；actualIntent=UNKNOWN 视为应进入澄清。
- 候选：配置 expectedCandidateIntents 时，期望候选必须全部出现在实际候选中。
- `offline` 报告是可重复的规则降级基线，不代表线上大模型效果；模型效果需使用 `--mode model` 单独运行并保留报告。
