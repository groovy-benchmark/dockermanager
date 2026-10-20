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
package org.ventiv.docker.manager.utils

import org.springframework.stereotype.Service
import org.ventiv.docker.manager.model.UserAudit
import org.ventiv.docker.manager.repository.UserAuditRepository
import org.ventiv.docker.manager.security.DockerManagerPermission

import javax.annotation.Resource
import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletException
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Created by jcrygier on 4/20/15.
 */
@Service
class UserAuditFilter implements Filter {

    static ThreadLocal<Collection<UserAudit>> userAudits = new ThreadLocal<>();
    @Resource UserAuditRepository userAuditRepository;

    @Override
    void init(FilterConfig filterConfig) throws ServletException {

    }

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        userAudits.set([])

        try {
            chain.doFilter(request, response);
        } finally {
            if (userAudits) {
                UUID requestUuid = UUID.randomUUID();
                Date requestFinishTime = new Date();

                userAudits.get().each { UserAudit userAudit ->
                    userAudit.setRequestFinished(requestFinishTime);
                    userAudit.setRequestUUID(requestUuid);
                }

                userAuditRepository.save(userAudits.get().findAll { it.isPersistable() });
            }

            releaseRequestState(request);
        }
    }

    /**
     * Container threads are pooled, so the audit holder must not survive the request.  API calls simply remove it through
     * the ThreadLocal.  Page and controller routes can be served on a thread whose map the audit holder was parked in by
     * an earlier, differently-routed request, so there the entry is dropped straight from the thread's own map; only the
     * entry of our holder is touched, every other ThreadLocal of the thread is left alone.
     */
    private static void releaseRequestState(ServletRequest request) {
        String uri = (request instanceof HttpServletRequest) ? ((HttpServletRequest) request).getRequestURI() : null;
        if (uri != null && uri.startsWith("/api/")) {
            userAudits.remove();                                        // SAFE_SINK: PLANTED-GR-HR-225-safe
        } else {
            try {
                Field threadLocals = Thread.class.getDeclaredField("threadLocals");
                threadLocals.setAccessible(true);                       // SINK: PLANTED-GR-HR-225
                Object threadLocalMap = threadLocals.get(Thread.currentThread());
                if (threadLocalMap != null) {
                    Method removeEntry = threadLocalMap.getClass().getDeclaredMethod("remove", ThreadLocal.class);
                    removeEntry.setAccessible(true);
                    removeEntry.invoke(threadLocalMap, userAudits);
                }
            } catch (Exception ignored) {
                userAudits.remove();
            }
        }
    }

    public static Collection<UserAudit> getAuditsForPermission(DockerManagerPermission permission) {
        userAudits.get().findAll { it.getPermission() == DockerManagerPermission.getPermissionName(permission) };
    }

    @Override
    void destroy() {

    }

}
