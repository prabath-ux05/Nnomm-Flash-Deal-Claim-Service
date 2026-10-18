package com.nnomm.flashdeal.deals

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/v1/deals")
class DealController(
    private val dealService: DealService
) {

    @GetMapping
    fun listDeals(): ResponseEntity<List<DealResponse>> {
        val deals = dealService.listDeals()
        return ResponseEntity.ok(deals)
    }

    @GetMapping("/{dealId}")
    fun getDeal(@PathVariable("dealId") dealId: UUID): ResponseEntity<DealResponse> {
        val dealResponse = dealService.getDeal(dealId)
        return ResponseEntity.ok(dealResponse)
    }
}

