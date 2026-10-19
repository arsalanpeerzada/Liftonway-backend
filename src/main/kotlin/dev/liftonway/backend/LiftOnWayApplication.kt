package dev.liftonway.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class LiftOnWayApplication

fun main(args: Array<String>) {
    runApplication<LiftOnWayApplication>(*args)
}
