# Hao-for-You 系統規格文件

> 版本：v0.1 | 最後更新：2026-05-26

---

## 目錄

1. [專案目標](#1-專案目標)
2. [系統架構總覽](#2-系統架構總覽)
3. [訊息處理流程](#3-訊息處理流程)
4. [Skill 系統設計](#4-skill-系統設計)
5. [Intent Router 設計](#5-intent-router-設計)
6. [對話記憶（Context）](#6-對話記憶context)
7. [外部服務整合](#7-外部服務整合)
8. [資料模型](#8-資料模型)
9. [目錄結構（目標）](#9-目錄結構目標)
10. [API 端點規格](#10-api-端點規格)
11. [環境變數](#11-環境變數)
12. [開發里程碑](#12-開發里程碑)

---

## 1. 專案目標

建立一個以 **LINE** 為介面的個人智慧助理 **Hao**，核心能力如下：

| 功能 | 描述 |
|------|------|
| 💬 一般對話 | 透過本地 Ollama LLM 進行自然語言問答 |
| 📅 行事曆管理 | 查詢、新增 Google Calendar 事件 |
| 📧 重要信件查詢 | 讀取並摘要 Gmail 重要未讀信件 |
| 🔄 Skill 擴充 | 透過插件式 Skill 系統方便新增功能 |

**設計原則：**
- LLM 僅用於理解意圖與生成回覆，不直接操作外部服務
- 每個功能封裝成獨立 Skill，易於測試與擴充
- 對話具備短期記憶（per-user context window）
- 資料不外傳，LLM 跑在本地 Ollama

---

## 2. 系統架構總覽

```
┌─────────────┐     Webhook      ┌──────────────────────────────────────────┐
│             │ ──────────────→  │              FastAPI Server               │
│  LINE App   │                  │                                            │
│             │ ←──────────────  │  ┌────────────┐    ┌───────────────────┐  │
└─────────────┘   Reply Message  │  │  Webhook   │    │  Auth Router      │  │
                                 │  │  Handler   │    │  /auth/google/    │  │
                                 │  └─────┬──────┘    └───────────────────┘  │
                                 │        │                                    │
                                 │  ┌─────▼──────────────────────────┐       │
                                 │  │        Intent Router            │       │
                                 │  │  1. Keyword Match (fast)        │       │
                                 │  │  2. LLM Classify (fallback)     │       │
                                 │  └──┬──────────┬──────────┬────────┘       │
                                 │     │          │          │                │
                                 │  ┌──▼──┐  ┌───▼──┐  ┌────▼──────┐        │
                                 │  │Cal  │  │Email │  │ General   │        │
                                 │  │Skill│  │Skill │  │ Chat Skill│        │
                                 │  └──┬──┘  └───┬──┘  └────┬──────┘        │
                                 │     │          │          │                │
                                 └─────┼──────────┼──────────┼────────────────┘
                                       │          │          │
                              ┌────────▼──┐  ┌────▼───┐  ┌──▼──────┐
                              │  Google   │  │ Gmail  │  │ Ollama  │
                              │ Calendar  │  │  API   │  │  LLM    │
                              │   API     │  │        │  │         │
                              └───────────┘  └────────┘  └─────────┘
                                                  ↑
                                         ┌────────┴────────┐
                                         │  SQLite / SQLAlchemy │
                                         │  - users            │
                                         │  - conversations    │
                                         │  - google_tokens    │
                                         └─────────────────────┘
```

---

## 3. 訊息處理流程

### 完整流程

```
使用者傳 LINE 訊息
        │
        ▼
[1] LINE Webhook 接收事件
        │
        ▼
[2] 取得 user_id，載入對話 Context（最近 10 輪）
        │
        ▼
[3] Intent Router 判斷意圖
    ├── 關鍵字比對（快速路徑）
    │   └── 命中 → 直接路由到對應 Skill
    └── 未命中 → 呼叫 LLM 判斷意圖（分類 prompt）
                └── 取得 intent + extracted_params
        │
        ▼
[4] 呼叫對應 Skill.execute(user_id, message, context, params)
        │
        ▼
[5] Skill 執行（可能呼叫外部 API）
        │
        ▼
[6] 更新對話 Context（append 本輪 user/assistant）
        │
        ▼
[7] 格式化回覆訊息 → LINE Reply
```

### 特殊流程：Google 帳號授權

```
使用者: "連結 Google 帳號"
        │
        ▼
系統: 產生 OAuth URL，含 state=line_user_id
        │
        ▼
使用者點擊 URL → Google 授權頁面
        │
        ▼
Google redirect → /auth/google/callback?code=xxx&state=line_user_id
        │
        ▼
儲存 access_token / refresh_token 到 DB（綁定 line_user_id）
        │
        ▼
系統主動推播: "Google 帳號連結成功！"（push message）
```

---

## 4. Skill 系統設計

### 4.1 BaseSkill 介面

```python
# app/skills/base_skill.py

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

@dataclass
class SkillContext:
    user_id: str
    message: str
    conversation_history: list[dict]  # [{"role": "user/assistant", "content": "..."}]
    extracted_params: dict             # LLM 或 keyword 解析出的參數

@dataclass
class SkillResult:
    success: bool
    reply_text: str
    error_message: Optional[str] = None

class BaseSkill(ABC):
    name: str                  # skill 唯一識別名
    description: str           # 給 LLM 的描述（用於 intent 分類）
    keywords: list[str]        # 快速比對關鍵字（中文）
    
    @abstractmethod
    async def execute(self, ctx: SkillContext) -> SkillResult:
        """執行 skill 的核心邏輯"""
        pass
    
    def keyword_match(self, message: str) -> bool:
        """預設關鍵字比對邏輯，可被覆寫"""
        return any(kw in message for kw in self.keywords)
```

### 4.2 Skill Registry

```python
# app/skills/registry.py

class SkillRegistry:
    _skills: dict[str, BaseSkill] = {}

    @classmethod
    def register(cls, skill: BaseSkill):
        cls._skills[skill.name] = skill

    @classmethod
    def get_all(cls) -> list[BaseSkill]:
        return list(cls._skills.values())

    @classmethod
    def get_by_name(cls, name: str) -> BaseSkill | None:
        return cls._skills.get(name)
```

### 4.3 各 Skill 設計

#### GeneralChatSkill

```
name: "general_chat"
keywords: []  (fallback，不做關鍵字比對)
行為:
  - 把對話 context + 使用者訊息組成 chat prompt
  - 呼叫 Ollama generate_response
  - 回傳 LLM 的文字回覆
```

#### CalendarSkill

```
name: "calendar"
keywords: ["行事曆", "行程", "會議", "預約", "排程", "今天", "明天", 
           "後天", "下週", "這週", "何時", "什麼時候", "記得", "提醒"]
子意圖:
  - query_events: 查詢事件（"今天有什麼行程？"）
  - create_event: 新增事件（"明天下午三點排會議"）
  - list_upcoming: 列出近期事件（"這週的行程"）
行為:
  1. 使用 LLM 從訊息中萃取: {sub_intent, date, time, title, duration}
  2. 呼叫 Google Calendar API
  3. 格式化結果回覆
```

**範例對話：**

```
使用者: 明天下午三點我有個跟客戶的會議，幫我記一下
LLM 萃取: {sub_intent: "create_event", date: "2026-05-27", time: "15:00", 
           title: "客戶會議", duration: 60}
API 呼叫: Calendar.events.insert(...)
回覆: "✅ 已新增「客戶會議」\n📅 2026/05/27（三）15:00 - 16:00"
```

#### EmailSkill

```
name: "email"
keywords: ["信件", "email", "郵件", "信", "未讀", "重要", "寄給我", "收到"]
子意圖:
  - list_important: 列出重要/未讀信件
  - summarize: 摘要特定信件
  - search: 搜尋特定寄件者或關鍵字
行為:
  1. 使用 LLM 判斷子意圖及搜尋條件
  2. 呼叫 Gmail API（is:important 或 is:unread）
  3. 用 LLM 摘要信件內容
  4. 格式化列表回覆
```

**範例對話：**

```
使用者: 最近有沒有重要信件？
Gmail API: 取得最近 5 封 is:important 信件
LLM 摘要: 每封產生 1-2 句摘要
回覆: "📬 你有 3 封重要信件：\n\n1. [PM Team] Sprint Review - 邀請你參加明天的會議\n
       2. [AWS] Billing Alert - 費用超過預算警戒\n3. [Boss] Q2 Report - 請在週五前提交..."
```

---

## 5. Intent Router 設計

### 5.1 混合模式流程

```python
# app/manager/intentManager.py

async def route(user_id: str, message: str, context: list) -> tuple[str, dict]:
    """
    回傳 (skill_name, extracted_params)
    """
    
    # Step 1: 快速關鍵字比對
    for skill in SkillRegistry.get_all():
        if skill.keyword_match(message):
            # 命中後，用輕量 LLM prompt 萃取參數
            params = await extract_params(skill.name, message)
            return skill.name, params
    
    # Step 2: 沒有關鍵字命中，呼叫 LLM 做意圖分類
    intent_result = await llm_classify_intent(message, context)
    return intent_result["intent"], intent_result.get("params", {})
```

### 5.2 LLM 意圖分類 Prompt

```
你是一個意圖分類器。根據使用者訊息，判斷屬於以下哪種意圖：

可用意圖：
- calendar: 查詢或操作行事曆、行程、會議
- email: 查詢或操作信件
- general_chat: 一般對話、問答、其他

使用者訊息：{message}
對話歷史（最近3輪）：{recent_context}

請以 JSON 回覆：{"intent": "...", "params": {...}}
不要輸出其他任何內容。
```

### 5.3 參數萃取 Prompt（以 Calendar 為例）

```
從以下訊息中萃取行事曆操作的參數：

訊息：{message}
今天日期：{today}

請以 JSON 回覆，欄位說明：
- sub_intent: "query_events" | "create_event" | "list_upcoming"
- date: "YYYY-MM-DD"（若有提到）
- time: "HH:MM"（若有提到，24hr格式）
- title: 事件標題（若有提到）
- duration_minutes: 持續分鐘（預設60）

範例：{"sub_intent": "create_event", "date": "2026-05-27", "time": "15:00", 
       "title": "客戶會議", "duration_minutes": 60}
```

---

## 6. 對話記憶（Context）

### 設計原則

- 以 `line_user_id` 為 key
- 每個 user 保留最近 **10 輪**對話（可設定）
- 每輪 = 1 個 user message + 1 個 assistant message
- 存在 SQLite，每次 webhook 時讀入、更新後寫回

### Context 結構

```python
ConversationTurn = {
    "role": "user" | "assistant",
    "content": str,
    "timestamp": datetime,
    "skill_used": str | None   # 記錄是哪個 skill 回答的
}
```

### 注入 LLM Prompt 方式

```python
def build_chat_prompt(system_prompt: str, history: list, user_message: str) -> str:
    turns = []
    for turn in history[-10:]:  # 最近 10 輪
        turns.append(f"{'使用者' if turn['role']=='user' else 'Hao'}: {turn['content']}")
    
    context_block = "\n".join(turns)
    return f"{system_prompt}\n\n對話記錄：\n{context_block}\n\n使用者: {user_message}\nHao:"
```

---

## 7. 外部服務整合

### 7.1 Google OAuth 流程

**所需 Google Cloud 設定：**
1. 建立 OAuth 2.0 Client ID（Web Application）
2. 授權回調 URL：`https://your-domain.com/auth/google/callback`
3. 需要的 scope：
   - `https://www.googleapis.com/auth/calendar`
   - `https://www.googleapis.com/auth/gmail.readonly`

**Token 管理：**
- access_token：短效，需自動刷新
- refresh_token：長效，存在 DB，加密儲存
- 每次呼叫 API 前檢查 token 是否過期，自動 refresh

### 7.2 Google Calendar API 操作

```python
# 查詢事件（當天）
service.events().list(
    calendarId='primary',
    timeMin=day_start_iso,
    timeMax=day_end_iso,
    singleEvents=True,
    orderBy='startTime'
)

# 新增事件
service.events().insert(
    calendarId='primary',
    body={
        'summary': title,
        'start': {'dateTime': start_iso, 'timeZone': 'Asia/Taipei'},
        'end': {'dateTime': end_iso, 'timeZone': 'Asia/Taipei'},
    }
)
```

### 7.3 Gmail API 操作

```python
# 取得重要未讀信件（最近 5 封）
service.users().messages().list(
    userId='me',
    q='is:important is:unread',
    maxResults=5
)

# 取得信件完整內容
service.users().messages().get(
    userId='me',
    id=message_id,
    format='full'  # or 'metadata'
)
```

### 7.4 LLM Provider 抽象層

為了未來可以切換模型，將 LLM 呼叫抽象化：

```python
# app/manager/modelManager.py

class BaseLLMProvider(ABC):
    @abstractmethod
    async def generate(self, prompt: str) -> str: ...
    
    @abstractmethod
    async def chat(self, messages: list[dict]) -> str: ...

class OllamaProvider(BaseLLMProvider):
    async def generate(self, prompt: str) -> str:
        # 現有的 Ollama 呼叫邏輯
        ...
    
    async def chat(self, messages: list[dict]) -> str:
        # 使用 Ollama /api/chat endpoint（支援對話格式）
        ...

# 未來可加：
# class OpenAIProvider(BaseLLMProvider): ...
# class ClaudeProvider(BaseLLMProvider): ...
```

**注意：** 目前使用 `/api/generate`（單輪），建議改用 `/api/chat`（多輪）以支援對話歷史。

---

## 8. 資料模型

### users 表

```sql
CREATE TABLE users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    line_user_id VARCHAR(50) UNIQUE NOT NULL,
    display_name VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

### conversations 表

```sql
CREATE TABLE conversations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    line_user_id VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,       -- 'user' | 'assistant'
    content TEXT NOT NULL,
    skill_used VARCHAR(50),          -- 'calendar' | 'email' | 'general_chat'
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id)
);

CREATE INDEX idx_conversations_user_time 
    ON conversations(line_user_id, created_at DESC);
```

### google_tokens 表

```sql
CREATE TABLE google_tokens (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    line_user_id VARCHAR(50) UNIQUE NOT NULL,
    access_token TEXT NOT NULL,
    refresh_token TEXT NOT NULL,
    token_expiry DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    
    FOREIGN KEY (line_user_id) REFERENCES users(line_user_id)
);
```

---

## 9. 目錄結構（目標）

```
hao-for-you/
├── app/
│   ├── api/
│   │   ├── api.py
│   │   ├── chatbot_router.py       # 現有，需改造
│   │   └── auth_router.py          # NEW: Google OAuth callback
│   ├── core/
│   │   ├── config.py               # 需擴充 Google / DB 設定
│   │   └── logger_settings.yml
│   ├── db/
│   │   ├── database.py             # NEW: SQLAlchemy engine & session
│   │   ├── models.py               # NEW: ORM models
│   │   └── crud/
│   │       ├── base.py             # 現有
│   │       ├── user_crud.py        # NEW
│   │       └── conversation_crud.py # NEW
│   ├── manager/
│   │   ├── modelManager.py         # 現有，需重構為抽象層
│   │   └── intentManager.py        # NEW: 混合意圖路由
│   ├── schemas/
│   │   ├── cahtbot_schema.py       # 現有
│   │   ├── intent_schema.py        # NEW
│   │   └── skill_schema.py         # NEW
│   ├── service/
│   │   ├── logger_service.py       # 現有
│   │   ├── util_service.py         # 現有
│   │   ├── context_service.py      # NEW: 對話記憶管理
│   │   └── google_auth_service.py  # NEW: Google OAuth & token
│   ├── skills/
│   │   ├── __init__.py             # NEW
│   │   ├── base_skill.py           # NEW: 抽象基底類別
│   │   ├── registry.py             # NEW: Skill 註冊中心
│   │   ├── general_chat.py         # NEW
│   │   ├── calendar_skill.py       # NEW
│   │   └── email_skill.py          # NEW
│   └── main.py                     # 需擴充 router 註冊
├── deployment/
│   ├── docker-compose.yaml
│   └── init.sh
├── tests/
│   ├── test_intent_router.py       # NEW
│   ├── test_calendar_skill.py      # NEW
│   └── test_email_skill.py         # NEW
├── Dockerfile
├── pyproject.toml                  # 需加 google-auth, google-api-python-client
└── SPEC.md                         # 本文件
```

---

## 10. API 端點規格

| Method | Path | 說明 |
|--------|------|------|
| GET | `/` | Health check |
| POST | `/webhook` | LINE Webhook（現有） |
| GET | `/auth/google` | 產生 Google OAuth URL |
| GET | `/auth/google/callback` | Google OAuth 回調，儲存 token |
| POST | `/test_ollama` | 測試 Ollama（現有） |
| POST | `/echo` | Echo（現有） |

### GET /auth/google

- Query param: `line_user_id`
- Response: `{"auth_url": "https://accounts.google.com/..."}`

### GET /auth/google/callback

- Query params: `code`, `state`（state = line_user_id）
- 儲存 token 後透過 LINE push message 通知使用者
- Response: HTML 頁面（"授權成功！請回到 LINE。"）

---

## 11. 環境變數

```env
# 現有
HOST=
PORT=
DEBUG=
ENV=dev
LINE_CHANNEL_SECRET=
LINE_CHANNEL_ACCESS_TOKEN=
OLLAMA_MODEL=qwen3:8b         # 建議升級到更大模型以支援工具呼叫
OLLAMA_BASE_URL=http://localhost:11434

# 新增：Google OAuth
GOOGLE_CLIENT_ID=
GOOGLE_CLIENT_SECRET=
GOOGLE_REDIRECT_URI=https://your-domain.com/auth/google/callback

# 新增：資料庫
DATABASE_URL=sqlite:///./hao.db

# 新增：LINE Push (for OAuth callback 通知)
LINE_USER_ID=                  # 你自己的 LINE user ID（單人使用時）
```

---

## 12. 開發里程碑

### Phase 1：基礎 Agent 架構（1-2 週）
- [ ] 重構 `modelManager.py` 為抽象 LLM 層，支援 `/api/chat`
- [ ] 建立 DB schema（users, conversations）+ SQLAlchemy models
- [ ] 實作 `context_service.py`（對話記憶讀寫）
- [ ] 建立 Skill 基礎架構（BaseSkill, Registry）
- [ ] 實作 `GeneralChatSkill`（替換現有直接呼叫）
- [ ] 實作 `intentManager.py`（混合路由）

### Phase 2：Google Calendar 整合（1 週）
- [ ] 設定 Google Cloud OAuth 2.0
- [ ] 實作 `google_auth_service.py` + `auth_router.py`
- [ ] 建立 `google_tokens` DB table
- [ ] 實作 `CalendarSkill`（query + create）
- [ ] 整合測試

### Phase 3：Gmail 整合（1 週）
- [ ] 擴充 Google OAuth scope 加入 Gmail
- [ ] 實作 `EmailSkill`（list important + summarize）
- [ ] LLM 信件摘要功能
- [ ] 整合測試

### Phase 4：優化與強化（持續）
- [ ] 升級 Ollama 模型（建議 qwen3:8b 以上）
- [ ] 錯誤處理與 fallback 訊息優化
- [ ] 使用者未授權 Google 時的引導流程
- [ ] 回覆訊息格式優化（LINE Flex Message）
- [ ] 單元測試覆蓋率 > 60%

---

## 附錄：建議新增的 Python 套件

```toml
# pyproject.toml 新增
dependencies = [
    # 現有 ...
    "google-auth>=2.0.0",
    "google-auth-oauthlib>=1.0.0",
    "google-api-python-client>=2.0.0",
    "aiosqlite>=0.19.0",           # async SQLite
    "httpx>=0.27.0",               # 已有，用於 async HTTP
]
```

---

*本文件為初版規格，歡迎隨開發進展持續修訂。*
