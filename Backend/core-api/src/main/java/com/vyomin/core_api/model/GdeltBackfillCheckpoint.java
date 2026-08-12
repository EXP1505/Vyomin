package com.vyomin.core_api.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * Single-row progress marker for GdeltHistoricalBackfillService - id is always 1. Lets a
 * restarted backfill resume from the last successfully processed masterfilelist.txt entry
 * instead of re-downloading every file from the start of the configured range.
 */
@Entity
@Table(name = "gdelt_backfill_checkpoint")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GdeltBackfillCheckpoint {

    @Id
    private Integer id;

    @Column(name = "last_completed_file")
    private String lastCompletedFile;

    @Column(name = "updated_at")
    private Instant updatedAt;
}