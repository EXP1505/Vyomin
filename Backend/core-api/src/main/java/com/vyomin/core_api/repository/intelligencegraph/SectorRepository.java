package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.Sector;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.Optional;

public interface SectorRepository extends Neo4jRepository<Sector, Long> {
    Optional<Sector> findByName(String name);
}
