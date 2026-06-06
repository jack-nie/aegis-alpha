package com.aegis.alpha.config;

import com.aegis.alpha.domain.User;
import com.aegis.alpha.domain.AgentTemplate;
import com.aegis.alpha.domain.WorkflowDefinition;
import com.aegis.alpha.domain.WorkflowRun;
import com.aegis.alpha.mapper.AgentMapper;
import com.aegis.alpha.mapper.DashboardMapper;
import com.aegis.alpha.mapper.UserMapper;
import com.aegis.alpha.mapper.WorkflowMapper;
import com.aegis.alpha.service.AuthService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Component
@Order(1)
@ConditionalOnProperty(name = "marketmind.seed-enabled", havingValue = "true", matchIfMissing = true)
public class ExistingDataSeeder implements CommandLineRunner {
    private final UserMapper userMapper;
    private final WorkflowMapper workflowMapper;
    private final DashboardMapper dashboardMapper;
    private final AgentMapper agentMapper;

    public ExistingDataSeeder(UserMapper userMapper, WorkflowMapper workflowMapper, DashboardMapper dashboardMapper, AgentMapper agentMapper) {
        this.userMapper = userMapper;
        this.workflowMapper = workflowMapper;
        this.dashboardMapper = dashboardMapper;
        this.agentMapper = agentMapper;
    }

    @Override
    public void run(String... args) {
        seedUser();
        seedWorkflows();
        seedAgents();
        seedDashboard();
    }

    private void seedUser() {
        if (userMapper.count() > 0) {
            return;
        }
        User user = new User();
        user.setUserId("eabb8343-3e82-459f-8ee4-8c95529484ee");
        user.setUsername("guanghui.nie");
        user.setPasswordHash(AuthService.hash("guanghui.nie"));
        user.setTenantId("0e8529cd-4251-448d-a8c2-4f3d553c95de");
        user.setRoles("portfolio_manager,research_user");
        userMapper.insert(user);
    }

    private void seedWorkflows() {
        if (workflowMapper.countDefinitions() == 0) {
            workflow("daily", "Daily Graph", 1, 7, 7,
                    "日报,晨报,每日,盘前,daily,morning briefing,daily graph",
                    "Generate daily market briefing with market overview, sector rotation, sentiment pulse, and key indicators summary");
            workflow("deep_dive", "Deep Dive Graph", 1, 17, 18,
                    "深度分析,深度研究,个股分析,股票分析,分析个股,分析股票,deep dive,deep analysis",
                    "Perform comprehensive deep-dive analysis on a specific stock covering 15 dimensions: fundamental, technical, valuation, money flow, industry, sentiment, news, tech breakthrough, risk, peer comparison, catalyst, thesis, risk-reward, entry strategy, and final recommendation");
            workflow("exit_workflow", "Exit Workflow", 1, 9, 9,
                    "止损,止盈,卖出,平仓,退出,exit,stop loss,take profit,close position",
                    "Analyze exit signals for a position including stop-loss review, take-profit review, signal decay, news risk scan, and exit decision");
            workflow("portfolio_workflow", "Portfolio Workflow", 1, 7, 7,
                    "投资组合,资产配置,组合分析,portfolio,asset allocation,portfolio workflow",
                    "Analyze investment portfolio including holdings overview, market scan, sector exposure, risk metrics, and rebalancing plan");
            workflow("position_workflow", "Position Workflow", 1, 15, 16,
                    "仓位,持仓,建仓,加仓,减仓,头寸,position sizing,position management,open position,add position",
                    "Manage position sizing and analysis including P&L analysis, cost basis, duration analysis, correlation, concentration risk, sector breakdown, cash flow, sentiment, tax impact, hedge ideas, and action items");
            workflow("sector-analyst-workflow", "Sector Analyst Workflow", 1, 15, 16,
                    "板块,行业,行业分析,sector,industry analysis,sector analyst",
                    "Analyze an industry sector including macro environment, industry chain, policy impact, competitive map, top players, tech trends, valuation band, sector sentiment, capital flow, sector risk, catalyst calendar, rotation signal, and sector recommendation");
            workflow("stock_analysis", "Stock Analysis", 1, 9, 10,
                    "股票分析,综合分析,帮我分析,分析一下,stock analysis,comprehensive analysis",
                    "Comprehensive stock analysis covering fundamental, technical, valuation, money flow, sentiment, risk, and aggregate recommendation");

            WorkflowRun run = new WorkflowRun();
            run.setRunId(UUID.randomUUID().toString());
            run.setWorkflowKey("daily");
            run.setTraceId(UUID.randomUUID().toString());
            run.setStatus("ERROR");
            run.setSubject("global market daily");
            run.setStartedAt("2026-04-25 09:05");
            workflowMapper.insertRun(run);
        }
        if (workflowMapper.findDefinition("stock_recommendation_research") == null) {
            workflow("stock_recommendation_research", "股票推荐智能体编排", 1, 10, 9,
                    "股票推荐,选股,推荐股票,recommendation,stock pick,stock screener",
                    "Orchestrate multi-agent stock recommendation research pipeline with market analysis, industry share, sentiment monitoring, tech breakthrough, industry news, web search, financial interpretation, and final stock recommendation aggregation");
        }
    }

