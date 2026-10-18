-- V2 migration: Align schema strictly with Nnomm specifications
-- Replaces initial placeholder structures with domain-specific invariants,
-- constraints, partial indexes, idempotency, and payment event auditing.

-- 1. Clean up V1 placeholder tables in dependency order
DROP TABLE IF EXISTS processed_webhooks CASCADE;
DROP TABLE IF EXISTS claims CASCADE;
DROP TABLE IF EXISTS deals CASCADE;

-- 2. Flash Deals Table
CREATE TABLE deals (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    description TEXT,
    price_paise BIGINT NOT NULL,
    stock_total INT NOT NULL,
    stock_available INT NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,

    -- Invariants: Positive prices, valid stock boundaries, and coherent time window
    CONSTRAINT chk_deals_price_paise_positive CHECK (price_paise > 0),
    CONSTRAINT chk_deals_stock_total_positive CHECK (stock_total > 0),
    CONSTRAINT chk_deals_stock_available_range CHECK (stock_available >= 0 AND stock_available <= stock_total),
    CONSTRAINT chk_deals_time_window CHECK (ends_at > starts_at)
);

-- 3. Deal Claims Table
CREATE TABLE claims (
    id UUID PRIMARY KEY,
    deal_id UUID NOT NULL REFERENCES deals(id),
    customer_id VARCHAR(128) NOT NULL,
    quantity INT NOT NULL,
    amount_paise BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,

    -- Invariants: Quantity strictly 1-3, positive money, and closed set of valid statuses
    CONSTRAINT chk_claims_quantity_range CHECK (quantity >= 1 AND quantity <= 3),
    CONSTRAINT chk_claims_amount_paise_positive CHECK (amount_paise > 0),
    CONSTRAINT chk_claims_status_valid CHECK (
        status IN ('RESERVED', 'PAID', 'EXPIRED', 'NEEDS_REVIEW', 'REFUND_PENDING')
    )
);

-- 4. Idempotent Requests Table
-- Stores customer requests to ensure exactly-once execution and crash recovery
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    key VARCHAR(255) NOT NULL,
    customer_id VARCHAR(128) NOT NULL,
    deal_id UUID NOT NULL REFERENCES deals(id),
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    http_status INT,
    response_body TEXT,
    claim_id UUID REFERENCES claims(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    locked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recovery_deadline TIMESTAMPTZ NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '60 SECONDS'),

    -- Invariants: Request scope uniqueness, valid quantity and request lifecycle states
    CONSTRAINT uq_idempotency_customer_key UNIQUE (customer_id, key),
    CONSTRAINT chk_idempotency_quantity_range CHECK (quantity >= 1 AND quantity <= 3),
    CONSTRAINT chk_idempotency_status_valid CHECK (
        status IN ('IN_PROGRESS', 'COMPLETED', 'FAILED')
    )
);

-- 5. Payment Events Table
-- Stores payment webhooks for deduplication, validation, and auditability
CREATE TABLE payment_events (
    id UUID PRIMARY KEY,
    event_id VARCHAR(255) NOT NULL UNIQUE,
    claim_id UUID REFERENCES claims(id),
    amount_paise BIGINT,
    status VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    error_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ,

    -- Invariants: Positive amount if present, strictly defined event status
    CONSTRAINT chk_payment_events_amount_paise_positive CHECK (amount_paise IS NULL OR amount_paise > 0),
    CONSTRAINT chk_payment_events_status_valid CHECK (
        status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'REJECTED', 'FAILED')
    )
);

-- 6. Indexes

-- Deal & Customer lookups
CREATE INDEX idx_claims_deal_id ON claims(deal_id);
CREATE INDEX idx_claims_customer_id ON claims(customer_id);
CREATE INDEX idx_claims_deal_status ON claims(deal_id, status);

-- CRITICAL: One active claim per customer per deal for RESERVED, PAID, and NEEDS_REVIEW
-- Allows customer to claim again once previous claim is EXPIRED or REFUND_PENDING
CREATE UNIQUE INDEX idx_claims_active_customer_deal ON claims (deal_id, customer_id)
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Expiry background worker index: fast retrieval of overdue reserved claims
CREATE INDEX idx_claims_expiry_processing ON claims (expires_at)
WHERE status = 'RESERVED';

-- Idempotency crash recovery index: identifies requests stuck in IN_PROGRESS past deadline
CREATE INDEX idx_idempotency_stuck_recovery ON idempotency_records (recovery_deadline)
WHERE status = 'IN_PROGRESS';

-- Payment event auditing index
CREATE INDEX idx_payment_events_claim_id ON payment_events(claim_id);
