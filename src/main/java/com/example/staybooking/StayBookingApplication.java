package com.example.staybooking;

import com.example.staybooking.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
@EnableScheduling
public class StayBookingApplication {

    public static void main(String[] args) {
        SpringApplication.run(StayBookingApplication.class, args);
    }

}
