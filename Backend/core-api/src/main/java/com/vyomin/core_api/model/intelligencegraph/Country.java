package com.vyomin.core_api.model.intelligencegraph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import lombok.Data;
import java.util.Set;
import java.util.HashSet;

@Node
@Data
public class Country {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String region;

    @Relationship(type = "ISSUED_SANCTION_AGAINST", direction = Relationship.Direction.OUTGOING)
    private Set<Country> sanctionedCountries = new HashSet<>();
}
