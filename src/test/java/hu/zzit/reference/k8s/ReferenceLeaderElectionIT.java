package hu.zzit.reference.k8s;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ServiceAccountBuilder;
import io.fabric8.kubernetes.api.model.authentication.TokenRequestBuilder;
import io.fabric8.kubernetes.api.model.coordination.v1.Lease;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * Integration test for the Kubernetes example against a real API server (k3s via Testcontainers).
 * The app runs the election as the {@code reference-app} service account with exactly the RBAC
 * documented in {@code k8s/leader-election-rbac.yaml} — not as cluster admin, which would hide a
 * missing permission. Being the only candidate it must acquire the Lease, keep renewing it past the
 * renew deadline, and — after losing it to another holder — rejoin the election and win it back.
 * Runs under failsafe ({@code *IT}).
 *
 * <p>The image tag is pinned to match {@code compose.yaml}. {@code @DirtiesContext}: the context
 * must close while its k3s is still running — kept cached until JVM exit, the elector's
 * shutdown-time lease release would stall against the long-gone API server.
 */
@SpringBootTest
@ActiveProfiles("k8s")
@Testcontainers
@DirtiesContext
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReferenceLeaderElectionIT {

    private static final String NAMESPACE = "default";
    private static final String LEASE = "reference-app-leader";
    private static final String SERVICE_ACCOUNT = "reference-app";

    @Container
    @SuppressWarnings("resource") // no leak: the @Testcontainers extension starts/stops @Container fields
    static final K3sContainer K3S = new K3sContainer(DockerImageName.parse("rancher/k3s:v1.37.0-k3s1"));

    @DynamicPropertySource
    static void k8sProperties(DynamicPropertyRegistry registry) {
        registry.add("k8s.kubeconfig", ReferenceLeaderElectionIT::serviceAccountKubeconfig);
        registry.add("k8s.namespace", () -> NAMESPACE);
        registry.add("k8s.identity", () -> "reference-it");
    }

    @Autowired
    LeaderElection leaderElection;

    @Test
    @Order(1)
    void acquiresTheLeaseAndKeepsRenewingIt() throws InterruptedException {
        awaitTrue(leaderElection::isLeader, Duration.ofSeconds(60), "should have become leader within 60s");
        Instant acquired = Instant.now();

        // Past the 10 s renew deadline: only a replica that can actually renew is still leader here.
        Thread.sleep(Duration.ofSeconds(20));
        assertTrue(leaderElection.isLeader(), "should still be leader after the renew deadline");

        Lease lease;
        try (KubernetesClient admin = admin()) {
            lease = admin.leases().inNamespace(NAMESPACE).withName(LEASE).get();
        }
        assertEquals("reference-it", lease.getSpec().getHolderIdentity());
        assertTrue(
                lease.getSpec().getRenewTime().toInstant().isAfter(acquired.plusSeconds(10)),
                "the lease should have been renewed recently");
    }

    @Test
    @Order(2)
    void rejoinsTheElectionAfterLosingTheLease() {
        awaitTrue(leaderElection::isLeader, Duration.ofSeconds(60), "should be leader before the takeover");

        // Another holder takes the Lease and then disappears without renewing it.
        ZonedDateTime now = ZonedDateTime.now().truncatedTo(ChronoUnit.MICROS);
        try (KubernetesClient admin = admin()) {
            admin.leases().inNamespace(NAMESPACE).withName(LEASE).edit(l -> {
                l.getSpec().setHolderIdentity("intruder");
                l.getSpec().setAcquireTime(now);
                l.getSpec().setRenewTime(now);
                return l;
            });
        }

        awaitTrue(() -> !leaderElection.isLeader(), Duration.ofSeconds(30), "should notice it lost the lease");
        awaitTrue(
                leaderElection::isLeader,
                Duration.ofSeconds(60),
                "should rejoin the election and win the lease back once the intruder's lease expires");
    }

    /**
     * Applies the documented RBAC, creates the service account and returns a kubeconfig that
     * authenticates as it (a TokenRequest token), so the app gets exactly those permissions.
     */
    private static String serviceAccountKubeconfig() {
        try (KubernetesClient admin = admin()) {
            admin.serviceAccounts()
                    .inNamespace(NAMESPACE)
                    .resource(new ServiceAccountBuilder()
                            .withNewMetadata()
                            .withName(SERVICE_ACCOUNT)
                            .endMetadata()
                            .build())
                    .serverSideApply();
            try (InputStream rbac = Files.newInputStream(Path.of("k8s/leader-election-rbac.yaml"))) {
                admin.load(rbac).inNamespace(NAMESPACE).serverSideApply();
            }
            String token = admin.serviceAccounts()
                    .inNamespace(NAMESPACE)
                    .withName(SERVICE_ACCOUNT)
                    .tokenRequest(new TokenRequestBuilder()
                            .withNewSpec()
                            .withExpirationSeconds(3600L)
                            .endSpec()
                            .build())
                    .getStatus()
                    .getToken();
            Config config = Config.fromKubeconfig(K3S.getKubeConfigYaml());
            Path kubeconfig = Files.createTempFile("k3s-sa-kubeconfig", ".yaml");
            Files.writeString(kubeconfig, """
                    apiVersion: v1
                    kind: Config
                    clusters:
                      - name: k3s
                        cluster:
                          server: %s
                          certificate-authority-data: %s
                    users:
                      - name: %s
                        user:
                          token: %s
                    contexts:
                      - name: sa
                        context:
                          cluster: k3s
                          user: %s
                          namespace: %s
                    current-context: sa
                    """.formatted(
                    config.getMasterUrl(), config.getCaCertData(), SERVICE_ACCOUNT, token, SERVICE_ACCOUNT, NAMESPACE));
            return kubeconfig.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static KubernetesClient admin() {
        return new KubernetesClientBuilder()
                .withConfig(Config.fromKubeconfig(K3S.getKubeConfigYaml()))
                .build();
    }

    private static void awaitTrue(BooleanSupplier condition, Duration timeout, String message) {
        Instant deadline = Instant.now().plus(timeout);
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertTrue(condition.getAsBoolean(), message);
    }
}
