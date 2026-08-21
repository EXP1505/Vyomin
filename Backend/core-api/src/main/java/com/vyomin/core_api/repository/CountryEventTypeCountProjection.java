package com.vyomin.core_api.repository;

/** One row per event_type for a single country's raw gdelt_event_history rows, count descending. */
public interface CountryEventTypeCountProjection {
    String getEventType();
    long getCount();
}
