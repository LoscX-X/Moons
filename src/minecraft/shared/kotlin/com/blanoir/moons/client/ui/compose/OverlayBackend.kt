package com.blanoir.moons.client.ui.compose

/** GPU operations stay in the compiled version backend; targets keep their actual type. */
internal interface OverlayBackend<T : Any> : AutoCloseable {
    /** Identity only, never a dynamically invoked GPU object. */
    val deviceIdentity: Any
    val ready: Boolean

    fun outputTarget(width: Int, height: Int): T?

    fun create(width: Int, height: Int)

    fun upload(surface: RasterFrameBuffer, regions: List<RasterRegion>, height: Int)

    fun blend(target: T, region: RasterRegion, height: Int)

    /** Drop handles after GPU and raster resources have closed in the existing order. */
    fun reset()
}
