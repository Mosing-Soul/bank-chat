import os
from typing import Any, Dict, List

import httpx


class JavaSkillClientError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class JavaSkillClient:
    def __init__(self, base_url=None, api_key=None, api_key_header=None, timeout=None, transport=None):
        self.base_url = (base_url or os.getenv("JAVA_SKILL_BASE_URL") or "http://localhost:8080/internal/skills").rstrip("/")
        self.api_key = api_key or os.getenv("INTERNAL_SKILL_API_KEY", "local-dev-internal-key")
        self.api_key_header = api_key_header or os.getenv("INTERNAL_SKILL_API_KEY_HEADER", "X-Internal-Api-Key")
        seconds = float(timeout or os.getenv("JAVA_SKILL_TIMEOUT_SECONDS", "5"))
        self.timeout = httpx.Timeout(seconds, connect=min(seconds, 2.0))
        self.transport = transport

    def search_customers(self, trace_id: str, name: str) -> List[Dict[str, Any]]:
        data = self._request("GET", "/customers/search", trace_id, params={"name": name})
        return data if isinstance(data, list) else []

    def get_aum(self, trace_id: str, customer_id: str) -> Dict[str, Any]:
        data = self._request("GET", f"/customers/{customer_id}/aum", trace_id)
        return data if isinstance(data, dict) else {}

    def preview_message(self, trace_id: str, customer_id: str, template_code: str, variables: Dict[str, str]) -> Dict[str, Any]:
        payload = {
            "traceId": trace_id,
            "customerId": customer_id,
            "templateCode": template_code,
            "variables": variables,
        }
        data = self._request("POST", "/messages/preview", trace_id, json=payload)
        return data if isinstance(data, dict) else {}

    def send_message(self, trace_id: str, operation_id: str, confirmed: bool = True) -> Dict[str, Any]:
        payload = {
            "traceId": trace_id,
            "operationId": operation_id,
            "confirmed": confirmed,
        }
        data = self._request("POST", "/messages/send", trace_id, json=payload)
        return data if isinstance(data, dict) else {}

    def _request(self, method: str, path: str, trace_id: str, **kwargs):
        headers = kwargs.pop("headers", {})
        headers[self.api_key_header] = self.api_key
        headers["X-Trace-Id"] = trace_id
        try:
            with httpx.Client(timeout=self.timeout, transport=self.transport, trust_env=False) as client:
                response = client.request(method, self.base_url + path, headers=headers, **kwargs)
        except httpx.TimeoutException as exc:
            raise JavaSkillClientError("JAVA_SKILL_TIMEOUT", "Java skill service timeout") from exc
        except httpx.HTTPError as exc:
            raise JavaSkillClientError("JAVA_SKILL_NETWORK_ERROR", "Java skill service unavailable") from exc

        if response.status_code >= 400:
            raise JavaSkillClientError("JAVA_SKILL_HTTP_ERROR", f"Java skill service returned {response.status_code}")
        body = response.json()
        if not body.get("success"):
            error = body.get("error") or {}
            raise JavaSkillClientError(error.get("code", "JAVA_SKILL_ERROR"), error.get("message", "Java skill error"))
        return body.get("data")
