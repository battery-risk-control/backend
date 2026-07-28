"""계약 조항 유형 분류기 (규칙기반).

검색된 계약서 청크의 본문을 보고 조항 유형(clause_type)과 한국어
표시명(clause_name_kr)을 판정한다. LLM을 쓰지 않고 국·영문 키워드로
분류하며, 어느 규칙에도 걸리지 않으면 'unknown'을 반환한다.

RAG 검색 결과에 런타임으로 라벨을 붙이는 용도이며 ChromaDB에는
저장하지 않는다. 규칙은 위에서부터 순서대로 검사하고, 처음 매칭된
규칙의 유형을 반환한다(구매 대응에 중요한 유형을 앞에 둔다).
"""

from dataclasses import dataclass


UNKNOWN_CLAUSE_TYPE = "unknown"
UNKNOWN_CLAUSE_NAME_KR = "기타 조항"


@dataclass(frozen=True)
class ClauseRule:
    clause_type: str
    clause_name_kr: str
    keywords: tuple[str, ...]


# 우선순위 순서. 하나라도 키워드가 본문에 포함되면 해당 유형으로 판정.
CLAUSE_RULES: tuple[ClauseRule, ...] = (
    ClauseRule(
        "delivery_delay",
        "납기·지체상금 조항",
        (
            "지체상금",
            "지연",
            "납기",
            "발주서",
            "delivery",
            "delay",
            "shortfall",
            "make-good",
            "make good",
            "liquidated damages",
        ),
    ),
    ClauseRule(
        "force_majeure",
        "불가항력 조항",
        (
            "불가항력",
            "천재지변",
            "항만 폐쇄",
            "force majeure",
            "act of god",
        ),
    ),
    ClauseRule(
        "termination",
        "해지 조항",
        (
            "해지",
            "해제",
            "파산",
            "회생절차",
            "terminate",
            "termination",
            "insolvency",
            "breach",
        ),
    ),
    ClauseRule(
        "volume_commitment",
        "공급 물량 조항",
        (
            "공급 물량",
            "물량",
            "발주 수량",
            "volume commitment",
            "base volume",
            "metric ton",
            "production plan",
            "right of first refusal",
        ),
    ),
    ClauseRule(
        "quality_warranty",
        "하자보수·품질보증 조항",
        (
            "하자",
            "품질",
            "검수",
            "보증",
            "사양",
            "warranty",
            "warrant",
            "defect",
            "quality",
            "specification",
            "inspection",
        ),
    ),
    ClauseRule(
        "price_adjustment",
        "단가·가격조정 조항",
        (
            "단가",
            "가격",
            "index adjustment",
            "price",
            "pricing",
        ),
    ),
    ClauseRule(
        "payment",
        "대금·지급 조항",
        (
            "대금",
            "지급 기일",
            "결제",
            "송장",
            "payment",
            "invoice",
            "remittance",
        ),
    ),
    ClauseRule(
        "confidentiality",
        "비밀유지 조항",
        (
            "비밀유지",
            "비밀정보",
            "기밀",
            "confidential",
            "non-disclosure",
        ),
    ),
    ClauseRule(
        "intellectual_property",
        "지식재산권 조항",
        (
            "지식재산",
            "특허",
            "상표",
            "실용신안",
            "intellectual property",
            "patent",
            "trademark",
        ),
    ),
    ClauseRule(
        "assignment",
        "양도 조항",
        (
            "양도",
            "담보로 제공",
            "assignment",
            "assign this agreement",
        ),
    ),
    ClauseRule(
        "governing_law",
        "관할·준거법 조항",
        (
            "준거법",
            "관할",
            "분쟁",
            "governing law",
            "jurisdiction",
            "arbitration",
            "venue",
        ),
    ),
)


def classify_clause(text: str | None) -> tuple[str, str]:
    """계약 조항 본문 → (clause_type, clause_name_kr).

    어느 규칙에도 걸리지 않으면 ('unknown', '기타 조항')을 반환한다.
    """

    if not text:
        return UNKNOWN_CLAUSE_TYPE, UNKNOWN_CLAUSE_NAME_KR

    normalized = text.casefold()

    for rule in CLAUSE_RULES:
        for keyword in rule.keywords:
            if keyword.casefold() in normalized:
                return rule.clause_type, rule.clause_name_kr

    return UNKNOWN_CLAUSE_TYPE, UNKNOWN_CLAUSE_NAME_KR
