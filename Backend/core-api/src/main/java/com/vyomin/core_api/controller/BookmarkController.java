package com.vyomin.core_api.controller;

import com.vyomin.core_api.dto.BookmarkDtos.*;
import com.vyomin.core_api.model.StockBookmark;
import com.vyomin.core_api.model.User;
import com.vyomin.core_api.model.WatchedEntity;
import com.vyomin.core_api.repository.StockBookmarkRepository;
import com.vyomin.core_api.repository.UserRepository;
import com.vyomin.core_api.repository.WatchedEntityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/bookmarks")
public class BookmarkController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StockBookmarkRepository stockBookmarkRepository;

    @Autowired
    private WatchedEntityRepository watchedEntityRepository;

    private User currentUser() {
        String email = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found: " + email));
    }

    @GetMapping("/stocks")
    public List<StockBookmarkResponse> listStocks() {
        return stockBookmarkRepository.findByUser(currentUser()).stream()
                .map(b -> new StockBookmarkResponse(b.getSymbol(), b.getCreatedAt()))
                .toList();
    }

    @PostMapping("/stocks")
    public ResponseEntity<?> addStock(@RequestBody StockBookmarkRequest request) {
        User user = currentUser();
        String symbol = request.symbol().toUpperCase();
        if (stockBookmarkRepository.existsByUserAndSymbol(user, symbol)) {
            return ResponseEntity.ok().build();
        }
        stockBookmarkRepository.save(StockBookmark.builder().user(user).symbol(symbol).build());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/stocks/{symbol}")
    public ResponseEntity<?> removeStock(@PathVariable String symbol) {
        stockBookmarkRepository.deleteByUserAndSymbol(currentUser(), symbol.toUpperCase());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/entities")
    public List<WatchedEntityResponse> listEntities() {
        return watchedEntityRepository.findByUser(currentUser()).stream()
                .map(e -> new WatchedEntityResponse(e.getEntityType().name(), e.getEntityId(), e.getEntityName(), e.getCreatedAt()))
                .toList();
    }

    @PostMapping("/entities")
    public ResponseEntity<?> addEntity(@RequestBody WatchedEntityRequest request) {
        User user = currentUser();
        if (watchedEntityRepository.existsByUserAndEntityTypeAndEntityId(user, request.entityType(), request.entityId())) {
            return ResponseEntity.ok().build();
        }
        watchedEntityRepository.save(WatchedEntity.builder()
                .user(user)
                .entityType(request.entityType())
                .entityId(request.entityId())
                .entityName(request.entityName())
                .build());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/entities/{entityType}/{entityId}")
    public ResponseEntity<?> removeEntity(@PathVariable WatchedEntity.EntityType entityType, @PathVariable String entityId) {
        watchedEntityRepository.deleteByUserAndEntityTypeAndEntityId(currentUser(), entityType, entityId);
        return ResponseEntity.ok().build();
    }
}
