from typing import Protocol

from app.schemas.analyze import AnalyzeResponseData


class RiskRepository(Protocol):
    def save_analysis(self, result: AnalyzeResponseData) -> None: ...


class InMemoryRiskRepository:
    def __init__(self) -> None:
        self._results: dict[str, AnalyzeResponseData] = {}

    def save_analysis(self, result: AnalyzeResponseData) -> None:
        self._results[result.analysis_id] = result

    def find_by_id(self, analysis_id: str) -> AnalyzeResponseData | None:
        return self._results.get(analysis_id)
