package org.csanchez.jenkins.plugins.kubernetes;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Queue;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.Permission;
import hudson.slaves.AbstractCloudComputer;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.acegisecurity.Authentication;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.kubernetes.auth.KubernetesAuthException;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.framework.io.ByteBuffer;
import org.kohsuke.stapler.framework.io.LargeText;

/**
 * @author Carlos Sanchez carlos@apache.org
 */
public class KubernetesComputer extends AbstractCloudComputer<KubernetesSlave> {
    private static final Logger LOGGER = Logger.getLogger(KubernetesComputer.class.getName());

    private boolean launching;

    public KubernetesComputer(KubernetesSlave slave) {
        super(slave);
    }

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        super.taskAccepted(executor, task);
        Queue.Executable exec = executor.getCurrentExecutable();
        LOGGER.log(Level.FINE, " Computer {0} accepted task {1}", new Object[] {this, exec});
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        Queue.Executable exec = executor.getCurrentExecutable();
        LOGGER.log(Level.FINE, " Computer {0} completed task {1}", new Object[] {this, exec});

        // May take the agent offline and remove it, in which case getNode()
        // above would return null and we'd not find our DockerSlave anymore.
        super.taskCompleted(executor, task, durationMS);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        super.taskCompletedWithProblems(executor, task, durationMS, problems);
        Queue.Executable exec = executor.getCurrentExecutable();
        LOGGER.log(Level.FINE, " Computer {0} completed task {1} with problems", new Object[] {this, exec});
    }

    @Exported
    public List<Container> getContainers() throws KubernetesAuthException, IOException {
        if (!Jenkins.get().hasPermission(Computer.EXTENDED_READ)) {
            LOGGER.log(Level.FINE, " Computer {0} getContainers, lack of admin permission, returning empty list", this);
            return Collections.emptyList();
        }

        KubernetesSlave slave = getNode();
        if (slave == null) {
            return Collections.emptyList();
        }

        KubernetesCloud cloud = slave.getKubernetesCloud();
        KubernetesClient client = cloud.connect();

        String namespace = StringUtils.defaultIfBlank(slave.getNamespace(), client.getNamespace());
        Pod pod = client.pods().inNamespace(namespace).withName(getName()).get();

        if (pod == null) {
            return Collections.emptyList();
        }

        return pod.getSpec().getContainers();
    }

    @Exported
    public List<Event> getPodEvents() throws KubernetesAuthException, IOException {
        if (!Jenkins.get().hasPermission(Computer.EXTENDED_READ)) {
            LOGGER.log(Level.FINE, " Computer {0} getPodEvents, lack of admin permission, returning empty list", this);
            return Collections.emptyList();
        }

        KubernetesSlave slave = getNode();
        if (slave != null) {
            KubernetesCloud cloud = slave.getKubernetesCloud();
            KubernetesClient client = cloud.connect();

            String namespace = StringUtils.defaultIfBlank(slave.getNamespace(), client.getNamespace());

            Pod pod = client.pods().inNamespace(namespace).withName(getName()).get();
            if (pod != null) {
                ObjectMeta podMeta = pod.getMetadata();
                String podNamespace = podMeta.getNamespace();

                Map<String, String> fields = new HashMap<>();
                fields.put("involvedObject.uid", podMeta.getUid());
                fields.put("involvedObject.name", podMeta.getName());
                fields.put("involvedObject.namespace", podNamespace);

                EventList eventList = client.v1()
                        .events()
                        .inNamespace(podNamespace)
                        .withFields(fields)
                        .list();
                if (eventList != null) {
                    return eventList.getItems();
                }
            }
        }

        return Collections.emptyList();
    }

    public void doContainerLog(@QueryParameter String containerId, StaplerRequest2 req, StaplerResponse2 rsp)
            throws KubernetesAuthException, IOException {
        Jenkins.get().checkPermission(Computer.EXTENDED_READ);

        ByteBuffer outputStream = new ByteBuffer();
        LargeText text = new LargeText(outputStream, false);
        KubernetesSlave slave = getNode();
        if (slave != null) {
            KubernetesCloud cloud = slave.getKubernetesCloud();
            String namespace = StringUtils.defaultIfBlank(slave.getNamespace(), cloud.getNamespace());
            PodResource resource = cloud.getPodResource(namespace, containerId);

            // check if pod exists
            Pod pod = resource.get();
            if (pod == null) {
                outputStream.write("Pod not found".getBytes(StandardCharsets.UTF_8));
                text.markAsComplete();
                text.doProgressText(req, rsp);
                return;
            }

            // Check if container exists and is running (maybe terminated if ephemeral)
            Optional<ContainerStatus> status = PodContainerSource.lookupContainerStatus(pod, containerId);
            if (status.isPresent()) {
                ContainerStatus cs = status.get();
                if (cs.getState().getTerminated() != null) {
                    outputStream.write("Container terminated".getBytes(StandardCharsets.UTF_8));
                    text.markAsComplete();
                    text.doProgressText(req, rsp);
                    return;
                }
            } else {
                outputStream.write("Container not found".getBytes(StandardCharsets.UTF_8));
                text.markAsComplete();
                text.doProgressText(req, rsp);
                return;
            }

            // Get logs
            try (LogWatch ignore =
                    resource.inContainer(containerId).tailingLines(20).watchLog(outputStream)) {
                text.doProgressText(req, rsp);
            } catch (KubernetesClientException kce) {
                LOGGER.log(Level.WARNING, "Failed getting container logs for " + containerId, kce);
            }
        } else {
            outputStream.write("Node not available".getBytes(StandardCharsets.UTF_8));
            text.markAsComplete();
            text.doProgressText(req, rsp);
        }
    }

    // TODO delete after https://github.com/jenkinsci/jenkins/pull/10595
    @Override
    public String toString() {
        return String.format("KubernetesComputer[%s]", getNode());
    }

    @Override
    @NonNull
    public ACL getACL() {
        final ACL base = super.getACL();
        return new KubernetesComputerACL(base);
    }

    public void annotateTtl(TaskListener listener) {
        Optional.ofNullable(getNode()).ifPresent(ks -> ks.annotateTtl(listener));
    }

    /**
     * Simple static inner class to be used by {@link #getACL()}.
     * It replaces an anonymous inner class in order to fix
     * <a href="https://spotbugs.readthedocs.io/en/stable/bugDescriptions.html#sic-could-be-refactored-into-a-named-static-inner-class-sic-inner-should-be-static-anon">SIC_INNER_SHOULD_BE_STATIC_ANON</a>.
     */
    private static final class KubernetesComputerACL extends ACL {

        private final ACL base;

        public KubernetesComputerACL(final ACL base) {
            this.base = base;
        }

        @Override
        public boolean hasPermission(Authentication a, Permission permission) {
            return permission == Computer.CONFIGURE ? false : base.hasPermission(a, permission);
        }
    }

    public void setLaunching(boolean launching) {
        this.launching = launching;
    }

    /**
     *
     * @return true if the Pod has been created in Kubernetes and the current instance is waiting for the pod to be usable.
     */
    public boolean isLaunching() {
        return launching;
    }

    @Override
    public void setAcceptingTasks(boolean acceptingTasks) {
        super.setAcceptingTasks(acceptingTasks);
        if (acceptingTasks) {
            launching = false;
        }
    }
}
