package com.nnomm.flashdeal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class FlashDealApplication

fun main(args: Array<String>) {
    runApplication<FlashDealApplication>(*args)
}
