package com.thomas.pt.writer.core

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ChannelThroughputTracker {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val channelCounts = ConcurrentHashMap<String, ConcurrentHashMap<Long, AtomicLong>>()
    private val overallCounts = ConcurrentHashMap<Long, AtomicLong>()

    fun recordEvent(simTime: Double, channelName: String) {
        val simSecond = simTime.toLong()

        channelCounts
            .computeIfAbsent(channelName) { ConcurrentHashMap() }
            .computeIfAbsent(simSecond) { AtomicLong(0) }
            .incrementAndGet()

        overallCounts
            .computeIfAbsent(simSecond) { AtomicLong(0) }
            .incrementAndGet()
    }

    fun logMaxThroughput() {
        logger.info("=== Channel Throughput Summary ===")

        if (overallCounts.isEmpty()) {
            logger.info("No events recorded")
            return
        }

        val overallMax = overallCounts.maxByOrNull { it.value.get() }
        if (overallMax != null) {
            logger.info(
                "Max overall throughput: {} events/sec at simulation second {}",
                overallMax.value.get(),
                overallMax.key
            )
        }

        logger.info("Per-channel maximums:")
        channelCounts.forEach { (channelName, counts) ->
            val max = counts.maxByOrNull { it.value.get() }
            if (max != null) {
                logger.info(
                    "  {}: {} events/sec at second {}",
                    channelName,
                    max.value.get(),
                    max.key
                )
            }
        }
    }
}
