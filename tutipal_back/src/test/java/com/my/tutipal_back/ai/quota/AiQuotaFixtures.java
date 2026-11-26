package com.my.tutipal_back.ai.quota;

import java.time.Clock;
import java.time.Duration;

/**
 * PT-97 test fixture shared by service/web tests in other packages:
 * builds an AiQuota on a test clock and probes remaining capacity from the outside.
 */
public final class AiQuotaFixtures {

    private AiQuotaFixtures() {
    }

    /** Every feature gets the same limit/window. */
    public static AiQuota quota(int limit, Duration window, Clock clock) {
        var each = new AiQuotaProperties.Limit(limit, window);
        return new AiQuota(new AiQuotaProperties(each, each, each, each, each, Duration.ofMinutes(10)), clock);
    }

    /** True when one more call for (userId, feature) would be accepted now. Leaves the state unchanged. */
    public static boolean hasRoom(AiQuota quota, long userId, AiFeature feature) {
        try {
            quota.reserve(userId, feature).refund();
            return true;
        } catch (AiQuotaExceededException e) {
            return false;
        }
    }
}
