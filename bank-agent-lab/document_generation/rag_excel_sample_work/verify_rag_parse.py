import sys
from collections import Counter

sys.path.insert(0, r"D:\JetBrains\project\bank-chat\bank-agent-demo\python")

from build_vector_store import parse_general_excel


path = r"D:\JetBrains\project\bank-chat\outputs\rag_excel_sample_v1\华辰银行零售客户经理知识库小样_V1.0.xlsx"
chunks = parse_general_excel(path)
print(f"chunk_count={len(chunks)}")
print("by_sheet=" + repr(Counter(c.metadata.get("sheet") for c in chunks)))
for chunk in chunks[:8]:
    print(chunk.metadata)
    print(chunk.page_content[:500])
