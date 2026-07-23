# Design: Google Mail & Calendar API Endpoints

**Date:** 2026-06-15
**Status:** Approved
**Updated:** 2026-07-23 — 依實際程式碼校正欄位、回應格式與行為（詳見各段落）

---

## 目標

在 Java Gateway 新增四個 REST endpoint，讓 Python service 的 LLM 可以透過 HTTP 呼叫執行 Google Mail 查詢、Google Calendar 查詢／新增／修改，達到智慧助理功能。

---

## 架構

```
LINE 訊息
    ↓
Java Gateway（/webhook）
    ↓
Python Service（LLM 判斷意圖）
    ↓ HTTP POST
Java Gateway Google API Endpoints
    ↓
Google API（Gmail / Calendar）
```

Java gateway 持有 Google token 與 API 執行能力，Python service 負責讓 LLM 決定何時、如何呼叫。

---

## 新增元件（Java Gateway）

```
hao-for-you-gateway/
├── controller/
│   ├── MailController.java              # POST /google/mail/query
│   └── CalendarApiController.java       # POST /google/calendar/{query,create,modify}
├── service/
│   ├── MailService.java                 # 執行 Gmail API 呼叫
│   ├── CalendarApiService.java          # 執行 Calendar API 呼叫
│   └── GoogleAuthService.java           # buildGmailService() / buildCalendarService()
└── dto/
    ├── GoogleMailApiRequest.java        # Mail query 的 request body
    ├── GoogleCalendarApiRequest.java    # Calendar query/create/modify 共用的 request body
    ├── GoogleApiResponse.java           # 四個 endpoint 共用的回應包裝（含 success/data/error）
    ├── MailData.java / MessageSummary.java       # Mail 回應內容
    └── CalendarData.java / EventSummary.java     # Calendar 回應內容
```

> Mail 和 Calendar 各自有獨立的 request DTO（`GoogleMailApiRequest` / `GoogleCalendarApiRequest`），**不是**單一共用的 request body。

---

## Request Body

### Calendar（`GoogleCalendarApiRequest`，query / create / modify 共用）

```json
{
  "line_user_id": "U123",
  "event_id": "",
  "title": "",
  "date": "",
  "date_to": "",
  "start_time": "",
  "end_time": "",
  "description": "",
  "all_day": false,
  "keyword": "",
  "limit": 5
}
```

| 欄位 | 型別 | 用途 |
|---|---|---|
| `line_user_id` | String | 必填，用來查 DB 取 Google token |
| `event_id` | String | Calendar modify 時必填 |
| `title` | String | 事件標題（對應 Google `summary`） |
| `date` | String | 格式 `YYYY-MM-DD`；query 時若搭配 `date_to` 代表查詢區間 |
| `date_to` | String | 查詢日期區間的結束日（僅 query 使用，需搭配 `date`） |
| `start_time` | String | 格式 `HH:mm[:ss]`；非全天事件建立/修改時使用 |
| `end_time` | String | 格式 `HH:mm[:ss]`；非全天事件建立/修改時使用 |
| `description` | String | 事件描述 |
| `all_day` | Boolean | 全天事件，預設 false |
| `keyword` | String | 查詢關鍵字 |
| `limit` | Integer | query 回傳筆數，預設 5 |

### Mail（`GoogleMailApiRequest`，query 專用）

```json
{
  "line_user_id": "U123",
  "date": "",
  "date_to": "",
  "keyword": "",
  "from": "",
  "unread_only": false,
  "limit": 5
}
```

| 欄位 | 型別 | 用途 |
|---|---|---|
| `line_user_id` | String | 必填，用來查 DB 取 Google token |
| `date` | String | 只帶 `date` → 查當天寄達的信件 |
| `date_to` | String | 搭配 `date` 一起使用才會查詢區間，單獨帶入無效 |
| `keyword` | String | 可直接帶 Gmail 搜尋語法（例如 `subject:會議` 只搜標題） |
| `from` | String | 寄件人篩選 |
| `unread_only` | Boolean | 僅顯示未讀郵件，預設 false |
| `limit` | Integer | 回傳筆數，預設 5 |

---

## 統一回應格式（`GoogleApiResponse<T>`）

四個 endpoint 都回傳同一個包裝格式，成功與失敗的差別只在 `success` / `data` / `error` 三個欄位：

```json
// 成功
{ "success": true, "data": { /* CalendarData 或 MailData */ }, "error": null }

// 失敗
{ "success": false, "data": null, "error": { "code": "ERROR_CODE", "message": "說明文字" } }
```

---

## Endpoints

### 1. Gmail 查詢

```
POST /google/mail/query
```

**使用欄位：** `line_user_id`, `date`, `date_to`, `keyword`, `from`, `unread_only`, `limit`

