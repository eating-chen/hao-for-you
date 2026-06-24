"""Google OAuth 2.0 endpoints"""
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import HTMLResponse
from sqlalchemy.ext.asyncio import AsyncSession

from app.db.database import get_db
from app.db.crud.google_token_crud import upsert_google_token
from app.service.google_auth_service import (
    build_auth_url,
    exchange_code_for_tokens,
    get_user_email,
    push_line_message,
)
from app.service.logger_service import logger, error_logger

router = APIRouter(prefix="/auth", tags=["google-auth"])


@router.get(
    "/google",
    summary="產生 Google OAuth 授權連結",
)
def start_google_auth(line_user_id: str):
    """
    傳入 line_user_id，回傳 Google 授權 URL。
    通常由 GoogleAuthSkill 呼叫，再把 URL 傳給使用者點擊。
    """
    auth_url = build_auth_url(line_user_id)
    return {"auth_url": auth_url}


@router.get(
    "/google/callback",
    response_class=HTMLResponse,
    summary="Google OAuth callback",
)
async def google_callback(
    code: str,
    state: str,
    db: AsyncSession = Depends(get_db),
):
    """
    Google 授權完成後的 redirect endpoint。
    - code: Google 提供的 authorization code
    - state: 授權前帶入的 line_user_id
    流程：code → tokens → 取 email → 存 DB → LINE push 通知
    """
    line_user_id = state

    try:
        # 1. 用 code 換 tokens（傳入 line_user_id 以取回對應的 flow，確保 code_verifier 一致）
        token_data = exchange_code_for_tokens(code, line_user_id)

        # 2. 取得 Google 帳號 email
        email = get_user_email(token_data["access_token"])

        # 3. 存入 DB（upsert：第一次授權建立，重新授權覆蓋舊的）
        await upsert_google_token(
            db,
            line_user_id=line_user_id,
            access_token=token_data["access_token"],
            refresh_token=token_data["refresh_token"],
            token_expiry=token_data["expiry"],
            google_email=email,
        )
        logger.info("Google token saved for user: %s (%s)", line_user_id, email)

        # 4. 主動推播 LINE 通知授權成功
        push_line_message(
            line_user_id,
            f"✅ 已成功連結 Google 帳號：{email}\n現在你可以查詢或新增行事曆了！",
        )

        # 5. 回傳給瀏覽器的成功頁面
        return HTMLResponse(content="""
        <html>
          <head><meta charset="utf-8"></head>
          <body style="font-family:sans-serif;text-align:center;padding:60px;">
            <h2>✅ Google 帳號授權成功！</h2>
            <p>請回到 LINE 繼續使用 Hao。</p>
            <p style="color:gray;font-size:14px;">此頁面可以關閉。</p>
          </body>
        </html>
        """)

    except Exception as e:
        error_logger.exception("Google OAuth callback error: %s", e)
        raise HTTPException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            detail="授權過程發生錯誤，請重試。",
        )
