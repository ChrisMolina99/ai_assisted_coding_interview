CREATE TABLE IF NOT EXISTS businesses (
    id UUID PRIMARY KEY,
    display_name VARCHAR(120) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS accounts (
    id UUID PRIMARY KEY,
    business_id UUID NOT NULL UNIQUE REFERENCES businesses(id),
    currency VARCHAR(3) NOT NULL CHECK(currency='USD'),
    opening_balance_in_cents BIGINT NOT NULL CHECK(opening_balance_in_cents>=0),
    balance_in_cents BIGINT NOT NULL CHECK(balance_in_cents>=0),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS transfers (
    id UUID PRIMARY KEY,
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    recipient_account_id UUID NOT NULL REFERENCES accounts(id),
    amount_in_cents BIGINT NOT NULL CHECK(amount_in_cents>0 AND amount_in_cents<=1000000000),
    currency VARCHAR(3) NOT NULL CHECK(currency='USD'),
    reason VARCHAR(280),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK(sender_account_id<>recipient_account_id)
);
CREATE INDEX IF NOT EXISTS transfer_sender_history ON transfers(sender_account_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS transfer_recipient_history ON transfers(recipient_account_id,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS transfer_requests (
    id UUID PRIMARY KEY,
    sender_account_id UUID NOT NULL REFERENCES accounts(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    outcome VARCHAR(16),
    transfer_id UUID REFERENCES transfers(id),
    http_status INTEGER,
    response_payload CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(sender_account_id,idempotency_key)
);
