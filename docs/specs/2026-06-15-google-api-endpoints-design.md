# Design: Google Mail & Calendar API Endpoints

**Date:** 2026-06-15
**Status:** Approved

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
│   ├── MailController.java           # POST /google/mail/query
│   └── CalendarApiController.java    # POST /google/calendar/{query,create,modify}
├── service/
│   ├── MailService.java              # 執行 Gmail API 呼叫
│   └── CalendarApiService.java       # 執行 Calendar API 呼叫
├── dto/
│   ├── GoogleApiRequest.java         # 四個 endpoint 共用的 request body
│   ├── MailQueryResponse.java
│   └── CalendarResponse.java
└── service/
    └── GoogleAuthService.java        # 新增 buildGmailService()
```

---

## 統一 Request Body

所有四個 endpoint 共用 `GoogleApiRequest`，各 endpoint 只取自己需要的欄位，其餘帶空值即可。

```json
{
  "line_user_id": "U123",

  "event_id": "",
  "title": "",
  "date": "",
  "start_time": "",
  "duration_minutes": 0,
  "description": "",
  "all_day": false,

  "keyword": "",
  "from": "",
  "unread_only": false,
  "limit": 5
}
```

| 欄位 | 型別 | 用途 |
|---|---|---|
| `line_user_id` | String | 必填，用來查 DB 取 Google token |
| `event_id` | String | Calendar modify 時必填 |
| `title` | String | Calendar 事件標題（對應 Google `summary`） |
| `date` | String | 格式 `YYYY-MM-DD` |
| `start_time` | String | 格式 `HH:MM`（24小時制） |
| `duration_minutes` | Integer | 持續分鐘數，預設 60 |
| `description` | String | Calendar 事件描述 |
| `all_day` | Boolean | 全天事件，預設 false |
| `keyword` | String | Gmail/Calendar 搜尋關鍵字 |
| `from` | String | Gmail 寄件者篩選 |
| `unread_only` | Boolean | Gmail 只看未讀，預設 false |
| `limit` | Integer | 回傳筆數，預設 5 |

---

## Endpoints

### 1. Gmail 查詢

```
POST /google/mail/query
```

**使用欄位：** `line_user_id`, `keyword`, `from`, `unread_only`, `limit`

**Google API 對應：**
1. `messages().list()` — 以 `q` 參數搜尋（組合 keyword / from / unread_only）
2. `messages().get()` — 取每封信的 subject、from、date、snippet

**Response：**
```json
{
  "messages": [
    {
      "id": "xxx",
      "subject": "明天開會通知",
      "from": "boss@gmail.com",
      "date": "2026-06-15",
      "snippet": "請準時出席..."
    }
  ],
  "total": 3
}
```

---

### 2. Calendar 查詢

```
POST /google/calendar/query
```

**使用欄位：** `line_user_id`, `date`, `limit`, `keyword`

**Google API 對應：** `events().list()`
- `date` 有值 → `timeMin` / `timeMax` 限定當天
- `date` 為空 → 從現在起往後查
- `keyword` 有值 → 帶入 `q` 參數
- `limit` → `maxResults`

**Response：**
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

**使用欄位：** `line_user_id`, `title`, `date`, `start_time`, `duration_minutes`, `description`, `all_day`

**Google API 對應：** `events().insert()`
- `all_day = false` → 用 `dateTime` 格式，timezone 固定 `Asia/Taipei`
- `all_day = true` → 用 `date` 格式
- `duration_minutes` 預設 60，加上 `start_time` 計算 `end`

**Response：**
```json
{
  "event_id": "xxx",
  "title": "開會",
  "start": "2026-06-15T14:00:00+08:00",
  "end": "2026-06-15T15:00:00+08:00",
  "status": "created"
}
```

---

### 4. Calendar 修改

```
POST /google/calendar/modify
```

**使用欄位：** `line_user_id`, `event_id`（必填）, `title`, `date`, `start_time`, `duration_minutes`, `description`

**Google API 對應：** `events().patch()`
- 只傳非空欄位到 Google，未傳的欄位保留原值
- 修改前先 `events().get(eventId)` 取原始事件，計算新的 end time

**Response：**
```json
{
  "event_id": "xxx",
  "title": "新標題",
  "start": "2026-06-16T15:00:00+08:00",
  "end": "2026-06-16T16:30:00+08:00",
  "status": "updated"
}
```

---

## 錯誤處理

所有 endpoint 共用錯誤格式：

```json
{
  "error": "ERROR_CODE",
  "message": "說明文字"
}
```

| HTTP 狀態碼 | Error Code | 情境 |
|---|---|---|
| 404 | `GOOGLE_NOT_LINKED` | 該 line_user_id 未連結 Google 帳號 |
| 401 | `TOKEN_REFRESH_FAILED` | refresh_token 失效，需重新授權 |
| 400 | `MISSING_REQUIRED_FIELD` | 必填欄位為空（如 modify 缺 event_id） |
| 500 | `GOOGLE_API_ERROR` | Google API 回傳錯誤 |

---

## Python Service 呼叫方式

Python service（`HaoServiceClient` 對應的 Python 端）需新增 HTTP client，呼叫這四個 endpoint。LLM 判斷意圖後，根據意圖決定呼叫哪個 endpoint 並組出 `GoogleApiRequest`。

---

## 不在本次範圍內

- Calendar 刪除事件
- Gmail 傳送信件
- Gmail 讀取完整信件內文（只回傳 snippet）
- 多個 Google Calendar 帳號切換
