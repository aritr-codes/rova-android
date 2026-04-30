package com.aritr.rova.service.surface

import android.os.Build
import java.util.concurrent.Executor

object HeadlessPreviewSurfaces {

    fun create(executor: Executor): HeadlessPreviewSurface =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            SurfaceTextureHeadlessSurface(executor)
        } else {
            ImageReaderHeadlessSurface(executor)
        }
}
