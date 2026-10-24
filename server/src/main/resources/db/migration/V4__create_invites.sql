-- Invites belong to their accounts: deleting either the creator or the user who
-- redeemed an invite removes it. A deleted invite's token is simply unknown.
CREATE TABLE invites (
	id UUID PRIMARY KEY,
	token_hash VARCHAR(64) NOT NULL UNIQUE,
	created_by UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
	expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
	used_by UUID REFERENCES users (id) ON DELETE CASCADE,
	used_at TIMESTAMP WITH TIME ZONE,
	CONSTRAINT invites_token_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
	CONSTRAINT invites_usage_consistent CHECK ((used_by IS NULL) = (used_at IS NULL))
);
