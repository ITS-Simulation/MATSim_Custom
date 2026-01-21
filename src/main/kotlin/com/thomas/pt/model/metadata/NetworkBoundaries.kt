package com.thomas.pt.model.metadata

import org.matsim.core.utils.collections.QuadTree

data class NetworkBoundaries(
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
) {
    fun <T> genQuadTree(): QuadTree<T>
        = QuadTree(minX, minY, maxX, maxY)
}