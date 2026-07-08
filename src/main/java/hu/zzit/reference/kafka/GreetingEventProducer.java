package hu.zzit.reference.kafka;

import hu.zzit.reference.kafka.event.GreetingEvent;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes {@link GreetingEvent}s. Owns the underlying {@code KafkaProducer}: unlike the consumer,
 * the producer IS thread-safe, so the whole application shares this ONE long-lived instance —
 * per-send producers throw away batching, connection reuse, and the idempotence session (see
 * {@link KafkaConfiguration#producerConfig} for acks/idempotence).
 */
public class GreetingEventProducer {

    private static final Logger log = LoggerFactory.getLogger(GreetingEventProducer.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final Producer<String, GreetingEvent> producer;
    private final String topic;

    GreetingEventProducer(Producer<String, GreetingEvent> producer, String topic) {
        this.producer = producer;
        this.topic = topic;
    }

    /**
     * Sends asynchronously, keyed by {@code name}: events with the same key land on the same
     * partition, so per-name ordering is preserved. Callers that must not lose the event block on
     * (or chain to) the returned future; fire-and-forget callers can drop it — failures are logged
     * here either way.
     */
    public CompletableFuture<RecordMetadata> publish(GreetingEvent event) {
        var result = new CompletableFuture<RecordMetadata>();
        producer.send(new ProducerRecord<>(topic, event.getName(), event), (metadata, exception) -> {
            if (exception != null) {
                log.error("failed to publish greeting {}", event.getId(), exception);
                result.completeExceptionally(exception);
            } else {
                log.debug(
                        "published greeting {} to {}-{}@{}",
                        event.getId(),
                        metadata.topic(),
                        metadata.partition(),
                        metadata.offset());
                result.complete(metadata);
            }
        });
        return result;
    }

    @PreDestroy
    void close() {
        // Bounded close: flushes in-flight batches but never holds shutdown hostage — it has to
        // fit the app's graceful-shutdown drain budget (spring.lifecycle.timeout-per-shutdown-phase).
        producer.close(CLOSE_TIMEOUT);
    }
}
