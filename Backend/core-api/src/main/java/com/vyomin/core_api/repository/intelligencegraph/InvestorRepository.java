package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.Investor;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.Optional;

public interface InvestorRepository extends Neo4jRepository<Investor, Long> {
    Optional<Investor> findByName(String name);
}
