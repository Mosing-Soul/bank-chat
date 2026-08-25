import logging
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass
from typing import Any, Optional

import uvicorn
from fastapi import FastAPI, Request

from ai_chat_models import (
    AiChatRequest,
    AiChatResponse,
    DialogueCommandRequest,
    DialogueCommandResponse,
    IntentType,
)
from ai_chat_orchestrator import AiChatOrchestrator, forced_intent_from_skill
from dialogue.command_interpreter import DialogueCommandInterpreter
from env_config import env_bool, env_float, env_int, env_path, optional_env, require_env
from intent.structured_intent import IntentRecognitionService
from rag_models import QueryRequest, QueryResponse
from rag_query_service import RagService, build_rag_answer_prompt, unique_sources
from skill_handlers import ExternalModelApiSkill, GeneralChatSkill, KnowledgeRagSkill
from skill_router import SkillRouter
from vector_store_manager import VectorStoreManager


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class RuntimeSettings:
    docs_folder: str
    vector_db_dir: str
    rag_score_threshold: float
    rag_top_k: int
    host: str
    port: int

    @classmethod
    def from_env(cls):
        return cls(
            docs_folder=str(env_path("BANK_DOCS_FOLDER")),
            vector_db_dir=str(env_path("VECTOR_DB_DIR")),
            rag_score_threshold=env_float("RAG_SCORE_THRESHOLD"),
            rag_top_k=env_int("RAG_TOP_K"),
            host=require_env("PYTHON_SERVICE_HOST"),
            port=env_int("PYTHON_SERVICE_PORT"),
        )


@dataclass
class RuntimeResources:
    settings: RuntimeSettings
    embedding_model: Any
    llm: Any
    external_search_client: Any
    vector_store_manager: VectorStoreManager
    rag_service: RagService
    intent_service: IntentRecognitionService
    dialogue_interpreter: DialogueCommandInterpreter
    skill_router: SkillRouter
    ai_chat_orchestrator: AiChatOrchestrator


def initialize_runtime() -> RuntimeResources:
    """Create one dependency graph during the FastAPI lifespan."""
    settings = RuntimeSettings.from_env()
    os.environ["OPENAI_API_KEY"] = require_env("OPENAI_API_KEY1")
    os.environ["OPENAI_BASE_URL"] = require_env("OPENAI_BASE_URL")
    embedding_model = create_embedding_model()
    llm = create_chat_model()
    intent_llm = create_intent_model()
    external_search_client = create_external_search_client()

    vector_store_manager = VectorStoreManager(settings.vector_db_dir, embedding_model)
    vector_store_manager.load()
    rag_service = RagService(
        vector_store_manager,
        llm,
        settings.rag_score_threshold,
        settings.rag_top_k,
    )
    intent_service = IntentRecognitionService(intent_llm)
    dialogue_interpreter = DialogueCommandInterpreter(llm)
    skill_router = build_skill_router(rag_service, external_search_client, llm)
    ai_chat_orchestrator = AiChatOrchestrator(intent_service, rag_service, external_search_client, llm)
    return RuntimeResources(
        settings=settings,
        embedding_model=embedding_model,
        llm=llm,
        external_search_client=external_search_client,
        vector_store_manager=vector_store_manager,
        rag_service=rag_service,
        intent_service=intent_service,
        dialogue_interpreter=dialogue_interpreter,
        skill_router=skill_router,
        ai_chat_orchestrator=ai_chat_orchestrator,
    )


def create_embedding_model():
    from langchain_community.embeddings import HuggingFaceEmbeddings

    return HuggingFaceEmbeddings(
        model_name=require_env("EMBEDDING_MODEL_NAME"),
        model_kwargs={
            "device": require_env("EMBEDDING_DEVICE"),
            "local_files_only": env_bool("EMBEDDING_LOCAL_FILES_ONLY"),
        },
        encode_kwargs={"normalize_embeddings": True},
    )


def create_chat_model():
    from langchain_openai import ChatOpenAI

    return ChatOpenAI(model=require_env("CHAT_MODEL"))


def create_intent_model():
    from langchain_openai import ChatOpenAI

    return ChatOpenAI(model=optional_env("INTENT_MODEL") or require_env("CHAT_MODEL"), temperature=0)


def create_external_search_client():
    from external_search_client import ExternalSearchClient, ExternalSearchConfigError

    try:
        return ExternalSearchClient()
    except (ExternalSearchConfigError, RuntimeError) as exc:
        logger.warning("external search client disabled: %s", exc)
        return None


def build_skill_router(rag_service, external_search_client, llm) -> SkillRouter:
    return SkillRouter({
        IntentType.KNOWLEDGE_QA: KnowledgeRagSkill(rag_service.query),
        IntentType.EXTERNAL_API_QUERY: ExternalModelApiSkill(external_search_client, llm),
        IntentType.GENERAL_CHAT: GeneralChatSkill(llm),
    })


@asynccontextmanager
async def lifespan(app_instance: FastAPI):
    app_instance.state.runtime = initialize_runtime()
    yield
    app_instance.state.runtime = None


app = FastAPI(lifespan=lifespan)


def runtime_from_request(request: Request) -> RuntimeResources:
    runtime = getattr(request.app.state, "runtime", None)
    if runtime is None:
        raise RuntimeError("application runtime is not initialized")
    return runtime


def perform_rag_query(question: str, session_id: str, history=None, runtime=None) -> QueryResponse:
    """Compatibility facade for non-HTTP callers."""
    if runtime is None:
        raise RuntimeError("runtime is required outside an HTTP request")
    return runtime.rag_service.query(question, session_id, history)


@app.get("/health")
def health(request: Request):
    runtime = runtime_from_request(request)
    return {"status": "ok", "vectorStoreReady": runtime.vector_store_manager.ready}


@app.post("/rag/query", response_model=QueryResponse)
def rag_query(payload: QueryRequest, request: Request):
    runtime = runtime_from_request(request)
    return runtime.rag_service.query(payload.question, payload.session_id, payload.history)


@app.post("/rag/eval_query", response_model=QueryResponse)
def rag_eval_query(payload: QueryRequest, request: Request):
    return runtime_from_request(request).rag_service.evaluate(payload)


@app.post("/ai/chat/invoke", response_model=AiChatResponse)
def ai_chat_invoke(payload: AiChatRequest, request: Request):
    return runtime_from_request(request).ai_chat_orchestrator.invoke(payload)


@app.post("/ai/dialogue/commands", response_model=DialogueCommandResponse)
def dialogue_commands(payload: DialogueCommandRequest, request: Request):
    """Shadow endpoint; Java policy validates commands before execution."""
    return runtime_from_request(request).dialogue_interpreter.interpret(payload)


@app.post("/refresh")
def refresh_vector_store(request: Request):
    runtime = runtime_from_request(request)
    try:
        result = runtime.vector_store_manager.refresh(runtime.settings.docs_folder)
    except (FileNotFoundError, ValueError) as exc:
        logger.warning("vector refresh rejected: %s", exc)
        return {"status": "error", "message": str(exc)}
    except Exception as exc:
        logger.exception("vector refresh failed")
        return {"status": "error", "message": f"向量库重建失败：{type(exc).__name__}"}
    return {
        "status": "ok",
        "message": "向量库重建成功",
        "indexId": result.index_id,
    }


if __name__ == "__main__":
    server_settings = RuntimeSettings.from_env()
    uvicorn.run(app, host=server_settings.host, port=server_settings.port)
