package hu.zzit.reference.kafka;

import hu.zzit.reference.kafka.event.GreetingEvent;

/**
 * What the application does with a received {@link GreetingEvent} — the seam between the Kafka
 * plumbing ({@link GreetingEventConsumer}) and business logic. The default bean just logs (see
 * {@link KafkaConfiguration}); real services replace it.
 *
 * <p>Contract: delivery is at-least-once, so implementations must tolerate duplicates (deduplicate
 * on {@code event.getId()} where processing isn't naturally idempotent). Exceptions are logged and
 * the event is SKIPPED — pair anything unskippable with a retry or dead-letter strategy.
 */
@FunctionalInterface
public interface GreetingEventHandler {

    void onGreeting(GreetingEvent event);
}
