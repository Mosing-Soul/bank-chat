"""Run the long-lived end-to-end manual regression baseline through the Java SSE API."""

import argparse
import json
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


ROOT = Path(__file__).resolve().parent
DEFAULT_DATASET = ROOT / "datasets" / "manual_regression_baseline.json"


def parse_sse(raw: str):
    events = []
    for block in raw.replace("\r\n", "\n").split("\n\n"):
        lines = block.splitlines()
        event = next((line[6:].strip() for line in lines if line.startswith("event:")), None)
        data = "\n".join(line[5:].strip() for line in lines if line.startswith("data:"))
        if event and data:
            events.append((event, json.loads(data)))
    return events


def invoke(base_url: str, session_id: str, message: str, trace_id: str, timeout: int):
    payload = json.dumps({"sessionId": session_id, "message": message}, ensure_ascii=False).encode("utf-8")
    request = Request(
        base_url.rstrip("/") + "/api/chat/stream",
        data=payload,
        headers={"Content-Type": "application/json", "X-Trace-Id": trace_id},
        method="POST",
    )
    started = time.perf_counter()
    try:
        with urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            status = response.status
    except HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        return {"status": exc.code, "durationMs": elapsed(started), "transportError": raw}
    except (URLError, TimeoutError) as exc:
        return {"status": 0, "durationMs": elapsed(started), "transportError": str(exc)}
    events = parse_sse(raw)
    result = next((data for event, data in reversed(events) if event == "result"), None)
    return {"status": status, "durationMs": elapsed(started), "events": events, "response": result}


def elapsed(started):
    return round((time.perf_counter() - started) * 1000)


def citation_types(response):
    result = set()
    for citation in (response or {}).get("citations") or []:
        result.add("WEB" if citation.get("type") == "WEB" or citation.get("url") else "INTERNAL")
    return result


def evaluate_turn(turn, execution):
    response = execution.get("response") or {}
    trace = ((response.get("data") or {}).get("executionTrace") or {})
    actual_route = trace.get("route")
    required_types = set(turn.get("requiredCitationTypes") or [])
    actual_types = citation_types(response)
    checks = {
        "httpOk": execution.get("status") == 200,
        "hasResponse": bool(response),
        "hasAnswer": bool((response.get("answer") or "").strip()),
        "routeMatched": actual_route == turn.get("expectedRoute"),
        "citationsMatched": required_types.issubset(actual_types),
        "noStructuredError": not bool(response.get("error")),
    }
    return {
        "message": turn["message"],
        "criteria": turn["criteria"],
        "expectedRoute": turn["expectedRoute"],
        "actualRoute": actual_route,
        "requiredCitationTypes": sorted(required_types),
        "actualCitationTypes": sorted(actual_types),
        "checks": checks,
        "structuralPass": all(checks.values()),
        "durationMs": execution.get("durationMs"),
        "answer": response.get("answer"),
        "traceId": response.get("traceId"),
        "transportError": execution.get("transportError"),
    }


def run_case(case, base_url, timeout):
    session_id = "manual-" + uuid.uuid4().hex
    turns = []
    for index, turn in enumerate(case["turns"], start=1):
        execution = invoke(base_url, session_id, turn["message"], f"{case['id']}-{index}-{uuid.uuid4().hex[:8]}", timeout)
        turns.append(evaluate_turn(turn, execution))
    return {
        "id": case["id"], "category": case["category"], "title": case["title"],
        "manualChecks": case.get("manualChecks", []), "turns": turns,
        "structuralPass": all(turn["structuralPass"] for turn in turns),
        "semanticReview": "PENDING",
    }


def write_markdown(report, path):
    lines = [
        "# 人工回归报告", "",
        f"- 数据集版本：`{report['datasetVersion']}`",
        f"- 被测系统版本：`{report['systemVersion']}`",
        f"- 执行时间：`{report['executedAt']}`",
        f"- 结构校验：`{report['summary']['passed']}/{report['summary']['total']}` 通过", "",
        "| 用例 | 分类 | 路径/结构 | 语义复核 | 耗时 |", "| --- | --- | --- | --- | --- |",
    ]
    for case in report["cases"]:
        duration = sum((turn.get("durationMs") or 0) for turn in case["turns"])
        lines.append(f"| {case['id']} {case['title']} | {case['category']} | {'通过' if case['structuralPass'] else '失败'} | {case['semanticReview']} | {duration} ms |")
    lines.extend(["", "## 逐题结果", ""])
    for case in report["cases"]:
        lines.append(f"### {case['id']} · {case['title']}")
        for turn in case["turns"]:
            lines.extend([
                "", f"- 问题：{turn['message']}",
                f"- 路径：`{turn['actualRoute']}`（预期 `{turn['expectedRoute']}`）",
                f"- 验收标准：{turn['criteria']}",
                f"- 回答：{turn.get('answer') or turn.get('transportError') or '无'}",
            ])
        for check in case.get("manualChecks", []):
            lines.append(f"- 人工观察：{check}")
        lines.append("")
    path.write_text("\n".join(lines), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:9091")
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--system-version", default="working-tree")
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=150)
    parser.add_argument("--output-dir", type=Path, default=ROOT.parent / "reports")
    args = parser.parse_args()

    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    cases = []
    with ThreadPoolExecutor(max_workers=max(1, args.concurrency)) as executor:
        futures = [executor.submit(run_case, case, args.base_url, args.timeout) for case in dataset["cases"]]
        for future in as_completed(futures):
            cases.append(future.result())
    order = {case["id"]: index for index, case in enumerate(dataset["cases"])}
    cases.sort(key=lambda item: order[item["id"]])
    report = {
        "datasetVersion": dataset["datasetVersion"], "systemVersion": args.system_version,
        "executedAt": datetime.now().astimezone().isoformat(), "baseUrl": args.base_url,
        "summary": {"total": len(cases), "passed": sum(case["structuralPass"] for case in cases)},
        "cases": cases,
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    json_path = args.output_dir / f"manual-regression-{stamp}.json"
    md_path = args.output_dir / f"manual-regression-{stamp}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    write_markdown(report, md_path)
    print(json.dumps({"json": str(json_path), "markdown": str(md_path), **report["summary"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
