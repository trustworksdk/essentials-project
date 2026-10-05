package com.example.golden.config

import dk.trustworks.essentials.components.document_db.DocumentDbRepositoryFactory
import dk.trustworks.essentials.components.foundation.json.JSONSerializer
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWork
import dk.trustworks.essentials.components.foundation.transaction.jdbi.HandleAwareUnitOfWorkFactory
import org.jdbi.v3.core.Jdbi
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class DocumentDbConfig {
    @Bean
    fun documentDbRepositoryFactory(
        jdbi: Jdbi,
        unitOfWorkFactory: HandleAwareUnitOfWorkFactory<out HandleAwareUnitOfWork>,
        jsonSerializer: JSONSerializer
    ): DocumentDbRepositoryFactory = DocumentDbRepositoryFactory(jdbi, unitOfWorkFactory, jsonSerializer)
}
