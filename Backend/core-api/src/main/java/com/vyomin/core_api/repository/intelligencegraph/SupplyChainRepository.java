package com.vyomin.core_api.repository.intelligencegraph;

import com.vyomin.core_api.model.intelligencegraph.SupplyChain;
import org.springframework.data.neo4j.repository.Neo4jRepository;

public interface SupplyChainRepository extends Neo4jRepository<SupplyChain, Long> {}
