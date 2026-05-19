package com.vyomin.core_api.repository.graph;
import com.vyomin.core_api.model.graph.Event;
import org.springframework.data.neo4j.repository.Neo4jRepository;
public interface EventRepository extends Neo4jRepository<Event, Long> {}
