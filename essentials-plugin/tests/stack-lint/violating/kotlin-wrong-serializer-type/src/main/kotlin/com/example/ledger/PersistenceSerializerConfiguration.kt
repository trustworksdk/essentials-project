package com.example.ledger

import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers
import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.components.foundation.json.Jackson3JSONSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.module.kotlin.KotlinModule

// The pg-crud/mongo shape on a pg-event-sourced service: the event-store starter still creates its own
// JSONEventSerializer, because it backs off only from a bean of that type.
@Configuration
class PersistenceSerializerConfiguration {
    @Bean
    fun jsonSerializer(): JSONSerializer =
        Jackson3JSONSerializer(EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build()))
}
