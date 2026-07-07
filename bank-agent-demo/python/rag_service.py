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
import logging
from pathlib import Path

from document_loader import load_document
from ai_chat_models import AiChatError, AiChatRequest, AiChatResponse, IntentType, SkillRequest
from external_search_client import ExternalSearchClient, ExternalSearchConfigError
from intent.structured_intent import IntentRecognitionService
from java_skill_client import JavaSkillClient
from skill_handlers import (
    CustomerAumSkill,
    ExternalModelApiSkill,
    GeneralChatSkill,
    KnowledgeRagSkill,
    MessagePreviewSkill,
    extract_pending_operation_id,
    has_open_message_flow,
    is_cancel_message,
    is_confirm_message,
    is_revision_message,
)
from skill_router import SkillRouter

app = FastAPI()
logger = logging.getLogger(__name__)

BASE_DIR = Path(__file__).resolve().parent
APP_DIR = BASE_DIR.parent

# 存放文档的文件夹路径。默认指向 bank-agent-demo/bank_docs，避免受启动目录影响。
DOCS_FOLDER = os.getenv("BANK_DOCS_FOLDER", str(APP_DIR / "bank_docs"))

# 向量库持久化目录（可配置）。容器部署优先使用 /app/chroma_db；本地开发使用 python/chroma_db。
DEFAULT_VECTOR_DB_DIR = APP_DIR / "chroma_db" if (APP_DIR / "chroma_db").exists() else BASE_DIR / "chroma_db"
VECTOR_DB_DIR = os.getenv("VECTOR_DB_DIR", str(DEFAULT_VECTOR_DB_DIR))
RAG_SCORE_THRESHOLD = float(os.getenv("RAG_SCORE_THRESHOLD", "0.7"))
RAG_TOP_K = int(os.getenv("RAG_TOP_K", "5"))

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

embedding_model = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",
    model_kwargs={'device': 'cpu', 'local_files_only': True},
    encode_kwargs={'normalize_embeddings': True}
)

llm = ChatOpenAI(model="deepseek-v4-pro")

try:
    external_search_client = ExternalSearchClient()
except ExternalSearchConfigError as exc:
    external_search_client = None
    logger.warning("external search client disabled: %s", exc)


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

def format_docs_for_prompt(docs_with_scores) -> str:
    if not docs_with_scores:
        return "无"
    chunks = []
    for index, (doc, score) in enumerate(docs_with_scores, start=1):
        source = doc.metadata.get("source", "unknown")
        page = doc.metadata.get("page")
        page_text = f"，页码/位置：{page}" if page is not None else ""
        chunks.append(
            f"【文档{index}】来源：{source}{page_text}，相关度分数：{score:.4f}\n{doc.page_content}"
        )
    return "\n\n".join(chunks)


def unique_sources(docs_with_scores) -> List[str]:
    sources = []
    for doc, _ in docs_with_scores:
        source = doc.metadata.get("source", "unknown")
        if source not in sources:
            sources.append(source)
    return sources


def build_rag_answer_prompt(question: str, history, relevant_docs, candidate_docs) -> str:
    history_text = ""
    if history:
        history_text = "\n".join([f"{msg.role}: {msg.content}" for msg in history])
        history_text = f"历史对话：\n{history_text}\n\n"

    if relevant_docs:
        internal_context = format_docs_for_prompt(relevant_docs)
        candidate_context = "无"
        internal_instruction = "已命中行内知识库文档。请优先基于【命中的行内文档】回答，并明确列出信息来自哪些文档。"
    else:
        internal_context = "暂无高相关行内文档。"
        candidate_context = format_docs_for_prompt(candidate_docs)
        internal_instruction = (
            "未命中高相关行内文档。仍需调用大模型回答，但必须明确说明：行内文档暂无相关文档。"
            "如果候选片段也无法支持问题，不要把候选片段包装成行内依据。"
        )

    return (
        "你是银行客户经理智能助手。请按固定结构回答用户问题：\n"
        "1. 【行内文档结论】：如果命中行内文档，先给基于行内文档的结论，并在句末标注来源文档名；"
        "如果未命中，写“暂无相关文档”。\n"
        "2. 【大模型补充】：用一到三句话给通用补充或下一步建议，并明确这部分不是行内文档依据。\n"
        "3. 【来源】：列出命中的行内文档名；没有则写“无”。\n"
        "不要编造文档来源，不要把未命中的候选片段说成已命中文档。\n\n"
        f"{internal_instruction}\n\n"
        f"{history_text}"
        f"命中的行内文档：\n{internal_context}\n\n"
        f"低相关候选片段（仅供判断是否暂无相关文档，不作为行内依据）：\n{candidate_context}\n\n"
        f"用户问题：{question}\n\n"
        "请输出："
    )

