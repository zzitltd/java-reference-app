package hu.zzit.reference.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Unit test for the retry policy declared on the {@link ObjectStorage} port: a flaky in-memory
 * implementation fails twice, then succeeds — the caller must see one successful call. Delays are
 * shrunk to milliseconds via the {@code storage.retry.*} properties so the test stays fast.
 */
@SpringBootTest(
        properties = {
            "storage.retry.initial-delay-ms=5",
            "storage.retry.max-delay-ms=20",
        })
class ObjectStorageRetryTest {

    private static final AtomicInteger CALLS = new AtomicInteger();

    @TestConfiguration
    static class FlakyStorageConfiguration {

        @Bean
        ObjectStorage flakyStorage() {
            return new ObjectStorage() {

                @Override
                public void ensureContainer(String container) {
                    // fails on the first two attempts, succeeds on the third
                    if (CALLS.incrementAndGet() < 3) {
                        throw new IllegalStateException("transient failure #" + CALLS.get());
                    }
                }

                @Override
                public void put(String container, String key, String content) {
                    // always fails — proves retries are bounded
                    throw new IllegalStateException("permanent failure");
                }

                @Override
                public String get(String container, String key) {
                    return "unused";
                }
            };
        }
    }

    @Autowired
    ObjectStorage storage;

    @Test
    void retriesTransientFailuresUntilSuccess() {
        CALLS.set(0);
        storage.ensureContainer("any"); // would throw without retries
        assertEquals(3, CALLS.get());
    }

    @Test
    void givesUpAfterMaxRetries() {
        assertThrows(IllegalStateException.class, () -> storage.put("any", "k", "v"));
    }
}
