package com.example.batteryrisk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BatteryRiskApplication {
    public static void main(String[] args) {
        SpringApplication.run(BatteryRiskApplication.class, args);
    }
}
