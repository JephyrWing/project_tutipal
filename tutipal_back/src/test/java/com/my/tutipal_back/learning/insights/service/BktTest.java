package com.my.tutipal_back.learning.insights.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

import com.my.tutipal_back.learning.quiz.entity.QuizType;

/**
 * PT-87 AC-9: BKT update with P(L0)=0.3, T=0.1, S=0.1, G=min(0.3, 1/choices) for CHOICE and 0.1 for TEXT.
 * Expected values are computed by hand from the D12 formulas (posterior, then learning transition).
 */
class BktTest {

    private static final double FOUR_DIGITS = 0.00005;

    private static double run(double guess, boolean... answers) {
        double p = Bkt.P_INIT;
        for (boolean correct : answers) {
            p = Bkt.update(p, correct, guess);
        }
        return p;
    }

    @Test
    void parametersMatchTheDesign() {
        assertThat(Bkt.P_INIT).isEqualTo(0.3);
        assertThat(Bkt.P_TRANSIT).isEqualTo(0.1);
        assertThat(Bkt.P_SLIP).isEqualTo(0.1);
    }

    @Test
    void guessIsOneOverChoicesCappedAtPointThreeForChoiceAndPointOneForText() {
        assertThat(Bkt.guess(QuizType.CHOICE, 4)).isCloseTo(0.25, within(1e-12));
        assertThat(Bkt.guess(QuizType.CHOICE, 5)).isCloseTo(0.2, within(1e-12));
        assertThat(Bkt.guess(QuizType.CHOICE, 3)).isCloseTo(0.3, within(1e-12));
        assertThat(Bkt.guess(QuizType.CHOICE, 2)).isCloseTo(0.3, within(1e-12));
        assertThat(Bkt.guess(QuizType.TEXT, 0)).isCloseTo(0.1, within(1e-12));
    }

    @Test
    void choiceWithFourOptionsCorrectAnswerMatchesHandValue() {
        // post = 0.3*0.9 / (0.27 + 0.7*0.25) = 0.606742 ; P = post + (1-post)*0.1 = 0.646067
        assertThat(run(0.25, true)).isCloseTo(0.6461, within(FOUR_DIGITS));
    }

    @Test
    void choiceWithFourOptionsWrongAnswerMatchesHandValue() {
        // post = 0.3*0.1 / (0.03 + 0.7*0.75) = 0.054054 ; P = 0.148649
        assertThat(run(0.25, false)).isCloseTo(0.1486, within(FOUR_DIGITS));
    }

    @Test
    void textCorrectAnswerMatchesHandValue() {
        // post = 0.27 / (0.27 + 0.7*0.1) = 0.794118 ; P = 0.814706
        assertThat(run(0.1, true)).isCloseTo(0.8147, within(FOUR_DIGITS));
    }

    @Test
    void textWrongAnswerMatchesHandValue() {
        // post = 0.03 / (0.03 + 0.7*0.9) = 0.045455 ; P = 0.140909
        assertThat(run(0.1, false)).isCloseTo(0.1409, within(FOUR_DIGITS));
    }

    @Test
    void sequencesMatchHandValues() {
        assertThat(run(0.25, true, true)).isCloseTo(0.8811, within(FOUR_DIGITS));
        assertThat(run(0.25, true, false)).isCloseTo(0.2762, within(FOUR_DIGITS));
        assertThat(run(0.25, false, true)).isCloseTo(0.4474, within(FOUR_DIGITS));
        assertThat(run(0.1, true, true)).isCloseTo(0.9778, within(FOUR_DIGITS));
        assertThat(run(0.1, true, false)).isCloseTo(0.3954, within(FOUR_DIGITS));
    }

    @Test
    void correctAnswerRaisesAndWrongAnswerLowersTheEstimate() {
        for (double guess : new double[] {0.1, 0.2, 0.25, 0.3}) {
            for (double prior : new double[] {0.05, 0.3, 0.5, 0.8, 0.95}) {
                double up = Bkt.update(prior, true, guess);
                double down = Bkt.update(prior, false, guess);
                assertThat(up).as("correct, prior %s guess %s", prior, guess).isGreaterThan(down);
                assertThat(up).isGreaterThan(prior);
                assertThat(down).isBetween(0.0, 1.0);
                assertThat(up).isBetween(0.0, 1.0);
            }
        }
        assertThat(Bkt.update(Bkt.P_INIT, false, 0.25)).isLessThan(Bkt.P_INIT);
    }
}
