package com.vyomin.core_api.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per successfully processed masterfilelist.txt entry for GdeltHistoricalBackfillService.
 * Replaces the old single "last completed file" pointer (GdeltBackfillCheckpoint, now removed):
 * with concurrent workers, files finish out of order, so a single pointer can't safely represent
 * progress - a later file might complete while an earlier one is still in flight or has failed,
 * and advancing a single pointer past it would cause that earlier file to be skipped on resume.
 * A completed-files set has no such ordering assumption - each file is independently marked done.
 */
@Entity
@Table(name = "gdelt_backfill_completed_file")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GdeltBackfillCompletedFile {

    @Id
    @Column(name = "file_url", length = 512)
    private String fileUrl;

    @Column(name = "completed_at")
    private Instant completedAt;
}
