import os
import json

import dotenv
import requests
from langchain_community.chat_models import ChatOpenAI
from langchain_community.vectorstores import Chroma
from langchain_community.embeddings import HuggingFaceEmbeddings

# 加载嵌入模型
dotenv.load_dotenv()  #加载当前目录下的 .env 文件


# 配置
VECTOR_DB_DIR = "../chroma_db"
RAG_SERVICE_URL = "http://127.0.0.1:8000/rag/eval_query"  # 你的 Python RAG 服务地址
EVAL_FILE = "./eval_question_diff1.json"
# os.environ['HTTP_PROXY'] = 'http://127.0.0.1:7890'
# os.environ['HTTPS_PROXY'] = 'http://127.0.0.1:7890'

os.environ['OPENAI_API_KEY'] = os.getenv("OPENAI_API_KEY1")
os.environ['OPENAI_BASE_URL'] = os.getenv("OPENAI_BASE_URL")

llm = ChatOpenAI(model="deepseek-v4-pro")


# 加载向量库（仅用于 Recall 评估，无需 LLM）
embedding_model = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",
    model_kwargs={'device': 'cpu', 'local_files_only': True},
    encode_kwargs={'normalize_embeddings': True}
)
vectordb = Chroma(persist_directory=VECTOR_DB_DIR, embedding_function=embedding_model)

def recall_at_k(retrieved_docs, expected_sources, k):
    retrieved_sources = [doc.metadata.get('source', '') for doc in retrieved_docs[:k]]
    hits = sum(1 for src in expected_sources if src in retrieved_sources)
    return hits / len(expected_sources) if expected_sources else 0.0

def call_rag(question, session_id="eval"):
    """调用你的 RAG 服务获取最终答案"""
    try:
        resp = requests.post(RAG_SERVICE_URL, json={
            "question": question,
            "session_id": session_id,
            "history": []
        }, timeout=300)
        if resp.status_code == 200:
            return resp.json().get("answer", "")
        else:
            return f"RAG service error: {resp.status_code}"
    except Exception as e:
        return f"Request failed: {str(e)}"

def evaluate_recall(questions):
    results = {k: [] for k in [3, 5]}
    for q in questions:
        if q["type"] != "Recall@K":
            continue
        question = q["question"]
        expected = q["expected_docs"]
        docs_with_scores = vectordb.similarity_search_with_score(question, k=5)
        docs = [doc for doc, _ in docs_with_scores]
        for k in [3, 5]:
            recall = recall_at_k(docs, expected, k)
            results[k].append(recall)
            print(f"[Recall] {question[:50]}... | Recall@{k}: {recall:.3f}")
    print("\n=== Recall Summary ===")
    for k in [3, 5]:
        avg = sum(results[k]) / len(results[k]) if results[k] else 0
        print(f"Average Recall@{k}: {avg:.4f}")

def evaluate_boundary(questions):
    passed = 0
    total = 0
    for q in questions:
        if q["type"] != "Boundary":
            continue
        total += 1
        answer = call_rag(q["question"])
        # 简单的兜底检测：回答中是否包含“未找到”、“无法回答”等关键词
        if any(keyword in answer for keyword in ["未找到", "无法回答", "暂无", "不存在", "没有"]):
            passed += 1
            print(f"[Boundary] PASS: {q['question'][:50]}... -> {answer[:50]}")
        else:
            print(f"[Boundary] FAIL: {q['question'][:50]}... -> {answer[:50]}")
    if total > 0:
        print(f"\nBoundary Pass Rate: {passed}/{total} = {passed/total:.2%}")
    else:
        print("No boundary questions.")


def llm_judge(question, expected_answer, actual_answer):
    """用LLM判断答案是否语义正确，返回True/False"""
    judge_prompt = f"""你是一个评估助手。请判断"模型回答"是否正确回答了问题，语义与"参考答案"一致。

        问题：{question}
        参考答案：{expected_answer}
        模型回答：{actual_answer}
        
        判断标准：
        - 核心信息（数值、时间、关键结论）与参考答案一致，即为正确
        - 表述方式不同但语义相同，视为正确
        - 核心信息有误或与参考答案矛盾，视为错误
        - 模型回答"未找到相关信息"但参考答案有具体内容，视为错误
        - 表述方式、顺序、格式不同但核心数值和结论一致，视为correct。
        
        只输出 correct 或 wrong，不要解释。"""

    result = llm.invoke(judge_prompt).content.strip().lower()
    return result == "correct"


def evaluate_end_to_end(questions):
    results = {"recall": [], "boundary": []}

    for q in questions:
        answer = call_rag(q["question"])

        if q["type"] == "Boundary":
            # 边界题：验收标准是"有没有触发兜底"
            triggered = "未在内部知识库" in answer
            results["boundary"].append(triggered)
            status = "✅ 拦截" if triggered else "❌ 未拦截（幻觉风险）"
            print(f"[Boundary] {status}: {q['question'][:50]}...")

        elif q["type"] == "E2E":
            # 端到端准确率：LLM-as-Judge
            is_correct = llm_judge(q["question"], q["answer"], answer)
            results["recall"].append(is_correct)
            status = "✅" if is_correct else "❌"
            print(f"[E2E] {status}: {q['question'][:50]}")
            if not is_correct:
                print(f"      期望: {q['answer'][:80]}")
                print(f"      实际: {answer[:80]}")

    # 汇总
    if results["recall"]:
        acc = sum(results["recall"]) / len(results["recall"])
        print(f"\nEnd-to-End Accuracy (LLM-as-Judge): "
              f"{sum(results['recall'])}/{len(results['recall'])} = {acc:.2%}")

    if results["boundary"]:
        rate = sum(results["boundary"]) / len(results["boundary"])
        print(f"Boundary 拦截率: "
              f"{sum(results['boundary'])}/{len(results['boundary'])} = {rate:.2%}")

if __name__ == "__main__":
    with open(EVAL_FILE, 'r', encoding='utf-8') as f:
        all_questions = json.load(f)

    print("===== 1. Recall Evaluation =====")
    evaluate_recall(all_questions)

    print("\n===== 2. Boundary Test =====")
    evaluate_boundary(all_questions)

    print("\n===== 3. End-to-End Accuracy =====")
    evaluate_end_to_end(all_questions)