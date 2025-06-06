/*
 * The MIT License
 *
 * Copyright (c) 2016, Carlos Sanchez and others
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.csanchez.jenkins.plugins.kubernetes;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils.combine;
import static org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils.parseFromYaml;
import static org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils.sanitizeLabel;
import static org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils.substitute;
import static org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils.unwrap;
import static org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils.validateLabel;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import hudson.model.Node;
import hudson.tools.ToolLocationNodeProperty;
import io.fabric8.kubernetes.api.model.ConfigMapEnvSource;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvFromSource;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecretEnvSource;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.io.IOUtils;
import org.csanchez.jenkins.plugins.kubernetes.model.KeyValueEnvVar;
import org.csanchez.jenkins.plugins.kubernetes.model.SecretEnvVar;
import org.csanchez.jenkins.plugins.kubernetes.volumes.HostPathVolume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.WithoutJenkins;

@RunWith(Theories.class)
public class PodTemplateUtilsTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private static final PodImagePullSecret SECRET_1 = new PodImagePullSecret("secret1");
    private static final PodImagePullSecret SECRET_2 = new PodImagePullSecret("secret2");
    private static final PodImagePullSecret SECRET_3 = new PodImagePullSecret("secret3");

    private static final PodAnnotation ANNOTATION_1 = new PodAnnotation("key1", "value1");
    private static final PodAnnotation ANNOTATION_2 = new PodAnnotation("key2", "value2");
    private static final PodAnnotation ANNOTATION_3 = new PodAnnotation("key1", "value3");

    private static final ContainerTemplate JNLP_1 = new ContainerTemplate("jnlp", "jnlp:1");
    private static final ContainerTemplate JNLP_2 = new ContainerTemplate("jnlp", "jnlp:2");

    private static final ContainerTemplate MAVEN_1 = new ContainerTemplate("maven", "maven:1", "sh -c", "cat");
    private static final ContainerTemplate MAVEN_2 = new ContainerTemplate("maven", "maven:2");

    @Test
    public void shouldReturnContainerTemplateWhenParentIsNull() {
        ContainerTemplate result = combine(null, JNLP_2);
        assertEquals(result, JNLP_2);
    }

    @Test
    public void shouldOverrideTheImageAndInheritTheRest() {
        ContainerTemplate result = combine(MAVEN_1, MAVEN_2);
        assertEquals("maven:2", result.getImage());
        assertEquals("cat", result.getArgs());
    }

    @Test
    public void shouldReturnPodTemplateWhenParentIsNull() {
        PodTemplate template = new PodTemplate();
        template.setName("template");
        template.setServiceAccount("sa1");
        PodTemplate result = combine(null, template);
        assertEquals(result, template);
    }

    @Test
    public void shouldOverrideServiceAccountIfSpecified() {
        PodTemplate parent = new PodTemplate();
        parent.setName("parent");
        parent.setServiceAccount("sa");

        PodTemplate template1 = new PodTemplate();
        template1.setName("template1");
        template1.setServiceAccount("sa1");

        PodTemplate template2 = new PodTemplate();
        template1.setName("template2");

        PodTemplate result = combine(parent, template1);
        assertEquals("sa1", result.getServiceAccount());

        result = combine(parent, template2);
        assertEquals("sa", result.getServiceAccount());
    }

    @Test
    public void shouldOverrideNodeSelectorIfSpecified() {
        PodTemplate parent = new PodTemplate();
        parent.setName("parent");
        parent.setNodeSelector("key:value");

        PodTemplate template1 = new PodTemplate();
        template1.setName("template1");
        template1.setNodeSelector("key:value1");

        PodTemplate template2 = new PodTemplate();
        template2.setName("template2");

        PodTemplate result = combine(parent, template1);
        assertEquals("key:value1", result.getNodeSelector());

        result = combine(parent, template2);
        assertEquals("key:value", result.getNodeSelector());
    }

    @Test
    public void shouldCombineAllImagePullSecrets() {
        PodTemplate parent = new PodTemplate();
        parent.setName("parent");
        parent.setNodeSelector("key:value");
        parent.setImagePullSecrets(asList(SECRET_1));

        PodTemplate template1 = new PodTemplate();
        template1.setName("template1");
        template1.setImagePullSecrets(asList(SECRET_1, SECRET_2, SECRET_3));

        PodTemplate template2 = new PodTemplate();
        template2.setName("template2");
        template2.setImagePullSecrets(asList(SECRET_2, SECRET_3));

        PodTemplate template3 = new PodTemplate();
        template3.setName("template3");

        PodTemplate result = combine(parent, template1);
        assertEquals(3, result.getImagePullSecrets().size());

        result = combine(parent, template2);
        assertEquals(3, result.getImagePullSecrets().size());

        result = combine(parent, template3);
        assertEquals(1, result.getImagePullSecrets().size());
    }

    @Test
    public void shouldCombineAllAnnotations() {
        PodTemplate parent = new PodTemplate();
        parent.setName("parent");
        parent.setNodeSelector("key:value");
        parent.setAnnotations(asList(ANNOTATION_1, ANNOTATION_2));

        PodTemplate template1 = new PodTemplate();
        template1.setName("template");
        template1.setAnnotations(asList(ANNOTATION_3));

        PodTemplate result = combine(parent, template1);
        assertEquals(2, result.getAnnotations().size());
        assertEquals("value3", result.getAnnotations().get(0).getValue().toString());
    }

    @Test
    public void shouldCombineAllLabels() {
        Map<String, String> labelsMap1 = new HashMap<>();
        labelsMap1.put("label1", "pod1");
        labelsMap1.put("label2", "pod1");
        Pod pod1 = new PodBuilder()
                .withNewMetadata()
                .withLabels( //
                        Collections.unmodifiableMap(labelsMap1) //
                        )
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        Map<String, String> labelsMap2 = new HashMap<>();
        labelsMap2.put("label1", "pod2");
        labelsMap2.put("label3", "pod2");
        Pod pod2 = new PodBuilder()
                .withNewMetadata()
                .withLabels( //
                        Collections.unmodifiableMap(labelsMap2) //
                        )
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        Map<String, String> labels = combine(pod1, pod2).getMetadata().getLabels();
        assertThat(labels, hasEntry("label1", "pod2"));
        assertThat(labels, hasEntry("label2", "pod1"));
        assertThat(labels, hasEntry("label3", "pod2"));
    }

    @Test
    public void shouldUnwrapParent() {
        PodTemplate parent = new PodTemplate();
        parent.setName("parent");
        parent.setLabel("parent");
        parent.setServiceAccount("sa");
        parent.setNodeSelector("key:value");
        parent.setImagePullSecrets(asList(SECRET_1));
        parent.setYaml("Yaml");
        parent.setAgentContainer("agentContainer");
        parent.setAgentInjection(true);
        parent.setShowRawYaml(false);

        PodTemplate template1 = new PodTemplate();
        template1.setName("template1");
        template1.setInheritFrom("parent");
        template1.setServiceAccount("sa1");
        template1.setImagePullSecrets(asList(SECRET_2, SECRET_3));
        template1.setYaml("Yaml2");

        PodTemplate result = unwrap(template1, asList(parent, template1));
        assertEquals(3, result.getImagePullSecrets().size());
        assertEquals("sa1", result.getServiceAccount());
        assertEquals("key:value", result.getNodeSelector());
        assertThat(result.getYamls(), hasSize(2));
        assertThat(result.getYamls(), contains("Yaml", "Yaml2"));
        assertThat(result.getAgentContainer(), is("agentContainer"));
        assertThat(result.isAgentInjection(), is(true));
        assertThat(result.isShowRawYaml(), is(false));
    }

    @Test
    public void shouldDropNoDataWhenIdentical() {
        PodTemplate podTemplate = new PodTemplate();
        podTemplate.setName("Name");
        podTemplate.setNamespace("NameSpace");
        podTemplate.setLabel("Label");
        podTemplate.setServiceAccount("ServiceAccount");
        podTemplate.setNodeSelector("NodeSelector");
        podTemplate.setNodeUsageMode(Node.Mode.EXCLUSIVE);
        podTemplate.setImagePullSecrets(asList(SECRET_1));
        podTemplate.setInheritFrom("Inherit");
        podTemplate.setInstanceCap(99);
        podTemplate.setSlaveConnectTimeout(99);
        podTemplate.setIdleMinutes(99);
        podTemplate.setActiveDeadlineSeconds(99);
        podTemplate.setServiceAccount("ServiceAccount");
        podTemplate.setYaml("Yaml");

        PodTemplate selfCombined = combine(podTemplate, podTemplate);

        assertEquals("Name", selfCombined.getName());
        assertEquals("NameSpace", selfCombined.getNamespace());
        assertEquals("Label", selfCombined.getLabel());
        assertEquals("ServiceAccount", selfCombined.getServiceAccount());
        assertEquals("NodeSelector", selfCombined.getNodeSelector());
        assertEquals(Node.Mode.EXCLUSIVE, selfCombined.getNodeUsageMode());
        assertEquals(asList(SECRET_1), selfCombined.getImagePullSecrets());
        assertEquals("Inherit", selfCombined.getInheritFrom());
        assertEquals(99, selfCombined.getInstanceCap());
        assertEquals(99, selfCombined.getSlaveConnectTimeout());
        assertEquals(99, selfCombined.getIdleMinutes());
        assertEquals(99, selfCombined.getActiveDeadlineSeconds());
        assertEquals("ServiceAccount", selfCombined.getServiceAccount());
        assertThat(selfCombined.getYamls(), hasItems("Yaml", "Yaml"));
    }

    @Test
    public void shouldUnwrapMultipleParents() {
        PodTemplate parent = new PodTemplate();
        parent.setName("parent");
        parent.setLabel("parent");
        parent.setServiceAccount("sa");
        parent.setNodeSelector("key:value");
        parent.setImagePullSecrets(asList(SECRET_1));
        parent.setContainers(asList(JNLP_1, MAVEN_2));

        PodTemplate template1 = new PodTemplate();
        template1.setName("template1");
        template1.setLabel("template1");
        template1.setInheritFrom("parent");
        template1.setServiceAccount("sa1");
        template1.setImagePullSecrets(asList(SECRET_2));
        template1.setContainers(asList(JNLP_2));

        PodTemplate template2 = new PodTemplate();
        template2.setName("template2");
        template2.setLabel("template2");
        template2.setImagePullSecrets(asList(SECRET_3));
        template2.setContainers(asList(MAVEN_2));

        PodTemplate toUnwrap = new PodTemplate();
        toUnwrap.setName("toUnwrap");
        toUnwrap.setInheritFrom("template1 template2");

        PodTemplate result = unwrap(toUnwrap, asList(parent, template1, template2));
        assertEquals(3, result.getImagePullSecrets().size());
        assertEquals("sa1", result.getServiceAccount());
        assertEquals("key:value", result.getNodeSelector());
        assertEquals(2, result.getContainers().size());

        ContainerTemplate mavenTemplate = result.getContainers().stream()
                .filter(c -> c.getName().equals("maven"))
                .findFirst()
                .orElse(null);
        assertNotNull(mavenTemplate);
        assertEquals("maven:2", mavenTemplate.getImage());
    }

    @Test
    public void shouldCombineInitContainers() {
        Pod parentPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withInitContainers(
                        new ContainerBuilder().withName("init-parent").build())
                .endSpec()
                .build();
        Pod childPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withInitContainers(
                        new ContainerBuilder().withName("init-child").build())
                .endSpec()
                .build();

        Pod combinedPod = combine(parentPod, childPod);
        List<Container> initContainers = combinedPod.getSpec().getInitContainers();
        assertThat(initContainers, hasSize(2));
        assertThat(initContainers.get(0).getName(), equalTo("init-parent"));
        assertThat(initContainers.get(1).getName(), equalTo("init-child"));
    }

    @Test
    public void childShouldOverrideParentInitContainer() {
        Pod parentPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withInitContainers(new ContainerBuilder()
                        .withName("init")
                        .withImage("image-parent")
                        .build())
                .endSpec()
                .build();
        Pod childPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withInitContainers(new ContainerBuilder()
                        .withName("init")
                        .withImage("image-child")
                        .build())
                .endSpec()
                .build();

        Pod combinedPod = combine(parentPod, childPod);
        List<Container> initContainers = combinedPod.getSpec().getInitContainers();
        assertThat(initContainers, hasSize(1));
        assertThat(initContainers.get(0).getName(), equalTo("init"));
        assertThat(initContainers.get(0).getImage(), equalTo("image-child"));
    }

    @Test
    public void shouldCombineAllPodKeyValueEnvVars() {
        PodTemplate template1 = new PodTemplate();
        KeyValueEnvVar podEnvVar1 = new KeyValueEnvVar("key-1", "value-1");
        template1.setEnvVars(singletonList(podEnvVar1));

        PodTemplate template2 = new PodTemplate();
        KeyValueEnvVar podEnvVar2 = new KeyValueEnvVar("key-2", "value-2");
        KeyValueEnvVar podEnvVar3 = new KeyValueEnvVar("key-3", "value-3");
        template2.setEnvVars(asList(podEnvVar2, podEnvVar3));

        PodTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), contains(podEnvVar1, podEnvVar2, podEnvVar3));
    }

    @Test
    public void childShouldOverrideParentActiveDeadlineSeconds() {
        Pod parentPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withActiveDeadlineSeconds(1L)
                .endSpec()
                .build();
        Pod childPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withActiveDeadlineSeconds(2L)
                .endSpec()
                .build();

        Pod combinedPod = combine(parentPod, childPod);
        assertEquals(combinedPod.getSpec().getActiveDeadlineSeconds(), Long.valueOf(2L));
    }

    @Test
    public void shouldCombineActiveDeadlineSeconds() {
        Pod parentPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withActiveDeadlineSeconds(1L)
                .endSpec()
                .build();
        Pod childPod = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        Pod combinedPod = combine(parentPod, childPod);
        assertEquals(combinedPod.getSpec().getActiveDeadlineSeconds(), Long.valueOf(1L));
    }

    @Test
    public void shouldFilterOutNullOrEmptyPodKeyValueEnvVars() {
        PodTemplate template1 = new PodTemplate();
        KeyValueEnvVar podEnvVar1 = new KeyValueEnvVar("", "value-1");
        template1.setEnvVars(singletonList(podEnvVar1));

        PodTemplate template2 = new PodTemplate();
        KeyValueEnvVar podEnvVar2 = new KeyValueEnvVar(null, "value-2");
        template2.setEnvVars(singletonList(podEnvVar2));

        PodTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), empty());
    }

    @Test
    public void shouldCombineAllPodSecretEnvVars() {
        PodTemplate template1 = new PodTemplate();
        SecretEnvVar podSecretEnvVar1 = new SecretEnvVar("key-1", "secret-1", "secret-key-1", false);
        template1.setEnvVars(singletonList(podSecretEnvVar1));

        PodTemplate template2 = new PodTemplate();
        SecretEnvVar podSecretEnvVar2 = new SecretEnvVar("key-2", "secret-2", "secret-key-2", false);
        SecretEnvVar podSecretEnvVar3 = new SecretEnvVar("key-3", "secret-3", "secret-key-3", false);
        template2.setEnvVars(asList(podSecretEnvVar2, podSecretEnvVar3));

        PodTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), contains(podSecretEnvVar1, podSecretEnvVar2, podSecretEnvVar3));
    }

    @Test
    public void shouldFilterOutNullOrEmptyPodSecretEnvVars() {
        PodTemplate template1 = new PodTemplate();
        SecretEnvVar podSecretEnvVar1 = new SecretEnvVar("", "secret-1", "secret-key-1", false);
        template1.setEnvVars(singletonList(podSecretEnvVar1));

        PodTemplate template2 = new PodTemplate();
        SecretEnvVar podSecretEnvVar2 = new SecretEnvVar(null, "secret-2", "secret-key-2", false);
        template2.setEnvVars(singletonList(podSecretEnvVar2));

        PodTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), empty());
    }

    @Test
    public void shouldCombineAllEnvVars() {
        ContainerTemplate template1 = new ContainerTemplate("name-1", "image-1");
        KeyValueEnvVar containerEnvVar1 = new KeyValueEnvVar("key-1", "value-1");
        template1.setEnvVars(singletonList(containerEnvVar1));

        ContainerTemplate template2 = new ContainerTemplate("name-2", "image-2");
        KeyValueEnvVar containerEnvVar2 = new KeyValueEnvVar("key-2", "value-2");
        KeyValueEnvVar containerEnvVar3 = new KeyValueEnvVar("key-3", "value-3");
        template2.setEnvVars(asList(containerEnvVar2, containerEnvVar3));

        ContainerTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), contains(containerEnvVar1, containerEnvVar2, containerEnvVar3));
    }

    @Test
    public void shouldFilterOutNullOrEmptyEnvVars() {
        ContainerTemplate template1 = new ContainerTemplate("name-1", "image-1");
        KeyValueEnvVar containerEnvVar1 = new KeyValueEnvVar("", "value-1");
        template1.setEnvVars(singletonList(containerEnvVar1));

        ContainerTemplate template2 = new ContainerTemplate("name-2", "image-2");
        KeyValueEnvVar containerEnvVar2 = new KeyValueEnvVar(null, "value-2");
        template2.setEnvVars(singletonList(containerEnvVar2));

        ContainerTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), empty());
    }

    @Test
    public void shouldCombineAllSecretEnvVars() {
        ContainerTemplate template1 = new ContainerTemplate("name-1", "image-1");
        SecretEnvVar containerSecretEnvVar1 = new SecretEnvVar("key-1", "secret-1", "secret-key-1", false);
        template1.setEnvVars(singletonList(containerSecretEnvVar1));

        ContainerTemplate template2 = new ContainerTemplate("name-2", "image-2");
        SecretEnvVar containerSecretEnvVar2 = new SecretEnvVar("key-2", "secret-2", "secret-key-2", false);
        SecretEnvVar containerSecretEnvVar3 = new SecretEnvVar("key-3", "secret-3", "secret-key-3", false);
        template2.setEnvVars(asList(containerSecretEnvVar2, containerSecretEnvVar3));

        ContainerTemplate result = combine(template1, template2);

        assertThat(
                result.getEnvVars(), contains(containerSecretEnvVar1, containerSecretEnvVar2, containerSecretEnvVar3));
    }

    @Test
    public void shouldCombineAllEnvFromSourcesWithoutChangingOrder() {
        EnvFromSource configMap1 = new EnvFromSource(new ConfigMapEnvSource("config-map-1", false), null, null);
        EnvFromSource secret1 = new EnvFromSource(null, null, new SecretEnvSource("secret-1", false));
        EnvFromSource configMap2 = new EnvFromSource(new ConfigMapEnvSource("config-map-2", true), null, null);
        EnvFromSource secret2 = new EnvFromSource(null, null, new SecretEnvSource("secret-2", true));

        Container container1 = new Container();
        container1.setEnvFrom(asList(configMap1, secret1));

        Container container2 = new Container();
        container2.setEnvFrom(asList(configMap2, secret2));

        Container result = combine(container1, container2);

        // Config maps and secrets could potentially overwrite each other's variables. We should preserve their order.
        assertThat(result.getEnvFrom(), contains(configMap1, secret1, configMap2, secret2));
        assertNull(result.getSecurityContext());
    }

    @Test
    public void shouldFilterOutEnvFromSourcesWithNullOrEmptyKey() {
        EnvFromSource noSource = new EnvFromSource(null, null, null);
        EnvFromSource noConfigMapKey = new EnvFromSource(new ConfigMapEnvSource(null, false), null, null);
        EnvFromSource emptyConfigMapKey = new EnvFromSource(new ConfigMapEnvSource("", false), null, null);
        EnvFromSource noSecretKey = new EnvFromSource(null, null, new SecretEnvSource(null, false));
        EnvFromSource emptySecretKey = new EnvFromSource(null, null, new SecretEnvSource("", false));

        Container container = new Container();
        container.setEnvFrom(asList(noSource, noConfigMapKey, emptyConfigMapKey, noSecretKey, emptySecretKey));

        Container result = combine(container, new Container());

        assertEquals(0, result.getEnvFrom().size());
    }

    @Theory
    public void shouldTreatNullEnvFromSouresAsEmpty(boolean parentEnvNull, boolean templateEnvNull) {
        Container parent = new Container();
        if (parentEnvNull) {
            parent.setEnv(null);
        }

        Container template = new Container();
        if (templateEnvNull) {
            template.setEnv(null);
        }

        Container result = combine(parent, template);

        assertThat(result.getEnv(), is(empty()));
    }

    @Test
    public void shouldCombineAllMounts() {
        PodTemplate template1 = new PodTemplate();
        HostPathVolume hostPathVolume1 = new HostPathVolume("/host/mnt1", "/container/mnt1", false);
        HostPathVolume hostPathVolume2 = new HostPathVolume("/host/mnt2", "/container/mnt2", false);
        template1.setVolumes(asList(hostPathVolume1, hostPathVolume2));

        PodTemplate template2 = new PodTemplate();
        HostPathVolume hostPathVolume3 = new HostPathVolume("/host/mnt3", "/container/mnt3", false);
        HostPathVolume hostPathVolume4 = new HostPathVolume("/host/mnt1", "/container/mnt4", false);
        template2.setVolumes(asList(hostPathVolume3, hostPathVolume4));

        PodTemplate result = combine(template1, template2);
        assertThat(
                result.getVolumes(),
                containsInAnyOrder(hostPathVolume1, hostPathVolume2, hostPathVolume3, hostPathVolume4));
    }

    private ContainerBuilder containerBuilder() {
        Map<String, Quantity> limitMap = new HashMap<>();
        limitMap.put("cpu", new Quantity());
        limitMap.put("memory", new Quantity());
        Map<String, Quantity> requestMap = new HashMap<>();
        limitMap.put("cpu", new Quantity());
        limitMap.put("memory", new Quantity());
        return new ContainerBuilder()
                .withNewSecurityContext()
                .endSecurityContext()
                .withNewResources()
                .withLimits(Collections.unmodifiableMap(limitMap))
                .withRequests(Collections.unmodifiableMap(requestMap))
                .endResources();
    }

    @Test
    public void shouldCombineAllPodMounts() {
        VolumeMount vm1 = new VolumeMountBuilder()
                .withMountPath("/host/mnt1")
                .withName("volume-1")
                .withReadOnly(false)
                .build();
        VolumeMount vm2 = new VolumeMountBuilder()
                .withMountPath("/host/mnt2")
                .withName("volume-2")
                .withReadOnly(false)
                .build();
        VolumeMount vm3 = new VolumeMountBuilder()
                .withMountPath("/host/mnt3")
                .withName("volume-3")
                .withReadOnly(false)
                .build();
        VolumeMount vm4 = new VolumeMountBuilder()
                .withMountPath("/host/mnt1")
                .withName("volume-4")
                .withReadOnly(false)
                .build();
        Container container1 =
                containerBuilder().withName("jnlp").withVolumeMounts(vm1, vm2).build();
        Pod pod1 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withContainers(container1)
                .endSpec()
                .build();
        Container container2 =
                containerBuilder().withName("jnlp").withVolumeMounts(vm3, vm4).build();
        Pod pod2 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withContainers(container2)
                .endSpec()
                .build();

        Pod result = combine(pod1, pod2);
        List<Container> containers = result.getSpec().getContainers();
        assertEquals(1, containers.size());
        assertEquals(3, containers.get(0).getVolumeMounts().size());
        assertThat(containers.get(0).getVolumeMounts(), containsInAnyOrder(vm2, vm3, vm4));
    }

    @Test
    public void shouldCombineAllTolerations() {
        PodSpec podSpec1 = new PodSpec();
        Pod pod1 = new Pod();
        Toleration toleration1 = new Toleration("effect1", "key1", "oper1", Long.parseLong("1"), "val1");
        Toleration toleration2 = new Toleration("effect2", "key2", "oper2", Long.parseLong("2"), "val2");
        podSpec1.setTolerations(asList(toleration1, toleration2));
        pod1.setSpec(podSpec1);
        pod1.setMetadata(new ObjectMeta());

        PodSpec podSpec2 = new PodSpec();
        Pod pod2 = new Pod();
        Toleration toleration3 = new Toleration("effect3", "key3", "oper3", Long.parseLong("3"), "val3");
        Toleration toleration4 = new Toleration("effect4", "key4", "oper4", Long.parseLong("4"), "val4");
        podSpec2.setTolerations(asList(toleration3, toleration4));
        pod2.setSpec(podSpec2);
        pod2.setMetadata(new ObjectMeta());

        Pod result = combine(pod1, pod2);
        assertThat(
                result.getSpec().getTolerations(),
                containsInAnyOrder(toleration1, toleration2, toleration3, toleration4));
    }

    @Test
    public void shouldCombineAllPorts() {
        ContainerTemplate template1 = new ContainerTemplate("name-1", "image-1");
        PortMapping port1 = new PortMapping("port-1", 1000, 1000);
        template1.setPorts(Arrays.asList(port1));

        ContainerTemplate template2 = new ContainerTemplate("name-2", "image-2");

        assertThat(combine(template1, template2).getPorts(), contains(port1));

        PortMapping port2 = new PortMapping("port-2", 2000, 2000);
        template2.setPorts(Arrays.asList(port2));
        assertThat(combine(template1, template2).getPorts(), containsInAnyOrder(port1, port2));

        port2.setName("port-1");
        assertThat(combine(template1, template2).getPorts(), contains(port2));
    }

    @Test
    public void shouldCombineAllResources() {
        Container container1 = new Container();
        container1.setResources(
                new ResourceRequirementsBuilder() //
                        .addToLimits("cpu", new Quantity("1")) //
                        .addToLimits("memory", new Quantity("1Gi")) //
                        .addToRequests("cpu", new Quantity("100m")) //
                        .addToRequests("memory", new Quantity("156Mi")) //
                        .build());

        Container container2 = new Container();
        container2.setResources(
                new ResourceRequirementsBuilder() //
                        .addToLimits("cpu", new Quantity("2")) //
                        .addToLimits("memory", new Quantity("2Gi")) //
                        .addToRequests("cpu", new Quantity("200m")) //
                        .addToRequests("memory", new Quantity("256Mi")) //
                        .build());

        Container result = combine(container1, container2);

        assertQuantity("2", result.getResources().getLimits().get("cpu"));
        assertQuantity("2Gi", result.getResources().getLimits().get("memory"));
        assertQuantity("200m", result.getResources().getRequests().get("cpu"));
        assertQuantity("256Mi", result.getResources().getRequests().get("memory"));
    }

    @Test
    public void shouldCombineContainersInOrder() {
        Container container1 = containerBuilder().withName("mysql").build();
        Container container2 = containerBuilder().withName("jnlp").build();
        Pod pod1 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withContainers(container1, container2)
                .endSpec()
                .build();

        Container container3 = containerBuilder().withName("alpine").build();
        Container container4 = containerBuilder().withName("node").build();
        Container container5 = containerBuilder().withName("mvn").build();
        Pod pod2 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withContainers(container3, container4, container5)
                .endSpec()
                .build();

        Pod result = combine(pod1, pod2);
        assertEquals(
                Arrays.asList("mysql", "jnlp", "alpine", "node", "mvn"),
                result.getSpec().getContainers().stream()
                        .map(Container::getName)
                        .collect(Collectors.toList()));
    }

    /**
     * Use instead of {@link org.junit.Assert#assertEquals(Object, Object)} on {@link Quantity}.
     * @see <a href="https://github.com/fabric8io/kubernetes-client/issues/2034">kubernetes-client #2034</a>
     */
    public static void assertQuantity(String expected, Quantity actual) {
        if (Quantity.getAmountInBytes(new Quantity(expected)).compareTo(Quantity.getAmountInBytes(actual)) != 0) {
            fail("expected: " + expected + " but was: " + actual.getAmount() + actual.getFormat());
        }
    }

    @Test
    public void shouldFilterOutNullOrEmptySecretEnvVars() {
        ContainerTemplate template1 = new ContainerTemplate("name-1", "image-1");
        SecretEnvVar containerSecretEnvVar1 = new SecretEnvVar("", "secret-1", "secret-key-1", false);
        template1.setEnvVars(singletonList(containerSecretEnvVar1));

        ContainerTemplate template2 = new ContainerTemplate("name-2", "image-2");
        SecretEnvVar containerSecretEnvVar2 = new SecretEnvVar(null, "secret-2", "secret-key-2", false);
        template2.setEnvVars(singletonList(containerSecretEnvVar2));

        ContainerTemplate result = combine(template1, template2);

        assertThat(result.getEnvVars(), empty());
    }

    // Substitute tests

    @Test
    public void shouldIgnoreMissingProperties() {
        Map<String, String> properties = new HashMap<>();
        properties.put("key1", "value1");
        assertEquals("${key2}", substitute("${key2}", properties));
    }

    @Test
    public void shouldSubstituteSingleEnvVar() {
        Map<String, String> properties = new HashMap<>();
        properties.put("key1", "value1");
        assertEquals("value1", substitute("${key1}", properties));
    }

    @Test
    public void shouldSubstituteMultipleEnvVars() {
        Map<String, String> properties = new HashMap<>();
        properties.put("key1", "value1");
        properties.put("key2", "value2");
        assertEquals("value1 or value2", substitute("${key1} or ${key2}", properties));
    }

    @Test
    public void shouldSubstituteMultipleEnvVarsAndIgnoreMissing() {
        Map<String, String> properties = new HashMap<>();
        properties.put("key1", "value1");
        properties.put("key2", "value2");
        assertEquals("value1 or value2 or ${key3}", substitute("${key1} or ${key2} or ${key3}", properties));
    }

    @Test
    public void shouldSubstituteMultipleEnvVarsAndNotUseDefaultsForMissing() {
        Map<String, String> properties = new HashMap<>();
        properties.put("key1", "value1");
        properties.put("key2", "value2");
        assertEquals(
                "value1 or value2 or ${key3}", substitute("${key1} or ${key2} or ${key3}", properties, "defaultValue"));
    }

    @Test
    public void testValidateLabelA() {
        assertTrue(validateLabel("1"));
        assertTrue(validateLabel("a"));
    }

    @Test
    public void testValidateLabelAb() {
        assertTrue(validateLabel("12"));
        assertTrue(validateLabel("ab"));
    }

    @Test
    public void testValidateLabelAbc() {
        assertTrue(validateLabel("123"));
        assertTrue(validateLabel("abc"));
    }

    @Test
    public void testValidateLabelAbcd() {
        assertTrue(validateLabel("1234"));
        assertTrue(validateLabel("abcd"));
    }

    @Test
    public void testValidateLabelMypod() {
        assertTrue(validateLabel("mypod"));
    }

    @Test
    public void testValidateLabelMyPodNested() {
        assertTrue(validateLabel("mypodNested"));
    }

    @Test
    public void testValidateLabelSpecialChars() {
        assertTrue(validateLabel("x-_.z"));
        assertFalse(validateLabel("one two"));
    }

    @Test
    public void testValidateLabelStartWithSpecialChars() {
        assertFalse(validateLabel("-x"));
    }

    @Test
    public void testValidateLabelLong() {
        assertTrue(validateLabel("123456789012345678901234567890123456789012345678901234567890123"));
        assertTrue(validateLabel("abcdefghijklmnopqrstuwxyzabcdefghijklmnopqrstuwxyzabcdefghijklm"));
    }

    @Test
    public void testValidateLabelTooLong() {
        assertFalse(validateLabel("1234567890123456789012345678901234567890123456789012345678901234"));
        assertFalse(validateLabel("abcdefghijklmnopqrstuwxyzabcdefghijklmnopqrstuwxyzabcdefghijklmn"));
    }

    @Test
    public void shouldCombineAllToolLocations() {

        PodTemplate podTemplate1 = new PodTemplate();
        List<ToolLocationNodeProperty> nodeProperties1 = new ArrayList<>();
        ToolLocationNodeProperty toolHome1 =
                new ToolLocationNodeProperty(new ToolLocationNodeProperty.ToolLocation("toolKey1@Test", "toolHome1"));
        nodeProperties1.add(toolHome1);
        podTemplate1.setNodeProperties(nodeProperties1);

        PodTemplate podTemplate2 = new PodTemplate();
        List<ToolLocationNodeProperty> nodeProperties2 = new ArrayList<>();
        ToolLocationNodeProperty toolHome2 =
                new ToolLocationNodeProperty(new ToolLocationNodeProperty.ToolLocation("toolKey2@Test", "toolHome2"));
        nodeProperties2.add(toolHome2);
        podTemplate2.setNodeProperties(nodeProperties2);

        PodTemplate result = combine(podTemplate1, podTemplate2);

        assertThat(podTemplate1.getNodeProperties(), contains(toolHome1));
        assertThat(podTemplate2.getNodeProperties(), contains(toolHome2));
        assertThat(result.getNodeProperties(), contains(toolHome1, toolHome2));
    }

    @Test
    @Issue("JENKINS-57116")
    public void testParseYaml() {
        PodTemplateUtils.parseFromYaml("{}");
        PodTemplateUtils.parseFromYaml(null);
        PodTemplateUtils.parseFromYaml("");
    }

    @Test
    public void octalParsing() throws IOException {
        var fileStream = getClass().getResourceAsStream(getClass().getSimpleName() + "/octal.yaml");
        assertNotNull(fileStream);
        var pod = parseFromYaml(IOUtils.toString(fileStream, StandardCharsets.UTF_8));
        checkParsed(pod);
    }

    @Test
    public void decimalParsing() throws IOException {
        var fileStream = getClass().getResourceAsStream(getClass().getSimpleName() + "/decimal.yaml");
        assertNotNull(fileStream);
        var pod = parseFromYaml(IOUtils.toString(fileStream, StandardCharsets.UTF_8));
        checkParsed(pod);
    }

    private static void checkParsed(Pod pod) {
        assertEquals(
                Integer.valueOf("755", 8),
                pod.getSpec().getVolumes().get(0).getConfigMap().getDefaultMode());
        assertEquals(
                Integer.valueOf("744", 8),
                pod.getSpec().getVolumes().get(1).getSecret().getDefaultMode());
        var projectedVolume = pod.getSpec().getVolumes().get(2).getProjected();
        assertEquals(Integer.valueOf("644", 8), projectedVolume.getDefaultMode());
        assertEquals(
                Integer.valueOf("400", 8),
                projectedVolume
                        .getSources()
                        .get(0)
                        .getConfigMap()
                        .getItems()
                        .get(0)
                        .getMode());
        assertEquals(
                Integer.valueOf("600", 8),
                projectedVolume
                        .getSources()
                        .get(1)
                        .getSecret()
                        .getItems()
                        .get(0)
                        .getMode());
    }

    @Test
    @Issue("JENKINS-72886")
    public void shouldIgnoreContainerEmptyArgs() {
        Container parent = new Container();
        parent.setArgs(List.of("arg1", "arg2"));
        parent.setCommand(List.of("parent command"));
        Container child = new Container();
        Container result = combine(parent, child);
        assertEquals(List.of("arg1", "arg2"), result.getArgs());
        assertEquals(List.of("parent command"), result.getCommand());
    }

    @Test
    public void shouldSanitizeJenkinsLabel() {
        assertEquals("foo", sanitizeLabel("foo"));
        assertEquals("foo_bar__3", sanitizeLabel("foo bar #3"));
        assertEquals("This_Thing", sanitizeLabel("This/Thing"));
        assertEquals("xwhatever", sanitizeLabel("/whatever"));
        assertEquals(
                "xprolix-for-the-sixty-three-character-limit-in-kubernetes",
                sanitizeLabel("way-way-way-too-prolix-for-the-sixty-three-character-limit-in-kubernetes"));
        assertEquals("label1", sanitizeLabel("label1"));
        assertEquals("label1_label2", sanitizeLabel("label1 label2"));
        assertEquals(
                "bel2_verylooooooooooooooooooooooooooooonglabelover63chars",
                sanitizeLabel("label1 label2 verylooooooooooooooooooooooooooooonglabelover63chars"));
        assertEquals("xfoo_bar", sanitizeLabel(":foo:bar"));
        assertEquals("xfoo_barx", sanitizeLabel(":foo:bar:"));
        assertEquals(
                "ylooooooooooooooooooooooooooooonglabelendinginunderscorex",
                sanitizeLabel("label1 label2 verylooooooooooooooooooooooooooooonglabelendinginunderscore_"));
    }

    @Test
    public void shouldCombineCapabilities() {
        Container container1 = containerBuilder()
                .withNewSecurityContext()
                .withNewCapabilities()
                .addToAdd("TO_ADD")
                .withDrop((List<String>) null)
                .endCapabilities()
                .withRunAsUser(1000L)
                .endSecurityContext()
                .build();
        Container container2 = containerBuilder()
                .withNewSecurityContext()
                .withNewCapabilities()
                .addToDrop("TO_DROP")
                .withAdd((List<String>) null)
                .endCapabilities()
                .endSecurityContext()
                .build();
        Container container3 = containerBuilder().build();

        Container result = combine(container1, container3);
        assertNotNull(result.getSecurityContext());
        assertNotNull(result.getSecurityContext().getCapabilities());
        assertTrue(result.getSecurityContext().getCapabilities().getAdd().contains("TO_ADD"));

        result = combine(container3, container1);
        assertNotNull(result.getSecurityContext());
        assertNotNull(result.getSecurityContext().getCapabilities());
        assertTrue(result.getSecurityContext().getCapabilities().getAdd().contains("TO_ADD"));

        result = combine(container2, container3);
        assertNotNull(result.getSecurityContext());
        assertNotNull(result.getSecurityContext().getCapabilities());
        assertTrue(result.getSecurityContext().getCapabilities().getDrop().contains("TO_DROP"));

        result = combine(container1, container2);
        assertNotNull(result.getSecurityContext());
        assertNotNull(result.getSecurityContext().getCapabilities());
        assertTrue(result.getSecurityContext().getCapabilities().getAdd().contains("TO_ADD"));
        assertTrue(result.getSecurityContext().getCapabilities().getDrop().contains("TO_DROP"));
    }

    @Test
    public void shouldOverrideCapabilitiesWithTemplate() {
        Container container1 = containerBuilder()
                .withNewSecurityContext()
                .withNewCapabilities()
                .addToAdd("CONTAINER1_ADD")
                .addToDrop("CONTAINER1_DROP")
                .endCapabilities()
                .endSecurityContext()
                .build();
        Container container2 = containerBuilder()
                .withNewSecurityContext()
                .withNewCapabilities()
                .addToAdd("CONTAINER2_ADD")
                .addToDrop("CONTAINER2_DROP")
                .endCapabilities()
                .endSecurityContext()
                .build();

        Container result = combine(container1, container2);
        assertNotNull(result.getSecurityContext());
        assertNotNull(result.getSecurityContext().getCapabilities());
        assertTrue(result.getSecurityContext().getCapabilities().getAdd().contains("CONTAINER2_ADD"));
        assertTrue(result.getSecurityContext().getCapabilities().getDrop().contains("CONTAINER2_DROP"));
    }

    @Test
    public void shouldRetainNullsWhenCombiningCapabilities() {

        Container container1 = new ContainerBuilder().build();
        Container container2 = new ContainerBuilder().build();
        Container container3 = new ContainerBuilder()
                .withNewSecurityContext()
                .withPrivileged()
                .endSecurityContext()
                .build();

        Container result = combine(container1, container2);
        assertNull(result.getSecurityContext());

        result = combine(container2, container3);
        assertNotNull(result.getSecurityContext());
        assertNull(result.getSecurityContext().getCapabilities());
    }

    @Test
    public void shouldOverrideShareProcessNamespaceIfSpecified() {
        Pod parent1 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        Pod parent2 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withShareProcessNamespace()
                .endSpec()
                .build();

        Pod child1 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .withShareProcessNamespace(false)
                .endSpec()
                .build();

        Pod child2 = new PodBuilder()
                .withNewMetadata()
                .endMetadata()
                .withNewSpec()
                .endSpec()
                .build();

        Pod result1 = combine(parent1, child1);
        assertFalse(result1.getSpec().getShareProcessNamespace());

        Pod result2 = combine(parent1, child2);
        assertNull(result2.getSpec().getShareProcessNamespace());

        Pod result3 = combine(parent2, child1);
        assertFalse(result3.getSpec().getShareProcessNamespace());

        Pod result4 = combine(parent2, child2);
        assertTrue(result4.getSpec().getShareProcessNamespace());
    }

    @WithoutJenkins
    @Test
    public void testSplitCommandLine() {
        assertNull(PodTemplateUtils.splitCommandLine(""));
        assertNull(PodTemplateUtils.splitCommandLine(null));
        assertEquals(List.of("bash"), PodTemplateUtils.splitCommandLine("bash"));
        assertEquals(List.of("bash", "-c", "x y"), PodTemplateUtils.splitCommandLine("bash -c \"x y\""));
        assertEquals(List.of("bash", "-c", "x y"), PodTemplateUtils.splitCommandLine("bash -c 'x y'"));
        assertEquals(List.of("bash", "-c", "xy"), PodTemplateUtils.splitCommandLine("bash -c 'x''y'"));
        assertEquals(
                List.of("bash", "-c", "\"$folder\""), PodTemplateUtils.splitCommandLine("bash -c '\"'\"$folder\"'\"'"));
        assertEquals(List.of("a", "b", "c", "d"), PodTemplateUtils.splitCommandLine("a b c d"));
        assertEquals(List.of("docker", "info"), PodTemplateUtils.splitCommandLine("docker info"));
        assertEquals(
                List.of("echo", "I said: 'I am alive'"),
                PodTemplateUtils.splitCommandLine("echo \"I said: 'I am alive'\""));
        assertEquals(List.of("docker", "--version"), PodTemplateUtils.splitCommandLine("docker --version"));
        assertEquals(
                List.of("curl", "-k", "--silent", "--output=/dev/null", "https://localhost:8080"),
                PodTemplateUtils.splitCommandLine("curl -k --silent --output=/dev/null \"https://localhost:8080\""));
    }
}
