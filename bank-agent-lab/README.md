# Bank Agent Lab

该目录存放不会参与生产服务运行的离线研发资产：评测集、检索/生成评测脚本、单元测试，以及 Mock PDF/Excel 的生成和验证工具。

`bank-agent-demo` 可以在不携带本目录的情况下独立构建、启动、查询和刷新向量库。本目录的脚本会只读引用 `../bank-agent-demo/python` 和已构建的向量库。

## 目录

- `evaluation/`：RAG 检索、MMR 与生成答案质量评测。
- `evaluation/datasets/`：版本化人工测试集。
- `tests/`：从生产目录拆出的单元测试。
- `document_generation/`：Mock QA、PDF、Excel 的生成及视觉验证工具。
- `reports/`：评测输出（默认不提交）。

## 运行评测

先确保 `bank-agent-demo/.env` 已配置，并已构建向量库。

```powershell
python evaluation/rag_evaluator.py retrieval
python evaluation/rag_evaluator.py generation --service-url http://127.0.0.1:8000
python evaluation/rag_evaluator.py all --service-url http://127.0.0.1:8000
```

生成评测默认使用 `CHAT_MODEL` 作为裁判模型，temperature 为 0，并保存逐题判分依据。可通过 `--no-judge` 只运行无需 LLM 的来源和关键词指标。

## 运行单元测试

```powershell
python -m pytest tests -q
```

## 运行长期人工回归基线

先启动完整 Java、Python 和前端主链路。独立用例并发执行，多轮用例会在同一会话内按顺序执行：

```powershell
python evaluation/manual_regression.py --system-version working-tree --concurrency 3
```

报告写入 `reports/`。脚本自动检查路径、引用、结构化错误和空回答；答案语义及少量浏览器视觉项保留人工复核。
