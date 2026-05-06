INSERT IGNORE INTO mm_user(user_id, username, password_hash, tenant_id, roles)
VALUES ('eabb8343-3e82-459f-8ee4-8c95529484ee', 'guanghui.nie', '71981c6fc49514ef962d892847ce06f8293c38e00bb74d8dce68f1f45ce263f4', '0e8529cd-4251-448d-a8c2-4f3d553c95de', 'portfolio_manager,research_user');

INSERT IGNORE INTO workflow_definition(workflow_key, name, version, nodes, edges) VALUES
('daily', 'Daily Graph', 1, 7, 6),
('deep_dive', 'Deep Dive Graph', 1, 17, 16),
('exit_workflow', 'Exit Workflow', 1, 8, 8),
('portfolio_workflow', 'Portfolio Workflow', 1, 6, 5),
('position_workflow', 'Position Workflow', 1, 23, 23),
('sector-analyst-workflow', 'Sector Analyst Workflow', 1, 16, 15);

INSERT IGNORE INTO agent_template(agent_id, name, category, tags, input_count, output_count, tool_count, system_preset, readonly_flag, owner_username, sort_order) VALUES
('agent-user-value-investing', 'Value Investing Advisor', 'analyst', 'analyst', 1, 1, 1, 0, 0, 'guanghui.nie', 10),
('agent-preset-price-action', 'Price Action Trading Advisor', 'analyst', 'analyst', 1, 1, 1, 1, 1, NULL, 20),
('agent-preset-value-investing', 'Value Investing Advisor', 'analyst', 'analyst', 1, 1, 1, 1, 1, NULL, 30),
('agent-preset-technical', 'Technical Analysis Advisor', 'analyst', 'analyst', 1, 1, 2, 1, 1, NULL, 40),
('agent-preset-fundamental', 'Fundamental Briefing Agent', 'fundamental', 'fundamental,summary', 3, 1, 0, 1, 1, NULL, 50),
('agent-preset-risk-exit', 'Risk Exit Suggestion Agent', 'risk', 'risk,exit', 3, 2, 0, 1, 1, NULL, 60);

INSERT IGNORE INTO macro_quadrant(as_of_date, china, usa, europe, japan) VALUES
('2025-03-01', 3, 4, 1, 4),
('2025-04-01', 4, 4, 1, 4),
('2025-05-01', 4, 3, 1, 1),
('2025-06-01', 3, 2, 2, 1),
('2025-07-01', 4, 2, 2, 1),
('2025-08-01', 4, 2, 2, 1),
('2025-09-01', 3, 2, 2, 1),
('2025-10-01', 3, 2, 1, 1),
('2025-11-01', 3, 1, 2, 1),
('2025-12-01', 3, 1, 1, 1),
('2026-01-01', 4, 1, 1, 1),
('2026-02-01', 3, 2, 1, 1);

INSERT IGNORE INTO credit_impulse(period, china, usa, europe, japan) VALUES
('2022-03', '-5.90% / R27.8', '-14.60% / R5.6', '-26.30% / R2.8', '-19.40% / R2.8'),
('2022-06', '+5.80% / R83.3', '-6.20% / R13.9', '-9.30% / R11.1', '-2.30% / R23.6'),
('2022-09', '+10.80% / R88.9', '-3.60% / R16.7', '-2.90% / R31.9', '+2.10% / R63.9'),
('2022-12', '+14.00% / R94.4', '-1.20% / R27.8', '+0.80% / R63.9', '+1.50% / R58.3'),
('2023-03', '+11.90% / R91.7', '0.00% / R56.9', '+2.50% / R72.2', '+1.40% / R55.6'),
('2023-06', '+5.90% / R75.0', '-1.20% / R29.2', '-1.50% / R33.3', '-4.20% / R16.7'),
('2023-09', '+3.90% / R58.3', '-0.90% / R33.3', '-4.20% / R25.0', '-6.30% / R13.9'),
('2023-12', '+0.90% / R47.2', '-0.20% / R52.8', '-1.90% / R36.1', '-6.50% / R13.9'),
('2024-03', '-3.30% / R36.1', '+1.00% / R69.4', '+1.40% / R72.2', '-3.00% / R25.0'),
('2024-06', '-2.40% / R41.7', '+2.00% / R83.3', '+3.70% / R83.3', '+0.20% / R44.4'),
('2024-09', '-3.10% / R38.9', '+2.80% / R83.3', '+6.10% / R88.9', '+0.60% / R50.0'),
('2024-12', '-2.90% / R41.7', '+1.50% / R73.6', '+5.70% / R86.1', '+3.10% / R69.4');

INSERT IGNORE INTO market_indicator(name, value, subtitle) VALUES
('US10Y', '4.31%', '^TNX | Range 0 - 8'),
('VIX', '18.71', '^VIX | Range 10 - 50'),
('DXY', '98.51', 'DX-Y.NYB | Range 90 - 110'),
('Gold', '4722.30$', 'GC=F | Range 1500 - 3200'),
('Crude Oil', '94.40$', 'CL=F | Range 40 - 120');

INSERT IGNORE INTO market_snapshot(symbol, name, change_pct) VALUES
('SPY', 'S&P 500', 0.77),
('QQQ', 'Nasdaq 100', 1.91),
('IWM', 'Russell 2000', 0.41),
('EFA', 'Developed Markets', 0.52),
('EEM', 'Emerging Markets', 2.23),
('VWO', 'Emerging Markets ETF', 1.90),
('FXI', 'China Large Cap', 1.01),
('EWJ', 'Japan', 0.29),
('EWZ', 'Brazil', -0.37),
('EWG', 'Germany', 1.03);
