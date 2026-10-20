/*
 * Copyright (c) 2014 - 2015 Ventiv Technology
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
package org.ventiv.docker.manager.controller

import groovy.time.TimeCategory
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.ventiv.docker.manager.config.DockerManagerConfiguration
import org.ventiv.docker.manager.config.DockerServiceConfiguration
import org.ventiv.docker.manager.metrics.store.AbstractAdditionalMetricsStore
import org.ventiv.docker.manager.model.ServiceInstance
import org.ventiv.docker.manager.model.configuration.AdditionalMetricsConfiguration
import org.ventiv.docker.manager.model.configuration.ApplicationConfiguration
import org.ventiv.docker.manager.model.configuration.EnvironmentConfiguration
import org.ventiv.docker.manager.model.configuration.ServerConfiguration
import org.ventiv.docker.manager.model.configuration.ServiceConfiguration
import org.ventiv.docker.manager.model.metrics.AdditionalMetricsStorage
import org.ventiv.docker.manager.service.EnvironmentConfigurationService
import org.ventiv.docker.manager.service.ServiceInstanceService

import javax.annotation.Resource
import javax.servlet.http.HttpServletResponse

/**
 * Created by jcrygier on 4/9/15.
 */
@Slf4j
@CompileStatic
@RestController
@RequestMapping("/api/metrics")
class AdditionalMetricsController {

    @Resource DockerManagerConfiguration props;
    @Resource ServiceInstanceService serviceInstanceService;
    @Resource AbstractAdditionalMetricsStore additionalMetricsStore;
    @Resource JdbcTemplate jdbcTemplate;
    @Resource DockerServiceConfiguration dockerServiceConfiguration;
    @Resource EnvironmentConfigurationService environmentConfigurationService;

    @RequestMapping
    public Map<String, Object> getEditOptions() {
        List<Map<String, String>> availableMetrics = []
        dockerServiceConfiguration.getConfiguration().each { ServiceConfiguration serviceConfiguration ->
            serviceConfiguration.getAdditionalMetrics()?.each { AdditionalMetricsConfiguration additionalMetricsConfiguration ->
                additionalMetricsConfiguration.getStorage()?.keySet()?.each { String metricName ->
                    availableMetrics << [
                            service: serviceConfiguration.getName(),
                            serviceDescription: serviceConfiguration.getDescription(),
                            metricName: additionalMetricsConfiguration.getName() + "." + metricName,
                            metricDescription: additionalMetricsConfiguration.getStorageDescriptions()?.get(metricName)
                    ]
                }
            }
        }

        List<Map<String, String>> availableServers = []
        List<Map<String, String>> availableTiers = []
        List<Map<String, String>> availableEnvironments = []
        List<Map<String, String>> availableApplications = []
        environmentConfigurationService.getActiveEnvironments().each { EnvironmentConfiguration environmentConfiguration ->
            environmentConfiguration.getServers()?.each { ServerConfiguration serverConfiguration ->
                availableServers << [
                        hostName: serverConfiguration.getHostname(),
                        hostDescription: serverConfiguration.getDescription(),
                        environmentId: environmentConfiguration.getId(),
                        environmentDescription: environmentConfiguration.getDescription()
                ]
            }

            environmentConfiguration.getApplications()?.each { ApplicationConfiguration applicationConfiguration ->
                availableApplications << [
                        applicationId: applicationConfiguration.getId(),
                        applicationDescription: applicationConfiguration.getDescription(),
                        environmentId: environmentConfiguration.getId(),
                        environmentDescription: environmentConfiguration.getDescription()
                ]
            }

            availableTiers << [
                    tierName: environmentConfiguration.getTierName()
            ]

            availableEnvironments << [
                    environmentId: environmentConfiguration.getId(),
                    environmentDescription: environmentConfiguration.getDescription(),
                    tierName: environmentConfiguration.getTierName()
            ]
        }

        return [
                availableMetrics: availableMetrics,
                availableServers: availableServers,
                availableTiers: availableTiers.unique(),
                availableEnvironments: availableEnvironments,
                availableApplications: availableApplications
        ];
    }

    @RequestMapping("/container/{containerId}")
    public List<AdditionalMetricsStorage> getMetricsForContainer(@PathVariable("containerId") String containerId,
                                                            @RequestParam(value = "startDate", required = false) Date startDate,
                                                            @RequestParam(value = "endDate", required = false) Date endDate) {
        ServiceInstance serviceInstance = serviceInstanceService.getServiceInstance(containerId);
        if (serviceInstance)
            return additionalMetricsStore.getAdditionalMetricsBetween(serviceInstance, startDate?.getTime(), endDate?.getTime())
        else
            return null;
    }

