package hu.zzit.reference.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import hu.zzit.reference.kafka.event.GreetingEvent;
import java.util.concurrent.CompletionException;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

/** Uses kafka-clients' own {@link MockProducer} — no broker, no mocking framework. */
class GreetingEventProducerTest {

    private static final GreetingEvent EVENT = GreetingEvent.newBuilder()
            .setId("42")
            .setName("Erika")
            .setMessage("Szia!")
            .build();

    @Test
    void sendsToTheTopicKeyedByName() {
        var mock =
                new MockProducer<String, GreetingEvent>(true, null, new StringSerializer(), new ProtobufSerializer<>());
        var producer = new GreetingEventProducer(mock, "reference-greetings");

        producer.publish(EVENT).join();

        assertEquals(1, mock.history().size());
        var record = mock.history().getFirst();
        assertEquals("reference-greetings", record.topic());
        assertEquals("Erika", record.key());
        assertEquals(EVENT, record.value());
    }

    @Test
    void brokerFailureFailsTheReturnedFuture() {
        var mock = new MockProducer<String, GreetingEvent>(
                false, null, new StringSerializer(), new ProtobufSerializer<>());
        var producer = new GreetingEventProducer(mock, "reference-greetings");

        var result = producer.publish(EVENT);
        mock.errorNext(new KafkaException("boom"));

        var thrown = assertThrows(CompletionException.class, result::join);
        assertInstanceOf(KafkaException.class, thrown.getCause());
    }

    @Test
    void preDestroyClosesTheClient() {
        var mock =
                new MockProducer<String, GreetingEvent>(true, null, new StringSerializer(), new ProtobufSerializer<>());
        var producer = new GreetingEventProducer(mock, "reference-greetings");

        producer.close();

        assertTrue(mock.closed());
    }
}
