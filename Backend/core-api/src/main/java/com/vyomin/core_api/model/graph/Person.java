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

//this is to find out the people and what shares they own and what companies they supply to, just like what congressman might have its biases
public class Person {
    @Id @GeneratedValue
    private Long id;
    private String name;
    private String role;

    @Relationship(type = "OWNS_SHARES_IN", direction = Relationship.Direction.OUTGOING)
    private Set<Company> ownedCompanies = new HashSet<>();
}
