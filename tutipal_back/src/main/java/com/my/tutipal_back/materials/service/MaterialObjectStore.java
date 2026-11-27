package com.my.tutipal_back.materials.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.my.tutipal_back.common.exception.ApiException;
import com.my.tutipal_back.common.support.TransientConnectionFailure;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 원본은 Spring 전용 저장소에 보관한다. local은 개발용, 운영은 s3를 명시한다. */
@Component
public class MaterialObjectStore {
    private static final Set<String> TEMPORARY_S3_ERRORS = Set.of(
            "SlowDown", "ServiceUnavailable", "RequestTimeout", "RequestTimeoutException");

    enum ReadFailureKind { TEMPORARY, MISSING, FORBIDDEN, UNKNOWN }

    static final class ReadFailure extends ApiException {
        private final ReadFailureKind kind;

        ReadFailure(ReadFailureKind kind) {
            super(HttpStatus.SERVICE_UNAVAILABLE, "자료 저장소에 연결하지 못했어요");
            this.kind = kind;
        }

        ReadFailureKind kind() {
            return kind;
        }
    }

    private final Path root;
    private final String mode;
    private final String bucket;
    private final String region;
    public MaterialObjectStore(@Value("${app.storage.mode:local}") String mode,
            @Value("${app.storage.local-path:./data/materials}") String root,
            @Value("${tutipal.storage.s3.bucket:}") String bucket,
            @Value("${tutipal.storage.s3.region:ap-northeast-2}") String region) {
        if (!mode.equals("local") && !mode.equals("s3")) throw new IllegalArgumentException("MATERIAL_STORAGE는 local 또는 s3입니다");
        this.mode = mode; this.root = Path.of(root).toAbsolutePath().normalize(); this.bucket = bucket; this.region = region;
        if (mode.equals("s3") && bucket.isBlank()) throw new IllegalArgumentException("S3_BUCKET이 필요합니다");
    }
    S3Client s3() { return S3Client.builder().region(Region.of(region)).build(); }
    private Path local(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("잘못된 저장소 경로");
        return p;
    }
    String newKey() {
        return "materials/" + UUID.randomUUID();
    }

    String scope() {
        return mode.equals("s3") ? "s3:" + bucket : "local:" + root;
    }

    void put(String key, byte[] data) {
        try {
            if (mode.equals("s3")) try (S3Client client = s3()) {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/octet-stream").build(), RequestBody.fromBytes(data));
            } else { Path p = local(key); Files.createDirectories(p.getParent()); Files.write(p, data); }
        } catch (Exception e) { throw unavailable(); }
    }

    public boolean exists(String key) {
        try {
            if (mode.equals("s3")) {
                try (S3Client client = s3()) {
                    try {
                        client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
                    } catch (S3Exception e) {
                        if (e.statusCode() != 404) {
                            throw e;
                        }
                        // A missing or unreachable bucket cannot establish that an individual key is missing.
                        try {
                            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
                        } catch (Exception bucketFailure) {
                            ReadFailureKind kind = classifyReadFailure(bucketFailure);
                            throw new ReadFailure(kind == ReadFailureKind.MISSING ? ReadFailureKind.UNKNOWN : kind);
                        }
                        return false;
                    }
                }
            } else {
                Files.readAttributes(local(key), java.nio.file.attribute.BasicFileAttributes.class);
            }
            return true;
        } catch (ReadFailure e) {
            throw e;
        } catch (NoSuchFileException e) {
            return false;
        } catch (Exception e) {
            ReadFailureKind kind = classifyReadFailure(e);
            throw new ReadFailure(kind == ReadFailureKind.MISSING ? ReadFailureKind.UNKNOWN : kind);
        }
    }
    public byte[] get(String key) {
        try {
            if (mode.equals("s3")) try (S3Client client = s3()) {
                return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            }
            return Files.readAllBytes(local(key));
        } catch (Exception e) { throw new ReadFailure(classifyReadFailure(e)); }
    }
    public void delete(String key) {
        try {
            if (mode.equals("s3")) try (S3Client client = s3()) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } else Files.deleteIfExists(local(key));
        } catch (Exception e) { throw unavailable(); }
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "자료 저장소에 연결하지 못했어요"); }

    static ReadFailureKind classifyReadFailure(Exception failure) {
        if (failure instanceof NoSuchFileException) return ReadFailureKind.MISSING;
        if (failure instanceof AccessDeniedException || failure instanceof SecurityException) return ReadFailureKind.FORBIDDEN;
        if (failure instanceof S3Exception response) {
            if (response.statusCode() == 404) return ReadFailureKind.MISSING;
            if (response.statusCode() == 401 || response.statusCode() == 403) return ReadFailureKind.FORBIDDEN;
            String code = response.awsErrorDetails() == null ? null : response.awsErrorDetails().errorCode();
            return code != null && TEMPORARY_S3_ERRORS.contains(code)
                    ? ReadFailureKind.TEMPORARY : ReadFailureKind.UNKNOWN;
        }
        if (failure instanceof ApiCallTimeoutException || failure instanceof ApiCallAttemptTimeoutException
                || TransientConnectionFailure.confirmed(failure)) {
            return ReadFailureKind.TEMPORARY;
        }
        return ReadFailureKind.UNKNOWN;
    }
}
