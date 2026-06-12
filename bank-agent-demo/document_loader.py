# 文档加载
import pdfplumber
from langchain_community.document_loaders import PyPDFLoader, Docx2txtLoader, TextLoader
import os

from langchain_core.documents import Document


def load_document(file_path):
    """根据文件扩展名自动选择合适的加载器"""
    ext = os.path.splitext(file_path)[1].lower()
    if ext == '.pdf':
        # 1.
        # loader = PyPDFLoader(file_path)
        # 使用 pdfplumber 替代 PyPDFLoader
        docs = []
        with pdfplumber.open(file_path) as pdf:
            full_text = ""
            for page in pdf.pages:
                text = page.extract_text()
                if text:
                    full_text += text + "\n"
        # 包装成 LangChain Document 对象
        docs = [Document(page_content=full_text, metadata={"source": os.path.basename(file_path)})]
        return docs
    elif ext in ['.doc', '.docx']:
        loader = Docx2txtLoader(file_path)
    elif ext == '.txt':
        loader = TextLoader(file_path, encoding='utf-8')
    else:
        raise ValueError(f"不支持的文件类型: {ext}")
    return loader.load()