package com.vyomin.core_api.repository;

import com.vyomin.core_api.model.AircraftDetailsEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AircraftDetailsRepository extends JpaRepository<AircraftDetailsEntity, String> {
}
