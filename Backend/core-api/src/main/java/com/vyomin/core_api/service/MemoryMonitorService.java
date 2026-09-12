package com.vyomin.core_api.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Render's free tier only exposes the Memory metrics graph on a paid plan, so this logs the
 * JVM's own view of memory usage straight into the Render logs every minute - enough to see
 * whether a restart lines up with memory climbing toward the container's 512MB limit, without
 * needing to pay for the dashboard graph.
 *
 * heap/non-heap alone were found to plateau around 320MB (nowhere near 512MB) right before a
 * restart, which pointed at memory MemoryMXBean doesn't cover: thread stacks and, especially,
 * NIO direct buffers - the off-heap memory Netty allocates per connection underneath the Neo4j
 * driver's TLS sockets. "direct" below is exactly that: if it's climbing toward
 * -XX:MaxDirectMemorySize while heap/non-heap stay flat, that confirms the connection-pool-driven
 * off-heap theory rather than a heap/metaspace leak.
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

        long directUsedMb = 0;
        long directCapacityMb = 0;
        List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        for (BufferPoolMXBean pool : bufferPools) {
            if ("direct".equals(pool.getName())) {
                directUsedMb = pool.getMemoryUsed() / MB;
                directCapacityMb = pool.getTotalCapacity() / MB;
            }
        }

        log.info("Memory: heap={}MB/{}MB (max {}MB), non-heap={}MB/{}MB, direct={}MB/{}MB, threads={}",
                heapUsedMb, heapCommittedMb, heapMaxMb,
                nonHeapUsedMb, nonHeapCommittedMb,
                directUsedMb, directCapacityMb,
                Thread.activeCount());
    }
}
