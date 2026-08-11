package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.User;
import com.vyomin.core_api.model.WatchedEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface WatchedEntityRepository extends JpaRepository<WatchedEntity, UUID> {
    List<WatchedEntity> findByUser(User user);
    boolean existsByUserAndEntityTypeAndEntityId(User user, WatchedEntity.EntityType entityType, String entityId);

    @Transactional
    void deleteByUserAndEntityTypeAndEntityId(User user, WatchedEntity.EntityType entityType, String entityId);
}
