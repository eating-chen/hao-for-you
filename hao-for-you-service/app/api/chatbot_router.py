"""access to FInishInfo data"""
from fastapi import APIRouter, Request, status
from app.schemas.cahtbot_schema import ChatRequest, ChatResponse, EchoInfoSchema
from app.manager.modelManager import OllamaModelManager

router = APIRouter()


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
    summary="回覆使用者的問題"
)
async def chat(request: Request, body: ChatRequest):
    reply_obj = await OllamaModelManager.generate_response(request, body)

    return reply_obj
