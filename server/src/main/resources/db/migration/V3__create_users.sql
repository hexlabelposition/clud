CREATE TABLE users (
	id UUID PRIMARY KEY,
	username VARCHAR(64) NOT NULL,
	email VARCHAR(254),
	password_hash VARCHAR(255) NOT NULL,
	role VARCHAR(16) NOT NULL,
	quota_bytes BIGINT,
	disabled BOOLEAN NOT NULL DEFAULT FALSE,
	created_at TIMESTAMP WITH TIME ZONE NOT NULL,
	CONSTRAINT users_username_format CHECK (username ~ '^[A-Za-z0-9._-]+$'),
	CONSTRAINT users_password_hash_not_blank CHECK (password_hash ~ '[^[:space:]]'),
	CONSTRAINT users_role_valid CHECK (role IN ('USER', 'ADMIN')),
	CONSTRAINT users_quota_non_negative CHECK (quota_bytes >= 0)
);

CREATE UNIQUE INDEX users_username_unique ON users (lower(username));

COMMENT ON COLUMN users.quota_bytes IS 'Storage limit in bytes; NULL means unlimited, zero allows no storage.';
