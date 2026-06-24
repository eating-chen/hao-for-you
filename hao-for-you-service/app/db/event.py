# import sqlite3


# DB_PATH = "chat_memory.db"


# def get_conn(db_path: str = "chat_memory.db") -> sqlite3.Connection:
#     conn = sqlite3.connect(db_path)
#     conn.row_factory = sqlite3.Row
#     return conn


# def init_db(conn: sqlite3.Connection) -> None:
#     conn.execute(
#         """
#         CREATE TABLE IF NOT EXISTS users (
#             user_id TEXT PRIMARY KEY,
#             display_name TEXT,
#             created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
#             updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
#         );
#         """
#     )

#     conn.execute(
#         """
#         CREATE TABLE IF NOT EXISTS sessions (
#             session_id TEXT PRIMARY KEY,
#             user_id TEXT NOT NULL,
#             summary TEXT DEFAULT '',
#             created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
#             updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
#             FOREIGN KEY(user_id) REFERENCES users(user_id)
#         );
#         """
#     )

#     conn.execute(
#         """
#         CREATE TABLE IF NOT EXISTS messages (
#             id INTEGER PRIMARY KEY AUTOINCREMENT,
#             session_id TEXT NOT NULL,
#             role TEXT NOT NULL CHECK(role IN ('human', 'ai')),
#             content TEXT NOT NULL,
#             created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
#             FOREIGN KEY(session_id) REFERENCES sessions(session_id)
#         );
#         """
#     )

#     conn.execute(
#         """
#         CREATE INDEX IF NOT EXISTS idx_messages_session_id_id
#         ON messages(session_id, id);
#         """
#     )

#     conn.commit()