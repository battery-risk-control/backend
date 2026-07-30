import { useCallback, useEffect, useRef, useState } from "react";
import {
  ACCESS_TOKEN_KEY,
  LAST_DOCUMENT_ID_KEY,
  getDocumentStatus,
  uploadDocument,
  validateDocumentFile,
} from "./documentApi";

const STATUS_COPY = {
  IDLE: ["업로드 준비", "PDF, TXT, 또는 CSV 파일을 선택해 주세요."],
  PENDING: ["업로드 접수", "파일을 Spring Boot로 안전하게 전송하고 있습니다."],
  PROCESSING: ["문서 처리 중", "텍스트 추출·청킹·Mock Embedding·ChromaDB 적재를 진행합니다."],
  COMPLETED: ["처리 완료", "문서가 검색 가능한 상태로 적재되었습니다."],
  FAILED: ["처리 실패", "오류 내용을 확인한 뒤 다시 시도해 주세요."],
  DUPLICATE: ["이미 등록된 문서", "동일한 문서가 있어 기존 처리 결과를 표시합니다."],
};

const terminalStatuses = new Set(["COMPLETED", "FAILED"]);

function StatusBadge({ status }) {
  return <span className={`status status--${status.toLowerCase()}`}>{STATUS_COPY[status]?.[0] ?? status}</span>;
}

