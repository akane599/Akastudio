package com.akane.akastudio.core.ingestion.decode

internal object ScaleMath {
    /** Size whose long edge equals [maxEdge], keeping the aspect ratio (rounded half up, each side at least 1). */
    fun fitLongEdge(width: Int, height: Int, maxEdge: Int): Pair<Int, Int> {
        val w = width.coerceAtLeast(1).toLong()
        val h = height.coerceAtLeast(1).toLong()
        val edge = maxEdge.coerceAtLeast(1).toLong()
        val longEdge = maxOf(w, h)
        fun scale(side: Long): Int = ((side * edge * 2 + longEdge) / (longEdge * 2)).coerceAtLeast(1).toInt()
        return scale(w) to scale(h)
    }
}
