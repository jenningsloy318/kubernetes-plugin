package org.csanchez.jenkins.plugins.kubernetes;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.Saveable;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.security.Permission;
import hudson.slaves.NodeProperty;
import hudson.util.FormApply;
import hudson.util.XStream2;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.csanchez.jenkins.plugins.kubernetes.model.TemplateEnvVar;
import org.csanchez.jenkins.plugins.kubernetes.pod.retention.PodRetention;
import org.csanchez.jenkins.plugins.kubernetes.pod.yaml.YamlMergeStrategy;
import org.csanchez.jenkins.plugins.kubernetes.volumes.PodVolume;
import org.csanchez.jenkins.plugins.kubernetes.volumes.workspace.WorkspaceVolume;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.Stapler;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;

/**
 * Kubernetes Pod Template
 *
 * @author <a href="mailto:nicolas.deloof@gmail.com">Nicolas De Loof</a>
 */
public class PodTemplate extends AbstractDescribableImpl<PodTemplate> implements Serializable, Saveable {

    private static final long serialVersionUID = 3285310269140845583L;

    private static final String FALLBACK_ARGUMENTS = "${computer.jnlpmac} ${computer.name}";

    private static final String DEFAULT_LABEL = "slave-default";

    private static final Logger LOGGER = Logger.getLogger(PodTemplate.class.getName());

    /**
     * Connection timeout expiration in seconds, default to 1000 seconds
     */
    public static final Integer DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT =
            Integer.getInteger(PodTemplate.class.getName() + ".connectionTimeout", 1000);

    public static final String JENKINS_LABEL = "jenkins/label";
    public static final String JENKINS_LABEL_DIGEST = "jenkins/label-digest";

    /**
     * The run this pod template is associated with.
     * Only applicable to pod templates defined by the `podTemplate` step.
     */
    @CheckForNull
    private transient Run<?, ?> run;

