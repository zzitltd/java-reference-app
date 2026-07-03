package hu.zzit.reference.k8s;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Kubernetes API example — optional, gated on {@code k8s.enabled} (default false). The
 * {@link KubernetesClient} bean is the generic, copy-me part: in-cluster it authenticates with the
 * pod's service account automatically (the RBAC the app needs is documented in
 * {@code k8s/leader-election-rbac.yaml}); outside the cluster {@code k8s.kubeconfig} points it at a
 * kubeconfig file (the {@code local-k8s} profile uses the compose-managed k3s's).
 *
 * <p>{@link LeaderElection} is the use case on top: Lease-based leader election, THE tool for
 * "exactly one replica does this" work. Keep the client, delete the elector if you don't need it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "k8s.enabled", havingValue = "true")
@EnableConfigurationProperties(K8sProperties.class)
class K8sConfiguration {

    @Bean(destroyMethod = "close")
    KubernetesClient kubernetesClient(K8sProperties properties) throws IOException {
        if (properties.kubeconfig() != null) {
            return new KubernetesClientBuilder()
                    .withConfig(Config.fromKubeconfig(Files.readString(Path.of(properties.kubeconfig()))))
                    .build();
        }
        return new KubernetesClientBuilder().build();
    }

    @Bean
    LeaderElection leaderElection(KubernetesClient client, K8sProperties properties) {
        return new LeaderElection(client, properties);
    }
}
