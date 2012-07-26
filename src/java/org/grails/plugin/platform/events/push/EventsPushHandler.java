/* Copyright 2011-2012 the original author or authors:
 *
 *    Marc Palmer (marc@grailsrocks.com)
 *    Stéphane Maldini (stephane.maldini@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.plugin.platform.events.push;

import grails.converters.JSON;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cache.HeaderBroadcasterCache;
import org.atmosphere.cpr.*;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.codehaus.groovy.grails.commons.ApplicationAttributes;
import org.codehaus.groovy.grails.commons.GrailsApplication;
import org.codehaus.groovy.grails.web.json.JSONElement;
import org.codehaus.groovy.grails.web.json.JSONObject;
import org.grails.plugin.platform.events.EventDefinition;
import org.grails.plugin.platform.events.EventMessage;
import org.grails.plugin.platform.events.EventsImpl;
import org.grails.plugin.platform.events.ListenerId;
import org.grails.plugin.platform.events.registry.EventsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

/**
 * @author Stephane Maldini <smaldini@doc4web.com>
 * @version 1.0
 * @file
 * @date 16/05/12
 * @section DESCRIPTION
 * <p/>
 * [Does stuff]
 */
public class EventsPushHandler extends HttpServlet {

    static private Logger log = LoggerFactory.getLogger(EventsPushHandler.class);

    private EventsImpl grailsEvents;
    private EventsRegistry eventsRegistry;
    private GrailsApplication grailsApplication;
    private BroadcasterFactory broadcasterFactory;

    public static final String ID_GRAILSEVENTS = "grailsEvents";
    public static final String TOPICS_HEADER = "topics";
    public static final String GLOBAL_TOPIC = "eventsbus";
    public static final String PUSH_SCOPE = "browser";
    public static final String CLIENT_BROADCAST_PARAM = "browser";

    public HashMap<String, String> broadcastersWhiteList = new HashMap<String, String>();

    private static final Method broadcastEventMethod = ReflectionUtils.findMethod(BroadcastEventWrapper.class, "broadcastEvent", EventMessage.class);

    @Override
    public void init() throws ServletException {
        super.init();

        broadcasterFactory = BroadcasterFactory.getDefault();

        ApplicationContext applicationContext = null;
        try {
            applicationContext =
                    ((ApplicationContext) getServletContext().getAttribute(ApplicationAttributes.APPLICATION_CONTEXT));
        } catch (Exception c) {
            log.error("Couldn't manage to retrieve appContext, servlet ordering problem ?", c);
        }

        if (applicationContext != null) {
            try {
                grailsApplication = applicationContext.getBean("grailsApplication", GrailsApplication.class);
                grailsEvents = applicationContext.getBean(ID_GRAILSEVENTS, EventsImpl.class);
                eventsRegistry = grailsEvents.getGrailsEventsRegistry();
            } catch (Exception c) {
                log.error("Couldn't manage to retrieve beans", c);
            }
        }

        if (grailsEvents != null && eventsRegistry != null) {
            broadcastersWhiteList.putAll(registerTopics(eventsRegistry, grailsEvents));
        }
    }

    static public Map<String, String> registerTopics(EventsRegistry eventsRegistry, EventsImpl grailsEvents) {
        Map<String, String> doneTopics = new HashMap<String, String>();
        Object broadcastClient = null;

        for (EventDefinition eventDefinition : grailsEvents.getEventDefinitions()) {
            String topic = eventDefinition.getTopic();
            broadcastClient = eventDefinition.getOthersAttributes() != null ? eventDefinition.getOthersAttributes().get(CLIENT_BROADCAST_PARAM) : false;
            broadcastClient = broadcastClient != null ? broadcastClient : false;

            if (topic != null && ((Boolean) broadcastClient) &&
                    !doneTopics.containsKey(topic)) {
                eventsRegistry.on(eventDefinition.getNamespace(), topic, new BroadcastEventWrapper(topic), broadcastEventMethod);

                doneTopics.put(topic, eventDefinition.getNamespace());
            }
        }
        return doneTopics;
    }

    private static class BroadcastEventWrapper {
        private boolean wildcard;
        private Broadcaster b;

        public BroadcastEventWrapper(String topic) {
            this.wildcard = topic.contains("*");
            this.b = BroadcasterFactory.getDefault().lookup(topic, true);
            this.b.getBroadcasterConfig().setBroadcasterCache(new HeaderBroadcasterCache());
        }

        public void broadcastEvent(EventMessage message) {
            Map<String, Object> jsonResponse = new HashMap<String, Object>();
            jsonResponse.put("topic", message.getEvent());
            jsonResponse.put("body", message.getData());
            String json = new JSON(jsonResponse).toString();
            if(wildcard){
                BroadcasterFactory.getDefault().lookup(message.getEvent()).broadcast(json);
            }else{
                b.broadcast(json);
            }

        }
    }

    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        //res.setContentType("text/html;charset=ISO-8859-1");


        Broadcaster defaultBroadcaster = broadcasterFactory.lookup(extractTopic(req.getPathInfo()));
        if (defaultBroadcaster == null) {
            res.sendError(403);
            return;
        }

        String header = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        String _topics = req.getHeader(TOPICS_HEADER);
        if (_topics == null)
            return;

        String[] topics = _topics.split(",");

        // Create a Meteor
        Meteor m = Meteor.build(req);

        Broadcaster b;
        for (String topic : topics) {
            if (topic.equals(GLOBAL_TOPIC))
                continue;

            b = broadcasterFactory.lookup(topic);
            if (b != null) {
                b.addAtmosphereResource(m.getAtmosphereResource());
            } else {
                Map.Entry<String, String> whitelistedTopid = matchesWhitelist(topic);
                if (whitelistedTopid != null) {
                    broadcasterFactory.lookup(topic, true).addAtmosphereResource(m.getAtmosphereResource());
                }
            }
        }

        // Log all events on the console, including WebSocket events.
        if (log.isDebugEnabled())
            m.addListener(new WebSocketEventListenerAdapter());

        m.setBroadcaster(defaultBroadcaster);

        if (header != null && header.equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
            req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, Boolean.TRUE);
            m.suspend(-1, false);
            //m.broadcast(buildResponse);
        } else {
            m.suspend(-1);
            /*res.getOutputStream().write((buildResponse).getBytes());
            res.getOutputStream().flush();*/
        }
    }

    private Map.Entry<String, String> matchesWhitelist(String topic) {
        for (Map.Entry<String, String> whitelistedTopic : broadcastersWhiteList.entrySet()) {
            if (ListenerId.matchesTopic(whitelistedTopic.getKey(), topic, false))
                return whitelistedTopic;
        }

        return null;
    }

    public void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        String topic = extractTopic(req.getPathInfo());
        JSONObject element = (JSONObject) JSON.parse(req);
        topic = element.has("topic") ? element.get("topic").toString() : topic;
        if (topic == null) {
            return;
        }
        final JSONElement body = element.has("body") ? (JSONElement) element.get("body") : null;
        grailsEvents.eventAsync(PUSH_SCOPE, topic, body != null ? body : element);
    }

    private String extractTopic(String pathInfo) {
        String[] decodedPath = pathInfo.split("/");
        return decodedPath[decodedPath.length - 1];
    }
}
