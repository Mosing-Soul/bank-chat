import os
import re
import pandas as pd
from langchain_community.document_loaders import PyPDFLoader, TextLoader
from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import Chroma
from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter


# 代理配置（如需）
os.environ['HTTP_PROXY'] = 'http://127.0.0.1:7890'
os.environ['HTTPS_PROXY'] = 'http://127.0.0.1:7890'

# 配置
DOCUMENTS_DIR = "../bank_docs"          # 存放文档的文件夹（相对于当前工作目录）
VECTOR_DB_DIR = "./chroma_db"          # 向量库持久化目录
CHUNK_SIZE = 500
CHUNK_OVERLAP = 50

# Embedding 模型（CPU 上运行）
embedding_model = HuggingFaceEmbeddings(
    model_name="BAAI/bge-small-zh-v1.5",
    model_kwargs={'device': 'cpu', 'local_files_only': True},
    encode_kwargs={'normalize_embeddings': True}
)

# ---------- Excel 解析函数 ----------
MONTH_OR_PERIOD_RE = re.compile(
    r"^("
    r"\d{1,2}月(?:份)?|"
    r"0?\d{1,2}月|"
    r"\d{4}年\d{1,2}月(?:份)?|"
    r"[一二三四1-4]季度|"
    r"\d{4}Q[1-4]|"
    r"Q[1-4]|"
    r"本年累计|累计数|本期数|当期数|"
    r"截至当期|截至\d{1,2}月末|截至期末|"
    r"本年累计/截至当期|月末|期末|当期|数值"
    r")$"
)

def clean_cell(value):
    if pd.isna(value):
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return re.sub(r"\s+", " ", str(value)).strip()


def is_empty(value):
    return clean_cell(value) == ""


def normalize_label(value):
    return re.sub(r"\s+", "", clean_cell(value))


def is_note_row(label):
    return clean_cell(label).startswith(("注：", "注:", "备注", "说明"))


def is_unit_row(row_values):
    values = [clean_cell(v) for v in row_values if not is_empty(v)]
    return bool(values) and all("单位" in v for v in values)


def is_time_row(row_values):
    return bool(row_values) and normalize_label(row_values[0]) == "时间"


def looks_like_period_header(value):
    text = normalize_label(value)
    return bool(MONTH_OR_PERIOD_RE.match(text)) or bool(re.match(r"^\d{4}年$", text))


