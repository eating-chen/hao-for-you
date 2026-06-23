# Hao-For-You Gateway

Hao For You 與 LINE Bot 串接之 gateway，負責管理對外 API、記錄使用者帳號與對話資訊，並透過 Skill 系統整合 AI 模型與 Google 服務。

---

## 系統架構

```
使用者 LINE 訊息
      ↓
  LINE Platform
      ↓  POST /webhook
  Gateway（hao-for-you-gateway）
      ↓
  AI Server（hao-for-you-server）
      ↓
  LLM Model（Ollama or other LLM Model）
      ↓
  Gateway（hao-for-you-gateway）
      ↓
  回覆使用者 or 執行 Google API
```

---

## 功能列表

### 基本功能

| Skill 名稱 | 說明 |
|---|---|
| `general_chat` | 一般問答，呼叫 Ollama 模型回應 |
| `calendar` | 查詢/新增 Google Calendar 行程 |
| `mail`  | 查詢 Google Mail 文件 |
| `google_auth`  | 引導使用者完成 Google OAuth 2.0 授權 |

### API Endpoints

| Method | Path | 說明 |
|---|---|---|
| GET | `/health` | 健康檢查 |
| POST | `/webhook` | LINE Webhook callback |
| GET | `/google/auth` | 產生 Google OAuth 授權連結 |
| GET | `/google/auth/callback` | Google OAuth callback（換 token、存 DB、推播通知） |
| POST | `/google/mail/query` | 查詢 Gmail 郵件 |
| POST | `/google/calendar/query` | 查詢 Google Calendar 事件 |
| POST | `/google/calendar/create` | 新增 Google Calendar 事件 |
| POST | `/google/calendar/modify` | 修改 Google Calendar 事件 |

### 資料庫 Schema（Postgresql）

#### `users`
| Table | 說明 |
|---|---|
| `id` | 資料唯一識別碼 |
| `line_user_id` | 使用者Line ID |
| `display_name` | LINE 使用者名稱 |
| `created_at` | LINE 使用者創建日期 |

#### `google_tokens`
| Table | 說明 |
|---|---|
| `id` | 資料唯一識別碼 |
| `line_user_id` | 使用者Line ID |
| `access_token` | google取得授權Token |
| `refresh_token` | 更新後的google授權token |
| `token_expiry` | Token效期 |
| `google_email` | 申請授權之google email |
| `created_at` | Token創建日期 |
| `updated_at` | Token更新日期 |

---

## 環境變數

複製範本後填入實際值：

```bash
cp .env.example .env
```

| 變數名稱 | 必填 | 說明 |
|---|---|---|
| `LINE_CHANNEL_SECRET` | ✅ | LINE Bot Channel Secret |
| `LINE_CHANNEL_ACCESS_TOKEN` | ✅ | LINE Bot Channel Access Token |
| `GOOGLE_CLIENT_ID` | ✅ | Google Cloud OAuth 用戶端 ID |
| `GOOGLE_CLIENT_SECRET` | ✅ | Google Cloud OAuth 用戶端密鑰 |
| `GOOGLE_REDIRECT_URI` | ✅ | OAuth callback URL（開發用 `http://localhost:8008/google/auth/callback`） |
| `OLLAMA_MODEL` | | AI 模型名稱，預設 `qwen3.5:0.8b` |
| `OLLAMA_BASE_URL` | | Ollama 服務位址，dev 預設 `http://localhost:11434` |
| `JASYPT_ENCRYPTOR_PASSWORD` | ✅ | Java 加密用key |
| `PORT` | | 伺服器 port，預設 `8008` |

---

# TODO - 待更新

## 啟動所需設置

### 1. 安裝 uv（套件管理工具）

```bash
curl -LsSf https://astral.sh/uv/install.sh | sh
source $HOME/.local/bin/env   # 讓當前終端機生效
```

### 2. 安裝依賴套件

```bash
cd hao-for-you-service
uv sync
```

### 3. 設定環境變數

```bash
cp .env.example .env
# 編輯 .env，填入 LINE 與 Google 金鑰
```

### 4. 啟動本機 Ollama（AI 模型）

```bash
ollama run qwen3.5:0.8b
```

### 5. 啟動伺服器

```bash
# 開發模式
python run.py --dev

# 生產模式（Ollama 連 docker 容器）
python run.py --prod
```

啟動後：
- `http://localhost:8008/` → 健康檢查
- `http://localhost:8008/docs` → Swagger 互動式 API 文件

---

## 執行測試

```bash
pytest
```

---

## Docker 部署

```bash
docker compose -f deployment/docker-compose.yaml up
```

---

## 目錄結構（hao-for-you-service/）

```
app/
├── main.py               # FastAPI 入口，DB 初始化、LINE handler 綁定
├── api/
│   ├── api.py            # 路由總整合
│   ├── chatbot_router.py # LINE webhook、echo、test_ollama
│   └── auth_router.py    # Google OAuth 流程（/auth/google, /auth/google/callback）
├── core/
│   └── config.py         # 環境設定（Line / Database / Google / Model）
├── db/
│   ├── database.py       # SQLAlchemy async engine、session、init_db
│   ├── models.py         # ORM Table 定義（User, Conversation, GoogleToken）
│   └── crud/             # 資料庫操作封裝
│       ├── user_crud.py
│       ├── conversation_crud.py
│       └── google_token_crud.py
├── manager/
│   ├── modelManager.py   # Ollama LLM 呼叫封裝
│   └── intentManager.py  # 意圖路由（關鍵字優先 → LLM fallback）
├── schemas/
│   └── cahtbot_schema.py # Pydantic 資料格式定義
├── service/
│   ├── logger_service.py # 日誌紀錄（info.log / error.log）
│   ├── util_service.py   # prompt 組合、JSON 解析工具
│   └── google_auth_service.py  # Google OAuth token 管理、Calendar/Gmail service 建立
└── skills/
    ├── base_skill.py     # BaseSkill 抽象類別、SkillContext、SkillResult
    ├── registry.py       # SkillRegistry（統一管理所有 Skill）
    ├── general_chat.py   # 一般問答 Skill
    ├── calendar_skill.py # Google Calendar 查詢/新增 Skill
    └── google_auth_skill.py  # 引導 Google 授權 Skill
```