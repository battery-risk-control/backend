from app.repositories.erp_repository import ErpContext, ErpRepository, InMemoryErpRepository
from app.schemas.analyze import ExtractionResult, MatchedEntities


class ErpContextService:
    def __init__(self, repository: ErpRepository | None = None) -> None:
        self._repository = repository or InMemoryErpRepository()

    def get_context(self, extraction: ExtractionResult) -> ErpContext:
        return self._repository.find_context(extraction.country_code, extraction.affected_materials)

    @staticmethod
    def matched_entities(context: ErpContext) -> MatchedEntities:
        return MatchedEntities(
            material_id=context.material_id,
            supplier_ids=context.supplier_ids,
            contract_ids=context.contract_ids,
        )