    private void workflow(String key, String name, int version, int nodes, int edges, String triggerKeywords, String routingDescription) {
        WorkflowDefinition definition = new WorkflowDefinition();
        definition.setWorkflowKey(key);
        definition.setName(name);
        definition.setDescription("System workflow for Aegis Alpha Platform.");
        definition.setEngine("langgraph");
        definition.setVersion(version);
        definition.setNodes(nodes);
        definition.setEdges(edges);
        definition.setReadonlyFlag(true);
        definition.setUpdatedAt(now());
        definition.setTriggerKeywords(triggerKeywords);
        definition.setRoutingDescription(routingDescription);
        workflowMapper.insertDefinition(definition);
    }

    private void seedAgents() {
        if (agentMapper.count() > 0) {
            return;
        }
        agent("agent-user-value-investing", "Value Investing Advisor", "analyst", "analyst", 1, 1, 1, false, false, "guanghui.nie", 10);
        agent("agent-preset-price-action", "Price Action Trading Advisor", "analyst", "analyst", 1, 1, 1, true, true, null, 20);
        agent("agent-preset-value-investing", "Value Investing Advisor", "analyst", "analyst", 1, 1, 1, true, true, null, 30);
        agent("agent-preset-technical", "Technical Analysis Advisor", "analyst", "analyst", 1, 1, 2, true, true, null, 40);
        agent("agent-preset-fundamental", "Fundamental Briefing Agent", "fundamental", "fundamental,summary", 3, 1, 0, true, true, null, 50);
        agent("agent-preset-risk-exit", "Risk Exit Suggestion Agent", "risk", "risk,exit", 3, 2, 0, true, true, null, 60);
    }

