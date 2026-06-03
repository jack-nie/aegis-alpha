import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  HANDLERS,
  promptForHandler,
  mockSummary,
} from './server.mjs';

// ============================================================
// 1. HANDLERS registration
// ============================================================
describe('HANDLERS set - new handler registration', () => {
  const required = [
    'finance.peer_comparison',
    'finance.catalyst_analysis',
    'finance.thesis_builder',
    'finance.risk_reward_analysis',
    'finance.entry_strategy',
  ];
  for (const h of required) {
    it('HANDLERS contains ' + h, () => {
      assert.ok(HANDLERS.has(h), h + ' should be in HANDLERS');
    });
  }
});

// ============================================================
// 2. promptForHandler returns specific prompts
// ============================================================
describe('promptForHandler - new handler prompts', () => {
  const cases = [
    {
      handler: 'finance.peer_comparison',
      requiredFields: ['peers', 'subject_position', 'relative_valuation', 'competitive_advantages', 'peer_avg_pe'],
    },
    {
      handler: 'finance.catalyst_analysis',
      requiredFields: ['upcoming_catalysts', 'catalyst_score', 'earnings_date'],
    },
    {
      handler: 'finance.thesis_builder',
      requiredFields: ['bull_case', 'bear_case', 'base_case', 'conviction_level'],
    },
    {
      handler: 'finance.risk_reward_analysis',
      requiredFields: ['bullish_target', 'bearish_target', 'risk_reward_ratio', 'suggested_position_pct'],
    },
    {
      handler: 'finance.entry_strategy',
      requiredFields: ['entry_zone', 'stop_loss', 'take_profit_1', 'position_sizing', 'entry_timing'],
    },
  ];

  for (const { handler, requiredFields } of cases) {
    it(handler + ' prompt mentions required fields', () => {
      const prompt = promptForHandler(handler);
      assert.ok(prompt.length > 50, 'Prompt for ' + handler + ' should be > 50 chars, got ' + prompt.length);
      for (const field of requiredFields) {
        assert.ok(prompt.includes(field), 'Prompt for ' + handler + ' missing field: ' + field);
      }
    });

    it(handler + ' is not the generic fallback prompt', () => {
      const prompt = promptForHandler(handler);
      const generic = 'Pass through workflow state and produce concise structured output';
      assert.ok(!prompt.startsWith(generic), handler + ' should not use generic fallback prompt');
    });
  }
});

// ============================================================
// 3. mockSummary returns handler-specific summaries
// ============================================================
describe('mockSummary - new handler mock summaries', () => {
  const handlers = [
    'finance.peer_comparison',
    'finance.catalyst_analysis',
    'finance.thesis_builder',
    'finance.risk_reward_analysis',
    'finance.entry_strategy',
  ];
  for (const h of handlers) {
    it('mockSummary returns specific text for ' + h, () => {
      const summary = mockSummary(h, 'TEST', {});
      assert.ok(summary.length > 20, 'Mock summary for ' + h + ' should be > 20 chars');
      assert.ok(summary.includes('TEST'), 'Mock summary for ' + h + ' should include subject');
    });
  }
});

// ============================================================
// 4. Existing prompts still work (regression guard)
// ============================================================
describe('promptForHandler - existing handlers unchanged', () => {
  const existingHandlers = [
    'finance.market_analysis',
    'finance.fundamental_analysis',
    'finance.technical_analysis',
    'finance.valuation_analysis',
    'finance.money_flow_analysis',
    'finance.risk_assessment',
    'finance.sentiment_monitor',
    'finance.industry_share',
    'finance.stock_recommendation_aggregate',
  ];
  for (const h of existingHandlers) {
    it(h + ' still has a prompt', () => {
      const prompt = promptForHandler(h);
      assert.ok(prompt.length > 30, 'Prompt for ' + h + ' should still exist');
    });
  }
});
