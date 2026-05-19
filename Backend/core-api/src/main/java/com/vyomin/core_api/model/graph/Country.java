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

//id is just the countires unique id, i might change it to string cuz India might be represented as "IND" and same with other countires
public class Country {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String region;

    @Relationship(type = "ISSUED_SANCTION_AGAINST", direction = Relationship.Direction.OUTGOING)
    private Set<Country> sanctionedCountries = new HashSet<>();
}
