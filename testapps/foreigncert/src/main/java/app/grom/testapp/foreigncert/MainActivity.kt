// SPDX-License-Identifier: Apache-2.0
package app.grom.testapp.foreigncert

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import app.grom.sdk.GromClient

/**
 * C-008 negative control (S4-R022).
 *
 * Signed with a different key, so the platform must refuse the bind on the
 * signature-level permission and no Binder may ever arrive. Expected result on
 * device:
 *   REFUSED: bindService returned false
 * or
 *   REFUSED: signature rejected
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
                // This would be a defect: a foreign signature must never connect.
                status.text = "DEFECT: CONNECTED api=$apiVersion"
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
