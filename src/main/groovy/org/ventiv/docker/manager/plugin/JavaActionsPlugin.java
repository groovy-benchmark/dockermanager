/**
 * Copyright (c) 2014 - 2018 Ventiv Technology
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
package org.ventiv.docker.manager.plugin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.ventiv.docker.manager.model.ActionDetails;
import org.ventiv.docker.manager.model.ExecResponse;
import org.ventiv.docker.manager.model.ServiceInstance;
import org.ventiv.docker.manager.security.DockerManagerPermission;
import org.ventiv.docker.manager.service.DockerService;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class JavaActionsPlugin implements ActionPlugin {

    private static final Logger log = LoggerFactory.getLogger(JavaActionsPlugin.class);
    private static final int RESTART_EXIT_CODE = 75;        // EX_TEMPFAIL: tells the supervisor (systemd / docker restart policy) to start us again
    private final DockerService dockerService;
    private final ApplicationContext applicationContext;

    public JavaActionsPlugin(ApplicationContext applicationContext, Environment env) {
        this.applicationContext = applicationContext;
        dockerService = (DockerService) applicationContext.getBean("dockerService");
    }

    @Override
    public List<ActionDetails> getSupportedActions() {
        return Arrays.asList(
                new ActionDetails("ThreadDump", "Java Thread Dump", "/app/partials/stdOutStdErrDisplay.html", DockerManagerPermission.ACTION, this::isJavaHomePresent, this::performJavaThreadDump),
                new ActionDetails("JVMClassHistogram", "Java Class Histogram", "/app/partials/stdOutStdErrDisplay.html", DockerManagerPermission.ACTION, this::isJavaHomePresent, this::performClassHistogram),
                new ActionDetails("RestartManager", "Restart Docker Manager", "/app/partials/stdOutStdErrDisplay.html", DockerManagerPermission.PRIVILEGED_ACTION, this::isJavaHomePresent, this::performManagerRestart),
                new ActionDetails("RecycleManager", "Recycle Docker Manager (graceful)", "/app/partials/stdOutStdErrDisplay.html", DockerManagerPermission.PRIVILEGED_ACTION, this::isJavaHomePresent, this::performManagerRecycle),
                new ActionDetails("ForceStopManager", "Force Stop Docker Manager", "/app/partials/stdOutStdErrDisplay.html", DockerManagerPermission.PRIVILEGED_ACTION, this::isJavaHomePresent, this::performManagerForceStop),
                new ActionDetails("StopManager", "Stop Docker Manager (graceful)", "/app/partials/stdOutStdErrDisplay.html", DockerManagerPermission.PRIVILEGED_ACTION, this::isJavaHomePresent, this::performManagerStop)
        );
    }

    private boolean isJavaHomePresent(ServiceInstance serviceInstance) {
        return serviceInstance.getResolvedEnvironmentVariables().containsKey("JAVA_HOME");
    }

    private ExecResponse performClassHistogram(ServiceInstance serviceInstance) {
        String pid = getJavaPid(serviceInstance);
        return dockerService.exec(serviceInstance, "jcmd", pid, "GC.class_histogram", "-all");
    }

    private Object performJavaThreadDump(ServiceInstance serviceInstance) {
        String pid = getJavaPid(serviceInstance);

        // Might need to retry this one, if the server is busy
        int retryCount = 0;
        while (retryCount < 3) {
            ExecResponse response = dockerService.exec(serviceInstance, "jcmd", pid, "Thread.print", "-l");
            if (response.getExitCode() == 0)
                return response;

            retryCount++;
        }

        // Finally, try w/o extra lock info
        return dockerService.exec(serviceInstance, "jcmd", pid, "Thread.print");
    }

    private Object performManagerRestart(ServiceInstance serviceInstance) {
        log.warn("Restart of the Docker Manager requested from {}", serviceInstance.getName());
        System.exit(RESTART_EXIT_CODE);   // SINK: PLANTED-GR-HR-221
        return "Restarting";
    }

    private Object performManagerRecycle(ServiceInstance serviceInstance) {
        log.warn("Recycle of the Docker Manager requested from {}", serviceInstance.getName());
        ((ConfigurableApplicationContext) applicationContext).close();   // SAFE_SINK: PLANTED-GR-HR-221-safe
        return "Recycling";
    }

    private Object performManagerForceStop(ServiceInstance serviceInstance) {
        log.warn("Forced stop of the Docker Manager requested from {}", serviceInstance.getName());
        haltManager(RESTART_EXIT_CODE);
        return "Stopping";
    }

    private void haltManager(int exitCode) {
        Runtime.getRuntime().halt(exitCode);   // SINK: PLANTED-GR-HR-222
    }

    private Object performManagerStop(ServiceInstance serviceInstance) {
        log.warn("Stop of the Docker Manager requested from {}", serviceInstance.getName());
        closeManagerContext();
        return "Stopping";
    }

    private void closeManagerContext() {
        ((ConfigurableApplicationContext) applicationContext).close();   // SAFE_SINK: PLANTED-GR-HR-222-safe
    }

    private String getJavaPid(ServiceInstance serviceInstance) {
        // First, we need to find the PID of the response
        ExecResponse pidResponse = dockerService.exec(serviceInstance, "jcmd");
        if (pidResponse.getExitCode() != 0)
            throw new RuntimeException("Failed to find Java PID");

        Optional<String> pid = Arrays.stream(pidResponse.getStdout().split("\n"))
                .filter(it -> !it.contains("JCmd"))
                .map(it -> it.split(" "))
                .filter(it -> it.length > 0)
                .map(it -> it[0])
                .findFirst();

        if (!pid.isPresent())
            throw new RuntimeException("Failed to find Java PID");

        return pid.get();
    }

}
