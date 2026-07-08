package hu.zzit.reference.kafka;

import com.google.protobuf.InvalidProtocolBufferException;
import hu.zzit.reference.kafka.event.GreetingEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The consumer side of the Kafka example — the part spring-kafka would otherwise hide, laid out
 * explicitly:
 *
 * <ul>
 *   <li><b>One thread owns the consumer.</b> {@code KafkaConsumer} is NOT thread-safe; everything
 *       from {@code subscribe()} to {@code close()} happens on the poll thread started by
 *       {@link #start()}. The single exception, by design, is {@link Consumer#wakeup()} — the only
 *       method a foreign thread may call — which {@link #stop()} uses to interrupt a blocking poll.
 *   <li><b>At-least-once delivery.</b> Auto-commit is off ({@link KafkaConfiguration#consumerConfig});
 *       offsets are committed only AFTER a batch is processed. A crash in between re-delivers the
 *       batch — which is why {@link GreetingEvent} carries a producer-generated {@code id} for
 *       consumer-side deduplication. (Rebalances can't interrupt mid-batch: they surface inside
 *       {@code poll()}, and by then the previous batch is processed and committed.)
 *   <li><b>Poison pills are skipped, not fatal.</b> Records are fetched as raw bytes and parsed
 *       here, per record — see {@link ProtobufSerializer} for why deserialization must not happen
 *       inside {@code poll()}.
 *   <li><b>Spring lifecycle, not Spring magic.</b> As a {@link SmartLifecycle} the loop starts once
 *       the context is ready and stops FIRST during graceful shutdown (default phase = web server's),
 *       inside the drain budget: wakeup, join, bounded close — the close also leaves the consumer
 *       group explicitly, so partitions are rebalanced to the remaining replicas immediately instead
 *       of after a session timeout.
 * </ul>
 */
public class GreetingEventConsumer implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(GreetingEventConsumer.class);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration STOP_JOIN_TIMEOUT = Duration.ofSeconds(10);

    private final Consumer<String, byte[]> consumer;
    private final String topic;
    private final GreetingEventHandler handler;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Thread pollThread;

    GreetingEventConsumer(Consumer<String, byte[]> consumer, String topic, GreetingEventHandler handler) {
        this.consumer = consumer;
        this.topic = topic;
        this.handler = handler;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        pollThread = Thread.ofPlatform().name("greeting-consumer").start(this::pollLoop);
    }

    private void pollLoop() {
        try {
            consumer.subscribe(List.of(topic));
            while (running.get()) {
                var records = consumer.poll(POLL_TIMEOUT);
                records.forEach(this::handle);
                if (!records.isEmpty()) {
                    consumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            // stop() interrupted a blocking poll — the normal shutdown path, nothing to do.
        } finally {
            consumer.close(CloseOptions.timeout(CLOSE_TIMEOUT));
            log.info("greeting consumer stopped");
        }
    }

    private void handle(ConsumerRecord<String, byte[]> record) {
        GreetingEvent event;
        try {
            event = GreetingEvent.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            // Poison pill: log with coordinates and move on. Production systems park the raw
            // record on a dead-letter topic here so it can be inspected and replayed.
            log.error(
                    "skipping unparseable record at {}-{}@{}", record.topic(), record.partition(), record.offset(), e);
            return;
        }
        try {
            handler.onGreeting(event);
        } catch (RuntimeException e) {
            // Log-and-skip keeps the example's loop alive; rethrowing without a retry/dead-letter
            // strategy would just re-crash on the same record (see GreetingEventHandler's contract).
            log.error("greeting handler failed for {}", event.getId(), e);
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        consumer.wakeup();
        try {
            if (!pollThread.join(STOP_JOIN_TIMEOUT)) {
                log.warn("greeting consumer poll thread did not stop within {}", STOP_JOIN_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
