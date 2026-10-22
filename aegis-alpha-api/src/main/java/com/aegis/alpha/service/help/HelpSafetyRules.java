package com.aegis.alpha.service.help;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * P2-2: single rule table for the help safety boundary.
 * <ul>
 *   <li>"How/where do I approve" is product help; "approve it for me / 直接批准" is refused.</li>
 *   <li>{@code user_id} only refuses together with an explicit id assignment or an "other user's data" context.</li>
 *   <li>Trade instructions are refused in Chinese and English.</li>
 * </ul>
 */
public final class HelpSafetyRules {

    public enum Category { NONE, APPROVE_ON_BEHALF, TRADE, CROSS_TENANT }

    /** Approval/rejection verbs. */
    static final List<String> APPROVAL_TERMS = Collections.unmodifiableList(Arrays.asList(
            "批准", "审批", "驳回", "否决", "approve", "reject"));

    /** Phrases that delegate the decision to the assistant: always refused. */
    static final List<String> DELEGATION_TERMS = Collections.unmodifiableList(Arrays.asList(
            "替我", "帮我", "代我", "给我直接", "直接", "自动", "一键",
            "for me", "on my behalf", "auto-approve", "auto approve", "just approve", "go ahead and"));

    /** Questions about how/where to do it yourself: product help, not refused. */
    static final List<String> HOW_TO_TERMS = Collections.unmodifiableList(Arrays.asList(
            "如何", "怎么", "怎样", "在哪", "哪里", "哪儿", "入口", "流程", "步骤", "什么是",
            "how do", "how to", "how can i", "where do", "where can", "where is", "what is"));

    static final List<String> TRADE_TERMS = Collections.unmodifiableList(Arrays.asList(
            "下单", "执行交易", "帮我交易", "帮我买", "帮我卖", "立即买入", "立即卖出",
            "place order", "place an order", "submit order", "submit an order", "execute trade",
            "execute a trade", "trade now", "buy now", "sell now", "market order", "limit order"));

    static final Pattern TRADE_QUANTITY = Pattern.compile(
            "(买入|卖出|买|卖)\\s*\\d+\\s*(股|手|shares?)?|\\b(buy|sell|short)\\s+\\d+\\s*(shares?|lots?)?\\b");

    static final List<String> OTHER_USER_TERMS = Collections.unmodifiableList(Arrays.asList(
            "别人的", "他人的", "其他用户", "其它用户", "别的用户", "另一个用户", "其他人的",
            "other user", "another user", "someone else", "other people's", "others'"));

    static final List<String> PRIVATE_DATA_TERMS = Collections.unmodifiableList(Arrays.asList(
            "组合", "持仓", "数据", "账户", "仓位", "交易记录",
            "portfolio", "holding", "position", "account", "data"));

    /** user_id / userId with an explicit value, e.g. {@code userId=9}, {@code user_id: abc}. */
    static final Pattern USER_ID_ASSIGNMENT = Pattern.compile("\\buser_?id\\s*[=:：]\\s*\\S+");

    private HelpSafetyRules() {
    }

    public static Category classify(String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (lower.trim().isEmpty()) {
            return Category.NONE;
        }
        if (containsAny(lower, APPROVAL_TERMS) && isApproveOnBehalf(lower)) {
            return Category.APPROVE_ON_BEHALF;
        }
        if (containsAny(lower, TRADE_TERMS) || TRADE_QUANTITY.matcher(lower).find()) {
            return Category.TRADE;
        }
        if (USER_ID_ASSIGNMENT.matcher(lower).find()
                || (containsAny(lower, OTHER_USER_TERMS) && containsAny(lower, PRIVATE_DATA_TERMS))) {
            return Category.CROSS_TENANT;
        }
        return Category.NONE;
    }

    private static boolean isApproveOnBehalf(String lower) {
        if (containsAny(lower, DELEGATION_TERMS)) {
            return true;
        }
        // Bare imperative ("批准这条推荐", "approve rec-1") is an action request; how/where questions are help.
        return !containsAny(lower, HOW_TO_TERMS) && !lower.contains("?") && !lower.contains("？")
                && !lower.endsWith("吗") && !lower.endsWith("呢");
    }

    static boolean containsAny(String lower, List<String> terms) {
        for (String term : terms) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
