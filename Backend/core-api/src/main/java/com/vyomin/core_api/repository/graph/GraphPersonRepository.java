package com.vyomin.core_api.repository.graph;
import com.vyomin.core_api.model.graph.Person;
import org.springframework.data.neo4j.repository.Neo4jRepository;
public interface GraphPersonRepository extends Neo4jRepository<Person, Long> {}
