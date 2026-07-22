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
