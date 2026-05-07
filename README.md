# hao-for-you

這個專案已重建為一個簡單的 Python Flask 伺服器框架。

## 主要功能

- `GET /` - 回傳伺服器狀態與簡單訊息
- `GET /health` - 健康檢查
- `POST /echo` - 回傳接收到的 JSON 內容

## 開發模式

安裝相依套件：

```bash
pip install -r requirements.txt
```

本地啟動：

```bash
python app_core.py
```

## 部署

使用 gunicorn 啟動：

```bash
gunicorn app_core:app
```

## 範例請求

```bash
curl http://127.0.0.1:5000/
```

```bash
curl http://127.0.0.1:5000/health
```

```bash
curl -X POST http://127.0.0.1:5000/echo -H 'Content-Type: application/json' -d '{"hello":"world"}'
```
