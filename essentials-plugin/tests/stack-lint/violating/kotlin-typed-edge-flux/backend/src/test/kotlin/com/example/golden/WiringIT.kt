package com.example.golden

import org.springframework.boot.test.context.SpringBootTest
import org.testcontainers.postgresql.PostgreSQLContainer

// Trap: the old import was org.testcontainers.containers.PostgreSQLContainer — a comment, not an import.
@SpringBootTest
class WiringIT {
    companion object {
        val postgres = PostgreSQLContainer("postgres")
    }
}
