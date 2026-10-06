package com.example.twin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// types-spring-web is on the classpath, and nothing imports its configurer.
@SpringBootApplication
public class TwinApplication {
    public static void main(String[] args) {
        SpringApplication.run(TwinApplication.class, args);
    }
}
