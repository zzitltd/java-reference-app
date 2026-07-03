package hu.zzit.reference.storage;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobServiceClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Azure adapter of {@link ObjectStorage}, backed by the {@link BlobServiceClient} that Spring Cloud
 * Azure autoconfigures. Active only when {@code cloud.provider=azure} — which the {@code azure}
 * profile sets together with {@code spring.cloud.azure.storage.blob.enabled=true} (the client bean
 * does not exist otherwise).
 */
@Component
@ConditionalOnProperty(name = "cloud.provider", havingValue = "azure")
class AzureBlobObjectStorage implements ObjectStorage {

    private final BlobServiceClient blobService;

    AzureBlobObjectStorage(BlobServiceClient blobService) {
        this.blobService = blobService;
    }

    @Override
    public void ensureContainer(String container) {
        blobService.getBlobContainerClient(container).createIfNotExists();
    }

    @Override
    public void put(String container, String key, String content) {
        blobService.getBlobContainerClient(container).getBlobClient(key).upload(BinaryData.fromString(content), true);
    }

    @Override
    public String get(String container, String key) {
        // BinaryData.toString() decodes UTF-8.
        return blobService
                .getBlobContainerClient(container)
                .getBlobClient(key)
                .downloadContent()
                .toString();
    }
}