# ---------- REST API 接口 ----------
def perform_rag_query(question: str, session_id: str, history=None) -> QueryResponse:
    global vectordb, llm
    history = history or []
    if vectordb is None:
        prompt = build_rag_answer_prompt(question, history, [], [])
        try:
            result = llm.invoke(prompt) if llm else None
            model_answer = (
                result.content
                if hasattr(result, "content")
                else str(result)
                if result
                else "【行内文档结论】暂无相关文档。\n\n【大模型补充】大模型暂不可用。\n\n【来源】无"
            )
        except Exception as exc:
            logger.warning("rag llm failed without vectordb: %s", type(exc).__name__)
            model_answer = "【行内文档结论】暂无相关文档。\n\n【大模型补充】大模型暂不可用。\n\n【来源】无"
        return QueryResponse(answer=model_answer.strip(), sources=[])

    docs_with_scores = vectordb.similarity_search_with_score(question, k=RAG_TOP_K)
    relevant_docs = [(doc, score) for doc, score in docs_with_scores if score < RAG_SCORE_THRESHOLD]
    candidate_docs = [] if relevant_docs else docs_with_scores[: min(len(docs_with_scores), 3)]
    sources = unique_sources(relevant_docs)
    prompt = build_rag_answer_prompt(question, history, relevant_docs, candidate_docs)

    try:
        result = llm.invoke(prompt)
        answer = result.content if hasattr(result, "content") else str(result)
    except Exception as exc:
        logger.warning("rag llm failed: %s", type(exc).__name__)
        if relevant_docs:
            source_text = "、".join(sources)
            answer = f"【行内文档结论】已在行内文档中找到相关片段，但大模型整理暂时不可用。\n\n【大模型补充】暂无。\n\n【来源】{source_text}"
        else:
            answer = "【行内文档结论】暂无相关文档。\n\n【大模型补充】大模型暂不可用，请稍后再试。\n\n【来源】无"
    return QueryResponse(answer=answer.strip(), sources=sources)

def build_skill_router() -> SkillRouter:
    java_client = JavaSkillClient()
    return SkillRouter({
        IntentType.KNOWLEDGE_QA: KnowledgeRagSkill(perform_rag_query),
        IntentType.CUSTOMER_AUM_QUERY: CustomerAumSkill(java_client),
        IntentType.EXTERNAL_API_QUERY: ExternalModelApiSkill(external_search_client, llm),
        IntentType.MESSAGE_SEND: MessagePreviewSkill(java_client),
        IntentType.GENERAL_CHAT: GeneralChatSkill(llm),
    })


@app.post("/rag/query", response_model=QueryResponse)
async def query(request: QueryRequest):
    return perform_rag_query(request.question, request.session_id, request.history)
