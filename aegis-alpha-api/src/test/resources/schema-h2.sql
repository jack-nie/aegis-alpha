DROP TABLE IF EXISTS chat_message;
DROP TABLE IF EXISTS chat_thread;
DROP TABLE IF EXISTS market_snapshot;
DROP TABLE IF EXISTS market_indicator;
DROP TABLE IF EXISTS credit_impulse;
DROP TABLE IF EXISTS macro_quadrant;
DROP TABLE IF EXISTS agent_call_span;
DROP TABLE IF EXISTS recommendation;
DROP TABLE IF EXISTS evidence_item;
DROP TABLE IF EXISTS llm_call;
DROP TABLE IF EXISTS model_config;
DROP TABLE IF EXISTS backtest_run;
DROP TABLE IF EXISTS agent_template;
DROP TABLE IF EXISTS workflow_node_run;
DROP TABLE IF EXISTS workflow_run_event;
DROP TABLE IF EXISTS workflow_run;
DROP TABLE IF EXISTS workflow_version;
DROP TABLE IF EXISTS workflow_layout;
DROP TABLE IF EXISTS workflow_definition;
DROP TABLE IF EXISTS portfolio_trade;
DROP TABLE IF EXISTS portfolio;
DROP TABLE IF EXISTS audit_event;
DROP TABLE IF EXISTS mm_user;

