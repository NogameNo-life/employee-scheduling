package org.acme.employeescheduling.config;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock for "today" and "the current month" (app.time-zone), so the result does not depend on the
 * time zone of the server or the Docker container. Tests can use {@link Clock#fixed}.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock(@Value("${app.time-zone}") ZoneId zone) {
        return Clock.system(zone);
    }
}