export default function App() {
  const [token, setToken] = useState(() => localStorage.getItem(ACCESS_TOKEN_KEY) ?? "");
  const [form, setForm] = useState({ contractId: "1", supplierId: "1", materialId: "1", documentType: "CONTRACT" });
  const [file, setFile] = useState(null);
  const [status, setStatus] = useState("IDLE");
  const [progress, setProgress] = useState(0);
  const [document, setDocument] = useState(null);
  const [error, setError] = useState(null);
  const [restoring, setRestoring] = useState(true);
  const timerRef = useRef(null);

  const clearPolling = useCallback(() => {
    if (timerRef.current) window.clearTimeout(timerRef.current);
    timerRef.current = null;
  }, []);

  const applyDocument = useCallback((data, duplicate = false) => {
    setDocument(data);
    setError(null);
    if (data.document_id) localStorage.setItem(LAST_DOCUMENT_ID_KEY, data.document_id);
    setStatus(duplicate ? "DUPLICATE" : data.processing_status);
    return data.processing_status;
  }, []);

  const loadStatus = useCallback(async (documentId, shouldPoll = true) => {
    clearPolling();
    try {
      const data = await getDocumentStatus(localStorage.getItem(ACCESS_TOKEN_KEY), documentId);
      const nextStatus = applyDocument(data);
      if (shouldPoll && !terminalStatuses.has(nextStatus)) {
        timerRef.current = window.setTimeout(() => loadStatus(documentId, true), 1500);
      }
    } catch (requestError) {
      setError({ code: requestError.code, message: requestError.message });
      if (requestError.status === 401) localStorage.removeItem(ACCESS_TOKEN_KEY);
    } finally {
      setRestoring(false);
    }
  }, [applyDocument, clearPolling]);

  useEffect(() => {
    const savedId = localStorage.getItem(LAST_DOCUMENT_ID_KEY);
    const savedToken = localStorage.getItem(ACCESS_TOKEN_KEY);
    if (savedId && savedToken) loadStatus(savedId);
    else setRestoring(false);
    return clearPolling;
  }, [clearPolling, loadStatus]);

  const saveToken = () => {
    const trimmed = token.trim();
    if (trimmed) localStorage.setItem(ACCESS_TOKEN_KEY, trimmed);
    else localStorage.removeItem(ACCESS_TOKEN_KEY);
    setError(null);
    const savedId = localStorage.getItem(LAST_DOCUMENT_ID_KEY);
    if (trimmed && savedId) loadStatus(savedId);
  };

  const submit = async (event) => {
    event.preventDefault();
    clearPolling();
    setError(null);
    const fileError = validateDocumentFile(file);
    if (fileError) {
      setStatus("FAILED");
      setError({ code: "INVALID_FILE", message: fileError });
      return;
    }
    if (!token.trim()) {
      setStatus("FAILED");
      setError({ code: "AUTHENTICATION_REQUIRED", message: "로그인 후 발급된 access token을 저장해 주세요." });
      return;
    }
    localStorage.setItem(ACCESS_TOKEN_KEY, token.trim());
    setStatus("PENDING");
    setProgress(0);
    setDocument(null);
    try {
      const data = await uploadDocument({
        token: token.trim(), file,
        contractId: form.contractId, supplierId: form.supplierId,
        materialId: form.materialId, documentType: form.documentType,
        onProgress: (value) => { setProgress(value); if (value === 100) setStatus("PROCESSING"); },
      });
      const nextStatus = applyDocument(data, data.duplicate === true);
      if (!data.duplicate && !terminalStatuses.has(nextStatus)) loadStatus(data.document_id);
    } catch (requestError) {
      setStatus("FAILED");
      setError({ code: requestError.code ?? "REQUEST_FAILED", message: requestError.message });
    }
  };

  const updateForm = (event) => setForm((current) => ({ ...current, [event.target.name]: event.target.value }));
  const isBusy = status === "PENDING" || status === "PROCESSING";
  const currentCopy = STATUS_COPY[status] ?? [status, ""];

  return (
    <main className="shell">
      <header className="topbar">
        <div className="brand-mark">BR</div>
        <div><strong>Battery Risk Control</strong><span>구매 문서 적재 센터</span></div>
        <div className="spring-only"><i /> Spring API 연결</div>
      </header>

      <section className="hero">
        <p className="eyebrow">CONTRACT INTELLIGENCE · C1-B</p>
        <h1>계약 문서를<br /><em>검색 가능한 근거</em>로.</h1>
        <p>업로드된 원문은 Spring Boot를 거쳐 검증·보존되고, 처리 결과는 PostgreSQL 상태와 함께 안전하게 관리됩니다.</p>
      </section>

      <section className="workspace">
        <form className="panel form-panel" onSubmit={submit}>
          <div className="panel-title"><span>01</span><div><h2>문서 등록</h2><p>계약과 공급망 기준 정보를 입력하세요.</p></div></div>

          <details className="token-box" open={!token}>
            <summary>인증 토큰 설정 <small>{token ? "저장됨" : "필요"}</small></summary>
            <div className="token-row">
              <input type="password" value={token} onChange={(e) => setToken(e.target.value)} placeholder="로그인 응답의 access_token" />
              <button type="button" className="secondary" onClick={saveToken}>저장</button>
            </div>
            <p>로그인 화면 연동 후에는 같은 <code>access_token</code> 값을 자동으로 사용합니다.</p>
          </details>

          <label className={`dropzone ${file ? "dropzone--selected" : ""}`}>
            <input type="file" accept=".pdf,.txt,.csv,application/pdf,text/plain,text/csv" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setError(null); }} />
            <span className="file-icon">{file ? "✓" : "+"}</span>
            <strong>{file?.name ?? "PDF, TXT, 또는 CSV 파일 선택"}</strong>
            <small>{file ? `${(file.size / 1024 / 1024).toFixed(2)} MB` : "최대 50MB · PDF / TXT / CSV 지원"}</small>
          </label>

          <div className="field-grid">
            <label>계약 ID<input name="contractId" type="number" min="1" required value={form.contractId} onChange={updateForm} /></label>
            <label>공급사 ID<input name="supplierId" type="number" min="1" required value={form.supplierId} onChange={updateForm} /></label>
            <label>자재 ID<input name="materialId" type="number" min="1" required value={form.materialId} onChange={updateForm} /></label>
            <label>문서 종류<select name="documentType" value={form.documentType} onChange={updateForm}>
              <option value="CONTRACT">계약서</option>
              <option value="PURCHASE_ORDER">발주서</option>
              <option value="SPECIFICATION">사양서</option>
              <option value="CERTIFICATE">인증서</option>
              <option value="OTHER">기타</option>
            </select></label>
          </div>

          <button className="primary" disabled={isBusy}>{isBusy ? "문서를 처리하고 있습니다" : "문서 업로드 시작"}<span>→</span></button>
        </form>

        <aside className="panel status-panel">
          <div className="panel-title"><span>02</span><div><h2>처리 현황</h2><p>마지막 문서 상태는 새로고침 후에도 유지됩니다.</p></div></div>
          {restoring ? <div className="empty-state">저장된 문서 상태를 불러오는 중…</div> : (
            <>
              <div className="status-head"><StatusBadge status={status} /><small>{document?.document_id ?? "문서 ID 대기 중"}</small></div>
              <h3>{currentCopy[0]}</h3><p className="status-copy">{currentCopy[1]}</p>
              {isBusy && <div className="progress"><div style={{ width: `${Math.max(progress, status === "PROCESSING" ? 100 : 4)}%` }} /></div>}
              {error && <div className="error-box"><strong>{error.code}</strong><span>{error.message}</span></div>}
              {document && <dl className="metadata">
                <div><dt>파일명</dt><dd>{document.original_file_name ?? document.file_name}</dd></div>
                <div><dt>처리 청크</dt><dd>{document.chunk_count ?? 0}개</dd></div>
                <div><dt>Embedding</dt><dd>{document.embedding_type ?? "-"}</dd></div>
                <div><dt>처리 시각</dt><dd>{document.processed_at ? new Date(document.processed_at).toLocaleString("ko-KR") : "-"}</dd></div>
              </dl>}
              {document?.document_id && <button className="secondary full" type="button" onClick={() => loadStatus(document.document_id)}>지금 상태 새로고침</button>}
            </>
          )}
          <div className="flow"><span>SPRING</span><b>→</b><span>POSTGRESQL</span><b>→</b><span>FASTAPI</span><b>→</b><span>CHROMA</span></div>
        </aside>
      </section>
    </main>
  );
}
