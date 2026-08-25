import os
from dataclasses import dataclass, field
from typing import List
from typing import Optional

from langchain_community.utilities import GoogleSerperAPIWrapper

from env_config import require_env


class ExternalSearchConfigError(Exception):
    pass


@dataclass
class ExternalSource:
    title: str
    url: str
    snippet: str = ""
    date: str = ""


@dataclass
class ExternalSearchResult:
    context: str
    sources: List[ExternalSource] = field(default_factory=list)


class ExternalSearchClient:
    def __init__(self, serper_api_key: Optional[str] = None):
        self.serper_api_key = serper_api_key or require_env("SERPER_API_KEY1")

        os.environ["SERPER_API_KEY"] = self.serper_api_key
        self.search = GoogleSerperAPIWrapper()

    def search_text(self, query: str) -> str:
        return self.search.run(query)

    def search_with_sources(self, query: str) -> ExternalSearchResult:
        payload = self.search.results(query)
        organic = payload.get("organic") if isinstance(payload, dict) else None
        sources = []
        for item in (organic or [])[:8]:
            url = str(item.get("link") or "").strip()
            if not url:
                continue
            sources.append(ExternalSource(
                title=str(item.get("title") or url).strip(),
                url=url,
                snippet=str(item.get("snippet") or "").strip(),
                date=str(item.get("date") or "").strip(),
            ))
        if not sources:
            return ExternalSearchResult(context=self.search.run(query), sources=[])
        context = "\n\n".join(
            f"[WEB-{index}] 标题：{source.title}\n发布日期：{source.date or '未提供'}\n"
            f"摘要：{source.snippet or '无'}\nURL：{source.url}"
            for index, source in enumerate(sources, start=1)
        )
        return ExternalSearchResult(context=context, sources=sources)
