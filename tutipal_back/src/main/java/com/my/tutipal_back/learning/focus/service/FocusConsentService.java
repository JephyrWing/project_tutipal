package com.my.tutipal_back.learning.focus.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.learning.focus.FocusProperties;
import com.my.tutipal_back.learning.focus.dto.FocusDtos.ConsentResponse;
import com.my.tutipal_back.learning.focus.repository.FocusStore;

/**
 * PT-90 AC-2: one consent per user for the current text version (app.focus.consent-version). Withdrawing deletes
 * the user's samples, states and consent in one transaction.
 */
@Service
public class FocusConsentService {

    private final FocusStore store;
    private final FocusProperties properties;
    private final Clock clock;

    @Autowired
    public FocusConsentService(FocusStore store, FocusProperties properties) {
        this(store, properties, Clock.systemUTC());
    }

    public FocusConsentService(FocusStore store, FocusProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ConsentResponse current(long userId) {
        return response(store.consent(userId));
    }

    /** Only the current version can be agreed to (an old screen gets 409 and must show the new text). */
    @Transactional
    public ConsentResponse give(long userId, String version) {
        if (!properties.consentVersion().equals(version)) {
            throw new ApiException(HttpStatus.CONFLICT, "동의 문구가 바뀌었어요. 새로 고친 뒤 다시 확인해 주세요");
        }
        Instant now = clock.instant();
        store.saveConsent(userId, version, now);
        return response(Optional.of(new FocusStore.Consent(version, now)));
    }

    @Transactional
    public void withdraw(long userId) {
        store.deleteUserData(userId);
    }

    private ConsentResponse response(Optional<FocusStore.Consent> consent) {
        String current = properties.consentVersion();
        int retention = properties.retentionDays();
        int maxAge = properties.maxSampleAgeDays();
        long stale = properties.staleAfter().toSeconds();
        return consent
                .map(c -> new ConsentResponse(current.equals(c.version()), c.version(), c.consentedAt(), current,
                        retention, maxAge, stale))
                .orElseGet(() -> new ConsentResponse(false, null, null, current, retention, maxAge, stale));
    }
}
