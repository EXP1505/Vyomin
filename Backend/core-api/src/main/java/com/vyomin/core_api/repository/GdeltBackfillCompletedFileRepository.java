package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.GdeltBackfillCompletedFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface GdeltBackfillCompletedFileRepository extends JpaRepository<GdeltBackfillCompletedFile, String> {

    @Query(value = "SELECT file_url FROM gdelt_backfill_completed_file", nativeQuery = true)
    List<String> findAllFileUrls();

    // ON CONFLICT DO NOTHING: multiple worker threads may (harmlessly) race to mark the same file
    // if a resume overlaps with an in-flight run - this makes that a no-op instead of an error.
    @Modifying
    @Transactional
    @Query(value = "INSERT INTO gdelt_backfill_completed_file (file_url, completed_at) VALUES (:fileUrl, now()) ON CONFLICT (file_url) DO NOTHING",
            nativeQuery = true)
    void markCompleted(@Param("fileUrl") String fileUrl);
}
