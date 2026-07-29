from app.multi_agent.rag.clause_classifier import (
    UNKNOWN_CLAUSE_TYPE,
    classify_clause,
)


def test_classifies_korean_delivery_delay():
    text = (
        "을이 납기일 이내에 납품을 완료하지 못한 경우 "
        "지연 일수 1일당 지체상금을 지급하여야 한다."
    )

    clause_type, clause_name_kr = classify_clause(text)

    assert clause_type == "delivery_delay"
    assert clause_name_kr == "납기·지체상금 조항"


def test_classifies_korean_force_majeure():
    text = (
        "천재지변 및 전염병 확산으로 인한 항만 폐쇄 등 "
        "불가항력 사유가 발생한 경우 배상 책임을 지지 아니한다."
    )

    assert classify_clause(text)[0] == "force_majeure"


def test_classifies_korean_termination():
    text = (
        "상대방이 본 계약의 핵심 조항을 위반하고 시정하지 "
        "아니한 경우 서면 통보로써 본 계약을 해지할 수 있다."
    )

    assert classify_clause(text)[0] == "termination"


def test_classifies_korean_confidentiality():
    text = (
        "양 당사자는 취득한 상대방의 비밀정보를 사전 서면 "
        "동의 없이 제3자에게 유출하여서는 아니 된다. "
        "비밀유지 의무는 계약 종료 후에도 존속한다."
    )

    assert classify_clause(text)[0] == "confidentiality"


def test_classifies_english_force_majeure():
    text = (
        "Neither Party shall be liable for a failure to perform "
        "caused by a Force Majeure Event beyond its control."
    )

    assert classify_clause(text)[0] == "force_majeure"


def test_classifies_english_delivery_delay():
    text = (
        "Seller shall notify Buyer in writing within five Business "
        "Days and propose a make-good delivery schedule for the "
        "shortfall."
    )

    assert classify_clause(text)[0] == "delivery_delay"


def test_delivery_delay_wins_over_payment_keyword():
    # '지급'(payment)과 '지체상금'(delivery)이 함께 있어도
    # 우선순위가 높은 delivery_delay로 판정되어야 한다.
    text = "납품 지연 시 납품 대금의 0.3%를 지체상금으로 지급한다."

    assert classify_clause(text)[0] == "delivery_delay"


def test_unknown_when_no_keyword_matches():
    text = (
        "This section sets out the background and general recitals "
        "of the agreement between the two entities."
    )

    assert classify_clause(text)[0] == UNKNOWN_CLAUSE_TYPE


def test_empty_or_none_text_is_unknown():
    assert classify_clause("")[0] == UNKNOWN_CLAUSE_TYPE
    assert classify_clause(None)[0] == UNKNOWN_CLAUSE_TYPE

def test_force_majeure_wins_when_delay_is_also_present():
    text = (
        "FORCE MAJEURE: Supplier must notify Buyer within "
        "48 hours when a strike causes a delivery delay."
    )

    assert classify_clause(text)[0] == "force_majeure"