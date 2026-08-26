import streamlit as st
import requests
import uuid

st.set_page_config(page_title="银行智能助手", page_icon="🏦")
st.title("🏦 银行客户经理智能助手")

# 初始化会话ID（整个浏览器会话只生成一次）
if "session_id" not in st.session_state:
    st.session_state.session_id = str(uuid.uuid4())

# 初始化消息列表
if "messages" not in st.session_state:
    st.session_state.messages = []

# 显示历史消息
for msg in st.session_state.messages:
    with st.chat_message(msg["role"]):
        st.markdown(msg["content"])

# 输入框
if prompt := st.chat_input("请问您想了解什么？"):
    # 显示用户消息
    with st.chat_message("user"):
        st.markdown(prompt)
    st.session_state.messages.append({"role": "user", "content": prompt})

    # 调用 Java 后端（注意字段名要与后端 DTO 一致，通常是 "sessionId"）
    with st.chat_message("assistant"):
        with st.spinner("思考中..."):
            try:
                response = requests.post(
                    "http://java-backend:9091/api/chat",
                    json={
                        "question": prompt,
                        "sessionId": st.session_state.session_id   # 使用存储的 sessionId
                    },
                    timeout=30
                )
                if response.status_code == 200:
                    data = response.json()
                    answer = data.get("answer", "")
                    # 如果后端返回了新的 sessionId（例如第一次请求），则更新
                    if "sessionId" in data and data["sessionId"] != st.session_state.session_id:
                        st.session_state.session_id = data["sessionId"]
                else:
                    answer = f"服务异常：{response.status_code}"
            except Exception as e:
                answer = f"请求失败：{str(e)}"
        st.markdown(answer)
    st.session_state.messages.append({"role": "assistant", "content": answer})