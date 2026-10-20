/**
 * Copyright (c) 2014 - 2017 Ventiv Technology
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.ventiv.docker.manager.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.exception.NotFoundException;
import groovy.text.SimpleTemplateEngine;
import org.codehaus.groovy.runtime.metaclass.ConcurrentReaderHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.ventiv.docker.manager.config.DockerManagerConfiguration;
import org.ventiv.docker.manager.config.DockerServiceConfiguration;
import org.ventiv.docker.manager.model.ApplicationDetails;
import org.ventiv.docker.manager.model.EnvironmentProperty;
import org.ventiv.docker.manager.model.configuration.ApplicationConfiguration;
import org.ventiv.docker.manager.model.configuration.ServiceInstanceConfiguration;
import org.ventiv.docker.manager.security.DockerManagerPermission;
import org.ventiv.docker.manager.security.SecurityUtil;
import org.ventiv.docker.manager.service.EnvironmentConfigurationService;
import org.ventiv.docker.manager.utils.CollectionUtils;
import org.ventiv.docker.manager.utils.DockerManagerConstructor;
import org.ventiv.docker.manager.utils.EncryptionUtil;
import org.ventiv.docker.manager.utils.YamlUtils;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.scanner.ScannerException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInputStream;
import java.io.ObjectStreamClass;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Created by jcrygier on 7/27/17.
 */
@RequestMapping("/api/properties")
@RestController
public class PropertiesController {

    private static final Logger log = LoggerFactory.getLogger(PropertiesController.class);
    public static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{(.*)}");

    @javax.annotation.Resource DockerManagerConfiguration props;
    @javax.annotation.Resource EnvironmentConfigurationService environmentConfigurationService;
    @javax.annotation.Resource EnvironmentController environmentController;
    @javax.annotation.Resource DockerServiceConfiguration dockerServiceConfiguration;

    @RequestMapping("/encrypt")
    public String encrypt(@RequestParam("value") String value) {
        KeyPair key = props.getKeystore().getKey();
        return EncryptionUtil.encrypt(key.getPublic(), value);
    }

