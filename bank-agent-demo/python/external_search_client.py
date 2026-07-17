import os
from typing import Optional

from langchain_community.utilities import GoogleSerperAPIWrapper

from env_config import require_env


class ExternalSearchConfigError(Exception):
    pass


class ExternalSearchClient:
    def __init__(self, serper_api_key: Optional[str] = None):
        self.serper_api_key = serper_api_key or require_env("SERPER_API_KEY1")

        os.environ["SERPER_API_KEY"] = self.serper_api_key
        self.search = GoogleSerperAPIWrapper()

    def search_text(self, query: str) -> str:
        return self.search.run(query)
