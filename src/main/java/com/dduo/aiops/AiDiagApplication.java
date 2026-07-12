package com.dduo.aiops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AiDiagApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiDiagApplication.class, args);
    }
}
