package hu.zzit.reference.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.floci.testcontainers.FlociContainer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration test: verifies the AWS {@link ObjectStorage} adapter works end-to-end — the {@code aws}
 * profile activates the adapter plus the Spring Cloud AWS S3 autoconfiguration, pointed at a Floci
 * AWS emulator started by Testcontainers. Runs under failsafe (named {@code *IT}), separate from the
 * unit tests.
 *
 * <p>The image tag is pinned to match {@code compose.yaml}. The endpoint/region/credentials are
 * supplied dynamically from the container.
 */
@SpringBootTest
@ActiveProfiles("aws")
@Testcontainers
class ReferenceS3IT {

    @Container
    static final FlociContainer FLOCI = new FlociContainer("floci/floci:1.5.29");

    @DynamicPropertySource
    static void awsProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.aws.s3.endpoint", FLOCI::getEndpoint);
        registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> true);
        registry.add("spring.cloud.aws.region.static", FLOCI::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", FLOCI::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", FLOCI::getSecretKey);
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
