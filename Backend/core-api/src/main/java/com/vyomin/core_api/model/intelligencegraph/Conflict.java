package com.vyomin.core_api.model.intelligencegraph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import lombok.Data;
import java.util.Set;
import java.util.HashSet;
import java.time.LocalDate;

@Node
@Data
public class Conflict {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String description;
    private LocalDate startDate;
    private String severity;
    private Integer severityScore;
    private String gdeltEventId;
    private String eventType;
    private String primaryRegion;

    @Relationship(type = "INVOLVES", direction = Relationship.Direction.OUTGOING)
    private Set<Country> involvedCountries = new HashSet<>();
}
