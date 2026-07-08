package hu.zzit.reference.kafka;

import hu.zzit.reference.kafka.event.GreetingEvent;
import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kafka example — optional, gated on {@code kafka.enabled} (default false). Uses the plain Apache
 * {@code kafka-clients} deliberately, NOT spring-kafka: the raw client is the stable, universal API,
 * and the two things a wrapper would provide — a shared producer and a consumer poll loop — are a
 * few explicit lines here ({@link GreetingEventProducer}, {@link GreetingEventConsumer}) instead of
 * framework behavior to reverse-engineer.
 *
 * <p>Broker selection and security are pure configuration: the {@code kafka} profile reads the
 * broker list from the environment, and anything security-related (TLS, SCRAM, Amazon MSK with IAM
 * — see {@code application-kafka-msk-iam.yaml}) arrives via the {@code kafka.properties.*}
 * pass-through, applied last so it can override the defaults set below. There is no
 * provider-specific code to keep the example multi-broker the way the storage port is multi-cloud.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "kafka.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaProperties.class)
class KafkaConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfiguration.class);

    @Bean
    GreetingEventProducer greetingEventProducer(KafkaProperties properties) {
        var producer = new KafkaProducer<String, GreetingEvent>(
                producerConfig(properties), new StringSerializer(), new ProtobufSerializer<>());
        return new GreetingEventProducer(producer, properties.topic());
    }

    @Bean
    GreetingEventConsumer greetingEventConsumer(KafkaProperties properties, GreetingEventHandler handler) {
        var consumer = new KafkaConsumer<String, byte[]>(
                consumerConfig(properties), new StringDeserializer(), new ByteArrayDeserializer());
        return new GreetingEventConsumer(consumer, properties.topic(), handler);
    }

    /** Placeholder sink so the example runs out of the box; real services define their own bean. */
    @Bean
    GreetingEventHandler loggingGreetingEventHandler() {
        return event -> log.info("greeting received: {} says '{}'", event.getName(), event.getMessage());
    }

    // Package-private so the profile-wiring test can assert what actually reaches the clients.

    static Map<String, Object> producerConfig(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "reference-app-producer");
        // Both are the defaults since Kafka 3.0 — stated explicitly because they ARE the delivery
        // guarantee: every in-sync replica acknowledges, and broker-side sequence numbers make
        // producer retries duplicate-free.
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.putAll(properties.properties());
        return config;
    }

    static Map<String, Object> consumerConfig(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, properties.bootstrapServers());
        config.put(ConsumerConfig.CLIENT_ID_CONFIG, "reference-app-consumer");
        config.put(ConsumerConfig.GROUP_ID_CONFIG, properties.groupId());
        // The consumer loop commits manually AFTER processing (at-least-once); auto-commit would
        // silently flip that to at-most-once by committing on a timer inside poll().
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // First start of a NEW group: begin at the topic's start instead of the default `latest`,
        // which would silently skip everything produced before the group's first rebalance.
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.putAll(properties.properties());
        return config;
    }
}
