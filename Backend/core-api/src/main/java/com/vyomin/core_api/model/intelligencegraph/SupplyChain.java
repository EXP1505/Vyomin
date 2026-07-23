package com.vyomin.core_api.model.intelligencegraph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import lombok.Data;

@Node
@Data
public class SupplyChain {
    @Id @GeneratedValue
    private Long id;
    private String commodity;
    private String riskLevel;

    @Relationship(type = "SOURCED_FROM", direction = Relationship.Direction.OUTGOING)
    private Company supplier;

    @Relationship(type = "DELIVERS_TO", direction = Relationship.Direction.OUTGOING)
    private Company recipient;

    @Relationship(type = "TRANSITS_THROUGH", direction = Relationship.Direction.OUTGOING)
    private Country transitCountry;
}
