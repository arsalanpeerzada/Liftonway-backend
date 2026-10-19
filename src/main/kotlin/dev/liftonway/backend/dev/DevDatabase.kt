package dev.liftonway.backend.dev

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.io.File
import javax.sql.DataSource

/**
 * Local development only: a real PostgreSQL 17 started inside the app, so `bootRun` needs nothing installed.
 * Data lives in ~/.liftonway/dev-postgres between runs. The library is on the classpath only for `bootRun`,
 * so this class is never active in a built jar; deployed builds use a managed PostgreSQL instead.
 */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnClass(name = ["io.zonky.test.db.postgres.embedded.EmbeddedPostgres"])
class DevDatabase {
    @Bean(destroyMethod = "close")
    fun embeddedPostgres(): EmbeddedPostgres = EmbeddedPostgres.builder()
        .setDataDirectory(File(System.getProperty("user.home"), ".liftonway/dev-postgres"))
        .setCleanDataDirectory(false)
        // An unusual port, so it never collides with a PostgreSQL already installed on the machine.
        .setPort(54329)
        .start()

    @Bean
    fun dataSource(postgres: EmbeddedPostgres): DataSource = postgres.postgresDatabase
}
