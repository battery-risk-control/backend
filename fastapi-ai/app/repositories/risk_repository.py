from typing import Protocol

from app.schemas.analyze import AnalyzeResponseData


class RiskRepository(Protocol):
    def save_analysis(self, result: AnalyzeResponseData) -> None: ...

    # [surin F3] 동일 외부 이벤트 재처리 식별(중복 방지)
    def remember(self, external_event_id: str) -> bool: ...


class InMemoryRiskRepository:
    def __init__(self) -> None:
        self._results: dict[str, AnalyzeResponseData] = {}
        # [surin F3] 이미 처리한 외부 이벤트 ID
        self._seen_external_event_ids: set[str] = set()

    def save_analysis(self, result: AnalyzeResponseData) -> None:
        self._results[result.analysis_id] = result

    def find_by_id(self, analysis_id: str) -> AnalyzeResponseData | None:
        return self._results.get(analysis_id)

    # [surin F3] 이미 본 이벤트면 False, 새로 기록했으면 True.
    def remember(self, external_event_id: str) -> bool:
        if external_event_id in self._seen_external_event_ids:
            return False
        self._seen_external_event_ids.add(external_event_id)
        return True

    def clear(self) -> None:
        self._seen_external_event_ids.clear()
