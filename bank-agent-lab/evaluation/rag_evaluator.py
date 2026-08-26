"""Offline RAG evaluation: retrieval, MMR comparison, and answer quality.

Metrics follow common IR/RAG practice:
- retrieval: HitRate/Precision/Recall@K, MRR, nDCG@K
- MMR: same relevance metrics plus source/evidence diversity
- generation: deterministic key-point/source scores and structured LLM-as-a-Judge
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import statistics
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests
from dotenv import load_dotenv

LAB_DIR = Path(__file__).resolve().parents[1]
REPO_DIR = LAB_DIR.parent
PROJECT_DIR = REPO_DIR / "bank-agent-demo"
PYTHON_DIR = PROJECT_DIR / "python"
DEFAULT_DATASET = Path(__file__).parent / "datasets" / "rag_eval_v1.json"
DEFAULT_REPORT_DIR = LAB_DIR / "reports"
if str(PYTHON_DIR) not in sys.path:
    sys.path.insert(0, str(PYTHON_DIR))


def load_dataset(path: Path) -> dict:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data.get("cases"), list) or not data["cases"]:
        raise ValueError("dataset must contain a non-empty cases array")
    required = {"id", "question", "expectedSources", "expectedEvidenceIds", "referenceAnswer", "keyPoints"}
    ids = set()
    for case in data["cases"]:
        missing = required - set(case)
        if missing:
            raise ValueError(f"case {case.get('id')} missing {sorted(missing)}")
        if case["id"] in ids:
            raise ValueError(f"duplicate case id: {case['id']}")
        ids.add(case["id"])
    return data


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def create_store():
    load_dotenv(PROJECT_DIR / ".env")
    from build_vector_store import create_embedding_model
    from env_config import env_path
    from vector_store_manager import VectorStoreManager

    manager = VectorStoreManager(str(env_path("VECTOR_DB_DIR")), create_embedding_model())
    store = manager.load()
    if store is None:
        raise RuntimeError("active vector store not found; run bank-agent-demo/python/build_vector_store.py")
    return store, manager.active_path


def query_text(case: dict) -> str:
    return case.get("standaloneQuestion") or case["question"]


def is_relevant(doc, case: dict) -> bool:
    source = doc.metadata.get("source", "")
    content = doc.page_content
    evidence_ids = case.get("expectedEvidenceIds", [])
    if evidence_ids and any(eid in content for eid in evidence_ids):
        return True
    return not evidence_ids and source in case.get("expectedSources", [])


def relevance_vector(docs: list, case: dict) -> list[int]:
    return [1 if is_relevant(doc, case) else 0 for doc in docs]


def retrieval_metrics(rels: list[int], relevant_total: int, k: int) -> dict:
    top = rels[:k]
    hits = sum(top)
    if relevant_total == 0:
        clean_rejection = 1.0 if hits == 0 else 0.0
        return {"hitRate": clean_rejection, "precision": clean_rejection, "recall": clean_rejection, "mrr": clean_rejection, "nDCG": clean_rejection}
    hit_rate = 1.0 if hits else 0.0
    precision = hits / k
    recall = hits / relevant_total if relevant_total else (1.0 if not hits else 0.0)
    rr = next((1.0 / (i + 1) for i, rel in enumerate(top) if rel), 0.0)
    dcg = sum(rel / math.log2(i + 2) for i, rel in enumerate(top))
    ideal_hits = min(relevant_total, k)
    idcg = sum(1.0 / math.log2(i + 2) for i in range(ideal_hits))
    ndcg = dcg / idcg if idcg else (1.0 if not hits else 0.0)
    return {"hitRate": hit_rate, "precision": precision, "recall": recall, "mrr": rr, "nDCG": ndcg}


def diversity_metrics(docs: list) -> dict:
    sources = [d.metadata.get("source", "unknown") for d in docs]
    normalized = [" ".join(d.page_content.lower().split()) for d in docs]
    unique_content = len(set(normalized)) / len(normalized) if normalized else 0.0
    unique_sources = len(set(sources)) / len(sources) if sources else 0.0
    return {"uniqueContentRatio": unique_content, "uniqueSourceRatio": unique_sources, "sourceCount": len(set(sources))}


def average_metric(rows: list[dict], metric: str) -> float:
    vals = [row[metric] for row in rows]
    return round(statistics.fmean(vals), 4) if vals else 0.0


def evaluate_retrieval(dataset: dict, store) -> dict:
    cfg = dataset.get("evaluationConfig", {})
    ks = cfg.get("kValues", [1, 3, 5, 10])
    max_k = max(ks)
    fetch_k = cfg.get("mmrFetchK", max(20, max_k))
    lambda_mult = cfg.get("mmrLambda", 0.5)
    cases = []
    for case in dataset["cases"]:
        q = query_text(case)
        similarity_pairs = store.similarity_search_with_score(q, k=max_k)
        sim_docs = [doc for doc, _ in similarity_pairs]
        mmr_docs = store.max_marginal_relevance_search(q, k=max_k, fetch_k=fetch_k, lambda_mult=lambda_mult)
        relevant_total = max(1, len(case.get("expectedEvidenceIds", []))) if not case.get("unanswerable") else 0
        row = {"id": case["id"], "question": q, "category": case.get("category"), "difficulty": case.get("difficulty"), "expectedEvidenceIds": case["expectedEvidenceIds"], "similarity": {}, "mmr": {}}
        for name, docs in (("similarity", sim_docs), ("mmr", mmr_docs)):
            rels = relevance_vector(docs, case)
            row[name]["ranking"] = [{"rank": i + 1, "relevant": bool(rels[i]), "source": d.metadata.get("source"), "evidencePreview": d.page_content[:240]} for i, d in enumerate(docs)]
            row[name]["diversity"] = diversity_metrics(docs)
            row[name]["atK"] = {str(k): retrieval_metrics(rels, relevant_total, k) for k in ks}
        cases.append(row)

    summary = {}
    for method in ("similarity", "mmr"):
        summary[method] = {"atK": {}, "diversity": {}}
        for k in ks:
            metric_rows = [row[method]["atK"][str(k)] for row in cases]
            summary[method]["atK"][str(k)] = {m: average_metric(metric_rows, m) for m in ("hitRate", "precision", "recall", "mrr", "nDCG")}
        div_rows = [row[method]["diversity"] for row in cases]
        summary[method]["diversity"] = {m: average_metric(div_rows, m) for m in ("uniqueContentRatio", "uniqueSourceRatio", "sourceCount")}
    return {"config": {"kValues": ks, "mmrFetchK": fetch_k, "mmrLambda": lambda_mult}, "summary": summary, "cases": cases}


def call_service(service_url: str, case: dict, timeout: float) -> tuple[str, list[str]]:
    payload = {"question": case["question"], "session_id": f"eval-{case['id']}", "history": case.get("history", [])}
    response = requests.post(service_url.rstrip("/") + "/rag/query", json=payload, timeout=timeout)
    response.raise_for_status()
    body = response.json()
    return body.get("answer", ""), body.get("sources", [])


def key_point_score(answer: str, points: list[str]) -> dict:
    matched = [p for p in points if p.lower() in answer.lower()]
    return {"score": round(len(matched) / len(points), 4) if points else 1.0, "matched": matched, "missing": [p for p in points if p not in matched]}


def source_scores(actual: list[str], expected: list[str]) -> dict:
    aset, eset = set(actual), set(expected)
    tp = len(aset & eset)
    return {"precision": round(tp / len(aset), 4) if aset else (1.0 if not eset else 0.0), "recall": round(tp / len(eset), 4) if eset else (1.0 if not aset else 0.0), "unexpected": sorted(aset - eset), "missing": sorted(eset - aset)}


def create_judge():
    from langchain_openai import ChatOpenAI
    from pydantic import BaseModel, Field

    class JudgeResult(BaseModel):
        correctness: float = Field(ge=0, le=1)
        faithfulness: float = Field(ge=0, le=1)
        relevance: float = Field(ge=0, le=1)
        completeness: float = Field(ge=0, le=1)
        critical_error: bool
        rationale: str

    model = ChatOpenAI(model=os.environ["CHAT_MODEL"], api_key=os.environ["OPENAI_API_KEY1"], base_url=os.environ["OPENAI_BASE_URL"], temperature=0, timeout=60, max_retries=1)
    return model.with_structured_output(JudgeResult), os.environ["CHAT_MODEL"]


def judge_answer(judge, case: dict, answer: str, context: str) -> dict:
    prompt = f"""你是严格的银行RAG评测员。仅按给定问题、参考答案、关键要点和检索证据评分。
