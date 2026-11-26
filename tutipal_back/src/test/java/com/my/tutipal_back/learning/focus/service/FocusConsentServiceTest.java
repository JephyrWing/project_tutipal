package com.my.tutipal_back.learning.focus.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.my.tutipal_back.ai.quota.MutableClock;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.FocusState;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.ConsentResponse;
import com.my.tutipal_back.learning.focus.repository.FakeFocusStore;
import com.my.tutipal_back.learning.focus.repository.FocusStore;

/**
 * PT-90 AC-2: consent is stored per user with the current version (app.focus.consent-version) and time; another
 * version is 409; withdrawing deletes the user's samples, states and consent at once.
 */
class FocusConsentServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-26T03:15:42Z");
    private static final long ME = 2L;
    private static final long OTHER = 3L;

    private final FocusProperties properties = FocusProperties.defaults();
    private final MutableClock clock = new MutableClock(NOW);
    private final FakeFocusStore store = new FakeFocusStore();
    private final FocusConsentService service = new FocusConsentService(store, properties, clock);

    @Test
    void currentVersionComesFromProperties() {
        assertThat(properties.consentVersion()).isEqualTo("2026-11-26");
    }

    @Test
    void noConsentYet() {
        ConsentResponse response = service.current(ME);

        assertThat(response.consented()).isFalse();
        assertThat(response.version()).isNull();
        assertThat(response.consentedAt()).isNull();
        assertThat(response.currentVersion()).isEqualTo("2026-11-26");
    }

    @Test
    void givingConsentRecordsVersionAndServerTime() {
        ConsentResponse response = service.give(ME, "2026-11-26");

        assertThat(response.consented()).isTrue();
        assertThat(response.version()).isEqualTo("2026-11-26");
        assertThat(response.consentedAt()).isEqualTo(NOW);
        assertThat(store.consent(ME)).contains(new FocusStore.Consent("2026-11-26", NOW));
    }

    @Test
    void givingConsentForAnotherVersionIs409AndStoresNothing() {
        assertThatThrownBy(() -> service.give(ME, "2025-01-01"))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(store.hasConsent(ME)).isFalse();
    }

    @Test
    void consentForAnOldVersionIsReportedAsNotConsented() {
        store.seedConsent(ME, "2025-01-01", NOW.minus(Duration.ofDays(300)));

        ConsentResponse response = service.current(ME);

        assertThat(response.consented()).isFalse();
        assertThat(response.version()).isEqualTo("2025-01-01");
        assertThat(response.currentVersion()).isEqualTo("2026-11-26");
    }

    @Test
    void givingConsentAgainUpdatesTheTime() {
        service.give(ME, "2026-11-26");
        clock.advance(Duration.ofDays(1));

        ConsentResponse again = service.give(ME, "2026-11-26");

        assertThat(again.consentedAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
    }

    @Test
    void withdrawingDeletesSamplesStatesAndConsentOfThatUserOnly() {
        store.seedConsent(ME, "2026-11-26", NOW);
        store.seedConsent(OTHER, "2026-11-26", NOW);
        store.seedSample(10L, ME, new FocusStore.Sample(NOW, "code.exe", "ide", 1, 1));
        store.seedSample(11L, ME, new FocusStore.Sample(NOW, "code.exe", "ide", 1, 1));
        store.seedSample(10L, OTHER, new FocusStore.Sample(NOW, "code.exe", "ide", 1, 1));
        store.seedState(10L, ME, FocusState.FOCUS, NOW);
        store.seedState(10L, OTHER, FocusState.FOCUS, NOW);

        service.withdraw(ME);

        assertThat(store.hasConsent(ME)).isFalse();
        assertThat(store.samplesOf(10L, ME)).isEmpty();
        assertThat(store.samplesOf(11L, ME)).isEmpty();
        assertThat(store.storedState(10L, ME)).isEmpty();
        assertThat(store.hasConsent(OTHER)).isTrue();
        assertThat(store.samplesOf(10L, OTHER)).hasSize(1);
        assertThat(store.storedState(10L, OTHER)).isPresent();
        assertThat(service.current(ME).consented()).isFalse();
    }

    @Test
    void withdrawingWithoutConsentIsHarmless() {
        service.withdraw(ME);

        assertThat(service.current(ME).consented()).isFalse();
    }

    @Test
    void responsesCarryTheRetentionSettingsForTheConsentText() {
        FocusProperties d = FocusProperties.defaults();
        FocusProperties custom = new FocusProperties(d.consentVersion(), 14, d.retentionBatchSize(),
                d.submitMinInterval(), Duration.ofMinutes(5), d.zone(), d.predictTimeout(), 20);
        FocusConsentService customService = new FocusConsentService(store, custom, clock);

        ConsentResponse none = customService.current(ME);
        ConsentResponse given = customService.give(ME, "2026-11-26");

        assertThat(none.retentionDays()).isEqualTo(14);
        assertThat(none.maxSampleAgeDays()).isEqualTo(20);
        assertThat(given.retentionDays()).isEqualTo(14);
        assertThat(given.maxSampleAgeDays()).isEqualTo(20);
        assertThat(none.staleAfterSeconds()).isEqualTo(300);
        assertThat(service.current(ME).staleAfterSeconds()).isEqualTo(180);
        assertThat(service.current(ME).retentionDays()).isEqualTo(30);
        assertThat(service.current(ME).maxSampleAgeDays()).isEqualTo(31);
    }
}
