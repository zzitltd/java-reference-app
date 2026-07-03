package hu.zzit.reference.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.azure.AzuriteContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: verifies the Azure {@link ObjectStorage} adapter works end-to-end — the
 * {@code azure} profile activates the adapter plus the Spring Cloud Azure Blob autoconfiguration,
 * pointed at an Azurite emulator started by Testcontainers. Runs under failsafe (named {@code *IT}),
 * separate from the unit tests.
 *
 * <p>The image tag is pinned to match {@code compose.yaml}. The connection string (Azurite's
 * well-known dev account) is supplied dynamically from the container.
 */
@SpringBootTest
@ActiveProfiles("azure")
@Testcontainers
class ReferenceAzureBlobIT {

    /**
     * --skipApiVersionCheck (same flag in compose.yaml): the Azure SDK speaks a storage API version
     * newer than Azurite knows — drop once Azurite catches up. The create-cmd modifier is needed
     * because AzuriteContainer overwrites withCommand() in configure().
     */
    @Container
    @SuppressWarnings("resource") // no leak: the @Testcontainers extension starts/stops @Container fields
    static final AzuriteContainer AZURITE = new AzuriteContainer("mcr.microsoft.com/azure-storage/azurite:3.35.0")
            .withCreateContainerCmdModifier(cmd -> cmd.withCmd(
                    "azurite",
                    "--blobHost",
                    "0.0.0.0",
                    "--queueHost",
                    "0.0.0.0",
                    "--tableHost",
                    "0.0.0.0",
                    "--skipApiVersionCheck"));

    @DynamicPropertySource
    static void azureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.azure.storage.blob.connection-string", AZURITE::getConnectionString);
    }

    @Autowired
    ObjectStorage storage;

    @Test
    void putsAndGetsAnObjectThroughTheEmulator() {
        String container = "reference-it";
        String key = "greeting.txt";
        String content = "üdvözlet"; // non-ASCII, to prove UTF-8 content round-trips

        storage.ensureContainer(container);
        storage.put(container, key, content);

        assertEquals(content, storage.get(container, key));
    }
}
