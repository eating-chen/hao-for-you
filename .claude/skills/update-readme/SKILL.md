# Skill: Update README

## 用途
自動掃描專案程式碼，更新 README.md 中指定的區段內容。

## 觸發時機
- 使用者要求更新 README
- 有新功能、新 API endpoint、新環境變數被加入時

---

## 執行步驟

### 1. 確認要更新哪些區段

執行 'git diff' 或查看最近的 commit，確認有哪些新功能或變更需要反映在 README 中。

### 2. 更新內容

1. .env檔：比對.env檔內容與README中的環境變數區段，新增任何缺少的變數。
2. API endpoints：比對程式碼中的 API 定義（如 Spring Controller 中的 @GetMapping、@PostMapping 等註解）與 README 中的 API 區段，新增任何缺少的 API 路徑、方法和說明。

### 3. 輸出格式

每個要調整的區段或內容需按照原本的格式做新增：
 
1. 環境變數區段
| 變數名稱 | 必填 | 說明 |
|---|---|---|
| `LINE_CHANNEL_SECRET` | ✅ | LINE Bot Channel Secret |

2. API endpoints 區段
| Method | Path | 說明 |
|---|---|---|
| GET | `/health` | 健康檢查 |

### 4. 寫入規則

- **只替換** 環境變數或 API endpoints 區段內的內容，**不動** 區段標題或表格結構
- 剩餘比對後判斷需要做修正的地方，都先詢問，確認完再做修正
- 完成後告知更新了哪些區段、變動了哪些內容

---

## 注意事項

- 若 README.md 不存在，先詢問是否要建立
- 若某個區段的來源檔案不存在，跳過該區段並回報