    /**
     * Digest function that is used to compute the kubernetes label "jenkins/label-digest"
     * Not used for security.
     */
    protected static MessageDigest getLabelDigestFunction() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            // will never happen, SHA-1 support required on every Java implementation
            e.printStackTrace();
            // throw error to allow variable to be set as final
            throw new AssertionError(e);
        }
    }

    private String id;

    private transient boolean unwrapped;

    private String inheritFrom;

    private String name;

    private String namespace;

    private String image;

    private boolean privileged;

    private Long runAsUser;

    private Long runAsGroup;

    private String supplementalGroups;

    private boolean capOnlyOnAlivePods;

    private boolean alwaysPullImage;

    private String command;

    private String args;

    private String remoteFs;

    private int instanceCap = Integer.MAX_VALUE;

    private int slaveConnectTimeout = DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT;

    private int idleMinutes;

    private int activeDeadlineSeconds;

    private String label;

    /**
     * Set of label atoms this pod template provides in Jenkins. Jenkins indexes them on creation.
     */
    private transient Set<LabelAtom> labelSet;

    private transient Map<String, String> labelsMap;

    private String serviceAccount;

    private String schedulerName;

    private String nodeSelector;

    private Node.Mode nodeUsageMode;

    private String resourceRequestCpu;

    private String resourceRequestMemory;

    private String resourceRequestEphemeralStorage;

    private String resourceLimitCpu;

    private String resourceLimitMemory;

    private String resourceLimitEphemeralStorage;

    private Boolean hostNetwork;

    private WorkspaceVolume workspaceVolume;

    private final List<PodVolume> volumes = new ArrayList<>();

    private List<ContainerTemplate> containers = new ArrayList<>();

    private List<TemplateEnvVar> envVars = new ArrayList<>();

    private List<PodAnnotation> annotations = new ArrayList<>();

    private List<PodImagePullSecret> imagePullSecrets = new ArrayList<>();

    private PodTemplateToolLocation nodeProperties;

    private Long terminationGracePeriodSeconds;

    private String agentContainer;

    private boolean agentInjection;

    /**
     * Persisted yaml fragment
     */
    private String yaml;

    /**
     * List of yaml fragments used for transient pod templates. Never persisted
     */
    private transient List<String> yamls;

    private transient boolean readonlyFromUi;

    @NonNull
    public String getId() {
        return id;
    }

    @CheckForNull
    public YamlMergeStrategy getYamlMergeStrategy() {
        return yamlMergeStrategy;
    }

    @NonNull
    public YamlMergeStrategy getResolvedYamlMergeStrategy() {
        return Optional.ofNullable(getYamlMergeStrategy()).orElse(YamlMergeStrategy.defaultStrategy());
    }

    @DataBoundSetter
    public void setYamlMergeStrategy(@CheckForNull YamlMergeStrategy yamlMergeStrategy) {
        this.yamlMergeStrategy = yamlMergeStrategy;
    }

    private YamlMergeStrategy yamlMergeStrategy;

    public Pod getYamlsPod() {
        return getResolvedYamlMergeStrategy().merge(getYamls());
    }

    @CheckForNull
    private Boolean inheritYamlMergeStrategy;

    private Boolean showRawYaml;

    /**
     * Listener of the run that created this pod template, if applicable
     */
    @CheckForNull
    private transient TaskListener listener;

    @CheckForNull
    private PodRetention podRetention;

    public PodTemplate() {
        this((String) null);
    }

    @DataBoundConstructor
    public PodTemplate(@CheckForNull String id) {
        if (Util.fixEmpty(id) == null) {
            this.id = UUID.randomUUID().toString();
        } else {
            this.id = id;
        }
        recomputeLabelDerivedFields();
    }

    public PodTemplate(PodTemplate from) {
        XStream2 xs = new XStream2();
        xs.unmarshal(XStream2.getDefaultDriver().createReader(new StringReader(xs.toXML(from))), this);
        this.yamls = from.yamls;
        this.listener = from.listener;
        recomputeLabelDerivedFields();
    }

    @Deprecated
    public PodTemplate(String image, List<? extends PodVolume> volumes) {
        this(null, image, volumes);
    }

    @Deprecated
    PodTemplate(String name, String image, List<? extends PodVolume> volumes) {
        this(name, volumes, Collections.emptyList());
        if (image != null) {
            getContainers().add(new ContainerTemplate(name, image));
        }
    }

    @Restricted(NoExternalUse.class) // testing only
    PodTemplate(String name, List<? extends PodVolume> volumes, List<? extends ContainerTemplate> containers) {
        this();
        this.name = name;
        this.volumes.addAll(volumes);
        this.containers.addAll(containers);
    }

    private Optional<ContainerTemplate> getFirstContainer() {
        return Optional.ofNullable(
                getContainers().isEmpty() ? null : getContainers().get(0));
    }

    public String getInheritFrom() {
        return inheritFrom;
    }

    @DataBoundSetter
    public void setInheritFrom(String inheritFrom) {
        this.inheritFrom = Util.fixEmptyAndTrim(inheritFrom);
    }

    @DataBoundSetter
    public void setName(String name) {
        this.name = Util.fixEmptyAndTrim(name);
    }

    public String getName() {
        return name;
    }

    public String getNamespace() {
        return namespace;
    }

    @DataBoundSetter
    public void setNamespace(String namespace) {
        this.namespace = Util.fixEmptyAndTrim(namespace);
    }

    @Deprecated
    public String getImage() {
        return getFirstContainer().map(ContainerTemplate::getImage).orElse(null);
    }

    @Deprecated
    @DataBoundSetter
    public void setCommand(String command) {
        getFirstContainer().ifPresent((i) -> i.setCommand(command));
    }

    @Deprecated
    public String getCommand() {
        return getFirstContainer().map(ContainerTemplate::getCommand).orElse(null);
    }

    @Deprecated
    @DataBoundSetter
    public void setArgs(String args) {
        getFirstContainer().ifPresent((i) -> i.setArgs(args));
    }

    @Deprecated
    public String getArgs() {
        return getFirstContainer().map(ContainerTemplate::getArgs).orElse(null);
    }

    @Deprecated // why would you use this method? It returns the constant "Kubernetes Pod Template".
    public String getDisplayName() {
        return "Kubernetes Pod Template";
    }

    @DataBoundSetter
    @Deprecated
    public void setRemoteFs(String remoteFs) {
        getFirstContainer().ifPresent((i) -> i.setWorkingDir(remoteFs));
    }

    @Deprecated
    public String getRemoteFs() {
        return getFirstContainer().map(ContainerTemplate::getWorkingDir).orElse(ContainerTemplate.DEFAULT_WORKING_DIR);
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        if (instanceCap < 0) {
            this.instanceCap = Integer.MAX_VALUE;
        } else {
            this.instanceCap = instanceCap;
        }
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    @DataBoundSetter
    public void setSlaveConnectTimeout(int slaveConnectTimeout) {
        if (slaveConnectTimeout <= 0) {
            LOGGER.log(
                    Level.WARNING,
                    "Agent -> Jenkins connection timeout " + "cannot be <= 0. Falling back to the default value: "
                            + DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT);
            this.slaveConnectTimeout = DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT;
        } else {
            this.slaveConnectTimeout = slaveConnectTimeout;
        }
    }

    public int getSlaveConnectTimeout() {
        if (slaveConnectTimeout == 0) return DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT;
        return slaveConnectTimeout;
    }

    @DataBoundSetter
    public void setInstanceCapStr(String instanceCapStr) {
        if (StringUtils.isBlank(instanceCapStr)) {
            setInstanceCap(Integer.MAX_VALUE);
        } else {
            setInstanceCap(Integer.parseInt(instanceCapStr));
        }
    }

    public String getInstanceCapStr() {
        if (getInstanceCap() == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(instanceCap);
        }
    }

    @DataBoundSetter
    public void setSlaveConnectTimeoutStr(String slaveConnectTimeoutStr) {
        if (StringUtils.isBlank(slaveConnectTimeoutStr)) {
            setSlaveConnectTimeout(DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT);
        } else {
            setSlaveConnectTimeout(Integer.parseInt(slaveConnectTimeoutStr));
        }
    }

    public String getSlaveConnectTimeoutStr() {
        return String.valueOf(slaveConnectTimeout);
    }

    @DataBoundSetter
    public void setIdleMinutes(int i) {
        this.idleMinutes = i;
    }

    public int getIdleMinutes() {
        return idleMinutes;
    }

    @DataBoundSetter
    public void setActiveDeadlineSeconds(int i) {
        this.activeDeadlineSeconds = i;
    }

    public int getActiveDeadlineSeconds() {
        return activeDeadlineSeconds;
    }

    @DataBoundSetter
    public void setIdleMinutesStr(String idleMinutes) {
        if (StringUtils.isBlank(idleMinutes)) {
            setIdleMinutes(0);
        } else {
            setIdleMinutes(Integer.parseInt(idleMinutes));
        }
    }

    public String getIdleMinutesStr() {
        if (getIdleMinutes() == 0) {
            return "";
        } else {
            return String.valueOf(idleMinutes);
        }
    }

    @DataBoundSetter
    public void setActiveDeadlineSecondsStr(String activeDeadlineSeconds) {
        if (StringUtils.isBlank(activeDeadlineSeconds)) {
            setActiveDeadlineSeconds(0);
        } else {
            setActiveDeadlineSeconds(Integer.parseInt(activeDeadlineSeconds));
        }
    }

    public String getActiveDeadlineSecondsStr() {
        if (getActiveDeadlineSeconds() == 0) {
            return "";
        } else {
            return String.valueOf(activeDeadlineSeconds);
        }
    }

    public Set<LabelAtom> getLabelSet() {
        return labelSet;
    }

    public Map<String, String> getLabelsMap() {
        return labelsMap;
    }

    @DataBoundSetter
    public void setLabel(String label) {
        this.label = Util.fixEmptyAndTrim(label);
        recomputeLabelDerivedFields();
    }

    private void recomputeLabelDerivedFields() {
        this.labelSet = Label.parse(label);
        Map<String, String> tempMap = new HashMap<>();
        if (label == null) {
            tempMap.put(JENKINS_LABEL, DEFAULT_LABEL);
            tempMap.put(JENKINS_LABEL_DIGEST, "0");
        } else {
            MessageDigest labelDigestFunction = getLabelDigestFunction();
            labelDigestFunction.update(label.getBytes(StandardCharsets.UTF_8));
            tempMap.put(JENKINS_LABEL, PodTemplateUtils.sanitizeLabel(label));
            tempMap.put(JENKINS_LABEL_DIGEST, String.format("%040x", new BigInteger(1, labelDigestFunction.digest())));
        }
        labelsMap = Collections.unmodifiableMap(tempMap);
    }

    public String getLabel() {
        return label;
    }

    @DataBoundSetter
    public void setNodeSelector(String nodeSelector) {
        this.nodeSelector = Util.fixEmptyAndTrim(nodeSelector);
    }

    public String getNodeSelector() {
        return nodeSelector;
    }

    @DataBoundSetter
    public void setNodeUsageMode(Node.Mode nodeUsageMode) {
        this.nodeUsageMode = nodeUsageMode == Node.Mode.EXCLUSIVE ? null : nodeUsageMode;
    }

    @DataBoundSetter
    public void setNodeUsageMode(String nodeUsageMode) {
        setNodeUsageMode(Node.Mode.valueOf(nodeUsageMode));
    }

    public Node.Mode getNodeUsageMode() {
        return nodeUsageMode == null ? Node.Mode.EXCLUSIVE : nodeUsageMode;
    }

    @Deprecated
    @DataBoundSetter
    public void setPrivileged(boolean privileged) {
        getFirstContainer().ifPresent((i) -> i.setPrivileged(privileged));
    }

    @Deprecated
    public boolean isPrivileged() {
        return getFirstContainer().map(ContainerTemplate::isPrivileged).orElse(false);
    }

    @DataBoundSetter
    public void setRunAsUser(String runAsUser) {
        this.runAsUser = PodTemplateUtils.parseLong(runAsUser);
    }

    public String getRunAsUser() {
        return runAsUser == null ? null : runAsUser.toString();
    }

    public Long getRunAsUserAsLong() {
        return runAsUser;
    }

    @DataBoundSetter
    public void setRunAsGroup(String runAsGroup) {
        this.runAsGroup = PodTemplateUtils.parseLong(runAsGroup);
    }

    public String getRunAsGroup() {
        return runAsGroup == null ? null : runAsGroup.toString();
    }

    public Long getRunAsGroupAsLong() {
        return runAsGroup;
    }

    @DataBoundSetter
    public void setSupplementalGroups(String supplementalGroups) {
        this.supplementalGroups = Util.fixEmpty(supplementalGroups);
    }

    public String getSupplementalGroups() {
        return this.supplementalGroups;
    }

    @DataBoundSetter
    public void setHostNetwork(Boolean hostNetwork) {
        this.hostNetwork = hostNetwork;
    }

    public boolean isHostNetwork() {
        return isHostNetworkSet() ? hostNetwork.booleanValue() : false;
    }

    public boolean isHostNetworkSet() {
        return hostNetwork != null;
    }

    public String getServiceAccount() {
        return serviceAccount;
    }

    @DataBoundSetter
    public void setServiceAccount(String serviceAccount) {
        this.serviceAccount = Util.fixEmpty(serviceAccount);
    }

    public String getSchedulerName() {
        return schedulerName;
    }

    @DataBoundSetter
    public void setSchedulerName(String schedulerName) {
        this.schedulerName = Util.fixEmpty(schedulerName);
    }

    @Deprecated
    @DataBoundSetter
    public void setAlwaysPullImage(boolean alwaysPullImage) {
        getFirstContainer().ifPresent((i) -> i.setAlwaysPullImage(alwaysPullImage));
    }

    @Deprecated
    public boolean isAlwaysPullImage() {
        return getFirstContainer().map(ContainerTemplate::isAlwaysPullImage).orElse(false);
    }

    @DataBoundSetter
    @Deprecated
    public void setCapOnlyOnAlivePods(boolean capOnlyOnAlivePods) {
        this.capOnlyOnAlivePods = capOnlyOnAlivePods;
    }

    @Deprecated
    public boolean isCapOnlyOnAlivePods() {
        return capOnlyOnAlivePods;
    }

    @CheckForNull
    public String getAgentContainer() {
        return agentContainer;
    }

    @DataBoundSetter
    public void setAgentContainer(@CheckForNull String agentContainer) {
        this.agentContainer = Util.fixEmpty(agentContainer);
    }

    public boolean isAgentInjection() {
        return agentInjection;
    }

    @DataBoundSetter
    public void setAgentInjection(boolean agentInjection) {
        this.agentInjection = agentInjection;
    }

    public List<TemplateEnvVar> getEnvVars() {
        if (envVars == null) {
            return Collections.emptyList();
        }
        return envVars;
    }

    public void addEnvVars(List<TemplateEnvVar> envVars) {
        if (envVars != null) {
            this.envVars.addAll(envVars);
        }
    }

    @SuppressWarnings("unused") // Used by jelly
    @Restricted(DoNotUse.class) // Used by jelly
    public boolean hasManagePermission() {
        StaplerRequest2 request = Stapler.getCurrentRequest2();
        if (request != null) {
            PodTemplateGroup groupFromRequest = request.findAncestorObject(PodTemplateGroup.class);
            if (groupFromRequest != null) {
                return groupFromRequest.hasManagePermission();
            }
        }
        return Jenkins.get().hasPermission(Jenkins.MANAGE);
    }

    /**
     * Deletes the template.
     */
    @POST
    public HttpResponse doDoDelete(@AncestorInPath PodTemplateGroup owner) throws IOException {
        if (owner == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        Jenkins j = Jenkins.get();
        owner.checkManagePermission();
        owner.removeTemplate(this);
        j.save();
        // take the user back.
        return new HttpRedirect(owner.getPodTemplateGroupUrl());
    }

    @POST
    public HttpResponse doConfigSubmit(StaplerRequest2 req, @AncestorInPath PodTemplateGroup owner)
            throws IOException, ServletException, Descriptor.FormException {
        if (owner == null) {
            throw new IllegalStateException("Cloud could not be found");
        }
        Jenkins j = Jenkins.get();
        owner.checkManagePermission();
        PodTemplate newTemplate = reconfigure(req, req.getSubmittedForm());
        owner.replaceTemplate(this, newTemplate);
        j.save();
        // take the user back.
        return FormApply.success(owner.getPodTemplateGroupUrl());
    }

    private PodTemplate reconfigure(@NonNull final StaplerRequest2 req, JSONObject form)
            throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        return getDescriptor().newInstance(req, form);
    }

    @DataBoundSetter
    public void setEnvVars(List<TemplateEnvVar> envVars) {
        if (envVars != null) {
            this.envVars.clear();
            this.addEnvVars(envVars);
        }
    }

    public List<PodAnnotation> getAnnotations() {
        if (annotations == null) {
            return Collections.emptyList();
        }
        return annotations;
    }

    public void addAnnotations(List<PodAnnotation> annotations) {
        this.annotations.addAll(annotations);
    }

    @DataBoundSetter
    public void setAnnotations(List<PodAnnotation> annotations) {
        if (annotations != null) {
            this.annotations = new ArrayList<PodAnnotation>();
            this.addAnnotations(annotations);
        }
    }

    public List<PodImagePullSecret> getImagePullSecrets() {
        return imagePullSecrets == null ? Collections.emptyList() : imagePullSecrets;
    }

    public void addImagePullSecrets(List<PodImagePullSecret> imagePullSecrets) {
        this.imagePullSecrets.addAll(imagePullSecrets);
    }

    @DataBoundSetter
    public void setImagePullSecrets(List<PodImagePullSecret> imagePullSecrets) {
        if (imagePullSecrets != null) {
            this.imagePullSecrets.clear();
            this.addImagePullSecrets(imagePullSecrets);
        }
    }

    @DataBoundSetter
    public void setNodeProperties(List<? extends NodeProperty<?>> properties) {
        this.getNodeProperties().clear();
        this.getNodeProperties().addAll(properties);
    }

    @NonNull
    public PodTemplateToolLocation getNodeProperties() {
        if (this.nodeProperties == null) this.nodeProperties = new PodTemplateToolLocation(this);
        return nodeProperties;
    }

    @Deprecated
    public String getResourceRequestMemory() {
        return getFirstContainer()
                .map(ContainerTemplate::getResourceRequestMemory)
                .orElse(null);
    }

    @Deprecated
    @DataBoundSetter
    public void setResourceRequestMemory(String resourceRequestMemory) {
        getFirstContainer().ifPresent((i) -> i.setResourceRequestMemory(resourceRequestMemory));
    }

    @Deprecated
    public String getResourceLimitCpu() {
        return getFirstContainer().map(ContainerTemplate::getResourceLimitCpu).orElse(null);
    }

    @Deprecated
    @DataBoundSetter
    public void setResourceLimitCpu(String resourceLimitCpu) {
        getFirstContainer().ifPresent((i) -> i.setResourceLimitCpu(resourceLimitCpu));
    }

    @Deprecated
    public String getResourceLimitMemory() {
        return getFirstContainer()
                .map(ContainerTemplate::getResourceLimitMemory)
                .orElse(null);
    }

    @Deprecated
    @DataBoundSetter
    public void setResourceLimitMemory(String resourceLimitMemory) {
        getFirstContainer().ifPresent((i) -> i.setResourceLimitMemory(resourceLimitMemory));
    }

    @Deprecated
    public String getResourceRequestCpu() {
        return getFirstContainer().map(ContainerTemplate::getResourceRequestCpu).orElse(null);
    }

    @Deprecated
    @DataBoundSetter
    public void setResourceRequestCpu(String resourceRequestCpu) {
        getFirstContainer().ifPresent((i) -> i.setResourceRequestCpu(resourceRequestCpu));
    }

    @DataBoundSetter
    public void setVolumes(@NonNull List<PodVolume> items) {
        synchronized (this.volumes) {
            this.volumes.clear();
            this.volumes.addAll(items);
        }
    }

    @NonNull
    public List<PodVolume> getVolumes() {
        if (volumes == null) {
            return Collections.emptyList();
        }
        return volumes;
    }

    @NonNull
    public WorkspaceVolume getWorkspaceVolume() {
        return workspaceVolume == null ? WorkspaceVolume.getDefault() : workspaceVolume;
    }

    @DataBoundSetter
    public void setWorkspaceVolume(WorkspaceVolume workspaceVolume) {
        this.workspaceVolume = WorkspaceVolume.getDefault().equals(workspaceVolume) ? null : workspaceVolume;
    }

    @DataBoundSetter
    public void setContainers(@NonNull List<ContainerTemplate> items) {
        synchronized (this.containers) {
            this.containers.clear();
            this.containers.addAll(items);
        }
    }

    @NonNull
    public final List<ContainerTemplate> getContainers() {
        if (containers == null) {
            return Collections.emptyList();
        }
        return containers;
    }

    /**
     * @return The persisted yaml fragment
     */
    public String getYaml() {
        return yaml;
    }

    @DataBoundSetter
    public void setYaml(String yaml) {
        this.yaml = Util.fixEmpty(yaml);
    }

    @NonNull
    public List<String> getYamls() {
        if (yamls == null || yamls.isEmpty()) {
            if (yaml != null) {
                return Collections.singletonList(yaml);
            } else {
                return Collections.emptyList();
            }
        }
        return yamls;
    }

    @Restricted(NoExternalUse.class)
    List<String> _getYamls() {
        return yamls;
    }

    public void setYamls(List<String> yamls) {
        if (yamls != null) {
            List<String> ys = new ArrayList<>();
            for (String y : yamls) {
                String trimmed = Util.fixEmpty(y);
                if (trimmed != null) {
                    ys.add(trimmed);
                }
            }
            this.yamls = ys;
        } else {
            this.yamls = Collections.emptyList();
        }
    }

    public PodRetention getPodRetention() {
        return podRetention == null ? PodRetention.getPodTemplateDefault() : podRetention;
    }

    @DataBoundSetter
    public void setPodRetention(PodRetention podRetention) {
        this.podRetention = PodRetention.getPodTemplateDefault().equals(podRetention) ? null : podRetention;
    }

    /** @see KubernetesSlave#getRunListener */
    @NonNull
    public TaskListener getListener() {
        return listener == null ? TaskListener.NULL : listener;
    }

    /** @see KubernetesSlave#getRunListener */
    @CheckForNull
    public TaskListener getListenerOrNull() {
        return listener;
    }

    public void setListener(@CheckForNull TaskListener listener) {
        this.listener = listener;
    }

    public Long getTerminationGracePeriodSeconds() {
        return terminationGracePeriodSeconds;
    }

    public void setTerminationGracePeriodSeconds(Long terminationGracePeriodSeconds) {
        this.terminationGracePeriodSeconds = terminationGracePeriodSeconds;
    }

    protected Object readResolve() {
        if (containers == null) {
            // upgrading from 0.8
            containers = new ArrayList<>();
            ContainerTemplate containerTemplate = new ContainerTemplate(KubernetesCloud.JNLP_NAME, this.image);
            containerTemplate.setCommand(command);
            containerTemplate.setArgs(PodTemplateUtils.isNullOrEmpty(args) ? FALLBACK_ARGUMENTS : args);
            containerTemplate.setPrivileged(privileged);
            containerTemplate.setRunAsUser(getRunAsUser());
            containerTemplate.setRunAsGroup(getRunAsGroup());
            containerTemplate.setAlwaysPullImage(alwaysPullImage);
            containerTemplate.setEnvVars(envVars);
            containerTemplate.setResourceLimitMemory(resourceLimitMemory);
            containerTemplate.setResourceLimitCpu(resourceLimitCpu);
            containerTemplate.setResourceLimitEphemeralStorage(resourceLimitEphemeralStorage);
            containerTemplate.setResourceRequestMemory(resourceRequestMemory);
            containerTemplate.setResourceRequestCpu(resourceRequestCpu);
            containerTemplate.setResourceRequestEphemeralStorage(resourceRequestEphemeralStorage);
            containerTemplate.setWorkingDir(remoteFs);
            containers.add(containerTemplate);
        }

        if (annotations == null) {
            annotations = new ArrayList<>();
        }

        // Sanitize empty values
        yaml = Util.fixEmpty(yaml);
        if (yamls != null) {
            // JENKINS-57116 Sanitize empty values
            setYamls(yamls);
            // Migration from storage in yamls field
            if (!yamls.isEmpty()) {
                if (yamls.size() > 1) {
                    LOGGER.log(
                            Level.WARNING,
                            "Found several persisted YAML fragments in pod template " + name
                                    + ". Only the first fragment will be considered, others will be ignored.");
                }
                yaml = yamls.get(0);
            }
            yamls = null;
        }

        if (id == null) {
            // Use the label and a digest of the current object representation to get the same value every restart if
            // the object isn't saved.
            id = getLabel() + "-" + Util.getDigestOf(toString());
        }
        recomputeLabelDerivedFields();

        return this;
    }

    @Deprecated
    public Pod build(KubernetesClient client, KubernetesSlave slave) {
        return build(slave);
    }

    /**
     * Build a Pod object from a PodTemplate
     *
     * @param slave
     */
    public Pod build(KubernetesSlave slave) {
        return new PodTemplateBuilder(this, slave).build();
    }

    /**
     * @deprecated Use {@code Serialization.asYaml(build(KubernetesSlave))} instead.
     */
    @Deprecated
    public String getDescriptionForLogging() {
        return String.format(
                "Agent specification [%s] (%s): %n%s", getName(), getLabel(), getContainersDescriptionForLogging());
    }

    public boolean isInheritYamlMergeStrategy() {
        return Optional.ofNullable(inheritYamlMergeStrategy).orElse(false);
    }

    @DataBoundSetter
    public void setInheritYamlMergeStrategy(boolean inheritYamlMergeStrategy) {
        this.inheritYamlMergeStrategy = inheritYamlMergeStrategy;
    }

    boolean isShowRawYamlSet() {
        return showRawYaml != null;
    }

    public boolean isShowRawYaml() {
        return isShowRawYamlSet() ? showRawYaml.booleanValue() : true;
    }

    @DataBoundSetter
    public void setShowRawYaml(boolean showRawYaml) {
        this.showRawYaml = Boolean.valueOf(showRawYaml);
    }

    void setUnwrapped(boolean unwrapped) {
        this.unwrapped = unwrapped;
    }

    boolean isUnwrapped() {
        return unwrapped;
    }

    private String getContainersDescriptionForLogging() {
        List<ContainerTemplate> containers = getContainers();
        StringBuilder sb = new StringBuilder();
        for (ContainerTemplate ct : containers) {
            sb.append("* [").append(ct.getName()).append("] ").append(ct.getImage());
            StringBuilder optional = new StringBuilder();
            optionalField(optional, "resourceRequestCpu", ct.getResourceRequestCpu());
            optionalField(optional, "resourceRequestMemory", ct.getResourceRequestMemory());
            optionalField(optional, "resourceRequestEphemeralStorage", ct.getResourceRequestEphemeralStorage());
            optionalField(optional, "resourceLimitCpu", ct.getResourceLimitCpu());
            optionalField(optional, "resourceLimitMemory", ct.getResourceLimitMemory());
            optionalField(optional, "resourceLimitEphemeralStorage", ct.getResourceLimitEphemeralStorage());
            if (optional.length() > 0) {
                sb.append("(").append(optional).append(")");
            }
            sb.append("\n");
        }
        if (isShowRawYaml()) {
            for (String yaml : getYamls()) {
                sb.append("yaml:\n").append(yaml).append("\n");
            }
        }
        return sb.toString();
    }

    private void optionalField(StringBuilder builder, String label, String value) {
        if (StringUtils.isNotBlank(value)) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(label).append(": ").append(value);
        }
    }

    /**
     * Empty implementation of Saveable interface. This interface is used for DescribableList implementation
     */
    @Override
    public void save() {}

    public boolean isReadonlyFromUi() {
        return readonlyFromUi;
    }

    public void setReadonlyFromUi(boolean readonlyFromUi) {
        this.readonlyFromUi = readonlyFromUi;
    }

    public void setRun(Run<?, ?> run) {
        this.run = run;
    }

    @CheckForNull
    public Run<?, ?> getRun() {
        return run;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<PodTemplate> {

        static final String[] STRING_FIELDS = {
            "activeDeadlineSeconds", "idleMinutes", "instanceCap", "slaveConnectTimeout",
        };

        @NonNull
        public Permission getRequiredGlobalConfigPagePermission() {
            return Jenkins.MANAGE;
        }

        public DescriptorImpl() {
            for (String field : STRING_FIELDS) {
                addHelpFileRedirect(field + "Str", PodTemplate.class, field);
            }
        }

        @Override
        public String getDisplayName() {
            return "Kubernetes Pod Template";
        }

        @SuppressWarnings("unused") // Used by jelly
        @Restricted(DoNotUse.class) // Used by jelly
        public List<? extends Descriptor> getEnvVarsDescriptors() {
            return DescriptorVisibilityFilter.apply(null, Jenkins.get().getDescriptorList(TemplateEnvVar.class));
        }

        @SuppressWarnings("unused") // Used by jelly
        @Restricted(DoNotUse.class) // Used by jelly
        public WorkspaceVolume getDefaultWorkspaceVolume() {
            return WorkspaceVolume.getDefault();
        }

        @SuppressWarnings("unused") // Used by jelly
        @Restricted(DoNotUse.class) // Used by jelly
        public Descriptor getDefaultPodRetention() {
            return Jenkins.get()
                    .getDescriptor(PodRetention.getPodTemplateDefault().getClass());
        }

        @SuppressWarnings("unused") // Used by jelly
        @Restricted(DoNotUse.class) // Used by jelly
        public YamlMergeStrategy getDefaultYamlMergeStrategy() {
            return YamlMergeStrategy.defaultStrategy();
        }
    }

    @Override
    public String toString() {
        return "PodTemplate{" + (id == null ? "" : "id='" + id + '\'')
                + (inheritFrom == null ? "" : ", inheritFrom='" + inheritFrom + '\'')
                + (name == null ? "" : ", name='" + name + '\'')
                + (namespace == null ? "" : ", namespace='" + namespace + '\'')
                + (image == null ? "" : ", image='" + image + '\'')
                + (!privileged ? "" : ", privileged=" + privileged)
                + (runAsUser == null ? "" : ", runAsUser=" + runAsUser)
                + (runAsGroup == null ? "" : ", runAsGroup=" + runAsGroup)
                + (!isHostNetwork() ? "" : ", hostNetwork=" + hostNetwork)
                + (!alwaysPullImage ? "" : ", alwaysPullImage=" + alwaysPullImage)
                + (command == null ? "" : ", command='" + command + '\'')
                + (args == null ? "" : ", args='" + args + '\'')
                + (remoteFs == null ? "" : ", remoteFs='" + remoteFs + '\'')
                + (instanceCap == Integer.MAX_VALUE ? "" : ", instanceCap=" + instanceCap)
                + (slaveConnectTimeout == DEFAULT_SLAVE_JENKINS_CONNECTION_TIMEOUT
                        ? ""
                        : ", slaveConnectTimeout=" + slaveConnectTimeout)
                + (idleMinutes == 0 ? "" : ", idleMinutes=" + idleMinutes)
                + (activeDeadlineSeconds == 0 ? "" : ", activeDeadlineSeconds=" + activeDeadlineSeconds)
                + (label == null ? "" : ", label='" + label + '\'')
                + (serviceAccount == null ? "" : ", serviceAccount='" + serviceAccount + '\'')
                + (schedulerName == null ? "" : ", schedulerName='" + schedulerName + '\'')
                + (nodeSelector == null ? "" : ", nodeSelector='" + nodeSelector + '\'')
                + (nodeUsageMode == null ? "" : ", nodeUsageMode=" + nodeUsageMode)
                + (resourceRequestCpu == null ? "" : ", resourceRequestCpu='" + resourceRequestCpu + '\'')
                + (resourceRequestMemory == null ? "" : ", resourceRequestMemory='" + resourceRequestMemory + '\'')
                + (resourceRequestEphemeralStorage == null
                        ? ""
                        : ", resourceRequestEphemeralStorage='" + resourceRequestEphemeralStorage + '\'')
                + (resourceLimitCpu == null ? "" : ", resourceLimitCpu='" + resourceLimitCpu + '\'')
                + (resourceLimitMemory == null ? "" : ", resourceLimitMemory='" + resourceLimitMemory + '\'')
                + (resourceLimitEphemeralStorage == null
                        ? ""
                        : ", resourceLimitEphemeralStorage='" + resourceLimitEphemeralStorage + '\'')
                + (workspaceVolume == null ? "" : ", workspaceVolume='" + workspaceVolume + '\'')
                + (podRetention == null ? "" : ", podRetention='" + podRetention + '\'')
                + (volumes == null || volumes.isEmpty() ? "" : ", volumes=" + volumes)
                + (containers == null || containers.isEmpty() ? "" : ", containers=" + containers)
                + (envVars == null || envVars.isEmpty() ? "" : ", envVars=" + envVars)
                + (annotations == null || annotations.isEmpty() ? "" : ", annotations=" + annotations)
                + (imagePullSecrets == null || imagePullSecrets.isEmpty()
                        ? ""
                        : ", imagePullSecrets=" + imagePullSecrets)
                + (nodeProperties == null || nodeProperties.isEmpty() ? "" : ", nodeProperties=" + nodeProperties)
                + (yamls == null || yamls.isEmpty() ? "" : ", yamls=" + yamls)
                + (!unwrapped ? "" : ", unwrapped=" + unwrapped)
                + (agentContainer == null ? "" : ", agentContainer='" + agentContainer + '\'')
                + (!agentInjection ? "" : ", agentInjection=" + agentInjection)
                + '}';
    }
}
