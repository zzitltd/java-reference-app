package hu.zzit.reference.kafka;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka example settings ({@code kafka.*}). The broker list is the only setting without a default:
 * the {@code kafka} profile takes it from {@code KAFKA_BOOTSTRAP_SERVERS}, {@code local-kafka}
 * points it at the compose-managed broker.
 *
 * @param enabled activates the example (the {@code kafka} / {@code local-kafka} profiles set it)
 * @param bootstrapServers broker list ({@code host:port,...})
 * @param topic the topic the example produces to and consumes from. Provisioned by the platform
 *     (partitions, replication, retention are capacity decisions) — the app never creates topics,
 *     mirroring the database example's platform-provisioned roles
 * @param groupId consumer group; all replicas of ONE deployment share it, so Kafka spreads the
 *     topic's partitions across the replicas and each event is processed by exactly one of them
 * @param properties extra client properties, merged verbatim into BOTH the producer and the
 *     consumer config (applied last, so they override the code defaults). This is the extension
 *     point that keeps broker security config-only — TLS, SCRAM, or MSK IAM (see
 *     {@code application-kafka-msk-iam.yaml}) are all just entries here
 */
@ConfigurationProperties("kafka")
record KafkaProperties(
        @DefaultValue("false") boolean enabled,
        String bootstrapServers,
        @DefaultValue("reference-greetings") String topic,
        @DefaultValue("reference-app") String groupId,
        @DefaultValue Map<String, String> properties) {}
