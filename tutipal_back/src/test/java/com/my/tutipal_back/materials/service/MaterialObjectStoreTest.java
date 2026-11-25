package com.my.tutipal_back.materials.service;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static com.my.tutipal_back.materials.service.MaterialObjectStore.ReadFailureKind.*;
import static com.my.tutipal_back.materials.service.MaterialObjectStore.classifyReadFailure;
import static org.assertj.core.api.Assertions.assertThat;

class MaterialObjectStoreTest {

    @Test
    void onlyKnownConnectionAndS3ErrorsAreTemporary() {
        assertThat(classifyReadFailure(new IOException(new ConnectException()))).isEqualTo(TEMPORARY);
        assertThat(classifyReadFailure(response(503, "SlowDown"))).isEqualTo(TEMPORARY);
        assertThat(classifyReadFailure(response(503, null))).isEqualTo(UNKNOWN);
        assertThat(classifyReadFailure(new IOException(new UnknownHostException()))).isEqualTo(UNKNOWN);
        assertThat(classifyReadFailure(new IOException())).isEqualTo(UNKNOWN);
    }

    @Test
    void missingAndForbiddenAreSeparatePermanentErrors() {
        assertThat(classifyReadFailure(new NoSuchFileException("fixture"))).isEqualTo(MISSING);
        assertThat(classifyReadFailure(response(404, "NoSuchKey"))).isEqualTo(MISSING);
        assertThat(classifyReadFailure(new AccessDeniedException("fixture"))).isEqualTo(FORBIDDEN);
        assertThat(classifyReadFailure(response(403, "AccessDenied"))).isEqualTo(FORBIDDEN);
    }

    private static AwsServiceException response(int status, String code) {
        return S3Exception.builder().statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(code).build()).build();
    }
}
