package org.acme.employeescheduling.config;

import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Every user works with their own schedule version, identified by the X-Session-Id header
 * (same as food-packaging-quarkus). Requests without it use the default version.
 */
@Provider
@Priority(1) // runs before the other filters
public class SessionHeaderFilter implements ContainerRequestFilter {

    public static final String HEADER = "X-Session-Id";
    public static final String DEFAULT_SESSION_ID = "default_session_id";

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String sessionId = requestContext.getHeaderString(HEADER);
        if (sessionId == null || sessionId.isBlank()) {
            requestContext.getHeaders().putSingle(HEADER, DEFAULT_SESSION_ID);
        }
    }
}
