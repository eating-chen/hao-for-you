BEGIN;

-- The old constraint accidentally made `platform` unique by itself, which
-- allowed only one user per platform. Identity is the platform/user_id pair.
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS idx_users_platform_user;

-- Older schemas also made user_id globally unique. Remove that constraint so
-- two platforms may use the same provider-specific identifier.
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_user_id_key;

ALTER TABLE users
    ADD CONSTRAINT idx_users_platform_user UNIQUE (platform, user_id);

COMMIT;
