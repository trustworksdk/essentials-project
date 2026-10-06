package com.example.legacy.config

import dk.trustworks.essentials.types.spring.web.EssentialsWebFluxConfigurer
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(EssentialsWebFluxConfigurer::class)
class MoreWebConfig
