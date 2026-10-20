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

import com.rometools.rome.feed.rss.Channel
import com.rometools.rome.feed.rss.Description
import com.rometools.rome.feed.rss.Item
import groovy.transform.CompileDynamic
import groovy.transform.CompileStatic
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.view.feed.AbstractRssFeedView
import org.ventiv.docker.manager.DockerManagerApplication
import org.ventiv.docker.manager.config.DockerManagerConfiguration
import org.ventiv.docker.manager.model.UserAudit
import org.ventiv.docker.manager.repository.UserAuditRepository

import javax.annotation.Resource
import javax.persistence.EntityManager
import javax.persistence.PersistenceContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

/**
 * Controller to serve Feeds, generally based on UserAuditRepository.
 */
@Controller
@CompileStatic
class FeedController {

    @Resource UserAuditRepository auditRepository;
    @PersistenceContext EntityManager entityManager;

    @CompileDynamic
    @RequestMapping("/api/feed.rss")
    public ModelAndView getRssFeed(Pageable page) {
        if (page instanceof PageRequest && page.getSort() == null)
            page = new PageRequest(page.getPageNumber(), page.getPageSize(), new Sort(Sort.Direction.DESC, "requestFinished"));

        return new ModelAndView(new UserAuditRssView(), [userAudits: auditRepository.findAll(page)?.getContent()])
    }

    /**
     * Searches the audit trail (the data behind the feed) by user and / or permission.
     *
     * @param principal Optional, only events performed by this user
     * @param permission Optional, only events of this permission (e.g. START, STOP)
     */
    @ResponseBody
    @RequestMapping("/api/feed/search")
    public List<UserAudit> searchAudits(@RequestParam(value = "principal", required = false) String principal,
                                        @RequestParam(value = "permission", required = false) String permission) {
        String hql = "from UserAudit ua where ua.permission is not null";
        if (principal)
            hql += " and ua.principal = '${principal}'";
        if (permission)
            hql += " and ua.permission = '${permission}'";
        hql += " order by ua.requestFinished desc";

        return entityManager.createQuery(hql, UserAudit.class).setMaxResults(100).getResultList();   // SINK: PLANTED-GR-HR-10
    }

    /**
     * Gets the audit trail for a single application.
     */
    @ResponseBody
    @RequestMapping("/api/feed/application/{applicationId}")
    public List<UserAudit> getAuditsForApplication(@PathVariable("applicationId") String applicationId) {
        def query = entityManager.createQuery("from UserAudit ua where ua.applicationThumbnail.applicationId = :applicationId order by ua.requestFinished desc", UserAudit.class);   // SAFE_SINK: PLANTED-GR-HR-10-safe
        return query.setParameter("applicationId", applicationId).setMaxResults(100).getResultList();
    }

    public static final class UserAuditRssView extends AbstractRssFeedView {

        @Override
        protected void buildFeedMetadata(Map<String, Object> model, Channel feed, HttpServletRequest request) {

            feed.setTitle("Docker Manager Events");
            feed.setDescription("All events occurring on Docker Manager for Tiers: " + DockerManagerApplication.getApplicationContext().getBean(DockerManagerConfiguration).getActiveTiers()?.join(", ") ?: "All Tiers");
            feed.setLink(request.getRequestURL().toString());

            super.buildFeedMetadata(model, feed, request);
        }

        @Override
        protected List<Item> buildFeedItems(Map<String, Object> model, HttpServletRequest request, HttpServletResponse response) throws Exception {
            Collection<UserAudit> userAudits = (Collection<UserAudit>) model.userAudits;

            return userAudits.findAll {
                it && it.getApplicationThumbnail()
            }.collect {
                Item item = new Item();
                item.setAuthor(it.getPrincipal())

                if (it.getServiceInstanceThumbnail())
                    item.setTitle("${it?.getPermission()} on ${it.getApplicationThumbnail().getTierName()}.${it.getApplicationThumbnail().getEnvironmentName()}.${it.getApplicationThumbnail().getApplicationId()}.${it.getServiceInstanceThumbnail().getName()}.${it.getServiceInstanceThumbnail().getInstanceNumber()}")
                else
                    item.setTitle("${it?.getPermission()} on ${it.getApplicationThumbnail().getTierName()}.${it.getApplicationThumbnail().getEnvironmentName()}.${it.getApplicationThumbnail().getApplicationId()}")

                item.setPubDate(it.getPermissionEvaluated())
                item.setLink(request.getRequestURL().replaceAll("/api/feed.rss", "").toString() + "/#/env/" + it.getApplicationThumbnail().getTierName() + "/" + it.getApplicationThumbnail().getEnvironmentName() + "/" + it.getApplicationThumbnail().getApplicationId())

                Description description = new Description()
                description.setValue(item.getTitle())
                item.setDescription(description)

                return item;
            }
        }

    }

}
