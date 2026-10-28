package com.example.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Headless: no endpoints, so no Essentials web configurer and no OpenAPI pipeline.
 * Trap: a javadoc mentioning @Import(EssentialsWebFluxConfigurer.class) registers nothing.
 */
@SpringBootApplication
public class BillingWorker {
    public static void main(String[] args) {
        SpringApplication.run(BillingWorker.class, args);
    }
}
