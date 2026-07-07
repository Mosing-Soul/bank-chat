import os
from typing import Optional

from langchain_community.utilities import GoogleSerperAPIWrapper


class ExternalSearchConfigError(Exception):
    pass


class ExternalSearchClient:
    def __init__(self, serper_api_key: Optional[str] = None):
        self.serper_api_key = serper_api_key or os.getenv("SERPER_API_KEY") or os.getenv("SERPER_API_KEY1")
        if not self.serper_api_key:
            raise ExternalSearchConfigError("SERPER_API_KEY is not configured")

        os.environ["SERPER_API_KEY"] = self.serper_api_key
        self.search = GoogleSerperAPIWrapper()

    def search_text(self, query: str) -> str:
        return self.search.run(query)
