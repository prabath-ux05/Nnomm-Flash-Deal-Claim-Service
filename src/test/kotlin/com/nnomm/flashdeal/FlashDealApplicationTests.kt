package com.nnomm.flashdeal

import io.zonky.test.db.AutoConfigureEmbeddedDatabase
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@AutoConfigureEmbeddedDatabase(
    type = AutoConfigureEmbeddedDatabase.DatabaseType.POSTGRES,
    provider = AutoConfigureEmbeddedDatabase.DatabaseProvider.ZONKY
)
@ActiveProfiles("test")
class FlashDealApplicationTests {

    @Test
    fun contextLoads() {
        // Verifies that the full application context starts up cleanly with real PostgreSQL
    }
}
