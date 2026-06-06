CREATE TABLE IF NOT EXISTS mm_user (
  user_id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  roles VARCHAR(512) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS audit_event (
  event_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64),
  user_id VARCHAR(64),
  actor_type VARCHAR(32) NOT NULL DEFAULT 'USER',
  action VARCHAR(128) NOT NULL,
  resource_type VARCHAR(128),
  resource_id VARCHAR(256),
  trace_id VARCHAR(64),
  request_id VARCHAR(64),
  before_json MEDIUMTEXT,
  after_json MEDIUMTEXT,
  ip_address VARCHAR(64),
  user_agent VARCHAR(512),
  created_at VARCHAR(64) NOT NULL,
  INDEX idx_audit_event_created (created_at),
  INDEX idx_audit_event_tenant (tenant_id),
  INDEX idx_audit_event_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS portfolio (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  nav DECIMAL(20, 4) NOT NULL,
  return_pct DECIMAL(10, 4) NOT NULL,
  assets INT NOT NULL,
  transactions INT NOT NULL,
  option_combos INT NOT NULL,
  updated_at VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS portfolio_trade (
  trade_id VARCHAR(64) PRIMARY KEY,
  portfolio_id VARCHAR(64),
  trade_date VARCHAR(32) NOT NULL,
  settlement_date VARCHAR(32),
  symbol VARCHAR(32) NOT NULL,
  exchange VARCHAR(32),
  market VARCHAR(32),
  security_name VARCHAR(200),
  side VARCHAR(8) NOT NULL,
  quantity DECIMAL(24, 8) NOT NULL,
  price DECIMAL(24, 8) NOT NULL,
  gross_amount DECIMAL(24, 8) NOT NULL,
  fee DECIMAL(24, 8) NOT NULL DEFAULT 0,
  tax DECIMAL(24, 8) NOT NULL DEFAULT 0,
  commission DECIMAL(24, 8) NOT NULL DEFAULT 0,
  other_fee DECIMAL(24, 8) NOT NULL DEFAULT 0,
  net_amount DECIMAL(24, 8) NOT NULL,
  currency VARCHAR(16) NOT NULL DEFAULT 'USD',
  fx_rate DECIMAL(24, 8) NOT NULL DEFAULT 1,
  broker VARCHAR(128),
  account_no VARCHAR(128),
  strategy VARCHAR(128),
  trade_type VARCHAR(64),
  order_type VARCHAR(64),
  notes TEXT,
  source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
  import_batch_id VARCHAR(64),
  created_at VARCHAR(32) NOT NULL,
  updated_at VARCHAR(32) NOT NULL,
  INDEX idx_portfolio_trade_portfolio (portfolio_id),
  INDEX idx_portfolio_trade_symbol_date (symbol, trade_date),
  INDEX idx_portfolio_trade_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_definition (
  workflow_key VARCHAR(128) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  engine VARCHAR(32) NOT NULL DEFAULT 'langgraph',
  version INT NOT NULL,
  nodes INT NOT NULL,
  edges INT NOT NULL,
  readonly_flag TINYINT NOT NULL DEFAULT 1,
  owner_username VARCHAR(128),
  updated_at VARCHAR(32) NOT NULL DEFAULT '',
  trigger_keywords VARCHAR(1024),
  routing_description VARCHAR(512)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_layout (
  workflow_key VARCHAR(128) PRIMARY KEY,
  layout_json MEDIUMTEXT NOT NULL,
  updated_at VARCHAR(32) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_version (
  version_id VARCHAR(64) PRIMARY KEY,
  workflow_key VARCHAR(128) NOT NULL,
  version INT NOT NULL,
  layout_json MEDIUMTEXT NOT NULL,
  validation_json MEDIUMTEXT,
  published_by VARCHAR(128),
  published_at VARCHAR(32) NOT NULL,
  INDEX idx_workflow_version_key (workflow_key, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_run (
  run_id VARCHAR(64) PRIMARY KEY,
  workflow_key VARCHAR(128) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  subject VARCHAR(256),
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  result_json MEDIUMTEXT,
  error_message TEXT,
  idempotency_key VARCHAR(128),
  workflow_version_id VARCHAR(64),
  inputs_json MEDIUMTEXT,
  control_status VARCHAR(32),
  pause_requested TINYINT NOT NULL DEFAULT 0,
  cancel_requested TINYINT NOT NULL DEFAULT 0,
  queued_at VARCHAR(32),
  node_count INT NOT NULL DEFAULT 0,
  INDEX idx_workflow_run_key (workflow_key),
  INDEX idx_workflow_run_status (status, queued_at),
  INDEX idx_workflow_run_idempotency (workflow_key, subject, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_node_run (
  node_run_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(128) NOT NULL,
  node_name VARCHAR(200) NOT NULL,
  node_type VARCHAR(32) NOT NULL,
  agent_id VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  input_json MEDIUMTEXT,
  output_json MEDIUMTEXT,
  error_message TEXT,
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  sort_order INT NOT NULL DEFAULT 0,
  attempt INT NOT NULL DEFAULT 1,
  max_attempts INT NOT NULL DEFAULT 1,
  retry_policy_json MEDIUMTEXT,
  timeout_ms INT,
  INDEX idx_workflow_node_run_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS workflow_run_event (
  event_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  node_run_id VARCHAR(64),
  node_id VARCHAR(128),
  status VARCHAR(32),
  message TEXT,
  payload_json MEDIUMTEXT,
  created_at VARCHAR(32) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  INDEX idx_workflow_run_event_run (run_id, sort_order)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_template (
  agent_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  category VARCHAR(64) NOT NULL,
  tags VARCHAR(512) NOT NULL,
  prompt TEXT,
  model_name VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash',
  tools_json TEXT,
  status VARCHAR(32) NOT NULL DEFAULT 'IDLE',
  schedule_cron VARCHAR(128),
  last_run_at VARCHAR(32),
  input_count INT NOT NULL,
  output_count INT NOT NULL,
  tool_count INT NOT NULL,
  system_preset TINYINT NOT NULL DEFAULT 0,
  readonly_flag TINYINT NOT NULL DEFAULT 0,
  owner_username VARCHAR(128),
  sort_order INT NOT NULL DEFAULT 100,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at VARCHAR(32) NOT NULL DEFAULT ''
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS backtest_run (
  id VARCHAR(64) PRIMARY KEY,
  run_name VARCHAR(200) NOT NULL,
  strategy VARCHAR(200) NOT NULL,
  status VARCHAR(32) NOT NULL,
  total_return_pct DECIMAL(10, 4) NOT NULL,
  sharpe DECIMAL(10, 4) NOT NULL,
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  workflow_run_id VARCHAR(64),
  trace_id VARCHAR(64),
  subject VARCHAR(256),
  symbol VARCHAR(32),
  inputs_json MEDIUMTEXT,
  result_json MEDIUMTEXT,
  error_message TEXT,
  node_count INT NOT NULL DEFAULT 0,
  final_recommendation TEXT,
  confidence DECIMAL(10, 4) NOT NULL DEFAULT 0,
  INDEX idx_backtest_workflow_run (workflow_run_id),
  INDEX idx_backtest_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agent_call_span (
  span_id VARCHAR(64) PRIMARY KEY,
  trace_id VARCHAR(64) NOT NULL,
  workflow_run_id VARCHAR(64),
  node_run_id VARCHAR(64),
  backtest_run_id VARCHAR(64),
  parent_span_id VARCHAR(64),
  span_type VARCHAR(32) NOT NULL,
  agent_id VARCHAR(64),
  tool_name VARCHAR(128),
  model_name VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  input_json MEDIUMTEXT,
  output_json MEDIUMTEXT,
  error_message TEXT,
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  latency_ms BIGINT,
  prompt_tokens INT,
  completion_tokens INT,
  total_tokens INT,
  sort_order INT NOT NULL DEFAULT 0,
  INDEX idx_agent_span_workflow_run (workflow_run_id),
  INDEX idx_agent_span_trace (trace_id),
  INDEX idx_agent_span_node_run (node_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS model_config (
  model_config_id VARCHAR(64) PRIMARY KEY,
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  context_window INT NOT NULL DEFAULT 0,
  prompt_token_cost_usd DECIMAL(18, 10) NOT NULL DEFAULT 0,
  completion_token_cost_usd DECIMAL(18, 10) NOT NULL DEFAULT 0,
  fallback_model VARCHAR(128),
  created_at VARCHAR(32) NOT NULL,
  INDEX idx_model_config_provider_model (provider, model_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS llm_call (
  llm_call_id VARCHAR(64) PRIMARY KEY,
  workflow_run_id VARCHAR(64) NOT NULL,
  node_run_id VARCHAR(64),
  trace_id VARCHAR(64),
  provider VARCHAR(64),
  model_name VARCHAR(128),
  status VARCHAR(32) NOT NULL,
  prompt_tokens INT,
  completion_tokens INT,
  total_tokens INT,
  estimated_cost_usd DECIMAL(18, 10) NOT NULL DEFAULT 0,
  started_at VARCHAR(32),
  completed_at VARCHAR(32),
  INDEX idx_llm_call_workflow_run (workflow_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS evidence_item (
  evidence_id VARCHAR(64) PRIMARY KEY,
  workflow_run_id VARCHAR(64) NOT NULL,
  node_run_id VARCHAR(64),
  source_type VARCHAR(64) NOT NULL,
  title VARCHAR(512) NOT NULL,
  url VARCHAR(1024),
  trust_tier VARCHAR(32) NOT NULL,
  summary TEXT,
  retrieved_at VARCHAR(32) NOT NULL,
  INDEX idx_evidence_workflow_run (workflow_run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS recommendation (
  recommendation_id VARCHAR(64) PRIMARY KEY,
  workflow_run_id VARCHAR(64) NOT NULL,
  backtest_run_id VARCHAR(64),
  trace_id VARCHAR(64),
  symbol VARCHAR(32),
  recommendation VARCHAR(32) NOT NULL,
  confidence DECIMAL(10, 4) NOT NULL DEFAULT 0,
  time_horizon VARCHAR(64),
  rationale_json MEDIUMTEXT,
  risk_json MEDIUMTEXT,
  missing_data_json MEDIUMTEXT,
  disclaimer TEXT,
  approval_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  created_at VARCHAR(32) NOT NULL,
  INDEX idx_recommendation_workflow_run (workflow_run_id),
  INDEX idx_recommendation_symbol (symbol)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS macro_quadrant (
  as_of_date VARCHAR(32) PRIMARY KEY,
  china INT NOT NULL,
  usa INT NOT NULL,
  europe INT NOT NULL,
  japan INT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS credit_impulse (
  period VARCHAR(32) PRIMARY KEY,
  china VARCHAR(64) NOT NULL,
  usa VARCHAR(64) NOT NULL,
  europe VARCHAR(64) NOT NULL,
  japan VARCHAR(64) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS market_indicator (
  name VARCHAR(64) PRIMARY KEY,
  value VARCHAR(64) NOT NULL,
  subtitle VARCHAR(128) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS market_snapshot (
  symbol VARCHAR(32) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  change_pct DECIMAL(10, 4) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_thread (
  thread_id VARCHAR(64) PRIMARY KEY,
  title VARCHAR(200) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS chat_message (
  message_id VARCHAR(64) PRIMARY KEY,
  thread_id VARCHAR(64) NOT NULL,
  role VARCHAR(32) NOT NULL,
  content TEXT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_chat_message_thread (thread_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
