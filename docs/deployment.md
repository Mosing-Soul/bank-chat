# Docker 部署说明

## RAG 部署选择

不建议把本机生成的 Chroma 向量库提交到 Git 或直接复制进镜像。向量库包含大量二进制文件，
并且与 Chroma 版本、Embedding 模型及构建参数相关。

推荐在服务器上使用项目镜像构建一次，然后保存在 Docker 命名卷 `rag-vector-data` 中。

`/refresh` 与 `rag-init` 现在复用同一套 PDF、Excel、Markdown 解析和切分逻辑，并为每次
完整重建生成独立索引版本。只有新版本构建成功后才切换服务内的活动索引；构建失败时继续
使用原索引。首次部署仍建议使用 `rag-init`。

## 首次部署

```bash
cp .env.deploy.example .env.deploy
# 编辑 .env.deploy，填写真实密钥和模型名称

docker compose build
docker compose --profile tools run --rm rag-init
docker compose up -d
docker compose ps
```

### 演示前最稳妥的离线部署

如果服务器下载依赖或模型不稳定，建议在与服务器CPU架构一致的电脑上提前完成：

```bash
# 1. 下载模型到项目目录
hf download BAAI/bge-small-zh-v1.5 \
  --local-dir models/bge-small-zh-v1.5

# 2. 构建全部业务镜像并拉取Redis
docker compose build
docker pull redis:7-alpine

# 3. 导出服务器所需镜像
docker save \
  bank-chat-python-rag:latest \
  bank-chat-java-backend:latest \
  bank-chat-frontend:latest \
  redis:7-alpine \
  -o bank-chat-images.tar
```

上传以下内容到服务器：

- `bank-chat-images.tar`
- 项目代码及 `docker-compose.yml`
- `models/bge-small-zh-v1.5/`
- `bank-agent-demo/assets/`
- 服务器专用 `.env.deploy`

服务器只需执行：

```bash
docker load -i bank-chat-images.tar
docker compose --profile tools run --rm --no-deps rag-init
docker compose up -d --no-build
docker compose ps
```

其中 `rag-init` 是Python RAG项目首次部署必须额外执行的一次初始化。它将原始文档转成
向量库并保存到命名卷；以后只重启服务不需要重复执行，只有更新RAG文档时才重新执行。

首次构建会下载 Embedding 模型并保存到 `huggingface-cache` 命名卷。完成后可将
`.env.deploy` 中的 `EMBEDDING_LOCAL_FILES_ONLY` 改成 `true`，后续重启可直接使用缓存。
Python 镜像包含 Torch、Chroma 和文档解析依赖，首次构建可能需要十几分钟。Dockerfile
已启用 BuildKit pip 缓存，中断后重试可以继续利用已下载的软件包。

### 服务器无法下载 Hugging Face 模型

Compose 已将宿主机 `models/` 挂载到容器 `/app/models/`。可在能够下载模型的电脑上先将
模型整理成普通目录：

```bash
hf download BAAI/bge-small-zh-v1.5 \
  --local-dir models/bge-small-zh-v1.5
```

把整个 `models/bge-small-zh-v1.5/` 上传到服务器项目目录后，将 `.env.deploy` 改为：

```dotenv
EMBEDDING_MODEL_NAME=/app/models/bge-small-zh-v1.5
EMBEDDING_LOCAL_FILES_ONLY=true
```

这种方式不需要向 Docker 命名卷手工复制 Hugging Face 缓存，也不要求服务器访问
Hugging Face。`models/` 已被 Git 忽略，不会把模型提交到仓库。

### 当前容量参考

当前项目实测：

- 原始资料：约 1.84 MB。
- Chroma 向量库：约 2.06 MB。
- `BAAI/bge-small-zh-v1.5` 模型文件：约 92 MB。
- Python RAG 服务加载模型后的常驻内存：约 1 GB。

部署整套服务建议至少 4 GB 内存；为了给向量重建、Java、Redis 和 Docker 留出余量，
演示服务器使用 8 GB 更稳妥。首次镜像构建会保存 Torch 等大体积依赖和构建缓存，
建议至少预留 20 GB 可用磁盘，30 GB 以上更从容。

访问地址：

```text
http://服务器IP/
```

## 更新业务代码

```bash
git pull
docker compose build
docker compose up -d
```

普通代码更新不会删除 Redis、技能配置、模型缓存或向量库。

## 更新 RAG 文档

更新 `bank-agent-demo/assets/` 后，可以在服务运行期间触发完整重建：

```bash
curl -X POST http://127.0.0.1:8000/refresh
```

成功响应包含本次激活的 `indexId`。如希望在停服状态下初始化或重建，也可执行：

```bash
docker compose stop python-rag
docker compose --profile tools run --rm rag-init
docker compose up -d python-rag
```

检查向量库是否加载：

```bash
docker compose exec python-rag python -c \
  "import json,urllib.request; print(json.load(urllib.request.urlopen('http://127.0.0.1:8000/health')))"
```

返回的 `vectorStoreReady` 应为 `true`。

## 常用排查

```bash
docker compose ps
docker compose logs -f --tail=200 python-rag
docker compose logs -f --tail=200 java-backend
docker compose logs -f --tail=200 frontend
```

`docker compose down -v` 会删除 Redis、技能配置、模型缓存和向量库。演示环境更新代码时
不要使用该命令。
