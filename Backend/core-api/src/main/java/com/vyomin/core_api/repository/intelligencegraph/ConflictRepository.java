package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.Conflict;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ConflictRepository extends Neo4jRepository<Conflict, Long> {
    List<Conflict> findByName(String name);
    Optional<Conflict> findByGdeltEventId(String gdeltEventId);
    List<Conflict> findByGdeltEventIdIn(Collection<String> gdeltEventIds);
    List<Conflict> findByDescriptionContainingIgnoreCase(String description);

    /**
     * Finds conflicts that INVOLVES one of the given (lowercased) country names, traversing the
     * relationship in the database instead of pulling every Conflict node into the JVM to filter
     * in memory - the previous findAll()-and-filter approach got slower every 15 minutes as GDELT
     * ingestion grew the table, and was the actual cause of the country-search endpoint hanging.
     */
    @Query("MATCH (c:Conflict)-[:INVOLVES]->(co:Country) WHERE toLower(co.name) IN $lowerNames RETURN DISTINCT c")
    List<Conflict> findByInvolvedCountryNamesIgnoreCase(@Param("lowerNames") Collection<String> lowerNames);
}