**Google API 對應：**
1. `messages().list()` — 以 `q` 參數搜尋（組合 keyword / from:from / is:unread / after:date / before:date_to+1天）
2. `messages().get()` — 取每封信的 subject、from、date、snippet，並遞迴解析 multipart 內文（`text/plain`）

**`data`（`MailData`）：**
```json
{
  "messages": [
    {
      "id": "xxx",
      "subject": "明天開會通知",
      "from": "boss@gmail.com",
      "date": "2026-06-15",
      "snippet": "請準時出席...",
      "body": "完整郵件純文字內容..."
    }
  ]
}
```

---

### 2. Calendar 查詢

```
POST /google/calendar/query
```

**使用欄位：** `line_user_id`, `date`, `date_to`, `limit`, `keyword`

**Google API 對應：** `events().list()`
- `date` + `date_to` 都有值 → `timeMin`/`timeMax` 限定該區間
- 只有 `date` 有值 → `timeMin`/`timeMax` 限定當天
- 都沒有值 → 從現在起往後查
- `keyword` 有值 → 帶入 `q` 參數
- `limit` → `maxResults`，預設 5

**`data`（`CalendarData`）：**
```json
{
  "events": [
    {
      "event_id": "xxx",
      "title": "開會",
      "start": "2026-06-15T14:00:00+08:00",
      "end": "2026-06-15T15:00:00+08:00",
      "all_day": false,
      "description": ""
    }
  ]
}
```

---

### 3. Calendar 新增

```
POST /google/calendar/create
```

**使用欄位：** `line_user_id`, `title`, `date`, `start_time`, `end_time`, `description`, `all_day`

**Google API 對應：** `events().insert()`
- `all_day = false` → 用 `dateTime` 格式，timezone 固定 `Asia/Taipei`，`start_time`/`end_time` 皆為必填（缺 `start_time` 會回 400 `MISSING_REQUIRED_FIELD`）
- `all_day = true` → 用 `date` 格式（單日全天事件），不需要 `start_time`/`end_time`

**`data`（`CalendarData`，單一事件）：**
```json
{
  "events": [
    {
      "event_id": "xxx",
      "title": "開會",
      "start": "2026-06-15T14:00:00+08:00",
      "end": "2026-06-15T15:00:00+08:00",
      "all_day": false,
      "description": "",
      "status": "created"
    }
  ]
}
```

---

### 4. Calendar 修改

```
POST /google/calendar/modify
```

**使用欄位：** `line_user_id`, `event_id`（必填）, `title`, `date`, `start_time`, `end_time`, `description`, `all_day`

**Google API 對應：** `events().patch()`
- 直接用 request 裡帶的欄位組成新的 `Event` 送給 Google（`title`、`description`、start/end），**不會**先呼叫 `events().get(eventId)` 取原始事件內容
- `all_day = false` 時，`date` + `start_time` / `date` + `end_time` 會分別組成新的開始/結束時間，因此若只想改標題，仍需連同 `date`、`start_time`、`end_time` 一起帶入，否則會解析失敗
- `description` 未帶值時會覆蓋為空字串（不會保留原值）

**`data`（`CalendarData`，單一事件）：**
```json
{
  "events": [
    {
      "event_id": "xxx",
      "title": "新標題",
      "start": "2026-06-16T15:00:00+08:00",
      "end": "2026-06-16T16:30:00+08:00",
      "all_day": false,
      "description": "",
      "status": "updated"
    }
  ]
}
```

---

## 錯誤處理

失敗時一律回傳 `success: false`，並在 `error` 物件帶 `code`/`message`（詳見上方「統一回應格式」）。

| HTTP 狀態碼 | Error Code | 情境 |
|---|---|---|
| 401 | `GOOGLE_NOT_LINKED` | 該 line_user_id 未連結 Google 帳號 |
| 401 | `TOKEN_REFRESH_FAILED` | refresh_token 失效，需重新授權 |
| 400 | `MISSING_REQUIRED_FIELD` | 必填欄位為空（如 create 缺 title/date/start_time、modify 缺 event_id） |
| 500 | `GOOGLE_API_ERROR` | Google API 呼叫失敗，或其他未預期例外 |

---

## Python Service 呼叫方式

Python service（`HaoServiceClient` 對應的 Python 端）需新增 HTTP client，呼叫這四個 endpoint。LLM 判斷意圖後，根據意圖決定呼叫哪個 endpoint 並組出對應的 request body（Calendar 用 `GoogleCalendarApiRequest`、Mail 用 `GoogleMailApiRequest`）。

---

## 不在本次範圍內

- Calendar 刪除事件
- Gmail 傳送信件
- 多個 Google Calendar 帳號切換
