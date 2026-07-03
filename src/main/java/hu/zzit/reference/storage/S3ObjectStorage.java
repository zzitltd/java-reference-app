package hu.zzit.reference.storage;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * AWS adapter of {@link ObjectStorage}, backed by the {@link S3Client} that Spring Cloud AWS
 * autoconfigures. Active only when {@code cloud.provider=aws} — which the {@code aws} profile sets
 * together with {@code spring.cloud.aws.s3.enabled=true} (the client bean does not exist otherwise).
 */
@Component
@ConditionalOnProperty(name = "cloud.provider", havingValue = "aws")
class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3;

    S3ObjectStorage(S3Client s3) {
        this.s3 = s3;
    }

    @Override
    public void ensureContainer(String container) {
        try {
            s3.createBucket(CreateBucketRequest.builder().bucket(container).build());
        } catch (BucketAlreadyOwnedByYouException e) {
            // idempotent: the bucket is already ours
        }
    }

    @Override
    public void put(String container, String key, String content) {
        s3.putObject(
                PutObjectRequest.builder().bucket(container).key(key).build(),
                RequestBody.fromString(content, StandardCharsets.UTF_8));
    }

    @Override
    public String get(String container, String key) {
        return s3.getObjectAsBytes(
                        GetObjectRequest.builder().bucket(container).key(key).build())
                .asString(StandardCharsets.UTF_8);
    }
}
