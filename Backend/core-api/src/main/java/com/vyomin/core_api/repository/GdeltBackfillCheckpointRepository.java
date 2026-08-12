package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.GdeltBackfillCheckpoint;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GdeltBackfillCheckpointRepository extends JpaRepository<GdeltBackfillCheckpoint, Integer> {
}