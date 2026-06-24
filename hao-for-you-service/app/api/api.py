from fastapi import APIRouter

from app.api import chatbot_router
from app.api import auth_router

router = APIRouter()
router.include_router(chatbot_router.router)
router.include_router(auth_router.router)
