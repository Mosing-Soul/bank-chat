import logging
import uuid

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app_exceptions import ApplicationError, ErrorCode


logger = logging.getLogger(__name__)
TRACE_HEADER = "X-Trace-Id"


def trace_id_from_request(request: Request) -> str:
    return getattr(request.state, "trace_id", None) or request.headers.get(TRACE_HEADER) or str(uuid.uuid4())


def error_payload(error_code: ErrorCode, trace_id: str):
    return {
        "code": error_code.name,
        "message": error_code.message,
        "traceId": trace_id,
        "retryable": error_code.retryable,
    }


def install_error_handling(app: FastAPI) -> None:
    @app.middleware("http")
    async def trace_middleware(request: Request, call_next):
        trace_id = request.headers.get(TRACE_HEADER) or str(uuid.uuid4())
        request.state.trace_id = trace_id
        response = await call_next(request)
        response.headers[TRACE_HEADER] = trace_id
        return response

    @app.exception_handler(ApplicationError)
    async def application_error_handler(request: Request, exc: ApplicationError):
        code = exc.error_code
        logger.warning("request failed code=%s traceId=%s detail=%s", code.name,
                       trace_id_from_request(request), exc.detail or "-")
        return JSONResponse(
            status_code=code.status_code,
            content=error_payload(code, trace_id_from_request(request)),
        )

    @app.exception_handler(RequestValidationError)
    async def validation_error_handler(request: Request, exc: RequestValidationError):
        trace_id = trace_id_from_request(request)
        logger.warning("request validation failed traceId=%s errors=%s", trace_id, exc.errors())
        code = ErrorCode.INVALID_REQUEST
        return JSONResponse(status_code=code.status_code, content=error_payload(code, trace_id))

    @app.exception_handler(Exception)
    async def unexpected_error_handler(request: Request, exc: Exception):
        trace_id = trace_id_from_request(request)
        logger.exception("unexpected request failure traceId=%s", trace_id)
        code = ErrorCode.INTERNAL_ERROR
        return JSONResponse(status_code=code.status_code, content=error_payload(code, trace_id))
