package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.Conflict;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.List;
import java.util.Optional;

public interface ConflictRepository extends Neo4jRepository<Conflict, Long> {
    List<Conflict> findByName(String name);
    Optional<Conflict> findByGdeltEventId(String gdeltEventId);
    List<Conflict> findByDescriptionContainingIgnoreCase(String description);
}
