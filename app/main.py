from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routes import router




app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",
        "http://localhost:5173"
    ],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(router, prefix="/api/v1")


@app.get("/")
def root():
    return {"message": "FastAPI 서버 실행 성공"}

@app.get("/health")
def health():
    return {
        "status": "ok"
    }
