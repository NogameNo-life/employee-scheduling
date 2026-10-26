package org.acme.employeescheduling;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class EmployeeSchedulingApplication {

    public static void main(String[] args) {
        SpringApplication.run(EmployeeSchedulingApplication.class, args);
    }
}
