FROM python:3.11-slim

WORKDIR /app

# 复制依赖文件并安装
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt -i https://mirrors.aliyun.com/pypi/simple/

# 复制 Streamlit 应用代码
COPY app.py .

# 暴露 Streamlit 默认端口
EXPOSE 8501

# 健康检查（可选）
HEALTHCHECK CMD curl --fail http://localhost:8501/_stcore/health || exit 1

# 启动 Streamlit（绑定到 0.0.0.0，禁用自动打开浏览器）
CMD ["streamlit", "run", "app.py", "--server.port=8501", "--server.address=0.0.0.0", "--server.headless=true"]