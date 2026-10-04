package com.blanoir.moons.client.ui.compose

import com.mojang.blaze3d.pipeline.RenderTarget

/** Version selection binds GPU types at compile time, while the raster flow stays shared. */
internal class FinalFrameSurface : RasterFramePipeline<RenderTarget>(GameOverlayBackend())
