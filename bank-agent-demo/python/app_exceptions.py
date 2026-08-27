from enum import Enum
from typing import Optional


class ErrorCode(Enum):
    INVALID_REQUEST = (400, "请求内容不完整或格式不正确，请检查后重试。", False)
    RUNTIME_NOT_READY = (503, "AI 服务正在初始化，请稍后重试。", True)
    VECTOR_STORE_NOT_READY = (503, "知识库暂时不可用，请稍后重试。", True)
    VECTOR_REFRESH_REJECTED = (400, "知识库更新请求不符合要求，请检查后重试。", False)
    VECTOR_REFRESH_FAILED = (500, "知识库更新失败，请稍后重试。", True)
    INTERNAL_ERROR = (500, "AI 服务暂时无法处理该请求，请稍后重试。", False)

    def __init__(self, status_code: int, message: str, retryable: bool):
        self.status_code = status_code
        self.message = message
        self.retryable = retryable


class ApplicationError(Exception):
    def __init__(self, error_code: ErrorCode, *, detail: Optional[str] = None):
        super().__init__(detail or error_code.message)
        self.error_code = error_code
        self.detail = detail


class RuntimeNotReadyError(ApplicationError):
    def __init__(self):
        super().__init__(ErrorCode.RUNTIME_NOT_READY)


class VectorRefreshRejectedError(ApplicationError):
    def __init__(self, detail: Optional[str] = None):
        super().__init__(ErrorCode.VECTOR_REFRESH_REJECTED, detail=detail)


class VectorRefreshFailedError(ApplicationError):
    def __init__(self, detail: Optional[str] = None):
        super().__init__(ErrorCode.VECTOR_REFRESH_FAILED, detail=detail)