# ---------- 评估eval效果接口 ----------
@app.post("/rag/eval_query", response_model=QueryResponse)
async def query(request: QueryRequest):
    global vectordb, llm
    if vectordb is None:
        return QueryResponse(answer="知识库尚未初始化，请先上传文档。", sources=[])

    # 检索
    docs_with_scores = vectordb.similarity_search_with_score(request.question, k=RAG_TOP_K)

    print(f"\n问题：{request.question}")
    for i, (doc, score) in enumerate(docs_with_scores):
        print(f"\n--- Chunk {i + 1} (score={score:.4f}) ---")
        print(f"来源：{doc.metadata.get('source', 'unknown')}")
        print(f"内容：{doc.page_content[:300]}")

    # Lower score represents more similarity
    relevant_docs = [(doc, score) for doc, score in docs_with_scores if score < RAG_SCORE_THRESHOLD]

    if not relevant_docs:
        # 边界场景：直接拒答，不调用 LLM
        answer = "未在内部知识库中找到相关信息，请确认问题是否在支持范围内。"
        sources = []
    else:
        context = "\n\n".join([doc.page_content for doc, _ in relevant_docs])
        sources = list(set([doc.metadata.get('source', '未知') for doc, _ in relevant_docs]))
        prompt = f"""你是银行内部知识库问答助手。
            
            规则：
            1. 只使用【参考文档】中明确出现的信息回答，不补充任何文档外的知识
            2. 如果参考文档中完全没有与问题相关的信息，回复：
               "未在内部知识库中找到相关信息"
            3. 特别注意机构名称：如果问题询问的是A银行/机构，
               但参考文档中只有B银行/机构的信息（且A与B明显不同），
               必须回复："未在内部知识库中找到相关信息"
               例如：问"交通银行的分级"，文档只有"招商银行的分级" → 拒答
               例如：问"大型商业银行的总负债"，文档中有该数据 → 正常回答
            4. 数字、比率等数据直接使用文档中的原始数值，
               百分比和小数形式均可接受（如0.01401即1.401%）
               数值精度不同但数值本身一致（如1970418.15与1970418.151652967），视为correct。
                以下情况均视为correct：
                - 数值精度不同但四舍五入后一致（如22399.84与22400）
                - 小数与百分比互换（如0.01401与1.401%）
                - 表述顺序不同但内容一致
            
            【参考文档】
            {context}
            
            【问题】
            {request.question}"""

        if request.history:
            history_text = "\n".join([f"{msg.role}：{msg.content}" for msg in request.history])
            prompt = f"历史对话：\n{history_text}\n\n" + prompt

        if llm is None:
            answer = "（LLM 未加载，无法生成答案）"
        else:
            try:
                result = llm.invoke(prompt)
                # 兼容 LangChain 不同返回值
                if hasattr(result, 'content'):
                    answer = result.content
                else:
                    answer = str(result)
            except Exception as e:
                answer = f"生成答案时出错：{str(e)}"

    return QueryResponse(answer=answer.strip(), sources=sources)

# ---------- 健康检查接口 ----------
@app.get("/health")
async def health():
    return {"status": "ok"}

# ---------- 更换文档目录或新增文档时，调用此接口重新加载所有文档 ----------
@app.post("/ai/chat/invoke", response_model=AiChatResponse)
async def ai_chat_invoke(request: AiChatRequest):
    intent_service = IntentRecognitionService(llm)
    intent = intent_service.recognize(request.message)
    forced_intent = forced_intent_from_skill(request.requestedSkill) if request.forceSkill else None
    effective_history = [] if forced_intent else request.history
    if forced_intent:
        intent.intent = forced_intent
        intent.confidence = 0.98
        intent.reason = "forced by requestedSkill"
    elif (
        extract_pending_operation_id(request.history)
        and (is_confirm_message(request.message) or is_cancel_message(request.message) or is_revision_message(request.message))
    ) or has_open_message_flow(request.history):
        intent.intent = IntentType.MESSAGE_SEND
        intent.confidence = max(intent.confidence, 0.95)
        intent.reason = "pending message confirmation flow"
    router = build_skill_router()
    skill_request = SkillRequest(
        trace_id=request.traceId,
        session_id=request.sessionId,
        user_message=request.message,
        intent=intent.intent,
        entities=intent.entities,
        history=effective_history,
    )
    result, call = router.route(skill_request)
    error = None
    if not result.success:
        error = AiChatError(code=result.error_code or "SKILL_ERROR", message=result.error_message or "skill failed")
    return AiChatResponse(
        traceId=request.traceId,
        sessionId=request.sessionId,
        intent=intent.intent,
        confidence=intent.confidence,
        answer=result.answer or "请说明您想查询产品规则、客户资产、黄金行情，还是需要生成客户消息。",
        data=result.data,
        citations=result.citations,
        sources=[citation.source for citation in result.citations],
        requiresConfirmation=result.requires_confirmation,
        confirmation=result.confirmation,
        skillCalls=[call],
        error=error,
    )


def forced_intent_from_skill(skill: Optional[str]) -> Optional[IntentType]:
    if not skill:
        return None
    value = skill.strip().upper()
    mapping = {
        "CUSTOMER_AUM": IntentType.CUSTOMER_AUM_QUERY,
        "AUM": IntentType.CUSTOMER_AUM_QUERY,
        "GOLD_PRICE": IntentType.EXTERNAL_API_QUERY,
        "GOLD": IntentType.EXTERNAL_API_QUERY,
        "RAG_QUERY": IntentType.KNOWLEDGE_QA,
        "RULE_QUERY": IntentType.KNOWLEDGE_QA,
        "MESSAGE_SEND": IntentType.MESSAGE_SEND,
        "MESSAGE": IntentType.MESSAGE_SEND,
    }
    return mapping.get(value)


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