CREATE TABLE mm_user (
  user_id VARCHAR(64) PRIMARY KEY,
  username VARCHAR(128) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  roles VARCHAR(512) NOT NULL
);
CREATE TABLE audit_event (
  event_id VARCHAR(64) PRIMARY KEY,
  tenant_id VARCHAR(64),
  user_id VARCHAR(64),
  actor_type VARCHAR(32) NOT NULL DEFAULT 'USER',
  action VARCHAR(128) NOT NULL,
  resource_type VARCHAR(128),
  resource_id VARCHAR(256),
  trace_id VARCHAR(64),
  request_id VARCHAR(64),
  before_json TEXT,
  after_json TEXT,
  ip_address VARCHAR(64),
  user_agent VARCHAR(512),
  created_at VARCHAR(64) NOT NULL
);
CREATE TABLE portfolio (
  id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  nav DECIMAL(20, 4) NOT NULL,
  return_pct DECIMAL(10, 4) NOT NULL,
  assets INT NOT NULL,
  transactions INT NOT NULL,
  option_combos INT NOT NULL,
  updated_at VARCHAR(32) NOT NULL
);
CREATE TABLE portfolio_trade (
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
  updated_at VARCHAR(32) NOT NULL
);
CREATE TABLE workflow_definition (
  workflow_key VARCHAR(128) PRIMARY KEY,
  name VARCHAR(200) NOT NULL,
  description VARCHAR(512) NOT NULL DEFAULT '',
  engine VARCHAR(32) NOT NULL DEFAULT 'langgraph',
  version INT NOT NULL,
  nodes INT NOT NULL,
  edges INT NOT NULL,
  readonly_flag TINYINT NOT NULL DEFAULT 1,
  owner_username VARCHAR(128),
  updated_at VARCHAR(32) NOT NULL DEFAULT ''
);
CREATE TABLE workflow_layout (
  workflow_key VARCHAR(128) PRIMARY KEY,
  layout_json TEXT NOT NULL,
  updated_at VARCHAR(32) NOT NULL
);
CREATE TABLE workflow_version (
  version_id VARCHAR(64) PRIMARY KEY,
  workflow_key VARCHAR(128) NOT NULL,
  version INT NOT NULL,
  layout_json TEXT NOT NULL,
  validation_json TEXT,
  published_by VARCHAR(128),
  published_at VARCHAR(32) NOT NULL
);
CREATE TABLE workflow_run (
  run_id VARCHAR(64) PRIMARY KEY,
  workflow_key VARCHAR(128) NOT NULL,
  trace_id VARCHAR(64) NOT NULL,
  status VARCHAR(32) NOT NULL,
  subject VARCHAR(256),
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  result_json TEXT,
  error_message TEXT,
  idempotency_key VARCHAR(128),
  workflow_version_id VARCHAR(64),
  inputs_json TEXT,
  control_status VARCHAR(32),
  pause_requested TINYINT NOT NULL DEFAULT 0,
  cancel_requested TINYINT NOT NULL DEFAULT 0,
  queued_at VARCHAR(32),
  node_count INT NOT NULL DEFAULT 0
);
CREATE TABLE workflow_node_run (
  node_run_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  node_id VARCHAR(128) NOT NULL,
  node_name VARCHAR(200) NOT NULL,
  node_type VARCHAR(32) NOT NULL,
  agent_id VARCHAR(64),
  status VARCHAR(32) NOT NULL,
  input_json TEXT,
  output_json TEXT,
  error_message TEXT,
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  sort_order INT NOT NULL DEFAULT 0,
  attempt INT NOT NULL DEFAULT 1,
  max_attempts INT NOT NULL DEFAULT 1,
  retry_policy_json TEXT,
  timeout_ms INT
);
CREATE TABLE workflow_run_event (
  event_id VARCHAR(64) PRIMARY KEY,
  run_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  node_run_id VARCHAR(64),
  node_id VARCHAR(128),
  status VARCHAR(32),
  message TEXT,
  payload_json TEXT,
  created_at VARCHAR(32) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE agent_template (
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
  updated_at VARCHAR(32) NOT NULL DEFAULT ''
);
CREATE TABLE backtest_run (
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
  inputs_json TEXT,
  result_json TEXT,
  error_message TEXT,
  node_count INT NOT NULL DEFAULT 0,
  final_recommendation TEXT,
  confidence DECIMAL(10, 4) NOT NULL DEFAULT 0
);
CREATE TABLE agent_call_span (
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
  input_json TEXT,
  output_json TEXT,
  error_message TEXT,
  started_at VARCHAR(32) NOT NULL,
  completed_at VARCHAR(32),
  latency_ms BIGINT,
  prompt_tokens INT,
  completion_tokens INT,
  total_tokens INT,
  sort_order INT NOT NULL DEFAULT 0
);
CREATE TABLE model_config (
  model_config_id VARCHAR(64) PRIMARY KEY,
  provider VARCHAR(64) NOT NULL,
  model_name VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  context_window INT NOT NULL DEFAULT 0,
  prompt_token_cost_usd DECIMAL(18, 10) NOT NULL DEFAULT 0,
  completion_token_cost_usd DECIMAL(18, 10) NOT NULL DEFAULT 0,
  fallback_model VARCHAR(128),
  created_at VARCHAR(32) NOT NULL
);
CREATE TABLE llm_call (
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
  completed_at VARCHAR(32)
);
CREATE TABLE evidence_item (
  evidence_id VARCHAR(64) PRIMARY KEY,
  workflow_run_id VARCHAR(64) NOT NULL,
  node_run_id VARCHAR(64),
  source_type VARCHAR(64) NOT NULL,
  title VARCHAR(512) NOT NULL,
  url VARCHAR(1024),
  trust_tier VARCHAR(32) NOT NULL,
  summary TEXT,
  retrieved_at VARCHAR(32) NOT NULL
);
CREATE TABLE recommendation (
  recommendation_id VARCHAR(64) PRIMARY KEY,
  workflow_run_id VARCHAR(64) NOT NULL,
  backtest_run_id VARCHAR(64),
  trace_id VARCHAR(64),
  symbol VARCHAR(32),
  recommendation VARCHAR(32) NOT NULL,
  confidence DECIMAL(10, 4) NOT NULL DEFAULT 0,
  time_horizon VARCHAR(64),
  rationale_json TEXT,
  risk_json TEXT,
  missing_data_json TEXT,
  disclaimer TEXT,
  approval_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
  created_at VARCHAR(32) NOT NULL
);
CREATE TABLE macro_quadrant (as_of_date VARCHAR(32) PRIMARY KEY, china INT, usa INT, europe INT, japan INT);
CREATE TABLE credit_impulse (period VARCHAR(32) PRIMARY KEY, china VARCHAR(64), usa VARCHAR(64), europe VARCHAR(64), japan VARCHAR(64));
CREATE TABLE market_indicator (name VARCHAR(64) PRIMARY KEY, value VARCHAR(64), subtitle VARCHAR(128));
CREATE TABLE market_snapshot (symbol VARCHAR(32) PRIMARY KEY, name VARCHAR(128), change_pct DECIMAL(10, 4));
CREATE TABLE chat_thread (thread_id VARCHAR(64) PRIMARY KEY, title VARCHAR(200));
CREATE TABLE chat_message (message_id VARCHAR(64) PRIMARY KEY, thread_id VARCHAR(64), role VARCHAR(32), content TEXT);
