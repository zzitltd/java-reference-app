package hu.zzit.reference.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import hu.zzit.reference.kafka.event.GreetingEvent;
import org.junit.jupiter.api.Test;

class ProtobufSerializerTest {

    private final ProtobufSerializer<GreetingEvent> serializer = new ProtobufSerializer<>();

    @Test
    void writesThePlainProtobufEncoding() throws Exception {
        var event = GreetingEvent.newBuilder()
                .setId("42")
                .setName("Erika")
                .setMessage("Szia!")
                .build();

        byte[] bytes = serializer.serialize("any-topic", event);

        // No framing around the encoding (no registry magic byte): parseFrom must round-trip.
        assertEquals(event, GreetingEvent.parseFrom(bytes));
    }

    @Test
    void serializesNullToNull() {
        // Kafka's Serializer contract: null stays null (tombstones on compacted topics).
        assertNull(serializer.serialize("any-topic", null));
    }
}
