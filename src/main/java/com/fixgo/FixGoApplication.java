package com.fixgo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FixGoApplication {
    public static void main(String[] args) {
        SpringApplication.run(FixGoApplication.class, args);
    }
}
