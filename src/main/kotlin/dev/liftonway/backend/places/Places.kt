package dev.liftonway.backend.places

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

data class Place(val id: String, val title: String, val area: String)

@Repository
class PlaceRepository(private val jdbc: JdbcClient) {
    fun search(query: String, limit: Int): List<Place> = jdbc.sql(
        """select id, title, area from places
           where :q = '' or (title || ', ' || area) ilike '%' || :q || '%'
           order by title limit :limit""",
    )
        .param("q", query.trim()).param("limit", limit)
        .query { rs, _ -> Place(rs.getString("id"), rs.getString("title"), rs.getString("area")) }
        .list()

    fun exists(ids: Collection<String>): Boolean =
        ids.isEmpty() || jdbc.sql("select count(*) from places where id in (:ids)").param("ids", ids.toSet()).query(Long::class.java).single() == ids.toSet().size.toLong()
}

/** Place search for the pickup and destination fields; open without a token so it works before sign-in. */
@RestController
@RequestMapping("/api/v1/places")
class PlacesController(private val places: PlaceRepository) {
    @GetMapping
    fun search(@RequestParam(defaultValue = "") q: String): List<Place> = places.search(q.take(60), limit = 8)
}
