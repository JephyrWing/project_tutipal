package com.my.tutipal_back.materials.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import com.my.tutipal_back.common.exception.ApiException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

/** 원본은 Spring 전용 저장소에 보관한다. local은 개발용, 운영은 s3를 명시한다. */
@Component
public class MaterialObjectStore {
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
    private S3Client s3() { return S3Client.builder().region(Region.of(region)).build(); }
    private Path local(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("잘못된 저장소 경로");
        return p;
    }
    public String put(byte[] data) {
        String key = "materials/" + UUID.randomUUID();
        try {
            if (mode.equals("s3")) try (S3Client client = s3()) {
                client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/octet-stream").build(), RequestBody.fromBytes(data));
            } else { Path p = local(key); Files.createDirectories(p.getParent()); Files.write(p, data); }
            return key;
        } catch (Exception e) { throw unavailable(); }
    }
    public byte[] get(String key) {
        try {
            if (mode.equals("s3")) try (S3Client client = s3()) {
                return client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray();
            }
            return Files.readAllBytes(local(key));
        } catch (Exception e) { throw unavailable(); }
    }
    public void delete(String key) {
        try {
            if (mode.equals("s3")) try (S3Client client = s3()) {
                client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } else Files.deleteIfExists(local(key));
        } catch (Exception e) { throw unavailable(); }
    }
    private ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "자료 저장소에 연결하지 못했어요"); }
}
