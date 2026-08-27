from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app_exceptions import RuntimeNotReadyError
from error_handlers import install_error_handling


test_app = FastAPI()
install_error_handling(test_app)


class Payload(BaseModel):
    question: str


@test_app.get("/runtime")
def runtime_endpoint():
    raise RuntimeNotReadyError()


@test_app.post("/validate")
def validation_endpoint(payload: Payload):
    return payload


def test_runtime_not_ready_uses_standard_error_contract():
    with TestClient(test_app, raise_server_exceptions=False) as client:
        response = client.get("/runtime", headers={"X-Trace-Id": "trace-runtime"})

    assert response.status_code == 503
    assert response.headers["X-Trace-Id"] == "trace-runtime"
    assert response.json() == {
        "code": "RUNTIME_NOT_READY",
        "message": "AI 服务正在初始化，请稍后重试。",
        "traceId": "trace-runtime",
        "retryable": True,
    }


def test_validation_error_does_not_expose_framework_details():
    with TestClient(test_app, raise_server_exceptions=False) as client:
        response = client.post(
            "/validate",
            headers={"X-Trace-Id": "trace-validation"},
            json={},
        )

    assert response.status_code == 400
    assert response.json()["code"] == "INVALID_REQUEST"
    assert response.json()["traceId"] == "trace-validation"
    assert "detail" not in response.json()
