-- Decision payloads deliberately exclude facts, evidence, descriptions and error messages.
CREATE TABLE mate_decision_record (
    id VARCHAR(36) PRIMARY KEY,
    decision_type VARCHAR(40) NOT NULL,
    workspace_id BIGINT,
    subject_id BIGINT,
    run_id BIGINT,
    conversation_id VARCHAR(128),
    attempt_id VARCHAR(128),
    phase VARCHAR(80) NOT NULL,
    question_version VARCHAR(80) NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    mode VARCHAR(16) NOT NULL,
    provider VARCHAR(80) NOT NULL,
    provider_version VARCHAR(80) NOT NULL,
    value_kind VARCHAR(16) NOT NULL,
    baseline_value VARCHAR(80) NOT NULL,
    proposed_value VARCHAR(80),
    effective_value VARCHAR(80) NOT NULL,
    confidence DOUBLE PRECISION,
    reason VARCHAR(40) NOT NULL,
    override_reason VARCHAR(40) NOT NULL,
    elapsed_ms BIGINT NOT NULL,
    create_time TIMESTAMP NOT NULL
);
CREATE INDEX idx_decision_retention ON mate_decision_record (create_time);
CREATE INDEX idx_decision_scope ON mate_decision_record (workspace_id, decision_type, subject_id, create_time);
-- Separate rows allow asynchronous proposals and committed outcomes to arrive in either order.
CREATE TABLE mate_decision_outcome (
    decision_id VARCHAR(36) PRIMARY KEY,
    outcome VARCHAR(16) NOT NULL,
    actual_value VARCHAR(80) NOT NULL,
    create_time TIMESTAMP NOT NULL
);
CREATE INDEX idx_decision_outcome_retention ON mate_decision_outcome (create_time);
