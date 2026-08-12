from fastapi.testclient import TestClient

from app.main import app
from app.core.config import Settings
import app.services.document_service as document_service_module


client = TestClient(app)


def make_text_pdf(page_texts: list[str]) -> bytes:
    objects: list[bytes] = []
    page_numbers = [3 + index * 2 for index in range(len(page_texts))]
    font_number = 3 + len(page_texts) * 2
    objects.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{number} 0 R" for number in page_numbers)
    objects.append(f"<< /Type /Pages /Kids [{kids}] /Count {len(page_texts)} >>".encode())
    for index, text in enumerate(page_texts):
        page_number = page_numbers[index]
        content_number = page_number + 1
        escaped = text.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
        stream = f"BT /F1 12 Tf 72 720 Td ({escaped}) Tj ET".encode("latin-1")
        objects.append(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 {font_number} 0 R >> >> "
            f"/Contents {content_number} 0 R >>".encode()
        )
        objects.append(
            f"<< /Length {len(stream)} >>\nstream\n".encode()
            + stream
            + b"\nendstream"
        )
    objects.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

    pdf = bytearray(b"%PDF-1.4\n")
    offsets = [0]
    for number, obj in enumerate(objects, start=1):
        offsets.append(len(pdf))
        pdf.extend(f"{number} 0 obj\n".encode())
        pdf.extend(obj)
        pdf.extend(b"\nendobj\n")
    xref = len(pdf)
    pdf.extend(f"xref\n0 {len(objects) + 1}\n".encode())
    pdf.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        pdf.extend(f"{offset:010d} 00000 n \n".encode())
    pdf.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\n"
        f"startxref\n{xref}\n%%EOF\n".encode()
    )
    return bytes(pdf)


def upload(content: bytes = b"Price adjustment clause for lithium supply."):
    return client.post(
        "/api/v1/documents/process",
        files={"file": ("contract.txt", content, "text/plain")},
        data={"document_id": "11111111-1111-1111-1111-111111111111", "contract_id": "1",
              "supplier_id": "2", "material_id": "3", "document_type": "LTA"},
    )


def test_process_text_document_and_detect_duplicate():
    first = upload()
    assert first.status_code == 200
    assert first.json()["data"]["processing_status"] == "COMPLETED"
    assert first.json()["data"]["document_id"] == "11111111-1111-1111-1111-111111111111"
    assert first.json()["data"]["chunk_count"] == 1
    assert first.json()["data"]["duplicate"] is False
    assert first.json()["data"]["embedding_type"] == "MOCK_TOKEN_HASH"
    assert first.json()["data"]["embedding_version"] == "mock-v1"
    assert first.json()["data"]["mock_embedding"] is True
    chunk = first.json()["data"]["chunks"][0]
    assert chunk == {
        "document_id": "11111111-1111-1111-1111-111111111111",
        "chunk_index": 0,
        "page_number": 1,
        "content": "Price adjustment clause for lithium supply.",
        "contract_id": 1,
        "supplier_id": 2,
        "material_id": 3,
        "product_id": None,
        "customer_id": None,
        "document_type": "LTA",
        "content_hash": first.json()["data"]["content_hash"],
    }

    second = upload()
    assert second.status_code == 200
    assert second.json()["data"]["document_id"] == first.json()["data"]["document_id"]
    assert second.json()["data"]["duplicate"] is True


def test_rejects_unsupported_document():
    response = client.post(
        "/api/v1/documents/process",
        files={"file": ("contract.exe", b"invalid", "application/octet-stream")},
        data={"document_id": "22222222-2222-2222-2222-222222222222", "contract_id": "1",
              "supplier_id": "2", "material_id": "3"},
    )
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "UNSUPPORTED_DOCUMENT_TYPE"


def test_rejects_empty_text_document():
    response = upload(b" \n\t ")
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "EMPTY_DOCUMENT"


def test_rejects_non_utf8_text_document():
    response = upload(b"\xff\xfe\xfa")
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "TEXT_EXTRACTION_FAILED"


