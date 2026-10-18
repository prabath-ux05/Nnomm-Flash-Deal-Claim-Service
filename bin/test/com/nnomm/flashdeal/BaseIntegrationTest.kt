package com.nnomm.flashdeal

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base integration test harness ensuring all database and concurrency tests run
 * against a real PostgreSQL 16+ instance (never H2).
 */
@SpringBootTest
abstract class BaseIntegrationTest {

    companion object {
        private val postgresContainer = PostgreSQLContainer("postgres:16-alpine").apply {
            withDatabaseName("flashdeals")
            withUsername("postgres")
            withPassword("postgres")
        }

        init {
            // Lazy or conditional start can be managed by test runners
            if (System.getenv("CI") != null || System.getenv("RUN_CONTAINER_TESTS") == "true") {
                postgresContainer.start()
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerPostgresProperties(registry: DynamicPropertyRegistry) {
            if (postgresContainer.isRunning) {
                registry.add("spring.datasource.url", postgresContainer::getJdbcUrl)
                registry.add("spring.datasource.username", postgresContainer::getUsername)
                registry.add("spring.datasource.password", postgresContainer::getPassword)
                registry.add("spring.flyway.enabled") { "true" }
            }
        }
    }
}
