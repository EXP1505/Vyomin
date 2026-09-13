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
     * 200k-node free-tier cap regardless of how many countries are whitelisted.
     *
     * LIMIT $batchSize caps this to one bounded transaction instead of one that DETACH DELETEs
     * every matching node at once - a single unbounded delete against a large backlog (a fresh
     * cutoff after months without pruning, or a lowered retention-days) held a connection/Aura's
     * query resources for so long it starved every other request on the pool, which is what
     * actually took the app down (a batch-save failing with "Unable to acquire connection from
     * the pool" right after a 197k-node single-shot prune, cascading into request timeouts the
     * browser reported as blanket CORS failures). The caller loops this in small batches instead.
     */
    @Query("MATCH (c:Conflict) WHERE c.dateReported < $cutoff WITH c LIMIT $batchSize DETACH DELETE c RETURN count(c) AS deletedCount")
    long deleteByDateReportedBefore(@Param("cutoff") LocalDate cutoff, @Param("batchSize") long batchSize);

    /**
     * Backs the "conflict" search type's name/description lookup (e.g. searching "India"). Was
     * previously done via conflictRepository.findAll().stream().filter(...) in
     * IntelligenceSearchService - loading every Conflict node in the whole database, each fully
     * hydrated with its INVOLVES relationships, into the JVM before filtering down to matches.
     * With tens of thousands of Conflict nodes that single findAll() round-trip was a massive,
     * sudden allocation - big enough to OOM the 512MB container in the few seconds it took to
     * execute, well within a single gap of the minute-by-minute memory-usage logging, which is
     * exactly why every observed crash followed an interactive search with no warning in the
     * memory logs beforehand. Pushing the CONTAINS filter into Cypher and capping the result size
     * mirrors the same fix already applied to findByInvolvedCountryNamesIgnoreCase above.
     */
    @Query("MATCH (c:Conflict) WHERE toLower(c.name) CONTAINS $lower OR toLower(c.description) CONTAINS $lower " +
            "WITH c ORDER BY c.dateReported DESC LIMIT 50 " +
            "OPTIONAL MATCH (c)-[rel:INVOLVES]->(co:Country) RETURN c, rel, co")
    List<Conflict> findByNameOrDescriptionContainingIgnoreCase(@Param("lower") String lower);
}
