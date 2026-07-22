from app.core.config import get_settings
import app.services.document_service as document_service_module
from app.services.document_service import DocumentService
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
    assert severity.score == 70.0


def test_document_chunker_returns_stable_chunks() -> None:
    chunks = DocumentService._chunk_text("A" * 50, chunk_size=20, overlap=5)
    assert len(chunks) == 3
    assert chunks[0] == "A" * 20


def test_document_chunker_prioritizes_contract_clause_boundaries() -> None:
    text = (
        "계약서 머리말\n\n"
        "제1조(목적)\n이 계약은 리튬 공급 조건을 정한다.\n\n"
        "제2조(가격 조정)\n가격은 지수 변동에 따라 조정한다."
    )
    chunks = DocumentService._chunk_text(text, chunk_size=500, overlap=50)
    assert chunks == [
        "계약서 머리말",
        "제1조(목적)\n이 계약은 리튬 공급 조건을 정한다.",
        "제2조(가격 조정)\n가격은 지수 변동에 따라 조정한다.",
    ]


def test_pdf_chunks_keep_one_based_source_page_numbers(monkeypatch) -> None:
    class FakePage:
        def __init__(self, text: str) -> None:
            self._text = text

        def extract_text(self) -> str:
            return self._text

    class FakeReader:
        is_encrypted = False
        pages = [FakePage("Article 1 Purpose\nFirst page."), FakePage("Article 2 Price\nSecond page.")]

    monkeypatch.setattr(document_service_module, "PdfReader", lambda _stream: FakeReader())
    document, duplicate = DocumentService().process(
        b"%PDF-test",
        "contract.pdf",
        contract_id=1,
        supplier_id=2,
        material_id=3,
        document_type="LTA",
        document_id="spring-document-id",
    )

    assert duplicate is False
    assert [chunk.chunk_index for chunk in document.chunks] == [0, 1]
    assert [chunk.page_number for chunk in document.chunks] == [1, 2]
    assert all(chunk.document_id == "spring-document-id" for chunk in document.chunks)
    assert all(chunk.content_hash == document.content_hash for chunk in document.chunks)
