// SPDX-License-Identifier: Apache-2.0
package app.grom.server.agent

import android.content.Context
import java.io.File

/**
 * Android side of [RuntimeInstaller]: exposes the APK assets as streams.
 *
 * The assets are `assets/node/grom-agent.js` (shipped with the app) and
 * `assets/node/libnode.so` (present only when an operator built the runtime
 * into the APK, or installed it with `scripts/build-node-android.sh`).
 */
object AssetRuntime {

    const val ASSET_PREFIX: String = "node/"

    fun install(context: Context): RuntimeInstaller.Outcome {
        val target = File(File(context.filesDir, "grom"), "node")
        return RuntimeInstaller.install(target) { name ->
            runCatching { context.assets.open(ASSET_PREFIX + name) }.getOrNull()
        }
    }
}
