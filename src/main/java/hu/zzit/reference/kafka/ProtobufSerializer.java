package hu.zzit.reference.kafka;

import com.google.protobuf.MessageLite;
import org.apache.kafka.common.serialization.Serializer;

/**
 * Kafka value serializer for any protobuf message. The wire format is the message's plain protobuf
 * encoding and nothing else — no schema-registry framing (magic byte + schema id): compatibility is
 * guaranteed at build time by sharing the .proto contract (see src/main/protobuf), not at runtime by
 * a registry. Mixing the two framings on one topic is NOT possible; adding a registry later means
 * migrating the topic.
 *
 * <p>Deliberately no matching {@code Deserializer}: a deserializer that throws inside {@code poll()}
 * wedges the partition — the client re-fetches the same broken record forever. The consumer instead
 * fetches raw bytes and parses them where a failure can be handled per record (see
 * {@link GreetingEventConsumer}).
 */
final class ProtobufSerializer<T extends MessageLite> implements Serializer<T> {

    @Override
    public byte[] serialize(String topic, T data) {
        return data == null ? null : data.toByteArray();
    }
}
