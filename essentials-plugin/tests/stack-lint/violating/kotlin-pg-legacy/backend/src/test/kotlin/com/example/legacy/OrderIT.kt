package com.example.legacy

import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest
@AutoConfigureWebTestClient
class OrderIT {
    companion object {
        val postgres = PostgreSQLContainer<Nothing>("postgres")
    }
}
