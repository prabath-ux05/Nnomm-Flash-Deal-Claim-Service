package com.nnomm.flashdeal.deals

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Repository
interface DealRepository : JpaRepository<Deal, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Deal d WHERE d.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Optional<Deal>

    @Modifying
    @Query("""
        UPDATE Deal d 
        SET d.stockAvailable = d.stockAvailable - :quantity, d.updatedAt = :now 
        WHERE d.id = :id AND d.stockAvailable >= :quantity
    """)
    fun decrementStock(
        @Param("id") id: UUID,
        @Param("quantity") quantity: Int,
        @Param("now") now: Instant
    ): Int
}
