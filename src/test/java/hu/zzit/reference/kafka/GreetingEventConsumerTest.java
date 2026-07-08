package hu.zzit.reference.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hu.zzit.reference.kafka.event.GreetingEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Drives the poll loop against kafka-clients' own {@link MockConsumer} — no broker, no mocking
 * framework. Records are injected via {@code schedulePollTask} so they appear ON the poll thread,
 * the same way a real rebalance/fetch would.
 */
class GreetingEventConsumerTest {

    private static final String TOPIC = "reference-greetings";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);

    private static final GreetingEvent EVENT = GreetingEvent.newBuilder()
            .setId("42")
            .setName("Erika")
            .setMessage("Szia!")
            .build();

    private final MockConsumer<String, byte[]> mock = new MockConsumer<>("earliest");
    private final LinkedBlockingQueue<GreetingEvent> received = new LinkedBlockingQueue<>();
    private GreetingEventConsumer consumer;

    @AfterEach
    void stopConsumer() {
        consumer.stop();
        assertFalse(consumer.isRunning());
        assertTrue(mock.closed(), "poll loop must close the client on exit");
    }

    @Test
    void deliversParsedEventsAndCommitsAfterTheBatch() throws Exception {
        startConsumerWithRecords(record(0, EVENT.toByteArray()));

        assertEquals(EVENT, received.poll(10, TimeUnit.SECONDS));
        awaitCommittedOffset(1);
    }

    @Test
    void skipsPoisonPillsAndKeepsConsuming() throws Exception {
        startConsumerWithRecords(
                record(0, "definitely not protobuf".getBytes(StandardCharsets.UTF_8)), record(1, EVENT.toByteArray()));

        // The unparseable record is skipped, the one behind it still arrives ...
        assertEquals(EVENT, received.poll(10, TimeUnit.SECONDS));
        assertNull(received.peek(), "poison pill must not reach the handler");
        // ... and the committed offset moves past BOTH (the poison pill is never re-fetched).
        awaitCommittedOffset(2);
    }

    @Test
    void survivesHandlerFailures() throws Exception {
        var failing = GreetingEvent.newBuilder().setId("boom").build();
        consumer = new GreetingEventConsumer(mock, TOPIC, event -> {
            if ("boom".equals(event.getId())) {
                throw new IllegalStateException("handler exploded");
            }
            received.add(event);
        });
        scheduleRecords(record(0, failing.toByteArray()), record(1, EVENT.toByteArray()));
        consumer.start();

        assertEquals(EVENT, received.poll(10, TimeUnit.SECONDS));
        awaitCommittedOffset(2);
    }

    @SafeVarargs
    private void startConsumerWithRecords(ConsumerRecord<String, byte[]>... records) {
        consumer = new GreetingEventConsumer(mock, TOPIC, received::add);
        scheduleRecords(records);
        consumer.start();
        assertTrue(consumer.isRunning());
    }

    @SafeVarargs
    private void scheduleRecords(ConsumerRecord<String, byte[]>... records) {
        mock.updateBeginningOffsets(Map.of(PARTITION, 0L));
        mock.schedulePollTask(() -> {
            mock.rebalance(List.of(PARTITION));
            for (var record : records) {
                mock.addRecord(record);
            }
        });
    }

    private static ConsumerRecord<String, byte[]> record(long offset, byte[] value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "Erika", value);
    }

    private void awaitCommittedOffset(long expected) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            var committed = mock.committed(Set.of(PARTITION)).get(PARTITION);
            if (committed != null && committed.offset() == expected) {
                return;
            }
            Thread.sleep(20);
        }
        var committed = mock.committed(Set.of(PARTITION)).get(PARTITION);
        assertNotNull(committed, "nothing committed within 10s");
        assertEquals(expected, committed.offset());
    }
}
