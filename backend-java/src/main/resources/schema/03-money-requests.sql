CREATE TABLE IF NOT EXISTS money_requests (
    id UUID PRIMARY KEY,
    requester_account_id UUID NOT NULL REFERENCES accounts(id),
    payer_account_id UUID NOT NULL REFERENCES accounts(id),
    amount_in_cents BIGINT NOT NULL CHECK(amount_in_cents BETWEEN 1 AND 1000000000),
    currency VARCHAR(3) NOT NULL CHECK(currency='USD'),
    reason VARCHAR(280),
    status VARCHAR(16) NOT NULL CHECK(status IN ('PENDING','PAID','DECLINED','CANCELLED')),
    transfer_id UUID UNIQUE REFERENCES transfers(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CHECK(requester_account_id<>payer_account_id),
    CHECK((status='PAID' AND transfer_id IS NOT NULL) OR (status<>'PAID' AND transfer_id IS NULL)),
    CHECK((status='PENDING' AND resolved_at IS NULL) OR (status<>'PENDING' AND resolved_at IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS request_incoming_history ON money_requests(payer_account_id,created_at DESC,id DESC);
CREATE INDEX IF NOT EXISTS request_outgoing_history ON money_requests(requester_account_id,created_at DESC,id DESC);
CREATE TABLE IF NOT EXISTS money_request_actions (
    id UUID PRIMARY KEY,
    actor_account_id UUID NOT NULL REFERENCES accounts(id),
    action VARCHAR(16) NOT NULL CHECK(action IN ('create','pay','decline','cancel')),
    idempotency_key VARCHAR(128) NOT NULL,
    request_fingerprint VARCHAR(64) NOT NULL,
    http_status INTEGER,
    response_payload CLOB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(actor_account_id,action,idempotency_key)
);
