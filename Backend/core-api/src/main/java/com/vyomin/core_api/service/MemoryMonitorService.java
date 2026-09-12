package com.vyomin.core_api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Render's free tier only exposes the Memory metrics graph on a paid plan, so this logs the
 * JVM's own view of heap/non-heap/metaspace usage straight into the Render logs every minute -
 * enough to see whether a restart lines up with memory climbing toward the container's 512MB
 * limit, without needing to pay for the dashboard graph. Heap max here reflects
 * -XX:MaxRAMPercentage; non-heap covers metaspace, thread stacks aren't included (the JVM doesn't
 * expose those via MemoryMXBean), so total process RSS as Render sees it will run higher than
 * heap+non-heap alone.
 */
@Service
@Slf4j
public class MemoryMonitorService {

    private static final long MB = 1024 * 1024;

    @Scheduled(fixedRate = 60_000, initialDelay = 15_000)
    public void logMemoryUsage() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = memoryBean.getNonHeapMemoryUsage();

        long heapUsedMb = heap.getUsed() / MB;
        long heapCommittedMb = heap.getCommitted() / MB;
        long heapMaxMb = heap.getMax() / MB;
        long nonHeapUsedMb = nonHeap.getUsed() / MB;
        long nonHeapCommittedMb = nonHeap.getCommitted() / MB;

        log.info("Memory: heap={}MB/{}MB (max {}MB), non-heap={}MB/{}MB, threads={}",
                heapUsedMb, heapCommittedMb, heapMaxMb,
                nonHeapUsedMb, nonHeapCommittedMb,
                Thread.activeCount());
    }
}
