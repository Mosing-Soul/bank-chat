import os
import pandas as pd
from langchain_community.document_loaders import PyPDFLoader, TextLoader
from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import Chroma
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter

os.environ['HTTP_PROXY'] = 'http://127.0.0.1:7890'
os.environ['HTTPS_PROXY'] = 'http://127.0.0.1:7890'
# os.environ['HF_TOKEN'] = '你的HuggingFace访问令牌'

# 配置
DOCUMENTS_DIR = "../bank_docs"          # 存放 9 个文档的文件夹
VECTOR_DB_DIR = "./chroma_db"          # 向量库持久化目录
CHUNK_SIZE = 500
CHUNK_OVERLAP = 50

# Embedding 模型（CPU 上运行）
embedding_model = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",
    model_kwargs={'device': 'cpu'},
    encode_kwargs={'normalize_embeddings': True}
)

# 自定义 Excel 加载器（按行切分）
def load_excel(file_path):
    """读取 Excel，支持 .xls 和 .xlsx"""
    ext = os.path.splitext(file_path)[1].lower()
    if ext == '.xls':
        # 旧版 .xls 使用 xlrd 引擎
        engine = 'xlrd'
    else:
        # .xlsx 使用 openpyxl
        engine = 'openpyxl'
    df = pd.read_excel(file_path, engine=engine)
    docs = []
    for idx, row in df.iterrows():
        row_text = " | ".join([f"{col}: {val}" for col, val in row.items() if pd.notna(val)])
        if row_text.strip():
            metadata = {"source": os.path.basename(file_path), "row": idx, "type": "excel"}
            docs.append(Document(page_content=row_text, metadata=metadata))
    return docs

def load_markdown(file_path):
    """加载 Markdown，按标题分割（可选：直接用 TextLoader 递归分割）"""
    loader = TextLoader(file_path, encoding='utf-8')
    docs = loader.load()
    # 给每个 chunk 添加元数据
    for doc in docs:
        doc.metadata["source"] = os.path.basename(file_path)
        doc.metadata["type"] = "markdown"
    return docs

def load_pdf(file_path):
    loader = PyPDFLoader(file_path)
    docs = loader.load()
    for doc in docs:
        doc.metadata["source"] = os.path.basename(file_path)
        doc.metadata["type"] = "pdf"
    return docs

def load_document(file_path):
    ext = os.path.splitext(file_path)[1].lower()
    if ext == '.pdf':
        return load_pdf(file_path)
    elif ext in ['.xls', '.xlsx']:
        return load_excel(file_path)
    elif ext == '.md':
        return load_markdown(file_path)
    else:
        raise ValueError(f"Unsupported file type: {ext}")

def build_vector_store():
    all_docs = []
    for filename in os.listdir(DOCUMENTS_DIR):
        if filename.endswith(('.pdf', '.xls', '.xlsx', '.md')):
            file_path = os.path.join(DOCUMENTS_DIR, filename)
            print(f"Loading {file_path}...")
            docs = load_document(file_path)
            # 文本分块（对 PDF/MD 进行切分，Excel 每个行已经是小块，可不再切分）
            if filename.endswith(('.pdf', '.md')):
                splitter = RecursiveCharacterTextSplitter(
                    chunk_size=CHUNK_SIZE,
                    chunk_overlap=CHUNK_OVERLAP,
                    separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""]
                )
                docs = splitter.split_documents(docs)
            all_docs.extend(docs)
            print(f"  -> {len(docs)} chunks created")

    print(f"Total chunks: {len(all_docs)}")
    # 构建向量库
    vectordb = Chroma.from_documents(
        documents=all_docs,
        embedding=embedding_model,
        persist_directory=VECTOR_DB_DIR
    )
    vectordb.persist()
    print(f"Vector store saved to {VECTOR_DB_DIR}")

if __name__ == "__main__":
    if not os.path.exists(DOCUMENTS_DIR):
        os.makedirs(DOCUMENTS_DIR)
        print(f"Please put your documents in {DOCUMENTS_DIR} and run again.")
    else:
        build_vector_store()