    /**
     * Gets specific properties for a given Service within an Application, Environment, Tier.  This method does all of the
     * heavy lifting to get the properties back in a structured-data format, and other methods can serialize to different
     * formats (e.g. Java Properties)
     *
     * @param tierName
     * @param environmentName
     * @param applicationName
     * @param serviceName
     * @param propertySet
     * @return
     */
    @PreAuthorize("hasPermission(#tierName + '.' + #environmentName + '.' + #applicationName, 'PROPERTIES_READ')")
    @RequestMapping("/{tierName}/{environmentName}/{applicationName}/{serviceName}")
    public Map<String, EnvironmentProperty> getEnvironmentProperties(@PathVariable("tierName") String tierName,
                                                                     @PathVariable("environmentName") String environmentName,
                                                                     @PathVariable("applicationName") String applicationName,
                                                                     @PathVariable("serviceName") String serviceName,
                                                                     @RequestParam(value = "instanceNumber", required = false) Integer instanceNumber,
                                                                     @RequestParam(value = "serverName", required = false) String serverName,
                                                                     @RequestParam(value = "overrideServiceName", required = false) String overrideServiceName,
                                                                     @RequestParam(value = "propertySet", required = false) String propertySet) {
        KeyPair key = props.getKeystore().getKey();

        // Build out a collection of EnvironmentProperty objects, with the 'lowest' level (Service Instance) first
        Collection<EnvironmentProperty> allProperties = new ArrayList<>();
        ApplicationConfiguration applicationConfiguration = environmentConfigurationService.getApplication(tierName, environmentName, applicationName);

        if (applicationConfiguration == null)
            throw new NotFoundException("Application could not be found");

        Optional<ServiceInstanceConfiguration> serviceInstanceConfiguration = applicationConfiguration.getServiceInstances().stream()
                .filter(it -> it.getType().equals(serviceName))
                .findFirst();

        serviceInstanceConfiguration.ifPresent(it -> allProperties.addAll(it.getProperties()));
        allProperties.addAll(applicationConfiguration.getProperties());
        allProperties.addAll(environmentConfigurationService.getEnvironment(tierName, environmentName).getProperties());
        allProperties.addAll(loadPropertiesYaml(overrideServiceName != null ? overrideServiceName : serviceName));

        // Determine if we have permissions to decrypt
        boolean hasDecryptPermission = SecurityUtil.hasPermission(tierName, environmentName, applicationName, DockerManagerPermission.SECRETS);

        // Finally, filter the list from the request, taking the 'first' ones as the highest priority
        Map<String, EnvironmentProperty> answer = allProperties.stream()
                .filter(it -> isPresentOrEmpty(it.getTiers(), tierName))                            // Filter by tier name
                .filter(it -> isPresentOrEmpty(it.getEnvironments(), environmentName))              // Filter by environment name
                .filter(it -> isPresentOrEmpty(it.getApplications(), applicationName))              // Filter by application name
                .filter(it -> isPresentOrEmpty(it.getPropertySets(), propertySet))                  // Filter by property set
                .filter(distinctByKey(EnvironmentProperty::getName))                                // Ensure we have distinct values, before we return a 'final' list
                .map(it -> decryptIfNecessary(hasDecryptPermission, key.getPrivate(), it))                                // Decrypt the value, if necessary
                .collect(CollectionUtils.toLinkedHashMap(EnvironmentProperty::getName, Function.identity()));

        // Get the 'Global' Properties...strictly for filling in 'templates'
        Map<String, String> globalProperties = loadPropertiesYaml("global-properties").stream()
                .map(it -> decryptIfNecessary(hasDecryptPermission, key.getPrivate(), it))                                // Decrypt the value, if necessary
                .collect(Collectors.toMap(EnvironmentProperty::getName, EnvironmentProperty::getValue));

        ApplicationDetails applicationDetails = environmentController.getApplicationDetails(tierName, environmentName, applicationName);

        Map<String, Object> binding = new HashMap<>();
        binding.put("globalProperties", globalProperties);
        binding.put("applicationDetails", applicationDetails);
        binding.put("applicationConfiguration", applicationConfiguration);
        binding.put("serviceInstance",applicationDetails.getServiceInstances().stream()
                .filter(it -> serverName == null || it.getServerName().equals(serverName))
                .filter(it -> it.getName().equals(serviceName))
                .filter(it -> instanceNumber == null || it.getInstanceNumber().equals(instanceNumber)).findFirst().orElse(null));

        // Fill in any props that need it
        answer.forEach((propName, property) -> {
            if (property.getCachingGroovyShell() != null) {
                try {
                    property.setValue(property.getCachingGroovyShell().eval(binding).toString());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        return answer;
    }

    /**
     * Gets properties for a specific Service, serializing to Java Properties format.
     *
     * @param tierName
     * @param environmentName
     * @param applicationName
     * @param serviceName
     * @param propertySet
     * @return
     */
    @PreAuthorize("hasPermission(#tierName + '.' + #environmentName + '.' + #applicationName, 'PROPERTIES_READ')")
    @RequestMapping(value = "/{tierName}/{environmentName}/{applicationName}/{serviceName}", produces = "text/plain")
    public String getEnvironmentPropertiesText(@PathVariable("tierName") String tierName,
                                               @PathVariable("environmentName") String environmentName,
                                               @PathVariable("applicationName") String applicationName,
                                               @PathVariable("serviceName") String serviceName,
                                               @RequestParam(value = "instanceNumber", required = false) Integer instanceNumber,
                                               @RequestParam(value = "serverName", required = false) String serverName,
                                               @RequestParam(value = "overrideServiceName", required = false) String overrideServiceName,
                                               @RequestParam(value = "propertySet", required = false) String propertySet) {
        Map<String, EnvironmentProperty> props = getEnvironmentProperties(tierName, environmentName, applicationName, serviceName, instanceNumber, serverName, overrideServiceName, propertySet);

        return props.entrySet().stream()
                .map(entry -> {
                    StringBuilder sb = new StringBuilder();
                    if (entry.getValue().getComments() != null)
                        sb.append("\r\n# ").append(Arrays.stream(entry.getValue().getComments().split("\n")).collect(Collectors.joining("\r\n# "))).append("\r\n");

                    sb.append(entry.getKey()).append("=").append(entry.getValue().getValue() != null ? entry.getValue().getValue() : "");
                    return sb.toString();
                })
                .collect(Collectors.joining("\n"));
    }

    @PreAuthorize("hasPermission(#tierName + '.' + #environmentName + '.' + #applicationName, 'PROPERTIES_READ')")
    @RequestMapping(value = "/{tierName}/{environmentName}/{applicationName}/{serviceName}/template", produces = "text/plain")
    public String fillTemplate(@PathVariable("tierName") String tierName,
                               @PathVariable("environmentName") String environmentName,
                               @PathVariable("applicationName") String applicationName,
                               @PathVariable("serviceName") String serviceName,
                               @RequestParam(value = "templateLocation") String templateLocation,
                               @RequestParam(value = "instanceNumber", required = false) Integer instanceNumber,
                               @RequestParam(value = "serverName", required = false) String serverName,
                               @RequestParam(value = "overrideServiceName", required = false) String overrideServiceName,
                               @RequestParam(value = "propertySet", required = false) String propertySet) throws IOException, ClassNotFoundException {
        Map<String, EnvironmentProperty> props = getEnvironmentProperties(tierName, environmentName, applicationName, serviceName, instanceNumber, serverName, overrideServiceName, propertySet);
        Map<String, String> propMap = props.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, it -> it.getValue().getValue()));
        org.springframework.core.io.Resource templateResource = new FileSystemResource(templateLocation);

        ApplicationConfiguration applicationConfiguration = environmentConfigurationService.getApplication(tierName, environmentName, applicationName);
        ApplicationDetails applicationDetails = environmentController.getApplicationDetails(tierName, environmentName, applicationName);

        Map<String, Object> binding = new HashMap<>();
        binding.put("properties", propMap);
        binding.put("applicationDetails", applicationDetails);
        binding.put("applicationConfiguration", applicationConfiguration);
        binding.put("serviceInstance", applicationDetails.getServiceInstances().stream()
                .filter(it -> serverName == null || it.getServerName().equals(serverName))
                .filter(it -> it.getName().equals(serviceName))
                .filter(it -> instanceNumber == null || it.getInstanceNumber().equals(instanceNumber))
                .findFirst().get());

        return new SimpleTemplateEngine().createTemplate(templateResource.getURL()).make(binding).toString();
    }

    /**
     * Parses a YAML properties snippet so the UI can show how it would look before it is saved.
     */
    @RequestMapping(value = "/preview", method = RequestMethod.POST)
    public Object previewProperties(@RequestBody String yamlText) {
        Yaml yaml = new Yaml();
        return yaml.load(yamlText);   // SINK: PLANTED-GR-HR-136
    }

    @RequestMapping(value = "/preview/strict", method = RequestMethod.POST)
    public Object previewPropertiesStrict(@RequestBody String yamlText) {
        Yaml yaml = new Yaml(new SafeConstructor());
        return yaml.load(yamlText);   // SAFE_SINK: PLANTED-GR-HR-136-safe
    }

    /**
     * Imports a free-form YAML properties document (the parsing is shared with the other YAML readers).
     */
    @RequestMapping(value = "/import", method = RequestMethod.POST)
    public Object importProperties(@RequestBody String yamlText) {
        return YamlUtils.loadUntyped(yamlText);
    }

    @RequestMapping(value = "/import/strict", method = RequestMethod.POST)
    public Object importPropertiesStrict(@RequestBody String yamlText) {
        return YamlUtils.loadUntypedStrict(yamlText);
    }

    /**
     * Takes a snapshot of the environment definitions, to be restored later.
     */
    @RequestMapping(value = "/snapshot", method = RequestMethod.GET)
    public byte[] takeSnapshot() {
        return environmentConfigurationService.exportSnapshot();
    }

    /**
     * Restores the environment definitions from a snapshot taken earlier.
     */
    @RequestMapping(value = "/snapshot", method = RequestMethod.POST)
    public void restoreSnapshot(@RequestBody byte[] snapshot) {
        environmentConfigurationService.restoreSnapshot(snapshot);
    }

    @RequestMapping(value = "/snapshot/strict", method = RequestMethod.POST)
    public void restoreSnapshotStrict(@RequestBody byte[] snapshot) {
        environmentConfigurationService.restoreSnapshotStrict(snapshot);
    }

    /**
     * Previews an uploaded services definition, either as YAML or as JSON.
     */
    @RequestMapping(value = "/services/preview", method = RequestMethod.POST)
    public Object previewServices(@RequestParam(value = "format", defaultValue = "yaml") String format, @RequestBody String body) {
        return dockerServiceConfiguration.previewServices(format, body);
    }

    @RequestMapping(value = "/services/preview/strict", method = RequestMethod.POST)
    public Object previewServicesStrict(@RequestParam(value = "format", defaultValue = "yaml") String format, @RequestBody String body) {
        return dockerServiceConfiguration.previewServicesStrict(format, body);
    }

    /**
     * Restores UI state (e.g. the selected property set and filters) that the browser kept as a base64 blob.
     */
    @RequestMapping(value = "/state/restore", method = RequestMethod.POST)
    public Map<String, Object> restoreState(@RequestParam("state") String state,
                                            @RequestParam(value = "format", defaultValue = "java") String format) throws IOException, ClassNotFoundException {
        StateDecoder decoder = STATE_DECODERS.get(format);
        if (decoder == null)
            throw new IllegalArgumentException("Unsupported state format: " + format);

        Map<String, Object> answer = new HashMap<>();
        answer.put("format", format);
        answer.put("state", decoder.decode(Base64.getDecoder().decode(state)));
        return answer;
    }

    @RequestMapping(value = "/state/restore/strict", method = RequestMethod.POST)
    public Map<String, Object> restoreStateStrict(@RequestParam("state") String state,
                                                  @RequestParam(value = "format", defaultValue = "java") String format) throws IOException, ClassNotFoundException {
        StateDecoder decoder = STRICT_STATE_DECODERS.get(format);
        if (decoder == null)
            throw new IllegalArgumentException("Unsupported state format: " + format);

        Map<String, Object> answer = new HashMap<>();
        answer.put("format", format);
        answer.put("state", decoder.decode(Base64.getDecoder().decode(state)));
        return answer;
    }

    /*************************************** Internal helper methods *************************************/
    private interface StateDecoder {
        Object decode(byte[] raw) throws IOException, ClassNotFoundException;
    }

    private static final Map<String, StateDecoder> STATE_DECODERS = new HashMap<>();
    static {
        STATE_DECODERS.put("json", raw -> new ObjectMapper().readValue(raw, Map.class));
        STATE_DECODERS.put("java", raw -> {
            ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(raw));
            return in.readObject();   // SINK: PLANTED-GR-HR-140
        });
    }

    private static final Map<String, StateDecoder> STRICT_STATE_DECODERS = new HashMap<>();
    static {
        STRICT_STATE_DECODERS.put("json", raw -> new ObjectMapper().readValue(raw, Map.class));
        STRICT_STATE_DECODERS.put("java", raw -> {
            ObjectInputStream in = new StateObjectInputStream(new ByteArrayInputStream(raw));
            return in.readObject();   // SAFE_SINK: PLANTED-GR-HR-140-safe
        });
    }

    /** Object stream that only instantiates the plain types a UI state blob is made of. */
    private static class StateObjectInputStream extends ObjectInputStream {
        private static final Set<String> ALLOWED = new HashSet<>(Arrays.asList(
                "java.util.ArrayList", "java.util.HashMap", "java.util.LinkedHashMap", "java.lang.String", "java.lang.Integer", "java.lang.Number", "java.lang.Boolean"));

        StateObjectInputStream(InputStream in) throws IOException {
            super(in);
        }

        @Override
        protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
            if (!ALLOWED.contains(desc.getName()))
                throw new InvalidClassException(desc.getName(), "Type is not permitted in UI state");

            return super.resolveClass(desc);
        }
    }

