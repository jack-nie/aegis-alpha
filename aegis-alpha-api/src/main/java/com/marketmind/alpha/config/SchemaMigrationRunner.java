package com.marketmind.alpha.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;

@Component
@Order(0)
public class SchemaMigrationRunner implements CommandLineRunner {
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public SchemaMigrationRunner(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @Override
    public void run(String... args) {
        addColumn("workflow_definition", "description", "VARCHAR(512) NOT NULL DEFAULT ''");
        addColumn("workflow_definition", "engine", "VARCHAR(32) NOT NULL DEFAULT 'langgraph'");
        addColumn("workflow_definition", "owner_username", "VARCHAR(128)");
        addColumn("workflow_definition", "updated_at", "VARCHAR(32) NOT NULL DEFAULT ''");

        addColumn("workflow_run", "completed_at", "VARCHAR(32)");
        addColumn("workflow_run", "result_json", textType());
        addColumn("workflow_run", "error_message", textType());
        addColumn("workflow_run", "idempotency_key", "VARCHAR(128)");
        addColumn("workflow_run", "workflow_version_id", "VARCHAR(64)");
        addColumn("workflow_run", "inputs_json", textType());
        addColumn("workflow_run", "control_status", "VARCHAR(32)");
        addColumn("workflow_run", "pause_requested", "TINYINT NOT NULL DEFAULT 0");
        addColumn("workflow_run", "cancel_requested", "TINYINT NOT NULL DEFAULT 0");
        addColumn("workflow_run", "queued_at", "VARCHAR(32)");
        addColumn("workflow_run", "node_count", "INT NOT NULL DEFAULT 0");

        addColumn("agent_template", "description", "VARCHAR(512) NOT NULL DEFAULT ''");
        addColumn("agent_template", "prompt", textType());
        addColumn("agent_template", "model_name", "VARCHAR(128) NOT NULL DEFAULT 'deepseek-v4-flash'");
        addColumn("agent_template", "tools_json", textType());
        addColumn("agent_template", "status", "VARCHAR(32) NOT NULL DEFAULT 'IDLE'");
        addColumn("agent_template", "schedule_cron", "VARCHAR(128)");
        addColumn("agent_template", "last_run_at", "VARCHAR(32)");
        addColumn("agent_template", "updated_at", "VARCHAR(32) NOT NULL DEFAULT ''");

        addColumn("backtest_run", "completed_at", "VARCHAR(32)");
        addColumn("backtest_run", "workflow_run_id", "VARCHAR(64)");
        addColumn("backtest_run", "trace_id", "VARCHAR(64)");
        addColumn("backtest_run", "subject", "VARCHAR(256)");
        addColumn("backtest_run", "symbol", "VARCHAR(32)");
        addColumn("backtest_run", "inputs_json", textType());
        addColumn("backtest_run", "result_json", textType());
        addColumn("backtest_run", "error_message", textType());
        addColumn("backtest_run", "node_count", "INT NOT NULL DEFAULT 0");
        addColumn("backtest_run", "final_recommendation", textType());
        addColumn("backtest_run", "confidence", "DECIMAL(10,4) NOT NULL DEFAULT 0");

        createWorkflowVersionTable();
        createWorkflowNodeRunTable();
        addColumn("workflow_node_run", "attempt", "INT NOT NULL DEFAULT 1");
        addColumn("workflow_node_run", "max_attempts", "INT NOT NULL DEFAULT 1");
        addColumn("workflow_node_run", "retry_policy_json", textType());
        addColumn("workflow_node_run", "timeout_ms", "INT");
        createWorkflowRunEventTable();
        createAgentCallSpanTable();
        createGovernanceTables();
        createPortfolioTradeTable();
        createAuditEventTable();
        seedDefaultModelConfig();
        updateLegacyAgentModels();
    }

    private void addColumn(String table, String column, String definition) {
        try {
            if (!hasColumn(table, column)) {
                jdbcTemplate.execute("alter table " + table + " add column " + column + " " + definition);
            }
        } catch (Exception ignored) {
        }
    }

    private boolean hasColumn(String table, String column) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            try (ResultSet rs = metaData.getColumns(null, null, table.toUpperCase(), column.toUpperCase())) {
                if (rs.next()) {
                    return true;
                }
            }
            try (ResultSet rs = metaData.getColumns(null, null, table.toLowerCase(), column.toLowerCase())) {
                return rs.next();
            }
        }
    }

    private void createWorkflowNodeRunTable() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists workflow_node_run (" +
                "node_run_id varchar(64) primary key, " +
                "run_id varchar(64) not null, " +
                "node_id varchar(128) not null, " +
                "node_name varchar(200) not null, " +
                "node_type varchar(32) not null, " +
                "agent_id varchar(64), " +
                "status varchar(32) not null, " +
                "input_json " + text + ", " +
                "output_json " + text + ", " +
                "error_message " + text + ", " +
                "started_at varchar(32) not null, " +
                "completed_at varchar(32), " +
                "sort_order int not null default 0, " +
                "attempt int not null default 1, " +
                "max_attempts int not null default 1, " +
                "retry_policy_json " + text + ", " +
                "timeout_ms int)");
    }

    private void createWorkflowVersionTable() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists workflow_version (" +
                "version_id varchar(64) primary key, " +
                "workflow_key varchar(128) not null, " +
                "version int not null, " +
                "layout_json " + text + " not null, " +
                "validation_json " + text + ", " +
                "published_by varchar(128), " +
                "published_at varchar(32) not null)");
    }

    private void createWorkflowRunEventTable() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists workflow_run_event (" +
                "event_id varchar(64) primary key, " +
                "run_id varchar(64) not null, " +
                "event_type varchar(64) not null, " +
                "node_run_id varchar(64), " +
                "node_id varchar(128), " +
                "status varchar(32), " +
                "message " + text + ", " +
                "payload_json " + text + ", " +
                "created_at varchar(32) not null, " +
                "sort_order int not null default 0)");
    }

    private void createPortfolioTradeTable() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists portfolio_trade (" +
                "trade_id varchar(64) primary key, " +
                "portfolio_id varchar(64), " +
                "trade_date varchar(32) not null, " +
                "settlement_date varchar(32), " +
                "symbol varchar(32) not null, " +
                "exchange varchar(32), " +
                "market varchar(32), " +
                "security_name varchar(200), " +
                "side varchar(8) not null, " +
                "quantity decimal(24, 8) not null, " +
                "price decimal(24, 8) not null, " +
                "gross_amount decimal(24, 8) not null, " +
                "fee decimal(24, 8) not null default 0, " +
                "tax decimal(24, 8) not null default 0, " +
                "commission decimal(24, 8) not null default 0, " +
                "other_fee decimal(24, 8) not null default 0, " +
                "net_amount decimal(24, 8) not null, " +
                "currency varchar(16) not null default 'USD', " +
                "fx_rate decimal(24, 8) not null default 1, " +
                "broker varchar(128), " +
                "account_no varchar(128), " +
                "strategy varchar(128), " +
                "trade_type varchar(64), " +
                "order_type varchar(64), " +
                "notes " + text + ", " +
                "source_type varchar(32) not null default 'MANUAL', " +
                "import_batch_id varchar(64), " +
                "created_at varchar(32) not null, " +
                "updated_at varchar(32) not null)");
    }

    private void createAgentCallSpanTable() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists agent_call_span (" +
                "span_id varchar(64) primary key, " +
                "trace_id varchar(64) not null, " +
                "workflow_run_id varchar(64), " +
                "node_run_id varchar(64), " +
                "backtest_run_id varchar(64), " +
                "parent_span_id varchar(64), " +
                "span_type varchar(32) not null, " +
                "agent_id varchar(64), " +
                "tool_name varchar(128), " +
                "model_name varchar(128), " +
                "status varchar(32) not null, " +
                "input_json " + text + ", " +
                "output_json " + text + ", " +
                "error_message " + text + ", " +
                "started_at varchar(32) not null, " +
                "completed_at varchar(32), " +
                "latency_ms bigint, " +
                "prompt_tokens int, " +
                "completion_tokens int, " +
                "total_tokens int, " +
                "sort_order int not null default 0)");
    }

    private void createGovernanceTables() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists model_config (" +
                "model_config_id varchar(64) primary key, " +
                "provider varchar(64) not null, " +
                "model_name varchar(128) not null, " +
                "status varchar(32) not null, " +
                "context_window int not null default 0, " +
                "prompt_token_cost_usd decimal(18, 10) not null default 0, " +
                "completion_token_cost_usd decimal(18, 10) not null default 0, " +
                "fallback_model varchar(128), " +
                "created_at varchar(32) not null)");
        jdbcTemplate.execute("create table if not exists llm_call (" +
                "llm_call_id varchar(64) primary key, " +
                "workflow_run_id varchar(64) not null, " +
                "node_run_id varchar(64), " +
                "trace_id varchar(64), " +
                "provider varchar(64), " +
                "model_name varchar(128), " +
                "status varchar(32) not null, " +
                "prompt_tokens int, " +
                "completion_tokens int, " +
                "total_tokens int, " +
                "estimated_cost_usd decimal(18, 10) not null default 0, " +
                "started_at varchar(32), " +
                "completed_at varchar(32))");
        jdbcTemplate.execute("create table if not exists evidence_item (" +
                "evidence_id varchar(64) primary key, " +
                "workflow_run_id varchar(64) not null, " +
                "node_run_id varchar(64), " +
                "source_type varchar(64) not null, " +
                "title varchar(512) not null, " +
                "url varchar(1024), " +
                "trust_tier varchar(32) not null, " +
                "summary " + text + ", " +
                "retrieved_at varchar(32) not null)");
        jdbcTemplate.execute("create table if not exists recommendation (" +
                "recommendation_id varchar(64) primary key, " +
                "workflow_run_id varchar(64) not null, " +
                "backtest_run_id varchar(64), " +
                "trace_id varchar(64), " +
                "symbol varchar(32), " +
                "recommendation varchar(32) not null, " +
                "confidence decimal(10, 4) not null default 0, " +
                "time_horizon varchar(64), " +
                "rationale_json " + text + ", " +
                "risk_json " + text + ", " +
                "missing_data_json " + text + ", " +
                "disclaimer " + text + ", " +
                "approval_status varchar(32) not null default 'PENDING_REVIEW', " +
                "created_at varchar(32) not null)");
    }

    private void createAuditEventTable() {
        String text = textType();
        jdbcTemplate.execute("create table if not exists audit_event (" +
                "event_id varchar(64) primary key, " +
                "tenant_id varchar(64), " +
                "user_id varchar(64), " +
                "actor_type varchar(32) not null default 'USER', " +
                "action varchar(128) not null, " +
                "resource_type varchar(128), " +
                "resource_id varchar(256), " +
                "trace_id varchar(64), " +
                "request_id varchar(64), " +
                "before_json " + text + ", " +
                "after_json " + text + ", " +
                "ip_address varchar(64), " +
                "user_agent varchar(512), " +
                "created_at varchar(64) not null)");
    }

    private void updateLegacyAgentModels() {
        try {
            jdbcTemplate.update("update agent_template set model_name = 'deepseek-v4-flash' where model_name = 'gpt-4o-mini'");
        } catch (Exception ignored) {
        }
    }

    private void seedDefaultModelConfig() {
        try {
            Integer count = jdbcTemplate.queryForObject("select count(*) from model_config", Integer.class);
            if (count != null && count == 0) {
                jdbcTemplate.update("insert into model_config(model_config_id, provider, model_name, status, context_window, prompt_token_cost_usd, completion_token_cost_usd, fallback_model, created_at) values(?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        "model-default-deepseek-v4-flash",
                        "openai",
                        "deepseek-v4-flash",
                        "ACTIVE",
                        128000,
                        0,
                        0,
                        "deepseek-v4-flash",
                        java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            }
        } catch (Exception ignored) {
        }
    }

    private String textType() {
        try (Connection connection = dataSource.getConnection()) {
            String product = connection.getMetaData().getDatabaseProductName().toLowerCase();
            if (product.contains("mysql")) {
                return "MEDIUMTEXT";
            }
        } catch (Exception ignored) {
        }
        return "TEXT";
    }
}