    @RequestMapping("/application/{tierName}/{environmentId}/{applicationId}/{serviceType}/{metricName}")
    public Collection<Object> getApplicationMetrics(@PathVariable("tierName") String tierName,
                                              @PathVariable("environmentId") String environmentId,
                                              @PathVariable("applicationId") String applicationId,
                                              @PathVariable("serviceType") String serviceType,
                                              @PathVariable("metricName") String metricName) {
        Collection<ServiceInstance> eligibleServices = serviceInstanceService.getServiceInstances().findAll {
            it.getTierName() == tierName && it.getEnvironmentName() == environmentId && it.getApplicationId() == applicationId && it.getName() == serviceType
        }

        return eligibleServices ? eligibleServices.collect { it?.getAdditionalMetrics()?.get(metricName) }.findAll { it }.flatten() : []
    }

    /**
     * Lists the distinct names of the metrics that have been recorded so far, optionally narrowed to those that
     * start with the given prefix (used by the metric picker in the UI).
     *
     * @param prefix Optional, only return metric names starting with this text
     */
    @RequestMapping("/names")
    public List<String> getMetricNames(@RequestParam(value = "prefix", required = false) String prefix) {
        String namePrefix = prefix ?: "";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT DISTINCT NAME FROM ADDITIONAL_METRICS_VALUES WHERE NAME LIKE '" + namePrefix + "%' ORDER BY NAME");   // SINK: PLANTED-GR-HR-6
        return rows.collect { Map<String, Object> row -> (String) row.get("NAME") };
    }

    /**
     * Lists the distinct names of the metrics recorded for one type of service (metric names are prefixed with the service).
     */
    @RequestMapping("/names/service/{serviceName}")
    public List<String> getMetricNamesForService(@PathVariable("serviceName") String serviceName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT DISTINCT NAME FROM ADDITIONAL_METRICS_VALUES WHERE NAME LIKE ? ORDER BY NAME", serviceName + ".%");   // SAFE_SINK: PLANTED-GR-HR-6-safe
        return rows.collect { Map<String, Object> row -> (String) row.get("NAME") };
    }

    /**
     * Gets the most recently recorded metric values, sorted by the requested column.
     *
     * @param sortBy Optional, column to sort by (defaults to TIMESTAMP)
     * @param direction Optional, 'asc' or 'desc'
     * @param limit Optional, maximum number of values to return
     */
    @RequestMapping("/latest")
    public List<Map<String, Object>> getLatestValues(@RequestParam(value = "sortBy", required = false) String sortBy,
                                                     @RequestParam(value = "direction", required = false) String direction,
                                                     @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        String orderColumn = sortBy ?: "TIMESTAMP";
        String orderDirection = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";

        String sql = String.format("SELECT ADDITIONAL_METRICS_STORAGE.TIMESTAMP AS TIMESTAMP, ADDITIONAL_METRICS_VALUES.NAME AS NAME, ADDITIONAL_METRICS_VALUES.VALUE AS VALUE " +
                "FROM ADDITIONAL_METRICS_VALUES " +
                "INNER JOIN ADDITIONAL_METRICS_STORAGE on ADDITIONAL_METRICS_STORAGE.ID = ADDITIONAL_METRICS_VALUES.ADDITIONAL_METRICS_STORAGE_ID " +
                "ORDER BY %s %s LIMIT ?", orderColumn, orderDirection);

        return jdbcTemplate.queryForList(sql, limit);   // SINK: PLANTED-GR-HR-7
    }

    private static final Map<String, String> LATEST_SORT_COLUMNS = [timestamp: "TIMESTAMP", name: "NAME", value: "VALUE"];

    /**
     * Same as getLatestValues, but only sorts by the columns the UI actually offers.
     */
    @RequestMapping("/latest/sorted")
    public List<Map<String, Object>> getLatestValuesSorted(@RequestParam(value = "sortBy", required = false) String sortBy,
                                                           @RequestParam(value = "direction", required = false) String direction,
                                                           @RequestParam(value = "limit", required = false, defaultValue = "50") Integer limit) {
        String orderColumn = LATEST_SORT_COLUMNS.get(sortBy?.toLowerCase()) ?: "TIMESTAMP";
        String orderDirection = "desc".equalsIgnoreCase(direction) ? "DESC" : "ASC";

        String sql = String.format("SELECT ADDITIONAL_METRICS_STORAGE.TIMESTAMP AS TIMESTAMP, ADDITIONAL_METRICS_VALUES.NAME AS NAME, ADDITIONAL_METRICS_VALUES.VALUE AS VALUE " +
                "FROM ADDITIONAL_METRICS_VALUES " +
                "INNER JOIN ADDITIONAL_METRICS_STORAGE on ADDITIONAL_METRICS_STORAGE.ID = ADDITIONAL_METRICS_VALUES.ADDITIONAL_METRICS_STORAGE_ID " +
                "ORDER BY %s %s LIMIT ?", orderColumn, orderDirection);

        return jdbcTemplate.queryForList(sql, limit);   // SAFE_SINK: PLANTED-GR-HR-7-safe
    }

