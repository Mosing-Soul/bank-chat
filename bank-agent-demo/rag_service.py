import dotenv
from fastapi import FastAPI
from langchain_community.chat_models import ChatOpenAI
from langchain_community.llms.openai import OpenAIChat
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pydantic import BaseModel
from typing import List, Optional
import uvicorn
from langchain_community.embeddings import HuggingFaceEmbeddings, OpenAIEmbeddings
from langchain_community.vectorstores import Chroma
import os

from document_loader import load_document

app = FastAPI()

# 存放文档的文件夹路径
DOCS_FOLDER = "./bank_docs"
# 向量库持久化目录（可配置）
VECTOR_DB_DIR = "./bank_vector_db"

# ---------- 定义请求和响应的数据结构 ----------
# 历史对话消息结构
class HistoryMessage(BaseModel):
    role: str  # "user" 或 "assistant"
    content: str


# Java 端发来的请求体结构
class QueryRequest(BaseModel):
    question: str
    session_id: str
    history: Optional[List[HistoryMessage]] = []  # 历史对话，可选


# 返回给 Java 端的响应结构
class QueryResponse(BaseModel):
    answer: str
    sources: List[str] = []  # 知识来源（第一期先留空）




# 中文 Embedding 模型（本地运行）
# embedding_model = HuggingFaceEmbeddings(
#     model_name="BAAI/bge-small-zh-v1.5",
#     model_kwargs={'device': 'cpu'},
#     encode_kwargs={'normalize_embeddings': True}
# )
# 加载嵌入模型
dotenv.load_dotenv()  #加载当前目录下的 .env 文件

os.environ['OPENAI_API_KEY'] = os.getenv("OPENAI_API_KEY1")
os.environ['OPENAI_BASE_URL'] = os.getenv("OPENAI_BASE_URL")

embedding_model = OpenAIEmbeddings(model = "text-embedding-ada-002")

llm = ChatOpenAI(model="deepseek-v4-pro")


def build_vector_store(docs_folder):
    """扫描指定文件夹，加载所有文档并构建向量库"""
    print(f"扫描指定文件夹，加载所有文档并构建向量库")
    all_chunks = []
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=500,
        chunk_overlap=50,
        separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""]
    )

    for filename in os.listdir(docs_folder):
        print(f"加载: {filename}" )
        if filename.endswith(('.pdf', '.doc', '.docx', '.txt')):
            file_path = os.path.join(docs_folder, filename)
            print(f"加载文档: {file_path}")
            docs = load_document(file_path)
            chunks = splitter.split_documents(docs)
            # 给每个 chunk 添加元数据（来源文件名）
            for chunk in chunks:
                chunk.metadata['source'] = filename
            all_chunks.extend(chunks)

    if all_chunks:
        vectordb = Chroma.from_documents(
            documents=all_chunks,
            embedding=embedding_model,
            persist_directory=VECTOR_DB_DIR
        )
        vectordb.persist()
        print(f"向量库构建完成，共 {len(all_chunks)} 个片段")
        return vectordb
    else:
        print("未找到任何文档，向量库为空")
        return None


# 启动时加载现有向量库（如果有）
if os.path.exists(VECTOR_DB_DIR) and os.listdir(VECTOR_DB_DIR):
    vectordb = Chroma(persist_directory=VECTOR_DB_DIR, embedding_function=embedding_model)
    print("从已有向量库加载")
else:
    vectordb = None
    print("向量库不存在，请调用 /refresh 接口初始化")



# # ---------- RAG 核心逻辑（第一期简化版：Mock LLM）----------
# def mock_rag_answer(question: str) -> str:
#     """第一期：先返回 Mock 答案，后续替换为真实的 LangChain RAG 检索"""
#
#     # 这里模拟不同的问答场景
#     if "金葵花" in question:
#         return "金葵花客户是指招商银行月日均总资产达到50万元及以上的客户，享受专属理财顾问、优先办理业务等权益。"
#     elif "结构性存款" in question:
#         return "结构性存款是指商业银行吸收的嵌入金融衍生产品的存款，通过与利率、汇率、指数等的波动挂钩，使存款人在承担一定风险的基础上获得更高收益。"
#     elif "代销基金" in question:
#         return "代销基金是指商业银行接受基金管理公司委托，代理销售其发行的基金产品。银行作为代销渠道，提供产品展示、交易执行等服务。"
#     else:
#         return f"（Mock答案）关于「{question}」的问题，后续将接入真实的知识库检索和LLM回答。"


