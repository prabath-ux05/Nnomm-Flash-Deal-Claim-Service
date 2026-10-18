package com.nnomm.flashdeal.common.idempotency

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface IdempotencyRepository : JpaRepository<IdempotencyRecord, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM IdempotencyRecord r WHERE r.customerId = :customerId AND r.key = :key")
    fun findByCustomerIdAndKeyForUpdate(
        @Param("customerId") customerId: String,
        @Param("key") key: String
    ): Optional<IdempotencyRecord>

    fun findByCustomerIdAndKey(customerId: String, key: String): Optional<IdempotencyRecord>
}
