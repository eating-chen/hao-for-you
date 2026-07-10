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

### Swagger API 規格說明
啟動Gateway後開啟網頁：http://localhost:8008/swagger-ui/index.html

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

## 快速啟動

### 1. 到 hao-for-you-gateway 資料夾目錄下

```bash
cd hao-for-you-gateway
```

### 2. 執行docker compose啟動服務

```bash
docker compose up -d --build
```

## 本地環境開發流程

### 1. 確認有執行環境套件

| 套件名稱 | 版本 | 說明 |
|---|---|---|
| `JDK` | 21 | Java 開發環境 |
| `Maven` | 3.9.x | Spring boot相關套件管理|
| `Postgresql` | 16 | 資料庫 |

### 2. 設定環境變數

```bash
cp .env.example .env
```

填入 `.env` 後，本機（非 Docker）連的是本地 Postgres，需指定 DB 連線以覆寫 dev profile 預設的 `db:5432`：

```bash
DB_URL=jdbc:postgresql://localhost:5432/haoforyou
DB_USER=haoforyou
DB_PASSWORD=haoforyou
```

> `.env` 會由 spring-dotenv 自動載入；預設啟動 `dev` profile。

### 3. 啟動 PostgreSQL

postgresql 啟動與停止
```bash
brew services start postgresql@16
brew services stop postgresql@16
```

本機若未安裝 Postgres，可用 Docker 只起一個 DB 容器：

```bash
docker run -d --name hao-pg \
  -e POSTGRES_DB=haoforyou \
  -e POSTGRES_USER=haoforyou \
  -e POSTGRES_PASSWORD=haoforyou \
  -p 5432:5432 postgres:16-alpine
```

啟動後由 Hibernate（`ddl-auto=update`）自動建立 `users` / `google_tokens` 資料表。

### 4. 編譯並啟動 Gateway

```bash
# 開發模式（預設 dev profile）
mvn spring-boot:run
```

或打包成 jar 後執行：

```bash
mvn clean package
java -jar target/hao-for-you-0.1.0.jar
```

啟動後：
- `http://localhost:8008/health` → 健康檢查
- `http://localhost:8008/actuator/health` → Actuator 健康狀態

> 💡 對話 / LLM 相關功能需另外啟動 `hao-for-you-service`（gateway 透過 `app.service-url` 呼叫它做模型推論）。

# TODO - 待更新

## 目錄結構（hao-for-you-service/）

```

```