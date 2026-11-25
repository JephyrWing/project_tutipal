package com.my.tutipal_back.learning.insights.service;

import com.my.tutipal_back.learning.quiz.entity.QuizType;

/**
 * PT-87 D12: standard Bayesian Knowledge Tracing (Corbett &amp; Anderson, 1995) with fixed textbook parameters.
 * The parameters are NOT fitted to TUTIPAL data (uncalibrated; see docs/PT-87_INSIGHTS_CONTRACT.md).
 *
 * <pre>
 * correct: post = P(1-S) / (P(1-S) + (1-P)G)
 * wrong:   post = P S / (P S + (1-P)(1-G))
 * then:    P' = post + (1-post) T
 * </pre>
 */
public final class Bkt {

    /** P(L0): prior that the student already knows the concept. */
    public static final double P_INIT = 0.3;
    /** T: chance to learn the concept between two observations. */
    public static final double P_TRANSIT = 0.1;
    /** S: chance to answer wrong while knowing it. */
    public static final double P_SLIP = 0.1;

    private static final double MAX_CHOICE_GUESS = 0.3;
    private static final double TEXT_GUESS = 0.1;

    private Bkt() {
    }

    /** G: CHOICE = min(0.3, 1/choices), TEXT = 0.1. A CHOICE quiz without choices counts as the 0.3 cap. */
    public static double guess(QuizType type, int choiceCount) {
        if (type == QuizType.TEXT) {
            return TEXT_GUESS;
        }
        return choiceCount <= 0 ? MAX_CHOICE_GUESS : Math.min(MAX_CHOICE_GUESS, 1.0 / choiceCount);
    }

    /** One observation: Bayesian posterior, then the learning transition. */
    public static double update(double pKnown, boolean correct, double guess) {
        double posterior;
        if (correct) {
            double known = pKnown * (1 - P_SLIP);
            posterior = known / (known + (1 - pKnown) * guess);
        } else {
            double known = pKnown * P_SLIP;
            posterior = known / (known + (1 - pKnown) * (1 - guess));
        }
        return posterior + (1 - posterior) * P_TRANSIT;
    }
}
