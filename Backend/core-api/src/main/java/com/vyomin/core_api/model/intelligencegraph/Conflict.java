package com.vyomin.core_api.model.intelligencegraph;

import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Relationship;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import lombok.Data;
import java.util.List;
import java.util.ArrayList;
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

    /** Dedup key for GDELT 2.0 Events ingestion: actor1Code|actor2Code|eventCode|sqlDate. */
    private String gdeltEventId;
    private String eventType;
    private String primaryRegion;

    // GDELT 2.0 Events actor/event fields
    private String actor1Name;
    private String actor1CountryCode;
    private String actor1Type;
    private String actor2Name;
    private String actor2CountryCode;
    private String actor2Type;
    private Integer eventCode;
    private Double tone;
    private Double latitude;
    private Double longitude;
    private LocalDate dateReported;
    private List<String> keywords = new ArrayList<>();

    /** GDELT's SOURCEURL column - the article this event was extracted from, when GDELT provides one. */
    private String sourceUrl;

    @Relationship(type = "INVOLVES", direction = Relationship.Direction.OUTGOING)
    private Set<Country> involvedCountries = new HashSet<>();
}
