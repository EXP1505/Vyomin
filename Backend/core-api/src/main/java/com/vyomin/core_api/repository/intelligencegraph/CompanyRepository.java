package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.Company;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import java.util.Optional;

public interface CompanyRepository extends Neo4jRepository<Company, Long> {
    Optional<Company> findByName(String name);
    Optional<Company> findByTicker(String ticker);
}
