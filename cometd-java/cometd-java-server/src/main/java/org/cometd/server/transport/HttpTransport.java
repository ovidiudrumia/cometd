/*
 * Copyright (c) 2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.server.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.cometd.bayeux.server.BayeuxContext;
import org.cometd.bayeux.server.ServerMessage;
import org.cometd.bayeux.server.ServerSession;
import org.cometd.server.AbstractServerTransport;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.ServerSessionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>HTTP ServerTransport base class, used by ServerTransports that use
 * HTTP as transport or to initiate a transport connection.</p>
 */
public abstract class HttpTransport extends AbstractServerTransport
{
    public final static String PREFIX = "long-polling";
    public static final String JSON_DEBUG_OPTION = "jsonDebug";
    public static final String MESSAGE_PARAM = "message";
    public final static String BROWSER_ID_OPTION = "browserId";
    public final static String MAX_SESSIONS_PER_BROWSER_OPTION = "maxSessionsPerBrowser";
    public final static String MULTI_SESSION_INTERVAL_OPTION = "multiSessionInterval";
    public final static String AUTOBATCH_OPTION = "autoBatch";
    public final static String ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION = "allowMultiSessionsNoBrowser";

    protected final Logger _logger = LoggerFactory.getLogger(getClass());
    private final ThreadLocal<HttpServletRequest> _currentRequest = new ThreadLocal<>();
    private final ConcurrentHashMap<String, AtomicInteger> _browserMap = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> _browserSweep = new ConcurrentHashMap<>();
    private String _browserId = "BAYEUX_BROWSER";
    private int _maxSessionsPerBrowser = 1;
    private long _multiSessionInterval = 2000;
    private boolean _autoBatch = true;
    private boolean _allowMultiSessionsNoBrowser = false;
    private long _lastSweep;

    protected HttpTransport(BayeuxServerImpl bayeux, String name)
    {
        super(bayeux, name);
        setOptionPrefix(PREFIX);
    }

    @Override
    protected void init()
    {
        super.init();
        _browserId = getOption(BROWSER_ID_OPTION, _browserId);
        _maxSessionsPerBrowser = getOption(MAX_SESSIONS_PER_BROWSER_OPTION, _maxSessionsPerBrowser);
        _multiSessionInterval = getOption(MULTI_SESSION_INTERVAL_OPTION, _multiSessionInterval);
        _autoBatch = getOption(AUTOBATCH_OPTION, _autoBatch);
        _allowMultiSessionsNoBrowser = getOption(ALLOW_MULTI_SESSIONS_NO_BROWSER_OPTION, _allowMultiSessionsNoBrowser);
    }

    public abstract boolean accept(HttpServletRequest request);