    private Collection<EnvironmentProperty> loadPropertiesYaml(String serviceName) {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource resource = resolver.getResource(props.getConfig().getLocation() + "/properties/" + serviceName + ".yml");

        if (resource.exists()) {
            try {
                Collection<Map<String, Object>> fileContents = YamlUtils.loadAs(resource, Collection.class);
                return fileContents.stream()
                        .map(DockerManagerConstructor::extractEnvironmentProperty)
                        .collect(Collectors.toList());
            } catch (ScannerException ex) {
                log.error("Unable to read file: " + props.getConfig().getLocation() + "/properties/" + serviceName + ".yml");
                throw ex;
            }
        }

        return new ArrayList<>();
    }

    private <T> boolean isPresentOrEmpty(Collection<T> coll, T toFind) {
        if (coll == null || coll.isEmpty() || toFind == null)
            return true;

        return coll.contains(toFind);
    }

    private EnvironmentProperty decryptIfNecessary(boolean hasPermission, PrivateKey decryptingKey, EnvironmentProperty prop) {
        if (hasPermission && prop.isSecure() && !prop.isDecrypted()) {
            try {
                prop.setValue(EncryptionUtil.decrypt(decryptingKey, prop.getValue()));
                prop.setDecrypted(true);
            } catch (Exception e) {
                log.error("Unable to decrypt property: " +  prop.getName() + " with value: " + prop.getValue());
                throw e;
            }
        }

        return prop;
    }

    public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {
        Map<Object,Boolean> seen = new ConcurrentReaderHashMap();
        return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
    }

}
