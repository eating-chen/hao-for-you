"""access to FInishInfo data"""
from fastapi import APIRouter, Depends, Header, HTTPException, Request, status
from app.schemas.cahtbot_schema import ChatRequest, ChatResponse, EchoInfoSchema
from app.manager.modelManager import OllamaModelManager
from app.core.config import config
from app.service import internal_auth

router = APIRouter()


async def require_internal_signature(
    request: Request,
    x_timestamp: str | None = Header(
        default=None,
        description="Unix epoch 秒，跨服務 HMAC 驗證用，需與 X-Signature 搭配",
    ),
    x_signature: str | None = Header(
        default=None,
        description='hex(HMAC-SHA256(INTERNAL_API_SECRET, X-Timestamp+"\\n"+METHOD+"\\n"+path+"\\n"+body))',
    ),
) -> None:
    """驗證跨服務 HMAC 簽章；只掛在受保護的內部 endpoint（/v1/chat）。

    驗失敗回 401 + error code AUTHORIZATION_FAIL。讀取 raw body 用 ``await
    request.body()``（Starlette 會快取，下游 ChatRequest 解析仍讀得到同一份 body）。
    """
    # fail-closed：非法 UTF-8 body 無法解碼時，一律當作驗簽失敗（不讓它變成 500）
    try:
        body = (await request.body()).decode("utf-8")
    except UnicodeDecodeError:
        valid = False
    else:
        # 空 secret 會讓 verify 拋 RuntimeError（伺服器設定錯誤）；此處刻意不攔，
        # 讓它往外傳成 500，而非被誤轉成 401 AUTHORIZATION_FAIL。
        valid = internal_auth.verify(
            secret=config.SecurityConfig.internal_api_secret,
            timestamp=x_timestamp,
            signature=x_signature,
            method=request.method,
            # path 不含 query string，與 gateway 端簽的 path 對齊
            path=request.url.path,
            body=body,
            window_seconds=config.SecurityConfig.internal_api_timestamp_window,
        )
    if not valid:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail={"code": "AUTHORIZATION_FAIL", "message": "內部服務驗證失敗"},
        )


@router.get(
    "/",
    status_code=status.HTTP_200_OK,
    tags=["Health Check"],
    summary="FastAPI server 健康狀況"
)
def health_check():

    return {
        'message': 'hao-for-you Python server is running',
        'status': 'ok'
    }


@router.post(
    "/echo",
    status_code=status.HTTP_200_OK,
    response_model=EchoInfoSchema,
    tags=["test fastapi"],
    summary="echo API，回傳收到的資料和訊息"
)
def echo(prompt: str):

    return {
        'received': prompt,
        'message': 'Echo from hao-for-you server'
    }



@router.post(
    "/v1/chat",
    status_code=status.HTTP_200_OK,
    tags=["chatbot"],
    response_model=ChatResponse,
    summary="回覆使用者的問題",
    dependencies=[Depends(require_internal_signature)],
)
async def chat(request: Request, body: ChatRequest):
    reply_obj = await OllamaModelManager.generate_response(request, body)

    return reply_obj
