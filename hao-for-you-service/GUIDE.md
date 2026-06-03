# hao-for-you 專案說明文件

這份文件說明專案的目錄結構、每個檔案的職責，以及從開發到執行的完整流程。
最後附有每個 `.py` 檔的逐行程式碼說明，適合 Python 新手閱讀。

---

## 目錄

1. [這個專案在做什麼？](#這個專案在做什麼)
2. [專案目錄結構](#專案目錄結構)
3. [app/ 各層說明](#app-各層說明)
4. [Python 開發生命週期](#python-開發生命週期)
5. [本機啟動流程](#本機啟動流程)
6. [一個請求的完整旅程](#一個請求的完整旅程)
7. [常用指令速查](#常用指令速查)
8. [程式碼逐行說明](#程式碼逐行說明)
   - [Python 基礎語法速查](#python-基礎語法速查)
   - [run.py](#runpy)
   - [app/main.py](#appmainpy)
   - [app/core/config.py](#appcoreconfigpy)
   - [app/api/api.py](#appapiapypy)
   - [app/api/chatbot_router.py](#appapichatbot_routerpy)
   - [app/manager/modelManager.py](#appmanagermodelmanagerpy)
   - [app/schemas/cahtbot_schema.py](#appschemascahtbot_schemapy)
   - [app/service/logger_service.py](#appservicelogger_servicepy)
   - [app/service/util_service.py](#appserviceutil_servicepy)
   - [app/db/crud/base.py](#appdbcrudbasepy)
   - [tests/ 測試檔案](#tests-測試檔案)

---

## 這個專案在做什麼？

這是一個 **LINE 聊天機器人後端服務**，使用者在 LINE 上傳訊息，訊息會傳到這個伺服器，伺服器呼叫本地 AI 模型（Ollama）產生回覆，再把回覆傳回給使用者。

```
使用者 LINE 訊息
      ↓
  LINE Platform
      ↓ (Webhook POST)
  FastAPI Server（你的程式）
      ↓
  Ollama（本地 AI 模型）
      ↓
  AI 回應 → 回傳給使用者
```

---

## 專案目錄結構

```
hao-for-you/
│
├── app/                      # ⭐ 主程式目錄（最重要）
│   ├── main.py               # 程式入口點，建立 FastAPI app
│   ├── api/                  # 處理 HTTP 請求的路由層
│   │   ├── api.py            # 路由總整合
│   │   └── chatbot_router.py # 各個 API endpoint 實作
│   ├── core/                 # 設定與配置
│   │   ├── config.py         # 環境變數、設定值
│   │   └── logger_settings.yml # 日誌格式設定
│   ├── db/                   # 資料庫相關（目前預留）
│   │   └── crud/base.py      # CRUD 操作基礎類別
│   ├── manager/              # 外部服務的管理器
│   │   └── modelManager.py   # 負責呼叫 Ollama AI 模型
│   ├── schemas/              # 資料格式定義
│   │   └── cahtbot_schema.py # 定義 API 輸入/輸出的資料結構
│   └── service/              # 共用工具服務
│       ├── logger_service.py # 日誌紀錄服務
│       └── util_service.py   # 工具函式（如組合 prompt）
│
├── tests/                    # 測試程式
│   ├── api_test/
│   │   └── test_chatbot_router.py
│   └── service_test/
│       └── test_logger_service.py
│
├── deployment/               # 部署相關設定
│   ├── docker-compose.yaml   # Docker 多服務啟動設定
│   └── init.sh               # 初始化腳本
│
├── run.py                    # 本機啟動入口（執行這個來啟動伺服器）
├── pyproject.toml            # 專案套件依賴清單（類似 npm 的 package.json）
├── uv.lock                   # 套件版本鎖定檔（自動產生，不要手動修改）
├── Dockerfile                # 打包 API 服務的 Docker 映像定義
├── Dockerfile.ollama         # 打包 Ollama AI 服務的 Docker 映像定義
├── .env                      # 環境變數（含密鑰，不進 git）
├── .env.example              # 環境變數範本（進 git，給別人參考用）
├── .python-version           # 指定 Python 版本（給 pyenv/uv 讀取）
├── .venv/                    # 虛擬環境目錄（自動產生，不要手動修改）
└── logs/                     # 執行時產生的日誌檔
```

---

## app/ 各層說明

這個專案採用「**分層架構**」，每一層只負責一件事，像餐廳的外場、廚房、採購分工一樣。

### 第一層：`main.py` — 程式入口

FastAPI 的 app 物件在這裡被建立。`lifespan` 定義**伺服器啟動時**和**關閉時**要做什麼事。
這裡也把 LINE Webhook handler 和所有路由（router）都「裝」進 app。

### 第二層：`api/` — 路由層（對外窗口）

**`api.py`** 是路由集合點。**`chatbot_router.py`** 定義了：

| Endpoint | 方法 | 說明 |
|---|---|---|
| `/` | GET | 健康檢查 |
| `/echo` | POST | 測試用 echo |
| `/webhook` | POST | LINE webhook |
| `/test_ollama` | POST | 測試 AI 模型 |

### 第三層：`core/` — 設定層

**`config.py`** 集中管理所有設定值，根據 `ENV` 環境變數自動切換 `DevConfig` / `ProdConfig`。

### 第四層：`manager/` — 外部服務管理層

**`modelManager.py`** 封裝所有和 Ollama AI 溝通的細節。

### 第五層：`schemas/` — 資料格式層

用 Pydantic `BaseModel` 定義 API 輸入輸出的資料形狀，FastAPI 自動驗證與產生文件。

### 第六層：`service/` — 共用工具層

**`logger_service.py`** 提供全域日誌紀錄器。**`util_service.py`** 組合 AI prompt 內容。

---

## Python 開發生命週期

### 1. 環境建置（首次設定）

```
安裝 uv（套件管理工具）
      ↓
uv sync（根據 pyproject.toml 安裝所有套件）
      ↓
.venv/ 虛擬環境建立完成
```

**為什麼需要虛擬環境？**
不同專案可能需要不同版本的套件。虛擬環境讓每個專案有自己獨立的套件空間，互不干擾。

### 2. 開發階段

```
修改程式碼
      ↓
python run.py --dev（啟動伺服器）
      ↓
瀏覽器開 http://localhost:8008/docs 測試 API
```

### 3. 測試階段

```
pytest（執行 tests/ 下所有測試）
      ↓
測試通過 → 準備部署 / 測試失敗 → 修 bug
```

### 4. 部署階段（Docker）

```
docker compose -f deployment/docker-compose.yaml up
      ↓
api 容器 + ollama 容器同時啟動，對外服務
```

---

## 本機啟動流程

1. 複製環境變數範本：`cp .env.example .env`
2. 填入 LINE 金鑰（`.env` 檔）
3. 安裝套件：`uv sync`
4. 啟動：`python run.py --dev`
5. 瀏覽器開 `http://localhost:8008/docs`

---

## 一個請求的完整旅程

以使用者在 LINE 傳「你好」為例：

```
1. LINE 平台把訊息以 POST 打到 /webhook
2. webhook() 驗證 LINE 簽章（防偽造）
3. handle_message() 取出 user_message = "你好"
4. OllamaModelManager.generate_response("你好")
   → prompt_template() 組合完整 prompt
5. HTTP POST 打給 Ollama（localhost:11434/api/generate）
6. Ollama 回傳 AI 生成文字
7. 透過 LINE Messaging API 回覆使用者
8. logger 記錄這次請求到 logs/
```

---

## 常用指令速查

```bash
uv sync                                              # 安裝/更新套件
uv add 套件名稱                                       # 新增套件
python run.py --dev                                  # 啟動開發伺服器
pytest                                               # 執行所有測試
pytest tests/api_test/test_chatbot_router.py         # 執行單一測試
docker compose -f deployment/docker-compose.yaml up  # Docker 啟動
docker compose -f deployment/docker-compose.yaml down # Docker 停止
```

---

## 程式碼逐行說明

---

### Python 基礎語法速查

在讀每個檔案之前，先建立幾個最基本的 Python 概念：

#### import — 引入模組

```python
import os               # 引入標準函式庫 os（用於讀環境變數、操作路徑）
import json             # 引入 json（用於序列化/反序列化 JSON 資料）
from fastapi import FastAPI   # 從 fastapi 套件裡，只取出 FastAPI 這個類別
```

`import` 就像是「把工具箱帶進來」。`from X import Y` 是「只拿工具箱裡的某一把工具」。

#### 函式（function）

```python
def greet(name: str) -> str:   # 定義一個叫 greet 的函式
    return "Hello " + name     # 回傳值

result = greet("Andy")         # 呼叫函式，result = "Hello Andy"
```

- `def` 是定義函式的關鍵字
- `name: str` 是**型別標注（type annotation）**，說明 `name` 這個參數預期是字串（`str`）
- `-> str` 說明這個函式會回傳一個字串
- 型別標注是給人看的提示，Python 不強制檢查，但 IDE 和工具（如 Pydantic）會用到

#### 類別（class）

```python
class Dog:
    def __init__(self, name: str):   # 建構子，建立物件時自動執行
        self.name = name             # self 代表「這個物件本身」

    def bark(self) -> str:
        return f"{self.name} says woof!"

my_dog = Dog("Rex")          # 建立一個 Dog 物件
print(my_dog.bark())         # "Rex says woof!"
```

- `class` 定義類別，類別是物件的「藍圖」
- `__init__` 是**建構子**，每次用 `Dog(...)` 建立物件時自動執行
- `self` 是固定寫法，代表「物件自己」，每個方法的第一個參數都要寫

#### 裝飾器（decorator）

```python
@router.get("/")          # 這是裝飾器
def health_check():
    return {"status": "ok"}
```

`@` 開頭的是裝飾器。它會「包住」下面的函式，加上額外的行為。
這裡的 `@router.get("/")` 告訴 FastAPI：「有人用 GET 方法訪問 `/`，就執行 `health_check`」。

#### f-string — 字串格式化

```python
name = "Andy"
model = "qwen3.5"
msg = f"User: {name}, Model: {model}"
# 結果：'User: Andy, Model: qwen3.5'
```

`f"..."` 是格式化字串，`{變數名稱}` 會被替換成變數的值。

#### try / except — 例外處理

```python
try:
    result = 10 / 0           # 這行會出錯（除以零）
except Exception as e:
    print(f"發生錯誤：{e}")   # 出錯時執行這裡，不讓程式崩潰
```

`try` 裡放「可能出錯的程式碼」，`except` 裡放「出錯時的處理」。

#### with — 上下文管理器

```python
with open("file.txt", "r") as f:
    content = f.read()
# 離開 with 區塊後，檔案自動關閉
```

`with` 確保資源（檔案、網路連線）用完後一定會被釋放，即使中途出錯也一樣。

#### async / await — 非同步

```python
async def fetch_data():       # async 定義非同步函式
    result = await some_io()  # await 等待 IO 完成，期間不阻塞其他請求
    return result
```

非同步讓伺服器在「等待」（如等 AI 回應）時，可以同時處理其他請求，提升效能。

---

### run.py

**職責：** 本機啟動伺服器的入口腳本，支援 `--dev` / `--prod` 模式切換。

```python
import argparse        # 標準函式庫：解析命令列參數（--dev, --prod）
import os              # 標準函式庫：操作環境變數
import uvicorn         # ASGI 伺服器，負責實際跑 FastAPI
from dotenv import load_dotenv  # 從 .env 檔讀取環境變數

load_dotenv()          # 執行時立刻讀取 .env 檔，把變數載入 os.environ
```

```python
if __name__ == "__main__":
```

這是 Python 的慣用寫法。`__name__` 是 Python 內建變數：
- 直接執行這個檔案時：`__name__ == "__main__"`（條件成立，進入區塊）
- 被其他檔案 import 時：`__name__ == "run"`（條件不成立，不執行）

這樣可以確保「只有直接執行 `python run.py` 才啟動伺服器」。

```python
    parser = argparse.ArgumentParser(description="Run FastAPI server in different modes.")
    parser.add_argument("--prod", action="store_true", help="Run in production mode.")
    parser.add_argument("--dev",  action="store_true", help="Run in development mode.")
    args = parser.parse_args()
```

- `ArgumentParser` 建立一個「命令列參數解析器」
- `add_argument("--prod", action="store_true")` 代表：`--prod` 是一個開關旗標，出現就是 `True`，不出現就是 `False`
- `args.prod` 就可以取得使用者有沒有加 `--prod`

```python
    if args.prod:
        os.environ["ENV"] = "prod"   # 設定環境變數 ENV = "prod"
    else:
        os.environ["ENV"] = "dev"    # 預設 dev

    uvicorn.run(
        "app.main:app",              # 字串格式：「哪個模組:哪個變數」
        host=os.getenv("HOST", "0.0.0.0"),  # 讀 HOST 環境變數，沒有就用預設值
        port=int(os.getenv("PORT", "8008")) # 讀 PORT，轉成整數
    )
```

- `os.environ["ENV"] = "prod"` 是設定環境變數（在執行期間有效）
- `os.getenv("PORT", "8008")` 讀取環境變數，第二個參數是預設值
- `int(...)` 將字串轉成整數（因為 `os.getenv` 回傳的都是字串）
- `uvicorn.run("app.main:app")` 告訴 uvicorn 去找 `app/main.py` 裡的 `app` 物件來執行

---

### app/main.py

**職責：** 建立 FastAPI 應用程式本體，組合所有路由與 LINE 初始化。

```python
"""fastapi app entry"""
```

檔案開頭的 `"""..."""` 是**模組 docstring**，說明這個檔案的用途。三個雙引號包住的字串是多行字串，放在函式/類別/模組最頂端時，叫做 docstring（說明文件字串）。

```python
from contextlib import asynccontextmanager
```

`contextlib` 是標準函式庫，`asynccontextmanager` 是一個裝飾器，讓你用 `async def` 函式寫出「非同步版本的 with 上下文管理器」。

```python
from fastapi import FastAPI
from linebot.v3 import WebhookHandler
from linebot.v3.messaging import Configuration
from app.api import chatbot_router
from app.api.api import router as api_router  # 匯入時重新命名為 api_router
from app.core.config import config
from app.service.logger_service import logger
```

- `from app.api.api import router as api_router`：`as` 關鍵字可以在匯入時給它一個新名字，避免命名衝突。

```python
@asynccontextmanager
async def lifespan(app: FastAPI):
    """start and shutdown event for FastAPI app"""
    logger.info("Starting FastAPI app...")
    yield                                  # ← 重點：yield 把控制權交給 app
    logger.info("Shutting down FastAPI app...")
```

這是 FastAPI 的**生命週期函式（lifespan）**：
- `yield` 之前的程式碼：伺服器**啟動時**執行（初始化）
- `yield` 之後的程式碼：伺服器**關閉時**執行（清理）
- `yield` 本身的功能是「把控制權暫時交出去，等對方用完再回來繼續」

```python
app = FastAPI(lifespan=lifespan)
```

建立 FastAPI 應用程式物件，傳入 `lifespan` 讓它知道啟動/關閉時做什麼。

```python
app.state.line_handler = WebhookHandler(config.LineConfig.channel_secret)
app.state.line_config = Configuration(
    access_token=config.LineConfig.channel_access_token
)
```

`app.state` 是 FastAPI 提供的「應用程式狀態儲存空間」，可以把任何物件掛在上面，之後在 request handler 裡用 `request.app.state.xxx` 取得。
這裡把 LINE 的 handler 和 config 掛上去，讓整個 app 都能用。

```python
chatbot_router.register_line_handlers(
    app.state.line_handler,
    app.state.line_config,
)
app.include_router(api_router)
```

- `register_line_handlers(...)` 把 LINE 訊息事件處理器綁定到 handler
- `app.include_router(api_router)` 把路由「掛載」到 app，讓 app 知道有哪些 API 端點

---

### app/core/config.py

**職責：** 集中管理所有設定值，根據環境自動切換。

```python
import os
from app.service.logger_service import logger, error_logger
```

```python
def _getenv(*keys, default=""):
    """get the first non-empty environment value"""
    for key in keys:
        value = os.getenv(key)
        if value:
            return value.strip()   # .strip() 去除前後空白
    return default
```

- `*keys` 是**可變參數**，`*` 代表「接受任意數量的參數，打包成 tuple」
  - 呼叫 `_getenv("A", "B", "C")` 時，`keys = ("A", "B", "C")`
- `for key in keys` 依序嘗試每個環境變數名稱，找到有值的就回傳
- 這個設計讓同一個設定值可以有多個備用的環境變數名稱

```python
class BaseConfig:
    class LineConfig:
        """line bot config"""
        channel_secret = _getenv("LINE_CHANNEL_SECRET", "channel_secret")
        channel_access_token = _getenv(
            "LINE_CHANNEL_ACCESS_TOKEN",
            "channel_access_token",
        )
```

這裡用到**巢狀類別（nested class）**，`LineConfig` 定義在 `BaseConfig` 裡面。
這樣用 `config.LineConfig.channel_secret` 取值，結構清晰，不會有命名衝突。

```python
    class ModelConfig:
        ollama_model = os.getenv("OLLAMA_MODEL", "qwen3.5:0.8b")
        timeout = 10
        generate_config = {           # dict（字典）：key-value 的資料結構
            'temperature': 0.1,       # 越高越有創意，越低越保守
            'num_predict': 256,       # 最多生成幾個 token
            'top_p': 0.95,
            'repeat_penalty': 1.15,
        }
```

類別的屬性直接寫在類別體內（不在任何方法裡），叫做**類別屬性（class attribute）**。
不需要建立物件就能用：`BaseConfig.ModelConfig.timeout`。

```python
class DevConfig(BaseConfig):
    """dev config"""
    class ModelConfig(BaseConfig.ModelConfig):
        ollama_host = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434")

class ProdConfig(BaseConfig):
    """prod config"""
    class ModelConfig(BaseConfig.ModelConfig):
        ollama_host = os.getenv("OLLAMA_BASE_URL", "http://ollama:11434")
```

`class DevConfig(BaseConfig)` 是**繼承（inheritance）**：
- `DevConfig` 繼承 `BaseConfig` 的所有屬性
- 只需要覆寫（override）不同的部分
- `DevConfig.ModelConfig(BaseConfig.ModelConfig)` 同理，繼承並加上 `ollama_host`

```python
def get_config():
    env = os.getenv('ENV', 'dev')   # 讀 ENV 環境變數，預設 'dev'
    if env == 'prod':
        return ProdConfig()          # 回傳 ProdConfig 的實例（物件）
    return DevConfig()               # 預設回傳 DevConfig

config = get_config()                # 模組載入時立刻執行，產生全域 config 物件
```

最後這一行在模組被 import 時就會執行，所以整個 app 只會建立一個 `config` 物件。

---

### app/api/api.py

**職責：** 路由整合點，把所有子路由集合成一個 router。

```python
from fastapi import APIRouter
from app.api import chatbot_router

router = APIRouter()               # 建立一個路由器物件
router.include_router(chatbot_router.router)  # 把 chatbot_router 的路由加進來
```

這個檔案很短，功能是「路由聚合」。
好處是：`main.py` 只需要匯入這一個 `router`，不需要知道底下有幾個子路由，未來增加新路由也只要在這裡加一行。

---

### app/api/chatbot_router.py

**職責：** 定義所有 API endpoint 和 LINE webhook handler，是請求處理的核心。

```python
import asyncio              # 非同步工具，讓同步函式在非同步環境執行
import json                 # JSON 序列化/反序列化
import urllib.request       # 標準函式庫 HTTP 工具（發送 HTTP 請求）
from fastapi import APIRouter, HTTPException, Request, status, Body
```

- `HTTPException`：丟出 HTTP 錯誤（如 400、500）
- `Request`：代表一個 HTTP 請求物件，可以讀 headers、body 等
- `status`：HTTP 狀態碼的常數，如 `status.HTTP_200_OK` 等於 `200`
- `Body`：用於宣告 request body 的參數

```python
router = APIRouter()
```

建立路由器。這個 router 後來會被 `api.py` 收集起來。

```python
def register_line_handlers(line_handler, line_config):
    """register line webhook event handlers"""

    @line_handler.add(MessageEvent, message=TextMessageContent)
    def handle_message(event):
        user_message = event.message.text
        reply_text = OllamaModelManager.generate_response(user_message)
        logger.info(f"Line Received message: {user_message}, reply with: {reply_text}")

        with ApiClient(line_config) as api_client:
            line_bot_api = MessagingApi(api_client)
            line_bot_api.reply_message(
                ReplyMessageRequest(
                    reply_token=event.reply_token,
                    messages=[TextMessage(text=reply_text)],
                )
            )
```

- `@line_handler.add(MessageEvent, message=TextMessageContent)`：裝飾器，告訴 LINE SDK「當收到文字訊息事件，執行 `handle_message`」
- `event.message.text`：從 LINE 事件物件取出使用者傳的文字
- `with ApiClient(line_config) as api_client`：用上下文管理器建立 LINE API 連線，自動管理連線的開啟和關閉
- `[TextMessage(text=reply_text)]`：`[...]` 是 Python 的 list（陣列），這裡建立一個只有一個元素的 list

```python
@router.get(
    "/",
    status_code=status.HTTP_200_OK,   # 成功時回傳的 HTTP 狀態碼
    tags=["Health Check"],            # Swagger 文件分類標籤
    summary="FastAPI server 健康狀況"  # Swagger 文件的摘要說明
)
def health_check():
    return {
        'message': 'hao-for-you Python server is running',
        'status': 'ok'
    }
```

- `@router.get("/")` 裝飾器：宣告這是一個 GET `/` endpoint
- 回傳一個 dict，FastAPI 會自動轉成 JSON 回應

```python
@router.post(
    "/echo",
    status_code=status.HTTP_200_OK,
    response_model=EchoInfoSchema,    # 告訴 FastAPI 回傳格式要符合這個 Schema
    tags=["finish_info"],
    summary="echo API，回傳收到的資料和訊息"
)
def echo(prompt: str):                # prompt 是 query string 參數
    return {
        'received': prompt,
        'message': 'Echo from hao-for-you server'
    }
```

- `response_model=EchoInfoSchema`：FastAPI 會根據此 Schema 驗證回傳值，並在 Swagger 文件顯示格式

```python
@router.post("/webhook", ...)
async def webhook(request: Request):                  # async 非同步函式
    signature = request.headers.get("X-Line-Signature", "")  # 讀取 header
    body = await request.body()                               # await 等待讀取 body
    body_text = body.decode("utf-8")                          # bytes 轉字串

    try:
        await asyncio.to_thread(                  # 把同步函式丟到另一個執行緒執行
            request.app.state.line_handler.handle,
            body_text,
            signature,
        )
    except InvalidSignatureError:
        raise HTTPException(status_code=400, detail="Invalid signature")

    return "OK"
```

- `request.headers.get("X-Line-Signature", "")` 取得 HTTP header，如果不存在回傳空字串
- `body.decode("utf-8")` 把 bytes（原始位元組）轉成 UTF-8 字串
- `asyncio.to_thread(...)` 把「同步函式」放到執行緒池執行，不阻塞非同步事件迴圈
- `raise HTTPException(...)` 主動拋出 HTTP 例外，讓 FastAPI 回傳對應的錯誤狀態碼

```python
@router.post("/test_ollama", ...)
def test_ollama(prompt: str = Body(..., embed=True)):
    reply_text = OllamaModelManager.generate_response(prompt)
    return reply_text
```

- `Body(..., embed=True)` 告訴 FastAPI：`prompt` 要從 **request body 的 JSON** 讀取
  - `...`（三個點）在 Python 裡叫做 `Ellipsis`，在 Pydantic/FastAPI 裡代表「這個欄位是必填的」
  - `embed=True` 代表 JSON body 格式是 `{"prompt": "..."}` 而非直接 `"..."`

---

### app/manager/modelManager.py

**職責：** 封裝與 Ollama 本地 AI 模型的所有通訊細節。

```python
class OllamaModelManager:
    """Ollama model manager"""

    @staticmethod
    def generate_response(prompt: str) -> str:
```

- `@staticmethod` 是一個裝飾器，宣告這是**靜態方法**
  - 靜態方法不需要 `self`，不依附於某個物件實例
  - 呼叫方式：`OllamaModelManager.generate_response(...)` 直接用類別名稱呼叫
  - 適合當「這個功能邏輯上屬於這個類別，但不需要存取物件狀態」的情況

```python
        payload = {
            "model": config.ModelConfig.ollama_model,
            "prompt": prompt_template(prompt),
            "stream": False,   # 不要串流，等完整回應
            "raw": False,
            "think": False,
            "options": config.ModelConfig.generate_config  # 把整個 dict 傳入
        }
```

`payload` 是一個 dict，之後會被序列化成 JSON 傳給 Ollama API。

```python
        request = urllib.request.Request(
            config.ModelConfig.ollama_host + "/api/generate",  # 字串相加組合 URL
            data=json.dumps(payload).encode("utf-8"),  # dict → JSON 字串 → bytes
            headers={"Content-Type": "application/json"},
            method="POST",
        )
```

- `json.dumps(payload)` 把 Python dict 轉成 JSON 字串
- `.encode("utf-8")` 把字串轉成 bytes（HTTP 傳輸需要 bytes）
- `urllib.request.Request` 建立 HTTP 請求物件（還沒有發送）

```python
        try:
            logger.info(
                "Calling Ollama API: host=%s, model=%s",
                config.ModelConfig.ollama_host,
                config.ModelConfig.ollama_model,
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                data = json.loads(response.read().decode("utf-8"))
        except Exception as e:
            error_logger.exception(f"Error calling Ollama API: {e}")
            data = {
                "response": "抱歉，我現在暫時無法連線到 AI 服務，請稍後再試。"
            }
```

- `logger.info("...", arg1, arg2)`：`%s` 佔位符會被後面的參數依序替換，這是 logging 模組的格式化方式（不用 f-string）
- `urllib.request.urlopen(request, timeout=120)`：發送 HTTP 請求，`timeout=120` 代表最多等 120 秒
- `response.read()` 讀取回應的 bytes，`.decode("utf-8")` 轉字串，`json.loads(...)` 字串轉 dict
- `except Exception as e`：捕捉所有例外（`Exception` 是所有例外的基底類別），`as e` 讓你可以用 `e` 取得例外物件

```python
        return data.get("response", "")
```

- `dict.get("key", default)` 取得 dict 的值，如果 key 不存在則回傳 default（`""`）
  - 比 `data["response"]` 安全，因為 key 不存在時不會報錯

---

### app/schemas/cahtbot_schema.py

**職責：** 用 Pydantic 定義 API 資料的「形狀」（Schema），讓 FastAPI 自動驗證。

```python
from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field
```

- `datetime`：日期時間型別（這個 Schema 目前沒用到，預留用）
- `Optional`：來自 `typing` 模組，`Optional[str]` 代表「字串或 None」
- `Field`：Pydantic 提供，用來為欄位加上額外說明（如 description）

```python
class EchoInfoSchema(BaseModel):
    received: str = Field(..., description="收到的消息")
    message: str = Field(..., description="系統訊息")
```

- `BaseModel`：Pydantic 的基底類別，繼承它就能自動做資料驗證
- `received: str`：型別標注，宣告 `received` 必須是字串
- `Field(..., description="...")`：
  - `...`（Ellipsis）代表**必填**，不能是 None 或缺少
  - `description` 是給 Swagger 文件看的說明文字

```python
    class Config:
        from_attributes = True       # 允許從 ORM 物件（如 SQLAlchemy）建立
        json_schema_extra = {
            "example": {             # Swagger 文件裡顯示的範例資料
                'received': "hello world",
                'message': 'Echo from hao-for-you server'
            },
        }
```

`class Config` 是 Pydantic 的慣例，用來設定這個 Model 的行為選項。

---

### app/service/logger_service.py

**職責：** 建立並管理應用程式的日誌紀錄器，讓程式執行時能把紀錄寫入檔案。

```python
import os
import logging          # Python 標準函式庫：日誌紀錄
import yaml             # 讀取 YAML 設定檔
from logging.handlers import RotatingFileHandler
```

- `RotatingFileHandler`：日誌滾動處理器，當 log 檔超過指定大小，自動建新檔、保留備份

```python
class LoggerService:
    with open('./app/core/logger_settings.yml', 'r') as file:
        config = yaml.safe_load(file)   # 讀取 YAML 轉成 Python dict
    loggers = {}                        # 類別屬性，儲存已建立的 logger（dict）
```

- `with open(...) as file` 開啟檔案，`'r'` 代表讀取模式
- `yaml.safe_load(file)` 把 YAML 內容轉成 Python dict
- `loggers = {}` 空 dict，用來快取已建立的 logger，避免重複建立

```python
    @classmethod
    def setup_logger(cls, name=None):
```

- `@classmethod` 裝飾器：宣告這是**類別方法（class method）**
  - 第一個參數是 `cls`（代表類別本身），而不是 `self`（代表物件實例）
  - 呼叫方式：`LoggerService.setup_logger()` 不需要建立物件
  - 和 `@staticmethod` 的差別：classmethod 可以存取類別屬性（如 `cls.loggers`）

```python
        if name in cls.loggers:
            return cls.loggers[name]  # 如果已建立過，直接回傳快取的 logger
```

`in` 運算子：檢查 `name` 是否在 dict 的 key 裡。

```python
        log_config = cls.config.get(name, {})   # 取對應名稱的設定，沒有就回傳空 dict
        if not log_config:
            raise ValueError(f"No logger configuration found for '{name}'")
```

- `if not log_config`：空 dict `{}` 在 Python 裡是 falsy，所以 `not {}` 是 `True`
- `raise ValueError(...)` 主動拋出例外，讓呼叫者知道設定有問題

```python
        formatter = logging.Formatter(log_format)
        handler = RotatingFileHandler(
            log_file,
            maxBytes=log_config.get('maxBytes', 1024 * 1024 * 100),  # 100 MB
            backupCount=log_config.get('backupCount', 20)
        )
        handler.setFormatter(formatter)   # 設定格式

        logger = logging.getLogger(name)  # 取得（或建立）指定名稱的 logger
        logger.setLevel(level)

        if not logger.handlers:           # 避免重複加 handler
            logger.addHandler(handler)

        cls.loggers[name] = logger        # 存入快取
        return logger
```

- `1024 * 1024 * 100`：Python 可以直接寫數學算式，這裡計算 100MB 的 bytes 數
- `if not logger.handlers`：`logger.handlers` 是 list，空 list 是 falsy

```python
logger = LoggerService.setup_logger()          # 建立 info logger（name=None，讀 info 設定）
error_logger = LoggerService.setup_logger('error')  # 建立 error logger
```

模組層級直接建立兩個 logger，其他檔案只需要 `from app.service.logger_service import logger` 就能用。

---

### app/service/util_service.py

**職責：** 提供工具函式，目前負責組合傳給 AI 的完整 prompt。

```python
"""define util function"""
from app.core.config import config


def prompt_template(user_message: str):

    return "\n\n".join([
        config.ModelConfig.system_prompt,
        "使用者問題: " + user_message,
        "請直接回答，不要輸出思考過程。",
    ])
```

- `"\n\n".join([...])` 是字串的 `join` 方法：用 `\n\n`（兩個換行）把 list 裡每個字串連接起來
  - `\n` 是換行符號（newline）
  - 結果類似：`"系統提示詞\n\n使用者問題: xxx\n\n請直接回答..."`
- `[...]` 是一個 list，裡面有三個字串元素

範例輸出：
```
你叫 Hao，是一位 AI 助手
請用繁體中文回答...

使用者問題: 你好

請直接回答，不要輸出思考過程。
```

---

### app/db/crud/base.py

**職責：** 資料庫操作的基底類別，目前預留，未來擴充用。

```python
"""base"""
from sqlalchemy.ext.asyncio import AsyncSession


class DBSessionContext(object):
    """有要初始db的物件先繼承這個class"""

    def __init__(self, _db: AsyncSession):
        self.db = _db
```

- `class DBSessionContext(object)` 繼承 `object`，這是 Python 3 的慣例寫法，等同於 `class DBSessionContext:` 
- `__init__(self, _db: AsyncSession)`：建構子，建立物件時需要傳入一個資料庫 session
- `self.db = _db`：把傳入的 session 存在物件的 `db` 屬性上
- 參數名前加 `_` 是 Python 慣例，表示「這是內部用的，外部不應直接存取」

```python
class AppCRUD(DBSessionContext):
    """負責處理CRUD的 base class"""
    pass
```

- `pass` 是 Python 關鍵字，代表「什麼都不做」，用於暫時佔位
- `AppCRUD` 繼承 `DBSessionContext`，未來在這裡加入 create/read/update/delete 方法

---

### tests/ 測試檔案

**職責：** 驗證程式功能正確性，確保修改後沒有破壞既有功能。

#### tests/api_test/test_chatbot_router.py

```python
from unittest.mock import patch    # 標準函式庫：模擬（mock）工具
from fastapi.testclient import TestClient   # FastAPI 提供的測試用 HTTP client
from app.main import app           # 匯入我們的 FastAPI app

client = TestClient(app)           # 建立測試用 client，不需要真的啟動伺服器


def test_echo():
    response = client.post("/echo", json={"message": "Hello, World!"})
    assert response.status_code == 200
```

- `TestClient(app)` 建立一個模擬 HTTP 用戶端，可以對 app 發送請求
- `client.post("/echo", json=...)` 發送 POST 請求，`json=` 自動把 dict 轉成 JSON body
- `assert`：斷言語句，`assert 條件` 如果條件是 `False`，測試失敗並報錯

#### tests/service_test/test_logger_service.py

```python
import logging
from app.service.logger_service import LoggerService


class TestLoggerService:

    def test_setup_logger(self):
        logger = LoggerService.setup_logger()
        error_logger = LoggerService.setup_logger('error')
        assert isinstance(logger, logging.RootLogger)   # 檢查類型是否正確
        assert isinstance(error_logger, logging.Logger)
```

- 測試函式放在類別裡，類別名稱以 `Test` 開頭，方法以 `test_` 開頭（pytest 的慣例）
- `isinstance(物件, 類別)` 檢查物件是否是某個類別的實例（instance），回傳 `True` 或 `False`

---

## 重要觀念總結

| 語法/觀念 | 在專案中的體現 |
|---|---|
| `import / from...import` | 每個檔案頂部，引入需要的工具 |
| `class` + 繼承 | `DevConfig(BaseConfig)` 複用設定、`AppCRUD(DBSessionContext)` 複用 DB session |
| `def` + 型別標注 | `def generate_response(prompt: str) -> str` |
| `@decorator` 裝飾器 | `@router.get("/")` 綁定路由、`@staticmethod` 宣告靜態方法 |
| `async / await` | webhook 非同步處理、`asynccontextmanager` lifespan |
| `try / except` | modelManager 捕捉 Ollama 連線錯誤 |
| `with` 上下文管理器 | 開檔、LINE API 連線的自動清理 |
| `os.getenv()` | config.py 讀取 .env 環境變數 |
| `f"..."` f-string | logger 的訊息格式化 |
| `dict.get(key, default)` | 安全取值，防止 KeyError |
| `assert` | 測試中驗證預期結果 |
