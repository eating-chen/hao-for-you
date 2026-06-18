"""interface for schema"""
from langgraph.graph import MessagesState
from typing import List, Optional
from pydantic import BaseModel, Field


class ChatRequest(BaseModel):
    user_id: str = Field(..., description="Java users.id")
    platform: str = Field(default="line", description="line / web / discord / telegram")
    message: str = Field(..., description="使用者訊息")


class ChatResponse(BaseModel):
    success: bool
    user_id: str
    session_id: str
    platform: str
    response: str


class MessageResponse(BaseModel):
    id: int
    role: str
    content: str
    created_at: str


class SessionResponse(BaseModel):
    user_id: str
    session_id: str
    platform: str
    status: str
    summary: str
    messages: List[MessageResponse]


class State(MessagesState):
    user_id: str = Field(..., description="users_id")
    session_id: str = Field(..., description="對話 Session ID")
    user_input: str = Field(..., description="這次使用者輸入")
    platform: str = Field(default="line", description="line / web / discord / telegram")
    summary: str = Field(default="", description="目前對話摘要")
    ai_response: Optional[str] = Field(default=None, description="AI 回覆")

class EchoInfoSchema(BaseModel):
    received: str = Field(..., description="收到的消息")
    message: str = Field(..., description="系統訊息")

    class Config:
        from_attributes = True
        json_schema_extra = {
            "example": {
                'received': "hello world",
                'message': 'Echo from hao-for-you server'
            },
        }
