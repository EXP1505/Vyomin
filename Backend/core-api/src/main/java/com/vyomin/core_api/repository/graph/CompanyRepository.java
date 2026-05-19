package com.vyomin.core_api.repository.graph;

import com.vyomin.core_api.model.graph.Company;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

//company entity will have only company name as identifier other details will be stored only in company profile

public interface CompanyRepository extends Neo4jRepository<Company, Long> {

    Optional<Company> findByName(String name);

    @Query("MATCH path = shortestPath((person:Person {name: $personName})-[:OWNS_SHARES_IN|SUPPLIES_TO*..5]->(sanctioned:Country)) " +
           "MATCH (sanctioner:Country)-[:ISSUED_SANCTION_AGAINST]->(sanctioned) " +
           "RETURN [n IN nodes(path) | properties(n)] AS pathNodes")
    List<Object> findRiskPathByPersonName(@Param("personName") String personName);
}
