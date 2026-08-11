package com.vyomin.core_api.dto;

import com.vyomin.core_api.model.WatchedEntity;

import java.time.Instant;

public class BookmarkDtos {

    public record StockBookmarkResponse(String symbol, Instant createdAt) {
    }

    public record StockBookmarkRequest(String symbol) {
    }

    public record WatchedEntityResponse(String entityType, String entityId, String entityName, Instant createdAt) {
    }

    public record WatchedEntityRequest(WatchedEntity.EntityType entityType, String entityId, String entityName) {
    }
}
