# 银行业客户经理智能助手

面向银行客户经理场景的 AI 助手演示项目。当前版本聚焦行内知识库 RAG、外部信息搜索、多轮对话与统一大模型回答，并通过引用来源和运行追踪展示完整处理过程。

> 本项目仅用于学习、面试演示和技术验证。全部业务数据与制度材料均为 Mock 内容，不代表任何真实银行规则，也未接入真实客户数据或生产系统。

## 当前能力

- 支持 PDF、Excel 等行内材料解析、向量化与语义检索。
- 使用轻量 LLM 结合对话上下文识别 RAG、Web Search、组合检索或直接回答意图。
- 将内部文档与外部搜索结果统一交给大模型生成自然回答。
- 内部依据使用正文角标和回答底部来源文件展示，外部信息使用可跳转链接引用。
- 页面提供本次请求的路径、检索证据、相似度、引用来源和耗时追踪。
- 提供独立评估与文档生成实验目录，不影响生产运行代码。

## 技术栈

- 前端：React、TypeScript、Vite、Ant Design
- 中台：Spring Boot、Redis
- AI 服务：FastAPI、LangChain、Chroma、BGE Embedding
- 部署：Docker Compose、Nginx

## 项目结构

```text
bank-chat/
├─ bank-front-end/             # React 对话页面与运行追踪
├─ bank-chat-middle-platform/  # Spring Boot 会话与接口中台
├─ bank-agent-demo/            # 可独立部署的 Python AI 服务
├─ bank-agent-lab/             # 评估、数据生成和实验工具
├─ bank-streamlit/             # V1.0 历史前端实现
├─ docs/                       # 需求、Roadmap 与问题记录
└─ docker-compose.yml          # 容器编排
```

## 快速启动

1. 复制部署环境变量模板并填写 LLM、Embedding 和外部搜索配置，请勿提交真实密钥：

```bash
cp .env.deploy.example .env.deploy
```

2. 构建并启动完整应用：

```bash
docker compose --env-file .env.deploy up -d --build
```

Python 服务启动时会检查知识文档指纹：首次部署或文档变化时自动重建向量索引，文档未变化时直接复用已有索引。默认通过 `http://localhost` 访问前端；端口可由 `HTTP_PORT` 调整。

查看启动状态：

```bash
docker compose --env-file .env.deploy ps
docker compose --env-file .env.deploy logs -f python-rag java-backend frontend
```

## 版本演进

### V1.0 · RAG MVP

完成行内文档知识问答、基础来源展示和多轮会话，使用 Streamlit 搭建首版交互页面，并验证外部行情查询与混合路由方案。

### V2.0 · 多技能架构探索

引入显式意图识别与技能路由设计，扩展客户查询、消息发送等 Mock 场景，并建立多格式文档、检索评估和人机确认的方案基础。

### V2.1 · 意图识别优化

使用小型 LLM 和结构化枚举替代多层规则路由，引入多轮上下文、复合意图和澄清机制，并建立意图识别评估集。

### V2.2 · 对话检索主链路

精简为 RAG、Web Search 和大模型直接回答三类路径，支持 RAG 与 Web 组合取证、统一答案生成、规范化引用及可视化运行追踪。当前主链路尚未使用 LangGraph。

## 文档

- [需求与当前实现](docs/requirements.md)
- [项目 Roadmap](docs/roadmap.md)
- [V2.2 检索问题与优化记录](docs/V2.2检索问题与优化记录.md)

## 后续方向

后续将基于固定评估集优化召回阈值、混合检索与重排序，并在现有链式方案稳定后评估迁移至 LangGraph。详细计划见 [Roadmap](docs/roadmap.md)。
