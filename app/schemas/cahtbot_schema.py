"""interface for schema"""
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field


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
