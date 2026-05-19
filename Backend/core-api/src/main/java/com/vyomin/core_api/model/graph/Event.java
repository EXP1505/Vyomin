package com.vyomin.core_api.model.graph;

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

//this is basically about the impacts in the outerworld in the form of news and current events, events might be similar so the id is being generated uniquely using @GeneratedValue
public class Event {
    @Id @GeneratedValue
    private Long id;
    private String headline;
    private LocalDate date;

    @Relationship(type = "IMPACTS", direction = Relationship.Direction.OUTGOING)
    private Set<Company> impactedCompanies = new HashSet<>();

    @Relationship(type = "IMPACTS", direction = Relationship.Direction.OUTGOING)
    private Set<Country> impactedCountries = new HashSet<>();
}
