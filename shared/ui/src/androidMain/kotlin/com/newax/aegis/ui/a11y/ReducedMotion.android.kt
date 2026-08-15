package com.newax.aegis.ui.a11y

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Reads `Settings.Global.ANIMATOR_DURATION_SCALE`, which is what the
 * "Remove animations" accessibility setting and Developer Options both write.
 * A scale of 0 means the user wants no animation.
 *
 * Read once per context rather than observed: the setting requires leaving the
 * app to change, and the activity is recreated on return.
 */
@Composable
actual fun reducedMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        scale == 0f
    }
}
