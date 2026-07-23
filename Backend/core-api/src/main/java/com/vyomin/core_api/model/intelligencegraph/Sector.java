package com.vyomin.core_api.model.intelligencegraph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import lombok.Data;

@Node
@Data
public class Sector {
    @Id @GeneratedValue
    private Long id;
    private String name;
}
