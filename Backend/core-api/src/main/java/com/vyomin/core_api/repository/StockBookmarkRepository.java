package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.StockBookmark;
import com.vyomin.core_api.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface StockBookmarkRepository extends JpaRepository<StockBookmark, UUID> {
    List<StockBookmark> findByUser(User user);
    boolean existsByUserAndSymbol(User user, String symbol);

    @Transactional
    void deleteByUserAndSymbol(User user, String symbol);
}
