import sys
from collections import Counter

sys.path.insert(0, r"D:\JetBrains\project\bank-chat\bank-agent-demo\python")
from build_vector_store import parse_general_excel

path = r"D:\JetBrains\project\bank-chat\bank-agent-demo\assets\华辰银行零售客户经理QA知识库_Mock_V1.0.xlsx"
chunks = parse_general_excel(path)
counts = Counter(c.metadata.get("sheet") for c in chunks)
assert len(chunks) == 61, len(chunks)
assert counts["客户经理QA"] == 31, counts
assert any("白金级客户的资产门槛" in c.page_content and "200万元" in c.page_content for c in chunks)
assert any("客户说刚被骗" in c.page_content and "停止继续转账" in c.page_content for c in chunks)
print("qa_chunks=61")
print(dict(counts))
