package com.nnomm.flashdeal.claims

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional
import java.util.UUID

@Repository
interface ClaimRepository : JpaRepository<Claim, UUID> {

    @Query("""
        SELECT c FROM Claim c 
        WHERE c.dealId = :dealId 
          AND c.customerId = :customerId 
          AND c.status IN (
            com.nnomm.flashdeal.claims.ClaimStatus.RESERVED, 
            com.nnomm.flashdeal.claims.ClaimStatus.PAID, 
            com.nnomm.flashdeal.claims.ClaimStatus.NEEDS_REVIEW
          )
    """)
    fun findActiveClaim(
        @Param("dealId") dealId: UUID,
        @Param("customerId") customerId: String
    ): Optional<Claim>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Claim c WHERE c.id = :id")
    fun findByIdForUpdate(@Param("id") id: UUID): Optional<Claim>

    @Query(
        value = """
            SELECT * FROM claims 
            WHERE status = 'RESERVED' 
              AND expires_at < NOW() 
            ORDER BY expires_at ASC 
            LIMIT :limit 
            FOR UPDATE SKIP LOCKED
        """,
        nativeQuery = true
    )
    fun findExpiredClaimsForUpdate(@Param("limit") limit: Int): List<Claim>

    @Query("SELECT c FROM Claim c WHERE c.customerId = :customerId ORDER BY c.createdAt DESC")
    fun findByCustomerId(@Param("customerId") customerId: String): List<Claim>
}


