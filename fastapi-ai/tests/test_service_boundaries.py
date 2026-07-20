from app.core.config import get_settings
from app.rag.chunker import ContractChunker
from app.repositories.erp_repository import InMemoryErpRepository
from app.services.classification_service import ClassificationService
from app.services.erp_context_service import ErpContextService
from app.services.extraction_service import ExtractionService
from app.services.severity_service import SeverityService
from app.schemas.analyze import FeatureVector


def test_configuration_defaults_to_explicit_mock_mode() -> None:
    assert get_settings().mock_mode is True


def test_erp_context_is_served_through_repository_boundary() -> None:
    service = ErpContextService(InMemoryErpRepository())
    context = service._repository.find_context("CL", ["LITHIUM"])
    assert context.stock_days == 12
    assert context.contract_ids == [501]


def test_classification_and_severity_are_deterministic() -> None:
    features = FeatureVector(
        goldstein_scale=-7.2, news_count=15, country_is_mining_hub=True,
        rainfall_24h_mm=230.0, gdacs_alert_level=2,
        actor1_type="GOV", actor2_type="COM", stock_volatility_20d=0.041,
    )
    classification = ClassificationService().classify(features)
    severity = SeverityService().score(features, InMemoryErpRepository().find_context("CL", ["LITHIUM"]))
    assert classification.impact_domain == "PRODUCTION"
    assert severity.severity == "CRITICAL"
    assert severity.score == 87.3


def test_chunker_returns_stable_placeholder_for_unparsed_pdf() -> None:
    chunks = ContractChunker().chunk("")
    assert len(chunks) == 1
    assert chunks[0].page_number == 1
