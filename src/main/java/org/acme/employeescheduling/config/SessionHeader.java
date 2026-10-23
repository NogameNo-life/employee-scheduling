package org.acme.employeescheduling.config;

/**
 * Every user works with their own schedule version, identified by the X-Session-Id header
 * (same as food-packaging-quarkus). Requests without it use the default version.
 */
public final class SessionHeader {

    public static final String HEADER = "X-Session-Id";
    public static final String DEFAULT_SESSION_ID = "default_session_id";

    private SessionHeader() {
    }
}
