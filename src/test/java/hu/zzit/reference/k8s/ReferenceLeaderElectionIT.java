package hu.zzit.reference.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.k3s.K3sContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Integration test for the Kubernetes example: a real API server (k3s via Testcontainers) is
 * started, the app joins the leader election, and — being the only candidate — must acquire the
 * Lease: {@code isLeader()} turns true and the {@code coordination.k8s.io} Lease object names this
 * instance as holder. Runs under failsafe ({@code *IT}).
 *
 * <p>The image tag is pinned to match {@code compose.yaml}. {@code @DirtiesContext}: the context
 * must close while its k3s is still running — kept cached until JVM exit, the elector's
 * shutdown-time lease release would stall against the long-gone API server.
 */
@SpringBootTest
@ActiveProfiles("k8s")
@Testcontainers
@DirtiesContext
class ReferenceLeaderElectionIT {

    @Container
    @SuppressWarnings("resource") // no leak: the @Testcontainers extension starts/stops @Container fields
    static final K3sContainer K3S = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.36.2-k3s1"));

    @DynamicPropertySource
    static void k8sProperties(DynamicPropertyRegistry registry) {
        registry.add("k8s.kubeconfig", () -> {
            try {
                Path kubeconfig = Files.createTempFile("k3s-kubeconfig", ".yaml");
                Files.writeString(kubeconfig, K3S.getKubeConfigYaml());
                return kubeconfig.toString();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        registry.add("k8s.namespace", () -> "default");
        registry.add("k8s.identity", () -> "reference-it");
    }

    @Autowired
    LeaderElection leaderElection;

    @Autowired
    KubernetesClient client;

    @Test
    void acquiresTheLeaseAsSoleCandidate() throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (!leaderElection.isLeader() && Instant.now().isBefore(deadline)) {
            Thread.sleep(250);
        }
        assertTrue(leaderElection.isLeader(), "should have become leader within 60s");

        var lease = client.leases()
                .inNamespace("default")
                .withName("reference-app-leader")
                .get();
        assertEquals("reference-it", lease.getSpec().getHolderIdentity());
    }
}
