from app.models.extraction_inference import ExtractionInference, MockExtractionInference
from app.schemas.analyze import EventInput, ExtractionRequest, ExtractionResult


class ExtractionService:
    """뉴스 원문을 구조화 정보(자재/이벤트유형/도메인초안/어조점수)로 변환합니다.

    실제 LLM 연동 시에는 ExtractionInference를 구현한 클래스를 생성자에 주입하면
    나머지 F3 파이프라인(OrchestrationService 등)은 코드 변경 없이 그대로 동작합니다.
    """

    def __init__(self, inference: ExtractionInference | None = None) -> None:
        self._inference = inference or MockExtractionInference()

    def extract(self, event: EventInput | ExtractionRequest) -> ExtractionResult:
        prediction = self._inference.extract(event)
        return ExtractionResult(
            affected_materials=prediction.affected_materials,
            event_type=prediction.event_type,
            impact_domain_draft=prediction.impact_domain_draft,
            tone_score=prediction.tone_score,
            summary_kr=prediction.summary_kr,
            is_supply_chain_relevant=prediction.is_supply_chain_relevant,
            extraction_model_version=prediction.model_version,
            mock=prediction.mock,
        )
