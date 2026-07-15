"""interface for schema"""
from typing import Any, List, Optional
from pydantic import BaseModel, ConfigDict, Field


class ChatRequest(BaseModel):
    user_id: str = Field(..., description="Java users.id")
    platform: str = Field(default="line", description="line / web / discord / telegram")
    message: str = Field(..., description="使用者訊息")
    session_id: Optional[str] = Field(
        default=None,
        description="同一段對話請沿用相同 session_id；未提供時沿用 platform-user_id",
    )


class ToolExecutionResponse(BaseModel):
    name: str
    arguments: dict[str, Any]
    status: str
    result: str


class ChatResponse(BaseModel):
    success: bool
    user_id: str
    session_id: str
    platform: str
    response: str
    tool_executions: List[ToolExecutionResponse] = Field(default_factory=list)


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


class EchoInfoSchema(BaseModel):
    model_config = ConfigDict(
        from_attributes=True,
        json_schema_extra={
            "example": {
                "received": "hello world",
                "message": "Echo from hao-for-you server",
            }
        },
    )

    received: str = Field(..., description="收到的消息")
    message: str = Field(..., description="系統訊息")