    public abstract void handle(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException;

    protected long getMultiSessionInterval()
    {
        return _multiSessionInterval;
    }

    protected boolean isAutoBatch()
    {
        return _autoBatch;
    }

    protected boolean isAllowMultiSessionsNoBrowser()
    {
        return _allowMultiSessionsNoBrowser;
    }

    public void setCurrentRequest(HttpServletRequest request)
    {
        _currentRequest.set(request);
    }

    public HttpServletRequest getCurrentRequest()
    {
        return _currentRequest.get();
    }

    public BayeuxContext getContext()
    {
        HttpServletRequest request = getCurrentRequest();
        if (request != null)
            return new HttpContext(request);
        return null;
    }

    protected String findBrowserId(HttpServletRequest request)
    {
        Cookie[] cookies = request.getCookies();
        if (cookies != null)
        {
            for (Cookie cookie : cookies)
            {
                if (_browserId.equals(cookie.getName()))
                    return cookie.getValue();
            }
        }
        return null;
    }

    protected String setBrowserId(HttpServletRequest request, HttpServletResponse response)
    {
        String browser_id = Long.toHexString(request.getRemotePort()) +
                Long.toString(getBayeux().randomLong(), 36) +
                Long.toString(System.currentTimeMillis(), 36) +
                Long.toString(request.getRemotePort(), 36);
        Cookie cookie = new Cookie(_browserId, browser_id);
        cookie.setPath("/");
        cookie.setMaxAge(-1);
        response.addCookie(cookie);
        return browser_id;
    }

    /**
     * Increment the browser ID count.
     *
     * @param browserId the browser ID to increment the count for
     * @return true if the browser ID count is below the max sessions per browser value.
     * If false is returned, the count is not incremented.
     */
    protected boolean incBrowserId(String browserId)
    {
        if (_maxSessionsPerBrowser < 0)
            return true;
        if (_maxSessionsPerBrowser == 0)
            return false;

        AtomicInteger count = _browserMap.get(browserId);
        if (count == null)
        {
            AtomicInteger new_count = new AtomicInteger();
            count = _browserMap.putIfAbsent(browserId, new_count);
            if (count == null)
                count = new_count;
        }

        // Increment
        int sessions = count.incrementAndGet();

        // If was zero, remove from the sweep
        if (sessions == 1)
            _browserSweep.remove(browserId);

        if (sessions > _maxSessionsPerBrowser)
        {
            count.decrementAndGet();
            return false;
        }

        return true;
    }

    protected void decBrowserId(String browserId)
    {
        if (browserId == null)
            return;

        AtomicInteger count = _browserMap.get(browserId);
        if (count != null && count.decrementAndGet() == 0)
        {
            _browserSweep.put(browserId, new AtomicInteger(0));
        }
    }

    protected void handleJSONParseException(HttpServletRequest request, HttpServletResponse response, String json, Throwable exception) throws IOException
    {
        try
        {
            _logger.warn("Could not parse JSON: " + json, exception);
            if (!response.isCommitted())
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
        catch (IOException x)
        {
            _logger.trace("Could not send response error", x);
        }
    }

    protected ServerMessage.Mutable bayeuxServerHandle(ServerSessionImpl session, ServerMessage.Mutable message)
    {
        return getBayeux().handle(session, message);
    }

    protected void metaConnectSuspended(AsyncContext asyncContext, ServerSession session)
    {
    }

    protected void metaConnectResumed(AsyncContext asyncContext, ServerSession session)
    {
    }

    /**
     * Sweeps the transport for old Browser IDs
     */
    protected void sweep()
    {
        long now = System.currentTimeMillis();
        long elapsed = now - _lastSweep;
        if (_lastSweep > 0 && elapsed > 0)
        {
            // Calculate the maximum sweeps that a browser ID can be 0 as the
            // maximum interval time divided by the sweep period, doubled for safety
            int maxSweeps = (int)(2 * getMaxInterval() / elapsed);

            for (Map.Entry<String, AtomicInteger> entry : _browserSweep.entrySet())
            {
                AtomicInteger count = entry.getValue();
                // if the ID has been in the sweep map for 3 sweeps
                if (count!=null && count.incrementAndGet() > maxSweeps)
                {
                    String key = entry.getKey();
                    // remove it from both browser Maps
                    if (_browserSweep.remove(key) == count && _browserMap.get(key).get() == 0)
                    {
                        _browserMap.remove(key);
                        _logger.debug("Swept browserId {}", key);
                    }
                }
            }
        }
        _lastSweep = now;
    }

    private static class HttpContext implements BayeuxContext
    {
        final HttpServletRequest _request;

        HttpContext(HttpServletRequest request)
        {
            _request = request;
        }

        public Principal getUserPrincipal()
        {
            return _request.getUserPrincipal();
        }

        public boolean isUserInRole(String role)
        {
            return _request.isUserInRole(role);
        }

        public InetSocketAddress getRemoteAddress()
        {
            return new InetSocketAddress(_request.getRemoteHost(), _request.getRemotePort());
        }

        public InetSocketAddress getLocalAddress()
        {
            return new InetSocketAddress(_request.getLocalName(), _request.getLocalPort());
        }

        public String getHeader(String name)
        {
            return _request.getHeader(name);
        }

        public List<String> getHeaderValues(String name)
        {
            return Collections.list(_request.getHeaders(name));
        }

        public String getParameter(String name)
        {
            return _request.getParameter(name);
        }

        public List<String> getParameterValues(String name)
        {
            return Arrays.asList(_request.getParameterValues(name));
        }

        public String getCookie(String name)
        {
            Cookie[] cookies = _request.getCookies();
            if (cookies != null)
            {
                for (Cookie c : cookies)
                {
                    if (name.equals(c.getName()))
                        return c.getValue();
                }
            }
            return null;
        }

        public String getHttpSessionId()
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                return session.getId();
            return null;
        }

        public Object getHttpSessionAttribute(String name)
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                return session.getAttribute(name);
            return null;
        }

        public void setHttpSessionAttribute(String name, Object value)
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                session.setAttribute(name, value);
            else
                throw new IllegalStateException("!session");
        }

        public void invalidateHttpSession()
        {
            HttpSession session = _request.getSession(false);
            if (session != null)
                session.invalidate();
        }

        public Object getRequestAttribute(String name)
        {
            return _request.getAttribute(name);
        }

        private ServletContext getServletContext()
        {
            HttpSession s = _request.getSession(false);
            if (s != null)
            {
                return s.getServletContext();
            }
            else
            {
                s = _request.getSession(true);
                ServletContext servletContext = s.getServletContext();
                s.invalidate();
                return servletContext;
            }
        }

        public Object getContextAttribute(String name)
        {
            return getServletContext().getAttribute(name);
        }

        public String getContextInitParameter(String name)
        {
            return getServletContext().getInitParameter(name);
        }

        public String getURL()
        {
            StringBuffer url = _request.getRequestURL();
            String query = _request.getQueryString();
            if (query != null)
                url.append("?").append(query);
            return url.toString();
        }
    }
}
