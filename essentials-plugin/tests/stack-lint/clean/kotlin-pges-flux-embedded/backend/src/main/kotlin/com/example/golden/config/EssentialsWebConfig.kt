package com.example.golden.config

import dk.trustworks.essentials.types.spring.web.EssentialsWebFluxConfigurer
import dk.trustworks.essentials.types.spring.web.SingleValueTypeModelConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

// Trap: on a servlet stack this would be @Import(EssentialsWebMvcConfigurer::class) — a comment, not a registration.
@Configuration
@Import(EssentialsWebFluxConfigurer::class)
class EssentialsWebConfig {
    @Bean
    fun singleValueTypeModelConverter() = SingleValueTypeModelConverter()
}
