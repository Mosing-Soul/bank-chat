import argparse
import hashlib
import json
import platform
import sys
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path


PYTHON_DIR = Path(__file__).resolve().parent.parent
if str(PYTHON_DIR) not in sys.path:
    sys.path.insert(0, str(PYTHON_DIR))

from env_config import env_path, require_env
from intent.structured_intent import IntentRecognitionService


DEFAULT_DATASET = Path(__file__).with_name("intent_eval.json")
DEFAULT_REPORT_DIR = Path(__file__).with_name("reports")


def load_dataset(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as stream:
        dataset = json.load(stream)
    if not isinstance(dataset, dict) or not isinstance(dataset.get("cases"), list):
        raise ValueError("评估集必须是包含 cases 数组的 JSON 对象")
    required = {"id", "input", "caseType", "expectedIntent"}
    seen_ids = set()
    for case in dataset["cases"]:
        missing = required.difference(case)
        if missing:
            raise ValueError(f"评估样本缺少字段 {sorted(missing)}: {case}")
        if case["id"] in seen_ids:
            raise ValueError(f"评估样本 ID 重复: {case['id']}")
        seen_ids.add(case["id"])
    return dataset


def dataset_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_recognizer(mode: str, threshold: float):
    if mode == "offline":
        return IntentRecognitionService(llm=None, threshold=threshold), "deterministic-fallback"
    from langchain_openai import ChatOpenAI

    model_name = require_env("CHAT_MODEL")
    llm = ChatOpenAI(
        model=model_name,
        api_key=require_env("OPENAI_API_KEY1"),
        base_url=require_env("OPENAI_BASE_URL"),
        temperature=0,
        timeout=30,
        max_retries=1,
    )
    return IntentRecognitionService(llm=llm, threshold=threshold), model_name


def _ratio(correct: int, total: int) -> dict:
    return {
        "correct": correct,
        "total": total,
        "accuracy": round(correct / total, 4) if total else None,
    }


def evaluate(dataset: dict, recognizer, dataset_path: Path, mode: str, model_name: str,
             threshold: float, workers: int = 1) -> dict:
    def evaluate_case(case):
        router = case.get("routerContext") or {}
        result = recognizer.recognize(
            case["input"],
            router_intent=router.get("routerIntent"),
            router_confidence=router.get("routerConfidence"),
            entities=router.get("entities"),
            dialog_act=router.get("dialogAct"),
            skill_examples=router.get("skillExamples"),
        )
        actual_intent = result.intent.value
        actual_candidates = [candidate.value for candidate in result.candidateIntents]
        actual_clarification = actual_intent == "UNKNOWN"
        expected_clarification = case.get("expectedClarification")
        expected_candidates = case.get("expectedCandidateIntents", [])

        allowed_intents = case.get("allowedIntents") or []
        intent_pass = (actual_intent in allowed_intents
                       if allowed_intents else actual_intent == case["expectedIntent"])
        clarification_pass = (expected_clarification is None
                              or actual_clarification == expected_clarification)
        candidate_pass = (not expected_candidates
                          or set(expected_candidates).issubset(set(actual_candidates)))
        return {
            "id": case["id"],
            "input": case["input"],
            "caseType": case["caseType"],
            "expectedIntent": case["expectedIntent"],
            "allowedIntents": allowed_intents,
            "actualIntent": actual_intent,
            "expectedClarification": expected_clarification,
            "actualClarification": actual_clarification,
            "expectedCandidateIntents": expected_candidates,
            "actualCandidateIntents": actual_candidates,
            "confidence": result.confidence,
            "reason": result.reason,
            "intentPass": intent_pass,
            "intentMatchRule": "allowedIntents" if allowed_intents else "exact",
            "clarificationPass": clarification_pass,
            "candidatePass": candidate_pass,
            "passed": intent_pass and clarification_pass and candidate_pass,
        }

    if workers > 1:
        with ThreadPoolExecutor(max_workers=workers) as executor:
            results = list(executor.map(evaluate_case, dataset["cases"]))
    else:
        results = [evaluate_case(case) for case in dataset["cases"]]

    by_intent = {}
    for intent in sorted({item["expectedIntent"] for item in results}):
        group = [item for item in results if item["expectedIntent"] == intent]
        by_intent[intent] = _ratio(sum(item["passed"] for item in group), len(group))

    by_case_type = {}
    for case_type in sorted({item["caseType"] for item in results}):
        group = [item for item in results if item["caseType"] == case_type]
        by_case_type[case_type] = _ratio(sum(item["passed"] for item in group), len(group))

    clarification_cases = [item for item in results if item["expectedClarification"] is not None]
    clarification_positive_cases = [item for item in results if item["expectedClarification"] is True]
    confusion = defaultdict(Counter)
    for item in results:
        confusion[item["expectedIntent"]][item["actualIntent"]] += 1

    passed = sum(item["passed"] for item in results)
    report = {
        "reportSchemaVersion": "1.0",
        "dataset": {
            "version": dataset.get("datasetVersion"),
            "description": dataset.get("description"),
            "path": str(dataset_path.resolve()),
            "sha256": dataset_sha256(dataset_path),
            "sampleCount": len(results),
        },
        "run": {
            "timestampUtc": datetime.now(timezone.utc).isoformat(),
            "mode": mode,
            "model": model_name,
            "confidenceThreshold": threshold,
            "temperature": 0 if mode == "model" else None,
            "workers": workers,
            "pythonVersion": platform.python_version(),
        },
        "scoring": {
            "intent": "普通样本要求 expectedIntent 精确匹配；MODEL_TOP1 样本要求输出属于 allowedIntents 且只返回一个非 UNKNOWN 意图",
            "clarification": "本评估聚焦 Python 意图层；actualIntent=UNKNOWN 视为应进入澄清",
            "candidates": "配置 expectedCandidateIntents 时，期望候选必须全部出现在实际候选中",
            "pass": "上述适用于该样本的判断全部通过",
        },
        "summary": {
            **_ratio(passed, len(results)),
            "intentAccuracy": _ratio(sum(item["intentPass"] for item in results), len(results)),
            "clarificationAccuracy": _ratio(
                sum(item["clarificationPass"] for item in clarification_cases),
                len(clarification_cases),
            ),
            "clarificationRecall": _ratio(
                sum(item["actualClarification"] for item in clarification_positive_cases),
                len(clarification_positive_cases),
            ),
            "failureCount": len(results) - passed,
        },
        "byExpectedIntent": by_intent,
        "byCaseType": by_case_type,
        "confusionMatrix": {key: dict(value) for key, value in sorted(confusion.items())},
        "failures": [item for item in results if not item["passed"]],
        "results": results,
    }
    return report


def render_markdown(report: dict) -> str:
    summary = report["summary"]
    dataset = report["dataset"]
    run = report["run"]
    lines = [
        f"# 意图识别评估报告（{dataset['version']}）",
        "",
        "## 运行信息",
        "",
        f"- 运行时间（UTC）：`{run['timestampUtc']}`",
        f"- 评估模式：`{run['mode']}`",
        f"- 模型/识别器：`{run['model']}`",
        f"- 置信度阈值：`{run['confidenceThreshold']}`",
        f"- Temperature：`{run['temperature'] if run['temperature'] is not None else 'N/A'}`",
        f"- 并发数：`{run['workers']}`",
        f"- 样本量：`{dataset['sampleCount']}`",
        f"- 数据集 SHA-256：`{dataset['sha256']}`",
        "",
        "## 汇总",
        "",
        "| 指标 | 正确/总数 | 准确率 |",
        "| --- | ---: | ---: |",
        f"| 总体 | {summary['correct']}/{summary['total']} | {summary['accuracy']:.2%} |",
        f"| 意图判定 | {summary['intentAccuracy']['correct']}/{summary['intentAccuracy']['total']} | {summary['intentAccuracy']['accuracy']:.2%} |",
        f"| UNKNOWN/澄清判断 | {summary['clarificationAccuracy']['correct']}/{summary['clarificationAccuracy']['total']} | {summary['clarificationAccuracy']['accuracy']:.2%} |",
        f"| UNKNOWN/澄清召回 | {summary['clarificationRecall']['correct']}/{summary['clarificationRecall']['total']} | {summary['clarificationRecall']['accuracy']:.2%} |",
        "",
        "## 按期望意图",
        "",
        "| 意图 | 正确/总数 | 准确率 |",
        "| --- | ---: | ---: |",
    ]
    for intent, metric in report["byExpectedIntent"].items():
        lines.append(f"| {intent} | {metric['correct']}/{metric['total']} | {metric['accuracy']:.2%} |")
    lines.extend(["", "## 按样本类型", "", "| 类型 | 正确/总数 | 准确率 |", "| --- | ---: | ---: |"])
    for case_type, metric in report["byCaseType"].items():
        lines.append(f"| {case_type} | {metric['correct']}/{metric['total']} | {metric['accuracy']:.2%} |")
    lines.extend(["", "## 失败样本", ""])
    if not report["failures"]:
        lines.append("无。")
    else:
        lines.extend(["| ID | 输入 | 期望 | 实际 | 置信度 |", "| --- | --- | --- | --- | ---: |"])
        for item in report["failures"]:
            escaped = item["input"].replace("|", "\\|")
            lines.append(
                f"| {item['id']} | {escaped} | {item['expectedIntent']} | "
                f"{item['actualIntent']} | {item['confidence']:.2f} |"
            )
    lines.extend([
        "",
        "## 判分说明",
        "",
        f"- 意图：{report['scoring']['intent']}。",
        f"- 澄清：{report['scoring']['clarification']}。",
        f"- 候选：{report['scoring']['candidates']}。",
        "- `offline` 报告是可重复的规则降级基线，不代表线上大模型效果；模型效果需使用 `--mode model` 单独运行并保留报告。",
        "",
    ])
    return "\n".join(lines)


def write_report(report: dict, output_dir: Path, stem: str) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"{stem}.json"
    markdown_path = output_dir / f"{stem}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    markdown_path.write_text(render_markdown(report), encoding="utf-8")
    return json_path, markdown_path


def parse_args():
    parser = argparse.ArgumentParser(description="运行当前五类意图、UNKNOWN 与澄清评估")
    parser.add_argument("--dataset", type=Path, default=None)
    parser.add_argument("--mode", choices=("offline", "model"), default="offline")
    parser.add_argument("--threshold", type=float, default=None)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_REPORT_DIR)
    parser.add_argument("--output-stem", default=None)
    parser.add_argument("--workers", type=int, default=None)
    parser.add_argument("--fail-on-error", action="store_true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    dataset_path = args.dataset or env_path("INTENT_EVAL_FILE")
    dataset = load_dataset(dataset_path)
    threshold = args.threshold
    if threshold is None:
        threshold = float(dataset.get("evaluationConfig", {}).get("confidenceThreshold", 0.6))
    recognizer, model_name = build_recognizer(args.mode, threshold)
    workers = args.workers if args.workers is not None else (4 if args.mode == "model" else 1)
    if workers < 1:
        raise ValueError("workers 必须大于等于 1")
    report = evaluate(dataset, recognizer, dataset_path, args.mode, model_name, threshold, workers)
    stem = args.output_stem or f"intent_eval_{dataset['datasetVersion'].replace('.', '_')}_{args.mode}"
    json_path, markdown_path = write_report(report, args.output_dir, stem)
    summary = report["summary"]
    print(f"意图评估完成：{summary['correct']}/{summary['total']} = {summary['accuracy']:.2%}")
    print(f"JSON 报告：{json_path.resolve()}")
    print(f"Markdown 报告：{markdown_path.resolve()}")
    return 1 if args.fail_on_error and summary["failureCount"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
