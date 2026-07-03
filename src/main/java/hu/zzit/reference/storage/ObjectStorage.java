package hu.zzit.reference.storage;

import org.springframework.resilience.annotation.Retryable;

/**
 * Cloud-agnostic object storage port. Exactly one adapter is activated by the {@code cloud.provider}
 * property ({@code aws} → S3, {@code azure} → Blob Storage); with the default {@code none} no adapter
 * (and no cloud SDK autoconfiguration) is active at all — cloud support is optional.
 *
 * <p>Select a provider via the {@code aws} / {@code azure} Spring profiles (see
 * {@code application-aws.yaml} / {@code application-azure.yaml}), or on a workstation via
 * {@code local-aws} / {@code local-azure}, which also start the matching emulator. Derived services
 * keep the adapter(s) they need and delete the rest.
 *
 * <p><b>Resilience:</b> external calls must expect network failure. The type-level {@link Retryable}
 * (Spring Framework's built-in resilience support, enabled by {@code @EnableResilientMethods} on the
 * application class — no extra dependency) retries every method of every implementing bean with
 * exponential backoff: 3 retries, 1s → 2s → 4s delays by default, tunable/disable-able per
 * environment via the {@code storage.retry.*} properties. Retrying a {@code put} is safe here
 * because all operations are idempotent — do NOT copy this blindly onto non-idempotent operations.
 */
@Retryable(
        maxRetriesString = "${storage.retry.max-retries:3}",
        delayString = "${storage.retry.initial-delay-ms:1000}",
        multiplierString = "${storage.retry.multiplier:2.0}",
        maxDelayString = "${storage.retry.max-delay-ms:8000}")
public interface ObjectStorage {

    /** Creates the container (S3: bucket, Azure: blob container) if it does not exist yet. */
    void ensureContainer(String container);

    /** Stores {@code content} UTF-8-encoded under {@code key}. */
    void put(String container, String key, String content);

    /** Returns the object at {@code key}, decoded as UTF-8. */
    String get(String container, String key);
}
