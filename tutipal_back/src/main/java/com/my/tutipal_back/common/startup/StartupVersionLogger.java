package com.my.tutipal_back.common.startup;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logs the deployed version and git SHA once the application is ready.
 * Values come from the APP_VERSION / GIT_SHA environment variables set by the Docker image.
 * Characters outside [0-9A-Za-z._+-] are replaced with '_' so a value cannot forge extra log lines.
 */
@Component
public class StartupVersionLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupVersionLogger.class);
    private static final String UNKNOWN = "unknown";
    private static final Pattern UNSAFE = Pattern.compile("[^0-9A-Za-z._+-]");

    private final String version;
    private final String sha;

    public StartupVersionLogger(
            @Value("${APP_VERSION:unknown}") String version,
            @Value("${GIT_SHA:unknown}") String sha) {
        this.version = orUnknown(version);
        this.sha = orUnknown(sha);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logStartup() {
        log.info("TUTIPAL started version={} sha={}", version, sha);
    }

    private static String orUnknown(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        return UNSAFE.matcher(value.strip()).replaceAll("_");
    }
}
