package hu.zzit.reference.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * The MSK IAM path cannot be integration-tested (no local emulator exists), but it is config-only —
 * so this test pins down exactly that configuration: the {@code kafka-msk-iam} profile group pulls
 * in the generic {@code kafka} profile, and the IAM SASL entries it contributes survive YAML map
 * binding (dotted keys need the {@code "[...]"} bracket syntax!) all the way into the property maps
 * handed to the actual clients. {@code kafka.enabled=false} keeps the clients themselves off — no
 * broker is contacted.
 */
@SpringBootTest(
        properties = {
            // Activated via the PROPERTY, the way deployments do (SPRING_PROFILES_ACTIVE) — NOT
            // @ActiveProfiles, which sets profiles directly on the test Environment and thereby
            // skips Spring Boot's profile-GROUP expansion, so `kafka` would never join.
            "spring.profiles.active=kafka-msk-iam",
            "kafka.enabled=false",
            // Resolves application-kafka.yaml's ${KAFKA_BOOTSTRAP_SERVERS} placeholder — in real
            // deployments this is the MSK IAM bootstrap endpoint from the environment.
            "KAFKA_BOOTSTRAP_SERVERS=b-1.msk.example.eu-central-1.amazonaws.com:9098"
        })
class KafkaMskIamProfileTest {

    @Autowired
    Environment environment;

    @Test
    void profileGroupPullsInTheGenericKafkaProfile() {
        assertTrue(List.of(environment.getActiveProfiles()).contains("kafka"));
    }

    @Test
    void iamSaslEntriesReachBothClientConfigs() {
        var properties =
                Binder.get(environment).bind("kafka", KafkaProperties.class).get();

        for (var config :
                List.of(KafkaConfiguration.producerConfig(properties), KafkaConfiguration.consumerConfig(properties))) {
            assertEquals("b-1.msk.example.eu-central-1.amazonaws.com:9098", config.get("bootstrap.servers"));
            assertEquals("SASL_SSL", config.get("security.protocol"));
            assertEquals("AWS_MSK_IAM", config.get("sasl.mechanism"));
            assertEquals("software.amazon.msk.auth.iam.IAMLoginModule required;", config.get("sasl.jaas.config"));
            assertEquals(
                    "software.amazon.msk.auth.iam.IAMClientCallbackHandler",
                    config.get("sasl.client.callback.handler.class"));
        }
    }

    @Test
    void iamLoginModuleIsOnTheRuntimeClasspath() throws ClassNotFoundException {
        // The jaas.config string is resolved reflectively by the client at connect time — a typo or
        // a missing aws-msk-iam-auth dependency would only surface in production otherwise.
        Class.forName("software.amazon.msk.auth.iam.IAMLoginModule");
        Class.forName("software.amazon.msk.auth.iam.IAMClientCallbackHandler");
    }
}
