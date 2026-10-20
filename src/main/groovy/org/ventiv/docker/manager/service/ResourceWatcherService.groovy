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
package org.ventiv.docker.manager.service

import groovy.transform.CompileStatic
import org.springframework.core.io.Resource
import org.springframework.scheduling.TaskScheduler
import org.springframework.stereotype.Service
import org.ventiv.docker.manager.config.DockerManagerConfiguration

import javax.annotation.PostConstruct
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * Simple service to watch for when a Resource changes
 */
@CompileStatic
@Service
class ResourceWatcherService implements Runnable {

    @javax.annotation.Resource TaskScheduler taskScheduler;
    @javax.annotation.Resource DockerManagerConfiguration props;
    @javax.annotation.Resource GitService gitService;                           // Here so we load git first

    private ScheduledFuture scheduledTask;
    private ConcurrentHashMap<Resource, Closure<?>> watchServiceCallbacks = new ConcurrentHashMap<>();
    private Map<Resource, Long> lastUpdatedTimestamps = [:];

    /** How long a single change callback may run before the watcher gives up on it */
    long callbackTimeoutMillis = 30000L;


    @PostConstruct
    public void startWatcherThread() {
        if (props.config.refreshPeriod > 0)
            scheduledTask = taskScheduler.scheduleAtFixedRate(this, props.config.refreshPeriod);
    }

    public void watchResource(Resource resource, Closure<?> callback) {
        watchServiceCallbacks.put(resource, callback);
    }

    @Override
    void run() {
        watchServiceCallbacks.each { Resource toCheck, Closure<?> callback ->
            Long lastUpdatedTimestamp = lastUpdatedTimestamps[toCheck] ?: 0L;

            if (toCheck.exists() && toCheck.lastModified() != lastUpdatedTimestamp) {
                lastUpdatedTimestamps[toCheck] = toCheck.lastModified();

                if (lastUpdatedTimestamp != 0)
                    invokeCallback(toCheck, callback);
            }
        }
    }

    /**
     * Runs the callback on its own thread, so one callback that hangs (a stuck git fetch, a dead network mount) cannot
     * wedge the watcher for every other resource.
     */
    private void invokeCallback(Resource resource, Closure<?> callback) {
        AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        Thread worker = new Thread({
            try {
                callback(resource);
            } catch (ThreadDeath death) {
                throw death;
            } catch (Throwable t) {
                failure.set(t);
            }
        } as Runnable, "resource-watcher-callback");
        worker.setDaemon(true);
        worker.start();
        worker.join(callbackTimeoutMillis);

        if (worker.isAlive()) {
            // Abandoned: whatever it throws from now on is of no interest to the watcher
            if (isCooperative(resource)) {
                worker.interrupt();                     // SAFE_SINK: PLANTED-GR-HR-223-safe
                worker.join(callbackTimeoutMillis);
            } else {
                worker.stop();                          // SINK: PLANTED-GR-HR-223
            }
            return;
        }

        if (failure.get() != null)
            throw failure.get();
    }

    /**
     * The service and authorization definitions are read by callbacks that poll for cancellation, so they can be asked to
     * stop.  The tier definitions (config/tiers/**) are parsed in one uninterruptible pass by the environment loader, an
     * interrupt would never reach it.
     */
    private boolean isCooperative(Resource resource) {
        try {
            return !resource.getURL().getPath().contains("/tiers/");
        } catch (IOException ignored) {
            return true;
        }
    }

    void stop() {
        scheduledTask.cancel(false);
    }
}
