import logging
from dataclasses import dataclass
from typing import Any, Dict, List

from rag_models import QueryRequest, QueryResponse


logger = logging.getLogger(__name__)
NO_STORE_ANSWER = "暂未在行内知识库中找到可用于回答该问题的相关资料。"


@dataclass
class RetrievalResult:
    context: str
    sources: List[str]
    hit_count: int
    candidates: str = ""
    evidence: List[Dict[str, Any]] = None


def format_docs_for_prompt(docs_with_scores) -> str:
    if not docs_with_scores:
        return "无"
    chunks = []
    for index, (doc, score) in enumerate(docs_with_scores, start=1):
        source = doc.metadata.get("source", "unknown")
        page = doc.metadata.get("page")
        location = f"，页码/位置：{page}" if page is not None else ""
        chunks.append(
            f"【文档{index}】来源：{source}{location}，相关度分数：{score:.4f}\n{doc.page_content}"
        )
    return "\n\n".join(chunks)


def unique_sources(docs_with_scores) -> List[str]:
    return list(dict.fromkeys(doc.metadata.get("source", "unknown") for doc, _ in docs_with_scores))


def build_rag_answer_prompt(question: str, history, relevant_docs, candidate_docs) -> str:
    history_text = ""
    if history:
        history_text = "历史对话：\n" + "\n".join(
            f"{message.role}: {message.content}" for message in history
        ) + "\n\n"
    source_index = "\n".join(
        f"[{index}] {source}" for index, source in enumerate(unique_sources(relevant_docs), start=1)
    ) or "无"
    conclusion_rule = (
        "已命中行内知识库文档，请依据命中文档自然、连贯地回答。"
        if relevant_docs else
        "暂未命中高相关行内文档，请直接说明暂未找到相关资料；候选片段不能作为回答依据。"
    )
    return f"""你是银行客户经理智能助手。请直接形成一套完整回答。
不要使用“行内文档结论”“大模型补充”“来源”等固定分段，不要编造文档外信息。
引用内部事实时，在对应句末添加 [数字](#internal-citation-数字)，数字必须来自来源编号。
不要在正文中写文件名或另列来源，页面会统一展示引用来源。

{conclusion_rule}

{history_text}命中的行内文档：
{format_docs_for_prompt(relevant_docs)}

低相关候选片段（不作为行内依据）：
{format_docs_for_prompt(candidate_docs)}

来源编号：
{source_index}

用户问题：{question}

请输出："""


class RagService:
    def __init__(self, vector_store_manager, llm, score_threshold: float, top_k: int):
        self._stores = vector_store_manager
        self._llm = llm
        self._score_threshold = score_threshold
        self._top_k = top_k

    def query(self, question: str, session_id: str, history=None) -> QueryResponse:
        del session_id  # Reserved for future session-aware retrieval/auditing.
        history = history or []
        vector_store = self._stores.get()
        if vector_store is None:
            return QueryResponse(
                answer=self._invoke_or_fallback(
                    build_rag_answer_prompt(question, history, [], []),
                    NO_STORE_ANSWER,
                    "rag llm failed without vector store",
                ),
                sources=[],
            )

        docs_with_scores = vector_store.similarity_search_with_score(question, k=self._top_k)
        relevant_docs = [
            (doc, score) for doc, score in docs_with_scores if score < self._score_threshold
        ]
        candidate_docs = [] if relevant_docs else docs_with_scores[:3]
        sources = unique_sources(relevant_docs)
        fallback = NO_STORE_ANSWER
        if relevant_docs:
            fallback = "已找到相关行内资料，但回答生成服务暂时不可用，请稍后重试。"
        answer = self._invoke_or_fallback(
            build_rag_answer_prompt(question, history, relevant_docs, candidate_docs),
            fallback,
            "rag llm failed",
        )
        return QueryResponse(answer=answer, sources=sources)

    def retrieve(self, question: str) -> RetrievalResult:
        """Retrieve internal evidence without asking an LLM to compose an answer."""
        vector_store = self._stores.get()
        if vector_store is None:
            return RetrievalResult(context="", sources=[], hit_count=0, evidence=[])
        docs_with_scores = vector_store.similarity_search_with_score(question, k=self._top_k)
        relevant_docs = [
            (doc, score) for doc, score in docs_with_scores if score < self._score_threshold
        ]
        candidates = [] if relevant_docs else docs_with_scores[:3]
        evidence = []
        for rank, (doc, score) in enumerate(docs_with_scores, start=1):
            metadata = doc.metadata or {}
            distance = float(score)
            evidence.append({
                "rank": rank,
                "source": metadata.get("source", "unknown"),
                "snippet": " ".join(doc.page_content.split())[:360],
                "distance": round(distance, 4),
                "similarity": round(1.0 / (1.0 + max(0.0, distance)), 4),
                "accepted": distance < self._score_threshold,
                "page": metadata.get("page"),
                "sheet": metadata.get("sheet"),
                "rowIndex": metadata.get("row_index"),
            })
        return RetrievalResult(
            context=format_docs_for_prompt(relevant_docs),
            sources=unique_sources(relevant_docs),
            hit_count=len(relevant_docs),
            candidates=format_docs_for_prompt(candidates),
            evidence=evidence,
        )

    def evaluate(self, payload: QueryRequest) -> QueryResponse:
        vector_store = self._stores.get()
        if vector_store is None:
            return QueryResponse(answer="知识库尚未初始化，请先上传文档。", sources=[])
        docs_with_scores = vector_store.similarity_search_with_score(payload.question, k=self._top_k)
        relevant_docs = [
            (doc, score) for doc, score in docs_with_scores if score < self._score_threshold
        ]
        if not relevant_docs:
            return QueryResponse(
                answer="未在内部知识库中找到相关信息，请确认问题是否在支持范围内。",
                sources=[],
            )
        context = "\n\n".join(doc.page_content for doc, _ in relevant_docs)
        history_text = ""
        if payload.history:
            history_text = "历史对话：\n" + "\n".join(
                f"{message.role}：{message.content}" for message in payload.history
            ) + "\n\n"
        prompt = f"""{history_text}你是银行内部知识库问答助手。
只使用【参考文档】中明确出现的信息回答，不补充文档外知识；机构名称不一致时视为未找到。
【参考文档】{context}

【问题】{payload.question}"""
        answer = self._invoke_or_fallback(
            prompt,
            "生成答案时出错，请稍后重试。",
            "rag evaluation llm failed",
        )
        return QueryResponse(answer=answer, sources=unique_sources(relevant_docs))

    def _invoke_or_fallback(self, prompt: str, fallback: str, log_message: str) -> str:
        try:
            result = self._llm.invoke(prompt) if self._llm else None
            if result is None:
                return fallback
            return (result.content if hasattr(result, "content") else str(result)).strip()
        except Exception as exc:
            logger.warning("%s: %s", log_message, type(exc).__name__)
            return fallback
