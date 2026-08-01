"""Generate a minimal, dependency-free PDF whose text pypdf can extract.

WinAnsi/Helvetica only -> ASCII content. The pipeline's PDF branch is
pypdf.PdfReader(...).pages[i].extract_text(), so this is the exact target.
"""
from pathlib import Path

PAGES = [
    [
        "MOCK CONTRACT - FOR UPLOAD PIPELINE TESTING ONLY",
        "",
        "Agreement No.: BA-2025-0002",
        "Contract ID: CTR-002",
        "Supplier: Pilbara Battery Minerals (SUP-AUS-01)",
        "Material: Lithium Carbonate (MAT-LI-CARB)",
        "Term: 2025-07-27 through 2027-08-01",
        "",
        "LITHIUM CARBONATE SUPPLY AGREEMENT (ALTERNATIVE SOURCE)",
        "",
        "This agreement is entered into between LG Energy Solution, Ltd.",
        "(\"Buyer\") and Pilbara Battery Minerals (\"Seller\").",
        "",
        "Article 1",
        "DEFINITIONS",
        "",
        "1.01 \"Material\" means Seller's lithium carbonate meeting the",
        "     specifications set out in Schedule A.",
        "1.02 \"Business Day\" means a day other than Saturday, Sunday or a",
        "     day on which banks are closed in Korea or Australia.",
        "1.03 \"Base Volume Commitment\" has the meaning in Section 2.01.",
        "1.04 \"Force Majeure Event\" has the meaning in Section 5.01.",
        "",
        "Article 2",
        "SUPPLY COMMITMENT",
        "",
        "2.01 Seller shall supply a Base Volume Commitment of 7,200 MT per",
        "     contract year, representing a 30 percent supply share.",
        "2.02 Buyer shall issue purchase orders no later than 45 days prior",
        "     to the requested delivery date.",
        "2.03 This agreement is designated ALTERNATIVE in Buyer's sourcing",
        "     plan and may be scaled up to 60 percent share on 60 days",
        "     written notice if the primary source is disrupted.",
    ],
    [
        "Article 3",
        "PRICE AND PAYMENT",
        "",
        "3.01 The base unit price is USD 31.06 per kilogram, CIF Busan,",
        "     INCOTERMS 2020.",
        "3.02 Prices are re-set quarterly against the Fastmarkets lithium",
        "     carbonate CIF Asia index.",
        "3.03 Payment terms are net 30 days from receipt of shipping",
        "     documents.",
        "",
        "Article 4",
        "QUALITY AND CERTIFICATION",
        "",
        "4.01 Material shall meet a minimum purity of 99.5 percent.",
        "4.02 Seller shall maintain ISO 9001 and ISO 14001 certification",
        "     throughout the Term.",
        "4.03 Buyer may reject any lot failing incoming inspection within",
        "     14 days of receipt; Seller shall replace it within 30 days at",
        "     no charge.",
        "",
        "Article 5",
        "FORCE MAJEURE",
        "",
        "5.01 \"Force Majeure Event\" means any event beyond the reasonable",
        "     control of a party, including natural disaster, war, strike,",
        "     government export restriction, port closure or grid failure.",
        "5.02 The affected party shall give written notice within 5",
        "     Business Days.",
        "5.03 If a Force Majeure Event continues for more than 90",
        "     consecutive days, either party may terminate this agreement.",
        "",
        "Article 6",
        "GOVERNING LAW",
        "",
        "6.01 This agreement is governed by the laws of the Republic of",
        "     Korea.",
        "6.02 Disputes shall be finally settled by arbitration in Seoul",
        "     under the KCAB International Arbitration Rules.",
    ],
]


def esc(s: str) -> str:
    return s.replace("\\", r"\\").replace("(", r"\(").replace(")", r"\)")


def content_stream(lines):
    out = ["BT", "/F1 10 Tf", "12 TL", "56 760 Td"]
    for line in lines:
        out.append(f"({esc(line)}) Tj")
        out.append("T*")
    out.append("ET")
    return "\n".join(out).encode("latin-1")


def build(path: Path):
    objects: list[bytes] = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)  # 1-based object number

    n = len(PAGES)
    # Object numbering: 1=Catalog 2=Pages 3=Font, then per page: page obj, stream obj
    page_ids = [4 + 2 * i for i in range(n)]
    stream_ids = [5 + 2 * i for i in range(n)]

    add(b"<< /Type /Catalog /Pages 2 0 R >>")
    kids = " ".join(f"{pid} 0 R" for pid in page_ids)
    add(f"<< /Type /Pages /Count {n} /Kids [{kids}] >>".encode())
    add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")

    for i, lines in enumerate(PAGES):
        add(
            f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            f"/Resources << /Font << /F1 3 0 R >> >> "
            f"/Contents {stream_ids[i]} 0 R >>".encode()
        )
        data = content_stream(lines)
        add(b"<< /Length " + str(len(data)).encode() + b" >>\nstream\n" + data + b"\nendstream")

    buf = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for num, body in enumerate(objects, start=1):
        offsets.append(len(buf))
        buf += f"{num} 0 obj\n".encode() + body + b"\nendobj\n"

    xref_at = len(buf)
    count = len(objects) + 1
    buf += f"xref\n0 {count}\n".encode()
    buf += b"0000000000 65535 f \n"
    for off in offsets[1:]:
        buf += f"{off:010d} 00000 n \n".encode()
    buf += f"trailer\n<< /Size {count} /Root 1 0 R >>\nstartxref\n{xref_at}\n%%EOF\n".encode()

    path.write_bytes(bytes(buf))


target = Path(__file__).parent / "CTR-002_mock_supply_agreement.pdf"
build(target)

from pypdf import PdfReader

reader = PdfReader(str(target))
print("pages:", len(reader.pages), "encrypted:", reader.is_encrypted, "bytes:", target.stat().st_size)
for i, page in enumerate(reader.pages):
    text = page.extract_text()
    print(f"--- page {i + 1}: {len(text)} chars ---")
    print(text[:200])
