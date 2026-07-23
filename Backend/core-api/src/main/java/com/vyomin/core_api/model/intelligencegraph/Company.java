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
public class Company {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String ticker;
    private String industry;
    private Double marketCap;
    private String description;

    @Relationship(type = "BELONGS_TO_SECTOR", direction = Relationship.Direction.OUTGOING)
    private Sector sector;

    @Relationship(type = "HEADQUARTERED_IN", direction = Relationship.Direction.OUTGOING)
    private Country headquarters;

    @Relationship(type = "INVESTED_BY", direction = Relationship.Direction.INCOMING)
    private Set<Investor> investors = new HashSet<>();
}