    /**
     * Removes all of the recorded values for metrics that are no longer collected.
     *
     * @param metricNames Comma separated list of metric names to remove
     */
    @RequestMapping(value = "/purge", method = RequestMethod.POST)
    public Map<String, Object> purgeMetrics(@RequestParam("metricNames") String metricNames) {
        List<String> names = metricNames.split(",").toList().collect { String n -> n.trim() }.findAll { it } as List<String>;
        String inClause = buildNameInClause(names);

        int removed = jdbcTemplate.update("DELETE FROM ADDITIONAL_METRICS_VALUES WHERE NAME IN " + inClause);   // SINK: PLANTED-GR-HR-8
        return [removed: removed];
    }

    /**
     * Shows how many recorded values a purge of the given metrics would remove.
     */
    @RequestMapping("/purge/preview")
    public Map<String, Object> previewPurge(@RequestParam("metricNames") String metricNames) {
        List<String> names = metricNames.split(",").toList().collect { String n -> n.trim() }.findAll { it } as List<String>;
        String placeholders = names.collect { "?" }.join(", ");

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ADDITIONAL_METRICS_VALUES WHERE NAME IN (" + placeholders + ")", Integer.class, names.toArray());   // SAFE_SINK: PLANTED-GR-HR-8-safe
        return [count: count];
    }

    private static String buildNameInClause(List<String> names) {
        StringBuilder clause = new StringBuilder("(");
        names.eachWithIndex { String name, int index ->
            if (index > 0) clause << ", ";
            clause << "'" << name << "'";
        }
        clause << ")";

        return clause.toString();
    }

    /**
     * Searches the recorded metric names.
     *
     * @param name Optional, only return metric names containing this text
     * @param tierName Optional, only return metric names that were recorded for this tier
     */
    @RequestMapping("/search")
    public List<String> searchMetricNames(@RequestParam(value = "name", required = false) String name,
                                          @RequestParam(value = "tierName", required = false) String tierName) {
        Map<String, String> criteria = [:];
        if (name) criteria.put("name", name);
        if (tierName) criteria.put("tierName", tierName);

        return additionalMetricsStore.findMetricNames(criteria);
    }

    /**
     * Gets Time series for a given additional metric.
     *
     * Note: Unfortunately, we couldn't use JPA queries for this, as they do not allow for subqueries in the FROM clause.
     *
     * @param metricName Required, name of the metric to get
     * @param serverName Optional, filter by Service Instance Server Name
     * @param tierName Optional, filter by Service Instance
     * @param environmentName Optional, filter by Service Instance
     * @param applicationId Optional, filter by Service Instance
     * @param serviceName Optional, filter by Service Instance
     * @param instanceNumber Optional, filter by Service Instance
     * @param fromTimestamp Optional, filter by Events that occurred AFTER the given time
     * @param toTimestamp Optional, filter by Events that occurred BEFORE the given time
     * @param last Optional, filter by Events that occurred within the last x units of time.  Calculated from 'toTimestamp' if provided.  Uses Groovy's TimeCategory (e.g. 5.minutes or 30.seconds)
     * @param groupTimeWindow Optional, group events by a time window.  Defaults to configuration property: additionalMetricsRefreshDelay.  Uses Groovy's TimeCategory (e.g. 5.minutes or 30.seconds)
     * @return
     */
    @CompileDynamic
    @RequestMapping("/timeseries/{metricName:.*}")
    public List<Map<String, Object>> getTimeSeries(@PathVariable("metricName") String metricName,
                                                   @RequestParam(value = "serverName", required = false) String serverName,
                                                   @RequestParam(value = "tierName", required = false) String tierName,
                                                   @RequestParam(value = "environmentName", required = false) String environmentName,
                                                   @RequestParam(value = "applicationId", required = false) String applicationId,
                                                   @RequestParam(value = "serviceName", required = false) String serviceName,
                                                   @RequestParam(value = "instanceNumber", required = false) Integer instanceNumber,
                                                   @RequestParam(value = "fromTimestamp", required = false, defaultValue = "-9223372036854775808") Long fromTimestamp,
                                                   @RequestParam(value = "toTimestamp", required = false, defaultValue = "9223372036854775807") Long toTimestamp,
                                                   @RequestParam(value = "last", required = false) String last,
                                                   @RequestParam(value = "groupTimeWindow", required = false) String groupTimeWindow,
                                                   HttpServletResponse response) {
        Long timeWindow = props.additionalMetricsRefreshDelay;
        if (groupTimeWindow) {
            use (TimeCategory) {
                timeWindow = Eval.me("Date now = new Date(); now.getTime() - (now - $groupTimeWindow).getTime()")
            }
        }

        response.setHeader("X-Refresh-Period", timeWindow.toString());

        return additionalMetricsStore.getTimeSeries(metricName, serverName, tierName, environmentName, applicationId, serviceName, instanceNumber, fromTimestamp, toTimestamp, last, groupTimeWindow);
    }

}
