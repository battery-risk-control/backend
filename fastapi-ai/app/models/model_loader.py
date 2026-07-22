from pathlib import Path

class ModelRegistry:
    def __init__(self, model_path: str | None = None):
        self.model_path = Path(model_path) if model_path else None
        self.loaded = False

    def load(self) -> None:
        self.loaded = bool(self.model_path and self.model_path.exists())

model_registry = ModelRegistry()
