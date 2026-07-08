package hu.zzit.reference.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.google.protobuf.Timestamp;
import hu.zzit.reference.kafka.event.GreetingEvent;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the Kafka example against a real broker (apache/kafka in KRaft mode via
 * Testcontainers; image tag pinned to match {@code compose.yaml}): the app under the {@code kafka}
 * profile publishes a protobuf {@link GreetingEvent}, the consumer poll loop must deliver it to the
 * handler — the full produce → broker → consume → parse round trip. Runs under failsafe
 * ({@code *IT}).
 *
 * <p>{@code @DirtiesContext}: the context must close while its broker is still running — kept
 * cached until JVM exit, the consumer loop would spin warnings against the long-gone broker and the
 * shutdown-time leave-group would stall (same reasoning as the k3s IT).
 */
@SpringBootTest
@ActiveProfiles("kafka")
@Testcontainers
@DirtiesContext
class ReferenceKafkaIT {

    @Container
    @SuppressWarnings("resource") // no leak: the @Testcontainers extension starts/stops @Container fields
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.2.1"));

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** Replaces the default logging handler with one the test can await on. */
    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingHandlerConfiguration {

        @Bean
        BlockingQueue<GreetingEvent> receivedGreetings() {
            return new LinkedBlockingQueue<>();
        }

        @Bean
        @Primary
        GreetingEventHandler recordingGreetingEventHandler(BlockingQueue<GreetingEvent> receivedGreetings) {
            return receivedGreetings::add;
        }
    }

    @Autowired
    GreetingEventProducer producer;

    @Autowired
    BlockingQueue<GreetingEvent> receivedGreetings;

    @Test
    void publishedEventReachesTheHandlerIntact() throws Exception {
        var now = Instant.now();
        var event = GreetingEvent.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setName("Erika")
                .setMessage("Szia!")
                .setCreatedAt(
                        Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()))
                .build();

        // Broker ack (the future completes on acks=all)...
        assertNotNull(producer.publish(event).get(30, TimeUnit.SECONDS));

        // ...and the consumer group delivers. The generous timeout covers the FIRST rebalance of a
        // brand-new group; auto.offset.reset=earliest covers the produce landing before it.
        var received = receivedGreetings.poll(60, TimeUnit.SECONDS);
        assertEquals(event, received, "the event must round-trip the broker bit-for-bit");
    }
}
