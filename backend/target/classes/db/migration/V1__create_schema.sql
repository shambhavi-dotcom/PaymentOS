CREATE TABLE payments (
    id BIGSERIAL PRIMARY KEY,
    merchant_id VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_fingerprint VARCHAR(512) NOT NULL,
    amount_minor BIGINT NOT NULL,
    currency VARCHAR(10) NOT NULL,
    customer_reference VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    selected_processor_id VARCHAR(100),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_payments_merchant_idempotency UNIQUE (merchant_id, idempotency_key)
);

CREATE INDEX idx_payments_merchant_id ON payments (merchant_id);
CREATE INDEX idx_payments_status ON payments (status);
CREATE INDEX idx_payments_created_at ON payments (created_at);

CREATE TABLE processors (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    base_latency_ms INTEGER NOT NULL,
    failure_probability DOUBLE PRECISION NOT NULL DEFAULT 0,
    timeout_probability DOUBLE PRECISION NOT NULL DEFAULT 0,
    recent_success_rate DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    recent_latency_ms BIGINT NOT NULL DEFAULT 150,
    circuit_state VARCHAR(20) NOT NULL DEFAULT 'CLOSED',
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_failure_at TIMESTAMP WITH TIME ZONE,
    last_success_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE payment_attempts (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    processor_id BIGINT,
    processor_request_id VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL,
    failure_reason VARCHAR(255),
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_ms BIGINT,
    CONSTRAINT uk_payment_attempts_processor_request UNIQUE (processor_request_id)
);

CREATE TABLE payment_events (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL REFERENCES payments(id),
    event_type VARCHAR(100) NOT NULL,
    previous_status VARCHAR(30),
    new_status VARCHAR(30),
    description VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_payment_attempts_payment_id ON payment_attempts (payment_id);
CREATE INDEX idx_payment_events_payment_id ON payment_events (payment_id);
