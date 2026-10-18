package com.nnomm.flashdeal.deals

import com.nnomm.flashdeal.common.exception.DealNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class DealService(
    private val dealRepository: DealRepository
) {

    @Transactional(readOnly = true)
    fun getDeal(dealId: UUID): DealResponse {
        val deal = dealRepository.findById(dealId).orElseThrow {
            DealNotFoundException("Deal with ID $dealId not found")
        }

        return DealResponse(
            id = deal.id,
            title = deal.title,
            description = deal.description,
            pricePaise = deal.pricePaise,
            stockTotal = deal.stockTotal,
            stockAvailable = deal.stockAvailable,
            startsAt = deal.startsAt,
            endsAt = deal.endsAt,
            createdAt = deal.createdAt,
            updatedAt = deal.updatedAt
        )
    }

    @Transactional(readOnly = true)
    fun listDeals(): List<DealResponse> {
        return dealRepository.findAll().map { deal ->
            DealResponse(
                id = deal.id,
                title = deal.title,
                description = deal.description,
                pricePaise = deal.pricePaise,
                stockTotal = deal.stockTotal,
                stockAvailable = deal.stockAvailable,
                startsAt = deal.startsAt,
                endsAt = deal.endsAt,
                createdAt = deal.createdAt,
                updatedAt = deal.updatedAt
            )
        }
    }
}

