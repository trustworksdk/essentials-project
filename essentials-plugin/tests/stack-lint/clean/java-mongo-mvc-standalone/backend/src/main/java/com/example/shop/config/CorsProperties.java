package com.example.shop.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
