-- ─────────────────────────────────────────────────────────────────────────────
-- V1 – Initial schema for Crescent Pharmacy Payment Idempotency Service
-- ─────────────────────────────────────────────────────────────────────────────

-- Needed for gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ── Idempotency keys (one row per unique payment intent) ─────────────────────
CREATE TABLE idempotency_keys (
    id                UUID         NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key   VARCHAR(255) NOT NULL,
    merchant_id       VARCHAR(255) NOT NULL DEFAULT 'default',
    customer_id       VARCHAR(255) NOT NULL,
    amount            NUMERIC(12, 2) NOT NULL,
    currency          VARCHAR(10)  NOT NULL,
    payment_method    VARCHAR(50),
    -- PENDING → payment in flight; COMPLETED → success; FAILED → declined / error
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    payment_reference VARCHAR(255),          -- Reference returned by the processor
    failure_reason    TEXT,
    response_payload  TEXT,                  -- Full processor response (JSON)
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at      TIMESTAMPTZ,
    expires_at        TIMESTAMPTZ  NOT NULL, -- Key is valid until this instant

    CONSTRAINT pk_idempotency_keys            PRIMARY KEY (id),
    -- The critical guard: the same key cannot be used twice for the same merchant
    CONSTRAINT uq_idempotency_key_merchant    UNIQUE (idempotency_key, merchant_id)
);

CREATE INDEX idx_idem_expires_at  ON idempotency_keys (expires_at);
CREATE INDEX idx_idem_merchant_ts ON idempotency_keys (merchant_id, created_at DESC);
CREATE INDEX idx_idem_customer    ON idempotency_keys (customer_id);
CREATE INDEX idx_idem_status      ON idempotency_keys (status);

-- ── Audit log (every event: new payment, duplicate blocked, completed, failed) ─
CREATE TABLE payment_audit_log (
    id              UUID        NOT NULL DEFAULT gen_random_uuid(),
    idempotency_key VARCHAR(255) NOT NULL,
    merchant_id     VARCHAR(255) NOT NULL DEFAULT 'default',
    event_type      VARCHAR(50) NOT NULL,   -- NEW_PAYMENT | DUPLICATE_BLOCKED | PAYMENT_COMPLETED | PAYMENT_FAILED | KEY_EXPIRED_REUSE
    customer_id     VARCHAR(255),
    amount          NUMERIC(12, 2),
    currency        VARCHAR(10),
    ip_address      VARCHAR(45),
    user_agent      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_payment_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_audit_idem_key  ON payment_audit_log (idempotency_key, merchant_id);
CREATE INDEX idx_audit_created   ON payment_audit_log (created_at);
CREATE INDEX idx_audit_event     ON payment_audit_log (event_type);
CREATE INDEX idx_audit_merchant  ON payment_audit_log (merchant_id, created_at DESC);
