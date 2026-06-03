-- PostgreSQL schema for hao-for-you Java project
-- Removed conversation persistence; only user and Google token tables remain.

CREATE TABLE users (
  id BIGSERIAL PRIMARY KEY,
  line_user_id VARCHAR(50) NOT NULL UNIQUE,
  display_name VARCHAR(100),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE google_tokens (
  id BIGSERIAL PRIMARY KEY,
  line_user_id VARCHAR(50) NOT NULL UNIQUE,
  access_token TEXT NOT NULL,
  refresh_token TEXT NOT NULL,
  token_expiry TIMESTAMPTZ,
  google_email VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ
);

-- Optional foreign-key-style consistency check if you want to enforce the relationship in application logic:
-- ALTER TABLE google_tokens
--   ADD CONSTRAINT fk_google_tokens_users
--   FOREIGN KEY (line_user_id) REFERENCES users(line_user_id);