def looks_like_header_row(row_values):
    if not row_values:
        return False
    first = normalize_label(row_values[0])
    rest = [v for v in row_values[1:] if not is_empty(v)]
    if first in {"项目", "指标", "名称"}:
        return True
    if rest and sum(1 for v in rest if looks_like_period_header(v)) >= max(1, len(rest) // 2):
        return True
    return False


def make_headers(row_values, previous_row=None):
    headers = []
    previous_row = previous_row or []
    max_len = max(len(row_values), len(previous_row))
    for idx in range(max_len):
        current = clean_cell(row_values[idx]) if idx < len(row_values) else ""
        previous = clean_cell(previous_row[idx]) if idx < len(previous_row) else ""
        if idx == 0:
            headers.append(current or previous or "项目")
        elif current:
            headers.append(current)
        elif previous and normalize_label(previous) not in {"时间", "项目", "指标", "名称"}:
            headers.append(previous)
        else:
            headers.append("")
    return headers


def is_parent_row(row_values):
    values = [clean_cell(v) for v in row_values]
    non_empty_positions = [idx for idx, value in enumerate(values) if value]
    if non_empty_positions != [0]:
        return False
    label = values[0]
    if is_note_row(label) or "单位" in label:
        return False
    if normalize_label(label) in {"项目", "指标", "时间", "数值"}:
        return False
    return True


def row_has_data(row_values):
    if not row_values or is_empty(row_values[0]) or is_note_row(row_values[0]):
        return False
    return any(not is_empty(v) for v in row_values[1:])


def is_vertical_child_label(label):
    text = normalize_label(label)
    return bool(re.match(r"^\d+[、.．]", text)) or text.startswith("其中：") or text.startswith("其中:")


def is_two_column_table(headers, row_values):
    header_count = sum(1 for header in headers if clean_cell(header))
    value_count = sum(1 for value in row_values if clean_cell(value))
    return max(header_count, value_count) <= 2


def format_cell_value(metric, header, value):
    value_text = clean_cell(value)
    metric_text = clean_cell(metric)
    header_text = clean_cell(header)
    metric_context = metric_text + header_text
    if any(keyword in metric_context for keyword in ("率", "占比", "比例", "净息差")):
        try:
            numeric_value = float(value_text.replace(",", ""))
        except ValueError:
            return value_text
        if -1 <= numeric_value <= 1:
            percent_text = f"{numeric_value * 100:.2f}".rstrip("0").rstrip(".")
            return f"{value_text}（{percent_text}%）"
    return value_text


def format_row_chunk(title, parent, row_label, headers, row_values, sheet_name):
    data_parts = []
    for idx in range(1, min(len(headers), len(row_values))):
        header = clean_cell(headers[idx])
        value = format_cell_value(row_label, header, row_values[idx])
        if not value or not header:
            continue
        if normalize_label(header) in {"项目", "指标", "时间"}:
            continue
        data_parts.append(f"{header}:{value}")

    if not data_parts:
        return ""

    subject = clean_cell(row_label)
    if parent:
        subject = f"{clean_cell(parent)} - {subject}"

    context = []
    if title:
        context.append(f"表:{clean_cell(title)}")
    if sheet_name:
        context.append(f"sheet:{clean_cell(sheet_name)}")
    context.append(f"{subject}：" + " | ".join(data_parts))
    return " | ".join(context)


def parse_general_excel(file_path):
    """按行解析 Excel，保留真实表头，并把父级标题带入数据行。"""
    try:
        sheets = pd.read_excel(file_path, sheet_name=None, header=None, dtype=object)
    except Exception as exc:
        print(f"Pandas 加载失败: {file_path}, 错误: {exc}")
        return []

    chunks = []
    source = os.path.basename(file_path)
    for sheet_name, df in sheets.items():
        if df is None or df.empty:
            continue
        df = df.dropna(how="all").dropna(axis=1, how="all")
        if df.empty:
            continue

        title = ""
        parent = ""
        vertical_parent = ""
        headers = []
        previous_row = []

        for row_idx, row in df.iterrows():
            row_values = [clean_cell(v) for v in row.tolist()]
            if not any(row_values):
                previous_row = row_values
                continue

            first_value = row_values[0]
            non_empty_count = sum(1 for value in row_values if value)

            if not title and non_empty_count == 1 and not is_note_row(first_value):
                title = first_value
                previous_row = row_values
                continue

            if is_note_row(first_value) or is_unit_row(row_values) or is_time_row(row_values):
                previous_row = row_values
                continue

            if looks_like_header_row(row_values):
                headers = make_headers(row_values, previous_row)
                previous_row = row_values
                continue

            if is_parent_row(row_values):
                parent = first_value
                vertical_parent = ""
                previous_row = row_values
                continue

            if row_has_data(row_values):
                if not headers:
                    headers = make_headers(["项目"] + [f"值{i}" for i in range(1, len(row_values))], previous_row)
                row_parent = parent
                if is_two_column_table(headers, row_values) and is_vertical_child_label(first_value):
                    row_parent = vertical_parent or parent
                chunk_text = format_row_chunk(title, row_parent, first_value, headers, row_values, sheet_name)
                if chunk_text:
                    chunks.append(
                        Document(
                            page_content=chunk_text,
                            metadata={
                                "source": source,
                                "sheet": str(sheet_name),
                                "row_index": int(row_idx),
                                "parent": clean_cell(row_parent),
                                "type": "excel_general",
                            },
                        )
                    )
                if is_two_column_table(headers, row_values) and not is_vertical_child_label(first_value):
                    vertical_parent = first_value

            previous_row = row_values

    return chunks


def load_excel(file_path):
    """统一使用结构感知解析，避免错误路由导致表头/父级丢失。"""
    chunks = parse_general_excel(file_path)
    if chunks:
        return chunks
    return load_excel_fallback(file_path)

def load_excel_fallback(file_path):
    """Pandas 后备解析，读取所有 sheet 并逐行转文本"""
    try:
        sheets = pd.read_excel(file_path, sheet_name=None, dtype=object)
    except Exception as exc:
        print(f"Pandas 加载失败: {file_path}, 错误: {exc}")
        return []

    chunks = []
    source = os.path.basename(file_path)
    for sheet_name, df in sheets.items():
        if df is None or df.empty:
            continue
        df = df.dropna(how="all")
        if df.empty:
            continue

        # 用列索引生成列名
        columns = []
        for idx, col in enumerate(df.columns):
            col_name = str(col).strip()
            if not col_name or col_name.lower().startswith("unnamed:"):
                col_name = f"column_{idx + 1}"
            columns.append(col_name)

        for row_idx, row in df.iterrows():
            row_parts = []
            for col_name, val in zip(columns, row.tolist()):
                if pd.isna(val):
                    continue
                val_text = str(val).strip()
                if val_text:
                    row_parts.append(f"{col_name}: {val_text}")
            if row_parts:
                chunks.append(
                    Document(
                        page_content=" | ".join(row_parts),
                        metadata={
                            "source": source,
                            "sheet": str(sheet_name),
                            "row_index": int(row_idx),
                            "type": "pandas_fallback"
                        }
                    )
                )
    return chunks

def load_with_eparse(file_path):
    try:
        from eparse.core import get_df_from_file
    except ImportError:
        print("eparse 未安装，请运行: pip install eparse")
        return []
    chunks = []
    tables = get_df_from_file(file_path)
    for table_idx, (df, metadata) in enumerate(tables):
        if df.empty:
            continue
        sheet_name = metadata.get('sheet_name', 'Unknown')
        table_range = metadata.get('range', '')
        for _, row in df.iterrows():
            row_parts = []
            for col, val in row.items():
                if pd.notna(val) and str(val).strip():
                    row_parts.append(f"{col}: {val}")
            if row_parts:
                chunks.append(
                    Document(
                        page_content=" | ".join(row_parts),
                        metadata={
                            "source": os.path.basename(file_path),
                            "sheet": sheet_name,
                            "table_index": table_idx,
                            "range": table_range,
                            "type": "eparse"
                        }
                    )
                )
    return chunks

def load_with_ks_parser(file_path):
    try:
        from ks_xlsx_parser import parse_xlsx
    except ImportError:
        print("ks-xlsx-parser 未安装，请运行: pip install ks-xlsx-parser")
        return []
    result = parse_xlsx(file_path)
    chunks = []
    for sheet in result.get("sheets", []):
        sheet_name = sheet.get("name", "Unknown")
        for table_idx, table in enumerate(sheet.get("tables", [])):
            headers = table.get("headers", [])
            rows = table.get("rows", [])
            table_range = table.get("range", "")
            for row_idx, row in enumerate(rows):
                row_data = []
                for i, val in enumerate(row):
                    if i < len(headers) and val and str(val).strip():
                        row_data.append(f"{headers[i]}: {val}")
                if row_data:
                    cell_refs = table.get("cell_refs", [])
                    ref_str = " | ".join(cell_refs[row_idx]) if cell_refs and row_idx < len(cell_refs) else ""
                    chunks.append(
                        Document(
                            page_content=" | ".join(row_data),
                            metadata={
                                "source": os.path.basename(file_path),
                                "sheet": sheet_name,
                                "table_index": table_idx,
                                "row_index": row_idx,
                                "range": table_range,
                                "cell_refs": ref_str,
                                "type": "ks_parser"
                            }
                        )
                    )
    return chunks

def has_complex_structure(file_path):
    try:
        from openpyxl import load_workbook
        wb = load_workbook(file_path, data_only=False)
        sheet = wb.active
        if sheet.merged_cells:
            return True
        for row in sheet.iter_rows(max_row=100):
            for cell in row:
                if cell.value and isinstance(cell.value, str) and cell.value.startswith('='):
                    return True
    except:
        pass
    return False

# ---------- 其他文档加载 ----------
def load_markdown(file_path):
    loader = TextLoader(file_path, encoding='utf-8')
    docs = loader.load()
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

# ---------- 构建向量库 ----------
def build_vector_store():
    # 检查目录是否存在
    if not os.path.exists(DOCUMENTS_DIR):
        print(f"错误：目录 {DOCUMENTS_DIR} 不存在")
        return

    # 列出支持的文件
    supported_exts = ('.pdf', '.xls', '.xlsx', '.md')
    files = [f for f in os.listdir(DOCUMENTS_DIR) if f.lower().endswith(supported_exts)]
    if not files:
        print(f"警告：在 {DOCUMENTS_DIR} 中没有找到支持的文档文件")
        print("支持的文件类型：", supported_exts)
        return

    all_docs = []
    for filename in files:
        file_path = os.path.join(DOCUMENTS_DIR, filename)
        print(f"正在加载: {file_path}")
        docs = load_document(file_path)
        if not docs:
            print(f"  加载 {filename} 返回空，跳过")
            continue
        # 对 PDF/MD 切分，Excel 已经按行切分，不再切分
        if filename.endswith(('.pdf', '.md')):
            splitter = RecursiveCharacterTextSplitter(
                chunk_size=CHUNK_SIZE,
                chunk_overlap=CHUNK_OVERLAP,
                separators=["\n\n", "\n", "。", "！", "？", "；", "，", " ", ""]
            )
            docs = splitter.split_documents(docs)
        all_docs.extend(docs)
        print(f"  -> 生成了 {len(docs)} 个片段")

    if not all_docs:
        print("错误：没有任何文档片段被生成，请检查文档内容或解析逻辑。")
        return

    print(f"总计片段数: {len(all_docs)}")
    # 构建向量库
    vectordb = Chroma.from_documents(
        documents=all_docs,
        embedding=embedding_model,
        persist_directory=VECTOR_DB_DIR
    )
    vectordb.persist()
    print(f"向量库已保存至 {VECTOR_DB_DIR}")

if __name__ == "__main__":
    if not os.path.exists(DOCUMENTS_DIR):
        os.makedirs(DOCUMENTS_DIR)
        print(f"Please put your documents in {DOCUMENTS_DIR} and run again.")
    else:
        build_vector_store()
