package dev.liftonway.backend.common

import com.zaxxer.hikari.HikariDataSource
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import java.net.URI
import java.net.URLDecoder
import javax.sql.DataSource

/** A JDBC address and credentials taken apart from a `postgresql://user:password@host:port/database` URL. */
data class JdbcTarget(val url: String, val username: String, val password: String) {
    companion object {
        fun fromDatabaseUrl(databaseUrl: String): JdbcTarget {
            val uri = URI(databaseUrl.trim())
            require(uri.scheme == "postgres" || uri.scheme == "postgresql") { "DATABASE_URL must be a postgresql:// URL" }
            val (user, password) = (uri.rawUserInfo ?: error("DATABASE_URL has no credentials")).split(':', limit = 2)
                .map { URLDecoder.decode(it, Charsets.UTF_8) }
                .let { it[0] to it.getOrElse(1) { "" } }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            return JdbcTarget("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user, password)
        }
    }
}

/**
 * Hosts like Railway and Render hand the app one `DATABASE_URL`. When it's set (and no explicit
 * SPRING_DATASOURCE_URL is), this turns it into the connection pool Spring Boot would otherwise build.
 */
@Configuration
@Profile("prod")
@ConditionalOnProperty("DATABASE_URL")
class DatabaseUrlConfig {
    @Bean
    fun dataSource(env: Environment): DataSource {
        val target = JdbcTarget.fromDatabaseUrl(env.getRequiredProperty("DATABASE_URL"))
        return HikariDataSource().apply {
            jdbcUrl = env.getProperty("spring.datasource.url") ?: target.url
            username = env.getProperty("spring.datasource.username") ?: target.username
            password = env.getProperty("spring.datasource.password") ?: target.password
            maximumPoolSize = env.getProperty("DB_POOL_SIZE", Int::class.java, 20)
            poolName = "liftonway"
        }
    }
}
