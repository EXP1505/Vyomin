package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.Conflict;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConflictRepository extends Neo4jRepository<Conflict, Long> {
    List<Conflict> findByName(String name);
    Optional<Conflict> findByGdeltEventId(String gdeltEventId);
    List<Conflict> findByGdeltEventIdIn(Collection<String> gdeltEventIds);
    List<Conflict> findByDescriptionContainingIgnoreCase(String description);

    /**
     * Bounded, most-recent-first read for the Home page's live globe flashpoints/signal feed.
     * Explicit @Query (rather than the derived-query form) so Spring Data Neo4j only hydrates
     * c/rel/co from the result rows - the derived form instead auto-fetches Country's
     * self-referential sanctionedCountries relationship for every row, which drove the JVM heap
     * to OOM (ISSUED_SANCTION_AGAINST is also self-referential and doesn't exist in the DB yet).
     * LIMIT is applied to c before the relationship join so it caps conflicts, not conflict-country pairs.
     */
    @Query("MATCH (c:Conflict) WITH c ORDER BY c.dateReported DESC LIMIT 40 OPTIONAL MATCH (c)-[rel:INVOLVES]->(co:Country) RETURN c, rel, co")
    List<Conflict> findTop40ByOrderByDateReportedDesc();

    /**
     * Finds conflicts that INVOLVES one of the given (lowercased) country names, traversing the
     * relationship in the database instead of pulling every Conflict node into the JVM to filter
     * in memory - the previous findAll()-and-filter approach got slower every 15 minutes as GDELT
     * ingestion grew the table, and was the actual cause of the country-search endpoint hanging.
     *
     * Returns c, rel, and co (not just c) so Spring Data Neo4j actually hydrates
     * involvedCountries on the mapped Conflict - returning only "c" leaves that relationship
     * collection empty, which is why the Intelligence Graph page showed disconnected nodes with
     * no edges for country searches.
     */
    // Ordered most-recent-first: without this, the 50-result cap in IntelligenceController just
    // took whatever order Neo4j happened to return rows in (effectively arbitrary/oldest-first),
    // so a search always surfaced old events instead of the most current ones.
    @Query("MATCH (c:Conflict)-[rel:INVOLVES]->(co:Country) WHERE toLower(co.name) IN $lowerNames RETURN DISTINCT c, rel, co ORDER BY c.dateReported DESC")
    List<Conflict> findByInvolvedCountryNamesIgnoreCase(@Param("lowerNames") Collection<String> lowerNames);

    /**
     * Keeps AuraDB free-tier node count bounded: without this, GDELT ingestion running every 15
     * minutes forever accumulates Conflict nodes indefinitely and eventually exhausts the
     * 200k-node free-tier cap regardless of how many countries are whitelisted. Widening in the
     * cutoff-less MATCH lets the DB batch/stream the delete instead of loading every match into
     * the JVM first. Returns the deleted count purely for logging - the delete itself doesn't
     * depend on it.
     */
    @Query("MATCH (c:Conflict) WHERE c.dateReported < $cutoff WITH c DETACH DELETE c RETURN count(c) AS deletedCount")
    long deleteByDateReportedBefore(@Param("cutoff") LocalDate cutoff);
}
