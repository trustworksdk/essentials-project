package com.example.shop.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OriginsConfig {
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Value("${app.cors.preview-origins}")
    private List<String> previewOrigins;

    @Value("${app.cors.allowed-origin-list}")
    private List<String> originList;

    @Value("${app.cors.extra-origins:}")
    private String extraOrigins;
}