评分维度均为0到1：correctness=事实与参考答案一致；faithfulness=所有实质陈述均可由证据支持；relevance=直接回答问题且无无关内容；completeness=覆盖关键要点。
若答案包含与参考答案/证据冲突的金额、时限、允许/禁止结论，critical_error=true。表述不同但语义一致不扣分。Mock免责声明不算无关内容。

问题：{case['question']}
参考答案：{case['referenceAnswer']}
关键要点：{json.dumps(case['keyPoints'], ensure_ascii=False)}
检索证据：{context or '无'}
待评答案：{answer}
"""
    result = judge.invoke(prompt)
    return result.model_dump()


def evaluate_generation(dataset: dict, store, service_url: str, timeout: float, use_judge: bool) -> dict:
    judge = None
    judge_model = None
    if use_judge:
        judge, judge_model = create_judge()
    rows = []
    threshold = dataset.get("evaluationConfig", {}).get("judgePassThreshold", 0.7)
    for case in dataset["cases"]:
        answer, sources = call_service(service_url, case, timeout)
        q = query_text(case)
        evidence_docs = [doc for doc, _ in store.similarity_search_with_score(q, k=5)]
        context = "\n\n".join(d.page_content for d in evidence_docs)
        kp = key_point_score(answer, case["keyPoints"])
        src = source_scores(sources, case["expectedSources"])
        llm_scores = judge_answer(judge, case, answer, context) if judge else None
        if llm_scores:
            overall = statistics.fmean(llm_scores[x] for x in ("correctness", "faithfulness", "relevance", "completeness"))
            passed = overall >= threshold and not llm_scores["critical_error"]
        else:
            overall = statistics.fmean([kp["score"], src["precision"], src["recall"]])
            passed = overall >= threshold
        rows.append({"id": case["id"], "question": case["question"], "answer": answer, "sources": sources, "keyPointCoverage": kp, "sourceMetrics": src, "judge": llm_scores, "overall": round(overall, 4), "passed": passed})
    summary = {"sampleCount": len(rows), "passCount": sum(r["passed"] for r in rows), "accuracy": round(sum(r["passed"] for r in rows) / len(rows), 4), "meanKeyPointCoverage": round(statistics.fmean(r["keyPointCoverage"]["score"] for r in rows), 4), "meanSourcePrecision": round(statistics.fmean(r["sourceMetrics"]["precision"] for r in rows), 4), "meanSourceRecall": round(statistics.fmean(r["sourceMetrics"]["recall"] for r in rows), 4), "judgeModel": judge_model, "passThreshold": threshold}
    if use_judge:
        for metric in ("correctness", "faithfulness", "relevance", "completeness"):
            summary[f"mean{metric.title()}"] = round(statistics.fmean(r["judge"][metric] for r in rows), 4)
    return {"summary": summary, "cases": rows}


def render_markdown(report: dict) -> str:
    lines = ["# RAG 评测报告", "", f"- 时间（UTC）：`{report['run']['timestampUtc']}`", f"- 测试集：`{report['dataset']['version']}`（{report['dataset']['sampleCount']} 条）", f"- 向量库：`{report['run']['vectorStore']}`", ""]
    if "retrieval" in report:
        lines += ["## 检索与 MMR", "", "| 方法 | K | HitRate | Precision | Recall | MRR | nDCG |", "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"]
        for method, block in report["retrieval"]["summary"].items():
            for k, m in block["atK"].items():
                lines.append(f"| {method} | {k} | {m['hitRate']:.2%} | {m['precision']:.2%} | {m['recall']:.2%} | {m['mrr']:.3f} | {m['nDCG']:.3f} |")
        lines += ["", "MMR 多样性用于辅助分析；是否更好仍以 Recall/MRR/nDCG 为主。", ""]
    if "generation" in report:
        s = report["generation"]["summary"]
        lines += ["## 生成质量", "", f"- 通过率：{s['passCount']}/{s['sampleCount']} = {s['accuracy']:.2%}", f"- 关键要点覆盖率：{s['meanKeyPointCoverage']:.2%}", f"- 来源 Precision / Recall：{s['meanSourcePrecision']:.2%} / {s['meanSourceRecall']:.2%}"]
        if s.get("judgeModel"):
            lines += [f"- 裁判模型：`{s['judgeModel']}`", f"- 正确性 / 忠实性 / 相关性 / 完整性：{s['meanCorrectness']:.2%} / {s['meanFaithfulness']:.2%} / {s['meanRelevance']:.2%} / {s['meanCompleteness']:.2%}"]
        lines += ["", "### 未通过样本", ""]
        failed = [r for r in report["generation"]["cases"] if not r["passed"]]
        lines += [f"- `{r['id']}` {r['question']}（overall={r['overall']:.2f}）" for r in failed] or ["无"]
    return "\n".join(lines) + "\n"


def parse_args():
    parser = argparse.ArgumentParser(description="Evaluate RAG retrieval, MMR, and generated answers")
    parser.add_argument("mode", choices=("retrieval", "generation", "all"), nargs="?", default="retrieval")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--service-url", default="http://127.0.0.1:8000")
    parser.add_argument("--timeout", type=float, default=120)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--no-judge", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    dataset = load_dataset(args.dataset)
    store, active_path = create_store()
    report: dict[str, Any] = {"reportSchemaVersion": "1.0", "dataset": {"version": dataset["datasetVersion"], "path": str(args.dataset.resolve()), "sha256": sha256(args.dataset), "sampleCount": len(dataset["cases"])}, "run": {"timestampUtc": datetime.now(timezone.utc).isoformat(), "pythonVersion": platform.python_version(), "vectorStore": active_path, "mode": args.mode}}
    if args.mode in ("retrieval", "all"):
        report["retrieval"] = evaluate_retrieval(dataset, store)
    if args.mode in ("generation", "all"):
        report["generation"] = evaluate_generation(dataset, store, args.service_url, args.timeout, not args.no_judge)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stem = f"rag_eval_{dataset['datasetVersion'].replace('.', '_')}_{args.mode}"
    json_path = args.output_dir / f"{stem}.json"
    md_path = args.output_dir / f"{stem}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")
    print(md_path.resolve())
    print(json_path.resolve())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
