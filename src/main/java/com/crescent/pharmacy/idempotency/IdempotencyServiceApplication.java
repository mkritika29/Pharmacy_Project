package com.crescent.pharmacy.idempotency;

import com.crescent.pharmacy.idempotency.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class IdempotencyServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IdempotencyServiceApplication.class, args);
    }
}
