# bank-front-end

二期正式前端，基于 React + TypeScript + Vite + Ant Design 重写一期 Streamlit 聊天页面。

## 功能

- 浏览器会话内生成并保存 `sessionId`
- 保存当前标签页聊天记录
- 调用 Java 后端 `POST /api/chat`
- 展示助手回答和后端返回的 `sources`
- 支持清空当前会话并重新生成 `sessionId`

## 开发运行

```bash
npm install
npm run dev
```

默认 Vite 代理会把 `/api` 转发到 `http://localhost:8080`。如需改后端地址：

```bash
VITE_BACKEND_URL=http://localhost:8080 npm run dev
```

## 构建

```bash
npm run build
```

## Docker

```bash
docker build -t bank-front-end:1.0 .
docker run --rm -p 8081:80 bank-front-end:1.0
```

生产容器内的 `nginx.conf` 会把 `/api/` 转发到 Docker 网络中的 `java-backend:8080`。
