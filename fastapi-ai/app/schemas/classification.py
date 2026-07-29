from typing import Annotated, Optional

from pydantic import Field

from app.schemas.common import ApiModel, ImpactDomain


class ClassificationResult(ApiModel):
    impact_domain: ImpactDomain
    # 분류 신뢰도. XGBoost 경로(/internal/ml/classify)는 실제 예측 확률을 넣는다.
    # impact_domain을 LLM 추출 결과에서 직결로 받는 경로에는 신뢰도 지표 자체가 없으므로 None을 넣는다 —
    # 근거 없는 상수(1.0)를 채우면 Spring confidenceLabel()이 0.7 임계값을 무조건 넘겨
    # 공개 화면 배지가 전부 "확정"으로 과대 표기된다. 기본값을 두지 않아 호출자가 매번 명시하게 한다.
    confidence: Optional[Annotated[float, Field(ge=0.0, le=1.0)]]
    model_version: str
    mock: bool = True
