package hu.zzit.reference.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderCallbacks;
import io.fabric8.kubernetes.client.extended.leaderelection.LeaderElectionConfigBuilder;
import io.fabric8.kubernetes.client.extended.leaderelection.resourcelock.LeaseLock;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Leader election over a {@code coordination.k8s.io} Lease: all replicas compete for one Lease and
 * exactly one holds it at a time. Guard singleton work with {@link #isLeader()} — scheduled jobs,
 * migrations of in-flight state, anything that must not run on every replica.
 *
 * <p>Fabric8's {@code LeaderElector} does the heavy lifting (acquire/renew/release loop with
 * fencing); this class only wires it to the Spring lifecycle. The timings below are the upstream
 * Kubernetes client-go defaults scaled down slightly: lease 15s, renew 10s, retry 2s — a dead
 * leader is replaced within ~15s. The lease is released on shutdown, so rollouts hand over
 * leadership immediately.
 */
public class LeaderElection {

    private static final Logger log = LoggerFactory.getLogger(LeaderElection.class);

    private final KubernetesClient client;
    private final K8sProperties properties;
    private final AtomicBoolean leader = new AtomicBoolean();
    private CompletableFuture<?> election;

    LeaderElection(KubernetesClient client, K8sProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @PostConstruct
    void start() {
        String namespace = properties.namespace() != null
                ? properties.namespace()
                : (client.getNamespace() != null ? client.getNamespace() : "default");
        String identity = properties.identity() != null ? properties.identity() : hostName();
        log.info("joining leader election for lease {}/{} as {}", namespace, properties.leaseName(), identity);
        election = client.leaderElector()
                .withConfig(new LeaderElectionConfigBuilder()
                        .withName(properties.leaseName())
                        .withLock(new LeaseLock(namespace, properties.leaseName(), identity))
                        .withLeaseDuration(Duration.ofSeconds(15))
                        .withRenewDeadline(Duration.ofSeconds(10))
                        .withRetryPeriod(Duration.ofSeconds(2))
                        .withReleaseOnCancel(true)
                        .withLeaderCallbacks(new LeaderCallbacks(
                                () -> {
                                    leader.set(true);
                                    log.info("became leader");
                                },
                                () -> {
                                    leader.set(false);
                                    log.info("stopped leading");
                                },
                                newLeader -> log.info("current leader: {}", newLeader)))
                        .build())
                .build()
                .start();
    }

    /** True while THIS instance holds the lease — the guard for run-on-one-replica-only work. */
    public boolean isLeader() {
        return leader.get();
    }

    @PreDestroy
    void stop() {
        if (election != null) {
            election.cancel(true); // releaseOnCancel: hands the lease over immediately
        }
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + UUID.randomUUID();
        }
    }
}
