package hu.zzit.reference.k8s;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kubernetes example settings ({@code k8s.*}). Everything except {@code enabled} has a sensible
 * in-cluster default: the client authenticates with the pod's service account, {@code namespace}
 * falls back to the pod's own, {@code identity} to the hostname (= the pod name).
 *
 * @param enabled activates the example (the {@code k8s} / {@code local-k8s} profiles set it)
 * @param namespace where the Lease lives; default: the client's current namespace
 * @param leaseName name of the coordination.k8s.io Lease used for leader election
 * @param identity this instance's name in the election; default: the hostname (pod name)
 * @param kubeconfig optional kubeconfig path for OUTSIDE-cluster use (e.g. the local k3s);
 *     default: the standard resolution (service account in-cluster, ~/.kube/config outside)
 */
@ConfigurationProperties("k8s")
record K8sProperties(
        @DefaultValue("false") boolean enabled,
        String namespace,
        @DefaultValue("reference-app-leader") String leaseName,
        String identity,
        String kubeconfig) {}