# --- 辅助函数 ---
def build_prompt(question: str, history: List[HistoryMessage], context: str) -> str:
    prompt = "你是一位专业的银行客户经理助手。请根据以下参考资料和历史对话回答用户的问题。如果参考资料不足以回答问题，请说明无法回答。\n\n"
    if context:
        prompt += f"参考资料：\n{context}\n\n"
    else:
        prompt += "参考资料：无\n\n"
    if history:
        prompt += "历史对话：\n"
        for msg in history:
            prompt += f"{msg.role}：{msg.content}\n"
        prompt += "\n"
    prompt += f"用户：{question}\n助手："
    return prompt

# ---------- REST API 接口 ----------
@app.post("/rag/query", response_model=QueryResponse)
async def query(request: QueryRequest):
    global vectordb
    if vectordb is None:
        return QueryResponse(answer="知识库尚未初始化，请先上传文档。", sources=[])

    # 相似度阈值（根据模型不同可调整）
    SCORE_THRESHOLD = 0.5

    # 执行相似度搜索，返回 (document, score) 列表
    docs_with_scores = vectordb.similarity_search_with_score(request.question, k=3)

    print("得分详情：")
    for doc, score in docs_with_scores:
        print(f"得分: {score}, 片段预览: {doc.page_content[:100]}...")

    # 过滤低分结果
    relevant_docs = [(doc, score) for doc, score in docs_with_scores if score < SCORE_THRESHOLD]

    if not relevant_docs:
        # ----- 情况A：未找到相关文档 -----
        # 构造 Prompt，要求 LLM 用自身知识回答，并注明非行内来源
        prompt = (
            "你是一个通用 AI 助手。用户的问题没有在银行内部知识库中找到匹配的答案。\n"
            "请根据你自己的知识回答用户的问题，并在回答的开头注明“（非行内文档来源）”。\n\n"
            f"用户问题：{request.question}\n\n"
            "回答："
        )
        # 在 prompt 中加入历史对话
        if request.history:
            history_text = "\n".join([f"{msg.role}：{msg.content}" for msg in request.history])
            prompt = f"历史对话：\n{history_text}\n\n" + prompt
        raw_answer = llm.invoke(prompt) if llm else "（LLM 未加载，无法生成通用回答）"
        # 强制加上标注（防止 LLM 忘记）
        if not raw_answer.content.startswith("（非行内文档来源）"):
            answer = "（非行内文档来源）" + raw_answer.content
        else:
            answer = raw_answer.content
        sources = []  # 无知识库来源
    else:
        # ----- 情况B：找到相关文档，基于文档回答 -----
        context = "\n\n".join([doc.page_content for doc, _ in relevant_docs])
        sources = list(set([doc.metadata.get('source', '未知') for doc, _ in relevant_docs]))
        # 构建强调文档优先的 Prompt
        prompt = (
            "你是一位专业的银行客户经理助手。请严格依据以下“参考资料”回答用户的问题。\n"
            "如果参考资料中不包含相关信息，请明确说“未找到相关信息”。不要编造答案。\n\n"
            f"参考资料：\n{context}\n\n"
            f"用户问题：{request.question}\n\n"
            "回答："
        )
        # 在 prompt 中加入历史对话
        if request.history:
            history_text = "\n".join([f"{msg.role}：{msg.content}" for msg in request.history])
            prompt = f"历史对话：\n{history_text}\n\n" + prompt

        answer = llm.invoke(prompt) if llm else "（LLM 未加载，无法生成答案）"
        answer = answer.content

    return QueryResponse(answer=answer, sources=sources)


# ---------- 健康检查接口 ----------
@app.get("/health")
async def health():
    return {"status": "ok"}

# ---------- 更换文档目录或新增文档时，调用此接口重新加载所有文档 ----------
@app.post("/refresh")
async def refresh_vector_store():
    global vectordb
    vectordb = build_vector_store(DOCS_FOLDER)
    if vectordb:
        return {"status": "ok", "message": "向量库重建成功"}
    else:
        return {"status": "error", "message": "未找到文档"}


# ---------- 启动服务 ----------
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8000)