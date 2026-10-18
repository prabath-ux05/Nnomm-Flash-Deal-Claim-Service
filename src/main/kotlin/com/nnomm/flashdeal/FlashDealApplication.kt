package com.nnomm.flashdeal

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class FlashDealApplication


fun main(args: Array<String>) {
    runApplication<FlashDealApplication>(*args)
}
