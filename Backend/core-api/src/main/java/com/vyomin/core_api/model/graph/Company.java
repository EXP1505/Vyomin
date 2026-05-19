package com.vyomin.core_api.model.graph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import lombok.Data;
import java.util.Set;
import java.util.HashSet;

@Node
@Data

//id is the unique company id i will be getting from finnhub api
public class Company {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String industry;

    @Relationship(type = "SUPPLIES_TO", direction = Relationship.Direction.OUTGOING)
    private Set<Country> suppliedCountries = new HashSet<>();
}
