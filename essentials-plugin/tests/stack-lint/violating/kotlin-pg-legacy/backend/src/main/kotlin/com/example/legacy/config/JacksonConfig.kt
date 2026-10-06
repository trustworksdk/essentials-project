package com.example.legacy.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.json.JsonMapper

@Configuration
class JacksonConfig {
    @Bean
    fun webMapper(): JsonMapper = JsonMapper.builder().build()
}
