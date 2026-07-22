# Model artifacts

실제 XGBoost 연동 시 다음 산출물을 이 디렉터리에 배치합니다.

- `xgboost_impact_domain.json`
- `feature_schema.json`
- `class_mapping.json`
- `model_metadata.json`

현재는 `MOCK_MODE=true`가 기본이므로 artifact 없이 실행됩니다. 실제 모드에서 artifact가 없으면 `503 MODEL_UNAVAILABLE` 오류를 반환하도록 adapter를 교체합니다.