    private void agent(String id, String name, String category, String tags, int inputCount, int outputCount, int toolCount,
                       boolean systemPreset, boolean readonlyFlag, String ownerUsername, int sortOrder) {
        AgentTemplate agent = new AgentTemplate();
        agent.setAgentId(id);
        agent.setName(name);
        agent.setDescription(category + " agent for Aegis Alpha workflow orchestration.");
        agent.setCategory(category);
        agent.setTags(tags);
        agent.setPrompt("You are " + name + ". Produce concise, structured investment research output from the supplied workflow state.");
        agent.setModelName("deepseek-v4-flash");
        agent.setToolsJson("[\"market-data\",\"portfolio\",\"news\"]");
        agent.setStatus("IDLE");
        agent.setInputCount(inputCount);
        agent.setOutputCount(outputCount);
        agent.setToolCount(toolCount);
        agent.setSystemPreset(systemPreset);
        agent.setReadonlyFlag(readonlyFlag);
        agent.setOwnerUsername(ownerUsername);
        agent.setSortOrder(sortOrder);
        agent.setUpdatedAt(now());
        agentMapper.insert(agent);
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void seedDashboard() {
        if (dashboardMapper.countQuadrant() > 0) {
            return;
        }
        quadrant("2025-03-01", 3, 4, 1, 4);
        quadrant("2025-04-01", 4, 4, 1, 4);
        quadrant("2025-05-01", 4, 3, 1, 1);
        quadrant("2025-06-01", 3, 2, 2, 1);
        quadrant("2025-07-01", 4, 2, 2, 1);
        quadrant("2025-08-01", 4, 2, 2, 1);
        quadrant("2025-09-01", 3, 2, 2, 1);
        quadrant("2025-10-01", 3, 2, 1, 1);
        quadrant("2025-11-01", 3, 1, 2, 1);
        quadrant("2025-12-01", 3, 1, 1, 1);
        quadrant("2026-01-01", 4, 1, 1, 1);
        quadrant("2026-02-01", 3, 2, 1, 1);

        credit("2022-03", "-5.90% / R27.8", "-14.60% / R5.6", "-26.30% / R2.8", "-19.40% / R2.8");
        credit("2022-06", "+5.80% / R83.3", "-6.20% / R13.9", "-9.30% / R11.1", "-2.30% / R23.6");
        credit("2022-09", "+10.80% / R88.9", "-3.60% / R16.7", "-2.90% / R31.9", "+2.10% / R63.9");
        credit("2022-12", "+14.00% / R94.4", "-1.20% / R27.8", "+0.80% / R63.9", "+1.50% / R58.3");
        credit("2023-03", "+11.90% / R91.7", "0.00% / R56.9", "+2.50% / R72.2", "+1.40% / R55.6");
        credit("2023-06", "+5.90% / R75.0", "-1.20% / R29.2", "-1.50% / R33.3", "-4.20% / R16.7");
        credit("2023-09", "+3.90% / R58.3", "-0.90% / R33.3", "-4.20% / R25.0", "-6.30% / R13.9");
        credit("2023-12", "+0.90% / R47.2", "-0.20% / R52.8", "-1.90% / R36.1", "-6.50% / R13.9");
        credit("2024-03", "-3.30% / R36.1", "+1.00% / R69.4", "+1.40% / R72.2", "-3.00% / R25.0");
        credit("2024-06", "-2.40% / R41.7", "+2.00% / R83.3", "+3.70% / R83.3", "+0.20% / R44.4");
        credit("2024-09", "-3.10% / R38.9", "+2.80% / R83.3", "+6.10% / R88.9", "+0.60% / R50.0");
        credit("2024-12", "-2.90% / R41.7", "+1.50% / R73.6", "+5.70% / R86.1", "+3.10% / R69.4");

        indicator("US10Y", "4.31%", "^TNX | Range 0 - 8");
        indicator("VIX", "18.71", "^VIX | Range 10 - 50");
        indicator("DXY", "98.51", "DX-Y.NYB | Range 90 - 110");
        indicator("Gold", "4722.30$", "GC=F | Range 1500 - 3200");
        indicator("Crude Oil", "94.40$", "CL=F | Range 40 - 120");

        market("SPY", "S&P 500", "0.77");
        market("QQQ", "Nasdaq 100", "1.91");
        market("IWM", "Russell 2000", "0.41");
        market("EFA", "Developed Markets", "0.52");
        market("EEM", "Emerging Markets", "2.23");
        market("VWO", "Emerging Markets ETF", "1.90");
        market("FXI", "China Large Cap", "1.01");
        market("EWJ", "Japan", "0.29");
        market("EWZ", "Brazil", "-0.37");
        market("EWG", "Germany", "1.03");
    }

    private void quadrant(String date, int china, int usa, int europe, int japan) {
        dashboardMapper.insertQuadrant(date, china, usa, europe, japan);
    }

    private void credit(String period, String china, String usa, String europe, String japan) {
        dashboardMapper.insertCredit(period, china, usa, europe, japan);
    }

    private void indicator(String name, String value, String subtitle) {
        dashboardMapper.insertIndicator(name, value, subtitle);
    }

    private void market(String symbol, String name, String changePct) {
        dashboardMapper.insertMarket(symbol, name, new BigDecimal(changePct));
    }
}
