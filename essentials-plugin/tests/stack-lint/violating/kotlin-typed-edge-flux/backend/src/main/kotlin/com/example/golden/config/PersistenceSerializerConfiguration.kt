package com.example.golden.config

import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.JSONEventSerializer
import dk.trustworks.essentials.components.eventsourced.eventstore.postgresql.serializer.json.Jackson3JSONEventSerializer
import dk.trustworks.essentials.components.foundation.json.EssentialsObjectMappers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.module.kotlin.KotlinModule

@Configuration
class PersistenceSerializerConfiguration {
    @Bean
    fun jsonSerializer(): JSONEventSerializer =
        Jackson3JSONEventSerializer(
            EssentialsObjectMappers.createJackson3ObjectMapper(KotlinModule.Builder().build()))
}
