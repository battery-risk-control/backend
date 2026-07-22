from fastapi.testclient import TestClient

from app.main import app


client = TestClient(app)


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
    chunk = first.json()["data"]["chunks"][0]
    assert chunk == {
        "document_id": "11111111-1111-1111-1111-111111111111",
        "chunk_index": 0,
        "page_number": 1,
        "content": "Price adjustment clause for lithium supply.",
        "contract_id": 1,
        "supplier_id": 2,
        "material_id": 3,
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

    chunk_schema = schema["components"]["schemas"]["DocumentChunkResult"]
    assert set(chunk_schema["required"]) == {
        "document_id", "chunk_index", "page_number", "content",
        "contract_id", "supplier_id", "material_id", "document_type",
        "content_hash",
    }
