import { describe, expect, it } from "vitest";
import { MAX_FILE_SIZE, validateDocumentFile } from "./documentApi";

const file = (name, type, size = 12) => ({ name, type, size });

describe("validateDocumentFile", () => {
  it("accepts PDF, TXT, and CSV", () => {
    expect(validateDocumentFile(file("contract.pdf", "application/pdf"))).toBeNull();
    expect(validateDocumentFile(file("guide.txt", "text/plain"))).toBeNull();
    expect(validateDocumentFile(file("erp.csv", "text/csv"))).toBeNull();
    expect(validateDocumentFile(file("erp.csv", "text/plain"))).toBeNull();
  });

  it("rejects an invalid extension, MIME mismatch, empty file, and oversized file", () => {
    expect(validateDocumentFile(file("contract.exe", "application/octet-stream"))).toContain("PDF, TXT, CSV");
    expect(validateDocumentFile(file("contract.pdf", "text/plain"))).toContain("MIME");
    expect(validateDocumentFile(file("empty.txt", "text/plain", 0))).toContain("내용이 있는");
    expect(validateDocumentFile(file("large.pdf", "application/pdf", MAX_FILE_SIZE + 1))).toContain("50MB");
  });
});
