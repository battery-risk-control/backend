const API_BASE_URL = (import.meta.env.VITE_SPRING_API_BASE_URL ?? "").replace(/\/$/, "");

export const ACCESS_TOKEN_KEY = "access_token";
export const LAST_DOCUMENT_ID_KEY = "last_document_id";
export const MAX_FILE_SIZE = 50 * 1024 * 1024;

const ALLOWED_EXTENSIONS = ["pdf", "txt", "csv"];
const ALLOWED_MIME = {
  pdf: ["application/pdf"],
  txt: ["text/plain"],
  csv: ["text/csv", "text/plain", "application/csv"],
};

export function validateDocumentFile(file) {
  if (!file || file.size === 0) return "내용이 있는 파일을 선택해 주세요.";
  const extension = file.name.split(".").pop()?.toLowerCase();
  if (!ALLOWED_EXTENSIONS.includes(extension)) return "PDF, TXT, CSV 파일만 업로드할 수 있습니다.";
  if (file.size > MAX_FILE_SIZE) return "파일은 최대 50MB까지 업로드할 수 있습니다.";
  const allowed = ALLOWED_MIME[extension] ?? [];
  if (file.type && !allowed.includes(file.type)) return "파일 확장자와 MIME 형식이 일치하지 않습니다.";
  return null;
}

function authHeaders(token) {
  if (!token) throw new ApiError("AUTHENTICATION_REQUIRED", "로그인 토큰이 필요합니다.", 401);
  return { Authorization: `Bearer ${token}` };
}

export class ApiError extends Error {
  constructor(code, message, status = 0) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.status = status;
  }
}

function parseResponse(xhr) {
  let body;
  try {
    body = JSON.parse(xhr.responseText || "{}");
  } catch {
    throw new ApiError("INVALID_SERVER_RESPONSE", "서버 응답을 읽을 수 없습니다.", xhr.status);
  }
  if (xhr.status < 200 || xhr.status >= 300 || body.success === false) {
    const error = body.error ?? {};
    throw new ApiError(error.code ?? "REQUEST_FAILED", error.message ?? "요청 처리에 실패했습니다.", xhr.status);
  }
  return body.data;
}

export function uploadDocument({ token, file, contractId, supplierId, materialId, documentType, onProgress }) {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    form.append("file", file);
    form.append("contract_id", contractId);
    form.append("supplier_id", supplierId);
    form.append("material_id", materialId);
    form.append("document_type", documentType);

    const xhr = new XMLHttpRequest();
    xhr.open("POST", `${API_BASE_URL}/api/v1/documents`);
    try {
      const headers = authHeaders(token);
      Object.entries(headers).forEach(([name, value]) => xhr.setRequestHeader(name, value));
    } catch (error) {
      reject(error);
      return;
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress?.(Math.round((event.loaded / event.total) * 100));
    };
    xhr.onload = () => {
      try { resolve(parseResponse(xhr)); } catch (error) { reject(error); }
    };
    xhr.onerror = () => reject(new ApiError("SPRING_API_UNAVAILABLE", "Spring Boot 서버에 연결할 수 없습니다."));
    xhr.send(form);
  });
}

export async function getDocumentStatus(token, documentId) {
  let response;
  try {
    response = await fetch(`${API_BASE_URL}/api/v1/documents/${encodeURIComponent(documentId)}`, {
      headers: authHeaders(token),
    });
  } catch (error) {
    if (error instanceof ApiError) throw error;
    throw new ApiError("SPRING_API_UNAVAILABLE", "Spring Boot 서버에 연결할 수 없습니다.");
  }
  const text = await response.text();
  const xhrLike = { status: response.status, responseText: text };
  return parseResponse(xhrLike);
}
