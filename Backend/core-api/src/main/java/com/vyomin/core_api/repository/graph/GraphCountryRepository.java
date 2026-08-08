package com.vyomin.core_api.repository.graph;
import com.vyomin.core_api.model.graph.Country;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;


public interface GraphCountryRepository extends Neo4jRepository<Country, Long> {
    Optional<Country> findByName(String name);
    List<Country> findByNameIn(Collection<String> names);
}
