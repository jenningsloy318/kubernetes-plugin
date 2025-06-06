package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Resources.*;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.EnvVars;
import hudson.LauncherDecorator;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.BodyInvoker;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

public class ContainerStepExecution extends StepExecution {

    private static final long serialVersionUID = 7634132798345235774L;

    private static final Logger LOGGER = Logger.getLogger(ContainerStepExecution.class.getName());

    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
    private final transient ContainerStep step;

    private ContainerExecDecorator decorator;

    ContainerStepExecution(ContainerStep step, StepContext context) {
        super(context);
        this.step = step;
    }

    @Override
    public boolean start() throws Exception {
        LOGGER.log(Level.FINE, "Starting container step.");
        String containerName = step.getName();
        String shell = step.getShell();

        KubernetesNodeContext nodeContext = new KubernetesNodeContext(getContext());

        EnvironmentExpander env = EnvironmentExpander.merge(
                getContext().get(EnvironmentExpander.class),
                EnvironmentExpander.constant(Collections.singletonMap("POD_CONTAINER", containerName)));

        EnvVars globalVars = null;
        Jenkins instance = Jenkins.get();

        DescribableList<NodeProperty<?>, NodePropertyDescriptor> globalNodeProperties =
                instance.getGlobalNodeProperties();
        List<EnvironmentVariablesNodeProperty> envVarsNodePropertyList =
                globalNodeProperties.getAll(EnvironmentVariablesNodeProperty.class);
        if (envVarsNodePropertyList != null && envVarsNodePropertyList.size() != 0) {
            globalVars = envVarsNodePropertyList.get(0).getEnvVars();
        }

        EnvVars rcEnvVars = null;
        Run run = getContext().get(Run.class);
        TaskListener taskListener = getContext().get(TaskListener.class);
        if (run != null && taskListener != null) {
            rcEnvVars = run.getEnvironment(taskListener);
        }

        decorator = new ContainerExecDecorator();
        decorator.setNodeContext(nodeContext);
        decorator.setContainerName(containerName);
        decorator.setEnvironmentExpander(env);
        decorator.setGlobalVars(globalVars);
        decorator.setRunContextEnvVars(rcEnvVars);
        decorator.setShell(shell);
        getContext()
                .newBodyInvoker()
                .withContexts(
                        BodyInvoker.mergeLauncherDecorators(getContext().get(LauncherDecorator.class), decorator), env)
                .withCallback(closeQuietlyCallback(decorator))
                .start();
        return false;
    }

    @Override
    public void stop(@NonNull Throwable cause) throws Exception {
        LOGGER.log(Level.FINE, "Stopping container step.");
        closeQuietly(getContext(), decorator);
    }

    /**
     * This class has been replaced but is not deleted to prevent {@code ClassNotFoundException}.
     * See <a href="https://issues.jenkins.io/browse/JENKINS-75720">JENKINS-75720</a>
     * @deprecated replaced {@link Resources#closeQuietlyCallback(Closeable...)}
     */
    @Deprecated
    @SuppressFBWarnings("SE_BAD_FIELD")
    private static class ContainerExecCallback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 6385838254761750483L;

        private final Closeable[] closeables;

        private ContainerExecCallback(Closeable... closeables) {
            this.closeables = closeables;
        }

        @Override
        public void finished(StepContext context) {
            closeQuietly(context, closeables);
        }
    }
}
