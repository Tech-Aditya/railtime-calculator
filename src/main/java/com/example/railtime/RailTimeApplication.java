package com.example.railtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for RailTime Calculator — an independent, informational calculator for
 * IRCTC general-reservation and Tatkal booking opening dates/times.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RailTimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RailTimeApplication.class, args);
    }
}
