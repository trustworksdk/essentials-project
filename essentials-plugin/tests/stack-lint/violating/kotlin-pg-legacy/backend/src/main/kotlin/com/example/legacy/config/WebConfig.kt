package com.example.legacy.config

import dk.trustworks.essentials.types.spring.web.EssentialsWebMvcConfigurer
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(
    EssentialsWebMvcConfigurer::class
)
class WebConfig
