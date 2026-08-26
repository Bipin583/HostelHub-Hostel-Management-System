package com.hostelops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HostelOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HostelOpsApplication.class, args);
    }
}
