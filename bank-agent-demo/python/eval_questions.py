import os
import json
from langchain_community.vectorstores import Chroma
from langchain_community.embeddings import HuggingFaceEmbeddings

VECTOR_DB_DIR = "./chroma_db"
EVAL_FILE = "eval_questions.json"   # 评估集文件
K_VALUES = [3, 5]                  # 计算 Recall@3 和 Recall@5
os.environ['HTTP_PROXY'] = 'http://127.0.0.1:7890'
os.environ['HTTPS_PROXY'] = 'http://127.0.0.1:7890'

# 加载向量库
embedding_model = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",
    model_kwargs={'device': 'cpu', 'local_files_only': True},
    encode_kwargs={'normalize_embeddings': True}
)
vectordb = Chroma(persist_directory=VECTOR_DB_DIR, embedding_function=embedding_model)

def recall_at_k(retrieved_docs, expected_sources, k):
    """计算前k个检索结果中命中预期文档的比例"""
    retrieved_sources = [doc.metadata.get('source', '') for doc in retrieved_docs[:k]]
    hits = sum(1 for src in expected_sources if src in retrieved_sources)
    return hits / len(expected_sources) if expected_sources else 0.0

def evaluate():
    with open(EVAL_FILE, 'r', encoding='utf-8') as f:
        questions = json.load(f)

    results = {k: [] for k in K_VALUES}
    for q in questions:
        question = q["question"]
        expected = q["expected_docs"]
        docs_with_scores = vectordb.similarity_search_with_score(question, k=max(K_VALUES))
        docs = [doc for doc, _ in docs_with_scores]

        # 打印检索结果详情
        print(f"\n问题: {question}")
        for i, doc in enumerate(docs[:5]):
            source = doc.metadata.get('source', '未知')
            content_preview = doc.page_content[:150].replace('\n', ' ')
            print(f"  Top{i+1}: {source} - {content_preview}...")

        for k in K_VALUES:
            recall = recall_at_k(docs, expected, k)
            results[k].append(recall)
            print(f"  Recall@{k}: {recall:.3f}")

    print("\n=== Summary ===")
    for k in K_VALUES:
        avg_recall = sum(results[k]) / len(results[k])
        print(f"Average Recall@{k}: {avg_recall:.4f}")

if __name__ == "__main__":
    evaluate()