def test_document_chunk_output_is_exposed_in_openapi():
    schema = client.get("/openapi.json").json()
    operation = schema["paths"]["/api/v1/documents/process"]["post"]
    assert "200" in operation["responses"]

    # supplier_id/material_id(인바운드)와 product_id/customer_id(아웃바운드)는 문서 종류에 따라
    # 둘 중 한 쌍만 채워지므로 스키마 레벨에선 전부 optional — 실제 인바운드 응답값은 그대로 채워짐.
    chunk_schema = schema["components"]["schemas"]["DocumentChunkResult"]
    assert set(chunk_schema["required"]) == {
        "document_id", "chunk_index", "page_number", "content",
        "contract_id", "document_type", "content_hash",
    }


def test_health_reports_chroma_collection() -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "UP"
    assert response.json()["vector_store"]["status"] == "UP"
    assert response.json()["vector_store"]["collection_name"] == "contract_documents_mock_v1"


def test_force_reprocess_reuses_document_id_without_duplicate_chunks() -> None:
    first = upload(b"Article 1 Price adjustment\nLithium price clause")
    assert first.status_code == 200

    second = client.post(
        "/api/v1/documents/process",
        files={"file": (
            "contract.txt",
            b"Article 1 Price adjustment\nLithium price clause",
            "text/plain",
        )},
        data={
            "document_id": "11111111-1111-1111-1111-111111111111",
            "contract_id": "1",
            "supplier_id": "2",
            "material_id": "3",
            "document_type": "LTA",
            "force_reprocess": "true",
        },
    )

    assert second.status_code == 200
    assert second.json()["data"]["document_id"] == first.json()["data"]["document_id"]
    assert second.json()["data"]["duplicate"] is False
    assert second.json()["data"]["chunk_count"] == first.json()["data"]["chunk_count"]


def test_delete_document_removes_chunks_and_is_idempotent() -> None:
    created = client.post(
        "/api/v1/documents/process",
        files={"file": ("contract.txt", b"Article 1 Deletion clause\nLithium delete path", "text/plain")},
        data={
            "document_id": "55555555-5555-5555-5555-555555555555",
            "contract_id": "1",
            "supplier_id": "2",
            "material_id": "3",
            "document_type": "LTA",
        },
    )
    assert created.status_code == 200
    document_id = created.json()["data"]["document_id"]
    chunk_count = created.json()["data"]["chunk_count"]
    assert chunk_count >= 1

    deleted = client.delete(f"/api/v1/documents/{document_id}")
    assert deleted.status_code == 200
    assert deleted.json()["data"]["document_id"] == document_id
    assert deleted.json()["data"]["deleted_chunks"] == chunk_count

    # 멱등 — 이미 지운 문서를 다시 지우면 0건이고 오류가 아니다.
    again = client.delete(f"/api/v1/documents/{document_id}")
    assert again.status_code == 200
    assert again.json()["data"]["deleted_chunks"] == 0


def test_valid_pdf_keeps_real_one_based_page_numbers() -> None:
    response = client.post(
        "/api/v1/documents/process",
        files={"file": (
            "contract.pdf",
            make_text_pdf(["Article 1 Purpose", "Article 2 Price adjustment"]),
            "application/pdf",
        )},
        data={
            "document_id": "33333333-3333-3333-3333-333333333333",
            "contract_id": "1",
            "supplier_id": "2",
            "material_id": "3",
            "document_type": "LTA",
        },
    )

    assert response.status_code == 200
    chunks = response.json()["data"]["chunks"]
    assert [item["page_number"] for item in chunks] == [1, 2]
    assert [item["chunk_index"] for item in chunks] == [0, 1]


def test_chunking_failure_uses_explicit_error_code(monkeypatch) -> None:
    monkeypatch.setattr(
        document_service_module,
        "get_settings",
        lambda: Settings(chunk_size=10, chunk_overlap=10),
    )

    response = client.post(
        "/api/v1/documents/process",
        files={"file": ("contract.txt", b"unique chunking failure text", "text/plain")},
        data={
            "document_id": "44444444-4444-4444-4444-444444444444",
            "contract_id": "1",
            "supplier_id": "2",
            "material_id": "3",
            "document_type": "LTA",
        },
    )

    assert response.status_code == 500
    assert response.json()["error"]["code"] == "CHUNKING_FAILED"
