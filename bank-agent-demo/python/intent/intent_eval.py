import json
import os

import dotenv
from langchain_openai import ChatOpenAI

from intent_classifier import classify_intent
from intent_schema import IntentType

# 加载嵌入模型
dotenv.load_dotenv()  #加载当前目录下的 .env 文件

os.environ['OPENAI_API_KEY'] = os.getenv("OPENAI_API_KEY1")
os.environ['OPENAI_BASE_URL'] = os.getenv("OPENAI_BASE_URL")

llm = ChatOpenAI(model="deepseek-v4-pro")

def run_eval(eval_file="intent_eval.json"):
    with open(eval_file, "r", encoding="utf-8") as f:
        cases = json.load(f)

    results = {"clear": [], "boundary": []}

    for case in cases:
        result = classify_intent(llm, case["input"])
        expected = case["expected"]
        # 处理expected可能是字符串或枚举值
        if isinstance(expected, IntentType):
            expected = expected.value
        is_correct = result.intent.value == expected
        case_type = case["case_type"]
        results[case_type].append(is_correct)

        status = "✅" if is_correct else "❌"
        print(f"[{case_type}] {status} | 输入: {case['input'][:30]}")
        if not is_correct:
            print(f"         期望: {expected} | 实际: {result.intent.value}")
            print(f"         置信度: {result.confidence} | 原因: {result.reason}")

    # 汇总
    for t in ["clear", "boundary"]:
        total = len(results[t])
        correct = sum(results[t])
        if total:
            print(f"\n{t} 准确率: {correct}/{total} = {correct/total:.2%}")

if __name__ == "__main__":
    # 示例：使用OpenAI模型运行评估
    run_eval()