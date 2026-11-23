// SPDX-License-Identifier: Apache-2.0
package app.grom.testapp.samecert

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import app.grom.sdk.GromClient

/**
 * C-008 positive control (S4-R021).
 *
 * Signed with the Grom test key, so it must be granted the signature permission
 * and must receive a working Binder. Expected result on device:
 *   CONNECTED api=8      (the current contract version; older servers answer lower)
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var client: GromClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        setContentView(status)

        client = GromClient(this)
        status.text = "binding\u2026"
        val bound = client.bind(object : GromClient.Listener {
            override fun onConnected(apiVersion: Int) {
                status.text = "CONNECTED api=$apiVersion"
            }

            override fun onDisconnected(reason: String) {
                status.text = "REFUSED: $reason"
            }
        })
        if (!bound) {
            status.text = "REFUSED: bindService returned false"
        }
    }

    override fun onDestroy() {
        client.unbind()
        super.onDestroy()
    }
}
