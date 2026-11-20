// SPDX-License-Identifier: Apache-2.0
package app.grom.server

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.grom.server.core.BindMode
import app.grom.server.http.JsonParser
import app.grom.server.service.GromPrefs
import app.grom.server.store.AndroidKeystoreSecretBox
import app.grom.server.superserver.AuthMode
import app.grom.server.superserver.LightningKind
import app.grom.server.superserver.LightningSettings
import app.grom.server.superserver.SuperServerConfig
import app.grom.server.superserver.SuperServerConfigStore
import app.grom.server.superserver.auth.Ed25519
import app.grom.server.superserver.auth.PasswordHash
import app.grom.server.ui.GromTheme
import app.grom.server.ui.Palette
import app.grom.server.ui.actionRow
import app.grom.server.ui.actionRowState
import app.grom.server.ui.card
import app.grom.server.ui.divider
import app.grom.server.ui.dp
import app.grom.server.ui.gap
import app.grom.server.ui.label
import app.grom.server.ui.pill
import app.grom.server.ui.shape
import app.grom.server.ui.text
import app.grom.server.update.UpdateChecker
import app.grom.sdk.GromClient
import app.grom.sdk.ipc.IGromServer
import java.io.File
import java.util.Base64

/**
 * The operator surface: monochrome, one status, one primary action.
 *
 * The screen is optional — the server is fully usable over the HTTP API and
 * Binder — but it is the honest face of the app. It says MISSING_RUNTIME when
 * the agent cannot start, never "running", and the update check is an explicit
 * operator action against the public GitHub release feed.
 */
class GromActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var client: GromClient
    private lateinit var palette: Palette

    private var connected = false
    private var disconnectReason: String? = null
    private var stoppedByOperator = false
    private var status: Map<String, Any?>? = null

    private lateinit var stateView: TextView
    private lateinit var detailView: TextView
    private lateinit var primaryAction: TextView
    private lateinit var runtimeTitle: TextView
    private lateinit var runtimeDetail: TextView
    private lateinit var bootValue: TextView
    private lateinit var updateTitle: TextView
    private lateinit var updateDetail: TextView
    private lateinit var updateAction: TextView
    private lateinit var themeAction: TextView

    private val superStore by lazy { SuperServerConfigStore(File(filesDir, "grom")) }
    private val secretBox by lazy { AndroidKeystoreSecretBox() }
    private var superConfig: SuperServerConfig = SuperServerConfig.defaults()
    private lateinit var superDetail: TextView
    private lateinit var superEnableRow: Pair<LinearLayout, TextView>
    private lateinit var superBindRow: Pair<LinearLayout, TextView>
    private lateinit var superAuthRow: Pair<LinearLayout, TextView>
    private lateinit var superPasswordRow: Pair<LinearLayout, TextView>
    private lateinit var superKeysRow: Pair<LinearLayout, TextView>
    private lateinit var superLightningRow: Pair<LinearLayout, TextView>

    private var updateState: UpdateState = UpdateState.Idle

    private sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data object Current : UpdateState
        data class Available(val release: UpdateChecker.ReleaseInfo) : UpdateState
        data class Failed(val reason: String) : UpdateState
    }

    private val refresher = object : Runnable {
        override fun run() {
            if (connected) {
                status = JsonParser.parseObjectOrNull(
                    client.call("{}") { it.statusJson() }
                )
                render()
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        GromTheme.applyWindow(this)
        palette = GromTheme.palette(this)
        client = GromClient(this)
        if (BuildConfig.GROMSERVER) superConfig = superStore.load()
        setContentView(buildUi())
        renderUpdate()
        requestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        if (BuildConfig.GROMSERVER && !stoppedByOperator) bind()
        handler.post(refresher)
    }

    override fun onStop() {
        handler.removeCallbacks(refresher)
        client.unbind()
        connected = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        render()
    }

    // ------------------------------------------------------------------ layout

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(palette.background)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
        }
        scroll.addView(
            column,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        column.addView(header())
        column.addView(gap(20))
        column.addView(heroCard())
        column.addView(gap(14))
        column.addView(runtimeCard())

        if (BuildConfig.GROMSERVER) {
            column.addView(gap(14))
            column.addView(controlCard())
            column.addView(gap(14))
            column.addView(superCard())
        }

        column.addView(gap(14))
        column.addView(updateCard())
        column.addView(gap(24))
        column.addView(
            text(
                getString(R.string.ui_footer, BuildConfig.VERSION_NAME),
                12f,
                palette.muted,
                gravity = Gravity.CENTER
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        )

        // Edge-to-edge is enforced for targetSdk 35+; older platforms have
        // already consumed the insets, so this padding is simply zero there.
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            view.setPadding(0, top, 0, bottom)
            insets
        }
        return scroll
    }

    private fun header(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = text("G", 16f, palette.onAccent, android.graphics.Typeface.BOLD, gravity = Gravity.CENTER).apply {
            background = shape(palette.accent, 9)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            includeFontPadding = false
        }
        row.addView(mark)
        row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(12), 1) })

        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text(getString(R.string.ui_wordmark), 21f, palette.onSurface, android.graphics.Typeface.BOLD, letterSpacing = 0.16f))
        titles.addView(text(getString(R.string.ui_subtitle), 10f, palette.muted, letterSpacing = 0.12f))
        row.addView(
            titles,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        themeAction = pill(
            value = GromTheme.mode(this).label,
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = {
                GromTheme.setMode(this, GromTheme.mode(this).next())
                recreate()
            }
        ).apply {
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        row.addView(themeAction)
        return row
    }

    private fun heroCard(): View {
        val card = card(palette, radiusDp = 24)
        card.addView(label(getString(R.string.ui_status), palette))
        card.addView(gap(8))
        stateView = text(getString(R.string.ui_dash), 34f, palette.onSurface, android.graphics.Typeface.BOLD, letterSpacing = -0.02f)
        card.addView(stateView)
        card.addView(gap(6))
        detailView = text("", 14f, palette.muted)
        detailView.maxLines = 3
        detailView.ellipsize = TextUtils.TruncateAt.END
        card.addView(detailView)

        if (BuildConfig.GROMSERVER) {
            card.addView(gap(18))
            primaryAction = pill(
                value = getString(R.string.action_start),
                textColor = palette.onAccent,
                fill = palette.accent,
                rippleColor = palette.ripple,
                onClick = {
                    val running = connected && state() != null && state() != "STOPPED"
                    if (running) stopServer() else startServer()
                }
            ).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            card.addView(primaryAction)
        } else {
            primaryAction = text("", 1f, palette.muted)
        }
        return card
    }

    private fun runtimeCard(): View {
        val card = card(palette)
        card.addView(label(getString(R.string.ui_node_runtime), palette))
        card.addView(gap(8))
        runtimeTitle = text(getString(R.string.ui_dash), 20f, palette.onSurface, android.graphics.Typeface.BOLD)
        card.addView(runtimeTitle)
        card.addView(gap(4))
        runtimeDetail = text("", 13f, palette.muted)
        runtimeDetail.maxLines = 4
        runtimeDetail.ellipsize = TextUtils.TruncateAt.END
        card.addView(runtimeDetail)
        return card
    }

    private fun controlCard(): View {
        val card = card(palette)
        card.addView(label(getString(R.string.ui_control), palette))
        card.addView(gap(4))
        card.addView(actionRow(getString(R.string.ui_restart_agent), getString(R.string.ui_agent), palette) { invoke("restart") { it.restartAgent() } })
        card.addView(divider(palette))
        card.addView(actionRow(getString(R.string.ui_reset_quarantine), getString(R.string.ui_policy), palette) { invoke("reset") { it.resetAgent() } })
        card.addView(divider(palette))
        card.addView(actionRow(getString(R.string.ui_rotate_token), getString(R.string.ui_rotate), palette) {
            invoke("rotate") { it.rotateToken() }
            toast(getString(R.string.toast_token_rotated))
        })
        card.addView(divider(palette))
        bootValue = text(
            if (GromPrefs.startOnBoot(this)) getString(R.string.ui_on) else getString(R.string.ui_off),
            13f,
            palette.muted,
            gravity = Gravity.END
        )
        val bootRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                GromPrefs.setStartOnBoot(this@GromActivity, !GromPrefs.startOnBoot(this@GromActivity))
                bootValue.text = if (GromPrefs.startOnBoot(this@GromActivity)) getString(R.string.ui_on) else getString(R.string.ui_off)
            }
            addView(
                text(getString(R.string.ui_start_on_boot), 15f, palette.onSurface),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            )
            addView(bootValue)
        }
        card.addView(bootRow)
        return card
    }

    private fun superCard(): View {
        val card = card(palette)
        card.addView(label(getString(R.string.ui_access), palette))
        card.addView(gap(8))
        card.addView(
            text(
                getString(R.string.access_title),
                20f,
                palette.onSurface,
                android.graphics.Typeface.BOLD
            )
        )
        card.addView(gap(4))
        superDetail = text("", 13f, palette.muted)
        superDetail.maxLines = 4
        superDetail.ellipsize = TextUtils.TruncateAt.END
        card.addView(superDetail)
        card.addView(gap(4))

        superEnableRow = actionRowState(getString(R.string.access_enable), palette) { toggleSuperEnabled() }
        card.addView(superEnableRow.first)
        card.addView(divider(palette))
        superBindRow = actionRowState(getString(R.string.access_bind), palette) { cycleBindMode() }
        card.addView(superBindRow.first)
        card.addView(divider(palette))
        superAuthRow = actionRowState(getString(R.string.access_auth), palette) { cycleAuthMode() }
        card.addView(superAuthRow.first)
        card.addView(divider(palette))
        superPasswordRow = actionRowState(getString(R.string.access_password), palette) { showPasswordDialog() }
        card.addView(superPasswordRow.first)
        card.addView(divider(palette))
        superKeysRow = actionRowState(getString(R.string.access_keys), palette) { showKeysDialog() }
        card.addView(superKeysRow.first)
        card.addView(divider(palette))
        superLightningRow = actionRowState(getString(R.string.access_lightning), palette) { showLightningDialog() }
        card.addView(superLightningRow.first)
        return card
    }

    private fun renderSuper() {
        val superStatus = status?.get("super") as? Map<*, *>
        val error = superStatus?.get("error") as? String
        val running = superStatus?.get("running") == true
        val address = superStatus?.get("address") as? String ?: getString(R.string.ui_dash)

        superDetail.text = when {
            error != null -> error
            !superConfig.enabled -> getString(R.string.access_off)
            running -> getString(R.string.access_listening, address)
            else -> getString(R.string.access_off)
        }
        superDetail.setTextColor(if (error != null) palette.negative else palette.muted)

        superEnableRow.second.text =
            if (superConfig.enabled) getString(R.string.super_on) else getString(R.string.super_off)
        superBindRow.second.text = when (superConfig.bind) {
            BindMode.LOOPBACK -> getString(R.string.access_bind_loopback)
            BindMode.LAN -> getString(R.string.access_bind_lan)
            BindMode.PUBLIC -> getString(R.string.access_bind_public)
        }
        superAuthRow.second.text = when (superConfig.auth) {
            AuthMode.PASSWORD -> getString(R.string.access_auth_password)
            AuthMode.KEY -> getString(R.string.access_auth_key)
            AuthMode.BOTH -> getString(R.string.access_auth_both)
        }
        superPasswordRow.second.text = if (superConfig.passwordHash != null) {
            getString(R.string.access_password_set)
        } else {
            getString(R.string.access_password_unset)
        }
        superKeysRow.second.text = if (superConfig.keys.isEmpty()) {
            getString(R.string.access_keys_none)
        } else {
            superConfig.keys.size.toString()
        }
        superLightningRow.second.text = if (superConfig.lightning.kind == LightningKind.NONE) {
            getString(R.string.access_lightning_none)
        } else {
            superConfig.lightning.kind.wireName
        }
    }

    private fun toggleSuperEnabled() {
        if (!superConfig.enabled) {
            if (!superConfig.hasCredential()) {
                toast(getString(R.string.access_credentials_required))
                showPasswordDialog()
                return
            }
            persistSuperConfig(superConfig.copy(enabled = true))
        } else {
            persistSuperConfig(superConfig.copy(enabled = false))
        }
    }

    private fun cycleBindMode() {
        val next = when (superConfig.bind) {
            BindMode.LOOPBACK -> BindMode.LAN
            BindMode.LAN -> BindMode.PUBLIC
            BindMode.PUBLIC -> BindMode.LOOPBACK
        }
        if (next != BindMode.LOOPBACK && !superConfig.hasCredential()) {
            toast(getString(R.string.access_credentials_required))
            return
        }
        if (next == BindMode.PUBLIC) toast(getString(R.string.access_public_warning))
        persistSuperConfig(
            superConfig.copy(bind = next, acknowledgePublicBind = next == BindMode.PUBLIC)
        )
    }

    private fun cycleAuthMode() {
        val next = when (superConfig.auth) {
            AuthMode.PASSWORD -> AuthMode.KEY
            AuthMode.KEY -> AuthMode.BOTH
            AuthMode.BOTH -> AuthMode.PASSWORD
        }
        if (next == AuthMode.PASSWORD && superConfig.passwordHash == null) {
            toast(getString(R.string.access_credentials_required))
            return
        }
        if (next == AuthMode.KEY && superConfig.keys.isEmpty()) {
            toast(getString(R.string.access_credentials_required))
            return
        }
        persistSuperConfig(superConfig.copy(auth = next))
    }

    /** Writes the configuration and asks the running server to rebuild the listener. */
    private fun persistSuperConfig(updated: SuperServerConfig) {
        val previous = superConfig
        superConfig = updated
        try {
            superStore.save(updated)
        } catch (_: Exception) {
            superConfig = previous
            toast(getString(R.string.access_reload_failed))
            renderSuper()
            return
        }
        val reloaded = try {
            client.call(false) {
                it.reloadSuperServer()
                true
            }
        } catch (failure: IllegalStateException) {
            toast(failure.message ?: getString(R.string.access_reload_failed))
            false
        }
        toast(
            getString(if (reloaded) R.string.access_saved else R.string.access_reload_failed)
        )
        handler.postDelayed({ refreshNow() }, 300)
        renderSuper()
    }

    private fun showPasswordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.dialog_password_hint)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_password_title)
            .setView(input)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val value = input.text.toString()
                if (value.length < PasswordHash.MIN_LENGTH) {
                    toast(getString(R.string.dialog_password_too_short))
                    return@setPositiveButton
                }
                persistSuperConfig(superConfig.copy(passwordHash = PasswordHash.create(value).value))
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(superConfig.copy(passwordHash = null))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showKeysDialog() {
        val summary = if (superConfig.keys.isEmpty()) {
            getString(R.string.dialog_key_none)
        } else {
            superConfig.keys.joinToString("\n") { "${it.keyId}  ${it.label}" }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_key_title)
            .setMessage(summary)
            .setPositiveButton(R.string.dialog_key_generate) { _, _ -> generateApiKey() }
            .setNeutralButton(R.string.dialog_key_remove) { _, _ ->
                persistSuperConfig(superConfig.copy(keys = emptyList()))
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun generateApiKey() {
        val seed = Ed25519.generatePrivateSeed()
        val publicKey = Ed25519.derivePublicKey(seed)
        val keyId = Ed25519.keyId(publicKey)
        val encoded = Base64.getEncoder().encodeToString(publicKey)
        val key = app.grom.server.superserver.ApiKeyConfig(
            keyId = keyId,
            label = "operator",
            publicKey = encoded
        )
        persistSuperConfig(superConfig.copy(keys = superConfig.keys + key))
        showSeedDialog(seed, keyId)
    }

    private fun showSeedDialog(seed: ByteArray, keyId: String) {
        val text = Base64.getUrlEncoder().withoutPadding().encodeToString(seed)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_key_seed_title)
            .setMessage(getString(R.string.dialog_key_seed_body, text, keyId))
            .setPositiveButton(R.string.dialog_key_copy) { _, _ ->
                val clipboard = getSystemService(ClipboardManager::class.java)
                clipboard?.setPrimaryClip(ClipData.newPlainText("grom-key", text))
                toast(getString(R.string.toast_key_copied))
            }
            .setNegativeButton(R.string.dialog_close, null)
            .show()
    }

    private fun showLightningDialog() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), 0)
        }
        val url = EditText(this).apply {
            hint = getString(R.string.dialog_lightning_url)
            setText(superConfig.lightning.baseUrl)
            setSingleLine()
        }
        val apiKey = EditText(this).apply {
            hint = getString(R.string.dialog_lightning_key)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        column.addView(url)
        column.addView(apiKey)
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_lightning_title)
            .setMessage(R.string.dialog_lightning_hint)
            .setView(column)
            .setPositiveButton(R.string.dialog_save) { _, _ ->
                val base = url.text.toString().trim()
                if (!base.startsWith("https://")) {
                    toast(getString(R.string.toast_https_required))
                    return@setPositiveButton
                }
                val typed = apiKey.text.toString()
                val sealed = if (typed.isNotBlank()) {
                    secretBox.seal(typed)
                } else {
                    superConfig.lightning.apiKeySealed
                }
                persistSuperConfig(
                    superConfig.copy(
                        lightning = LightningSettings(
                            kind = LightningKind.LNBITS,
                            baseUrl = base,
                            apiKeySealed = sealed
                        )
                    )
                )
            }
            .setNeutralButton(R.string.dialog_clear) { _, _ ->
                persistSuperConfig(superConfig.copy(lightning = LightningSettings()))
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun updateCard(): View {
        val card = card(palette)
        card.addView(label(getString(R.string.ui_update), palette))
        card.addView(gap(8))
        updateTitle = text(getString(R.string.update_updates), 20f, palette.onSurface, android.graphics.Typeface.BOLD)
        card.addView(updateTitle)
        card.addView(gap(4))
        updateDetail = text(getString(R.string.update_installed, BuildConfig.VERSION_NAME), 13f, palette.muted)
        updateDetail.maxLines = 4
        updateDetail.ellipsize = TextUtils.TruncateAt.END
        card.addView(updateDetail)
        card.addView(gap(14))
        updateAction = pill(
            value = getString(R.string.update_check_now),
            textColor = palette.onSurface,
            fill = palette.background,
            rippleColor = palette.ripple,
            stroke = palette.border,
            onClick = {
                val current = updateState
                if (current is UpdateState.Available) {
                    openUrl(current.release.apkUrl ?: current.release.htmlUrl)
                } else {
                    checkForUpdates()
                }
            }
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        card.addView(updateAction)
        return card
    }

    // --------------------------------------------------------------- behaviour

    private fun bind() {
        disconnectReason = null
        client.bind(object : GromClient.Listener {
            override fun onConnected(apiVersion: Int) {
                connected = true
                refreshNow()
            }

            override fun onDisconnected(reason: String) {
                connected = false
                disconnectReason = reason
                render()
            }
        })
        render()
    }

    private fun startServer() {
        stoppedByOperator = false
        startForegroundService(Intent(this, app.grom.server.service.GromService::class.java))
        handler.postDelayed({
            if (!connected) bind() else refreshNow()
        }, 350)
    }

    private fun stopServer() {
        stoppedByOperator = true
        client.unbind()
        connected = false
        status = null
        stopService(Intent(this, app.grom.server.service.GromService::class.java))
        render()
    }

    private fun refreshNow() {
        if (!connected) return
        status = JsonParser.parseObjectOrNull(client.call("{}") { it.statusJson() })
        render()
    }

    private fun invoke(label: String, block: (IGromServer) -> Unit) {
        val server = client.server
        if (server == null) {
            toast(getString(R.string.toast_not_bound, label))
            return
        }
        try {
            block(server)
            toast(getString(R.string.toast_ok, label))
        } catch (failure: IllegalStateException) {
            toast(getString(R.string.toast_error, label, failure.message ?: failure.javaClass.simpleName))
        } catch (failure: SecurityException) {
            toast(getString(R.string.toast_forbidden, label))
        } catch (failure: android.os.RemoteException) {
            toast(getString(R.string.toast_server_gone, label))
        }
        handler.postDelayed({ refreshNow() }, 300)
    }

    // -------------------------------------------------------------------- render

    private fun render() {
        if (BuildConfig.GROMSERVER) renderSuper()
        val state = state()
        val runtimePresent = status?.get("runtime") == "node"
        val pid = number("pid")

        if (!BuildConfig.GROMSERVER) {
            stateView.text = getString(R.string.state_peripheral)
            stateView.setTextColor(palette.onSurface)
            detailView.text = getString(R.string.detail_peripheral)
            runtimeTitle.text = getString(R.string.runtime_not_applicable)
            runtimeDetail.text = getString(R.string.runtime_install_server_role)
            return
        }

        if (!connected) {
            stateView.text = getString(R.string.state_offline)
            stateView.setTextColor(palette.onSurface)
            detailView.text = disconnectReason?.let { getString(R.string.detail_ipc_reason, it) }
                ?: getString(R.string.detail_ipc_unbound)
            runtimeTitle.text = getString(R.string.runtime_unknown)
            runtimeDetail.text = getString(R.string.runtime_start_to_inspect)
            primaryAction.text = getString(R.string.action_start)
            return
        }

        when (state) {
            "READY" -> {
                stateView.text = getString(R.string.state_running)
                stateView.setTextColor(palette.positive)
                detailView.text = if (number("agentPort") > 0) {
                    getString(R.string.detail_address_agent, number("port"), number("agentPort"))
                } else {
                    getString(R.string.detail_address, number("port"))
                }
            }
            "STARTING" -> {
                stateView.text = getString(R.string.state_starting)
                stateView.setTextColor(palette.onSurface)
                detailView.text = getString(R.string.detail_starting)
            }
            "MISSING_RUNTIME" -> {
                stateView.text = getString(R.string.state_runtime_missing)
                stateView.setTextColor(palette.negative)
                detailView.text = getString(R.string.detail_runtime_missing)
            }
            "QUARANTINED" -> {
                stateView.text = getString(R.string.state_quarantined)
                stateView.setTextColor(palette.negative)
                detailView.text = status?.get("quarantineReason")
                    ?.toString()?.takeIf { it.isNotEmpty() }
                    ?: getString(R.string.detail_quarantine_default)
            }
            "STOPPED" -> {
                stateView.text = getString(R.string.state_stopped)
                stateView.setTextColor(palette.onSurface)
                detailView.text = getString(R.string.detail_stopped)
            }
            else -> {
                stateView.text = getString(R.string.state_server)
                stateView.setTextColor(palette.onSurface)
                detailView.text = getString(R.string.detail_address, number("port"))
            }
        }

        if (runtimePresent) {
            runtimeTitle.text = if (pid > 0) {
                getString(R.string.runtime_ready)
            } else {
                getString(R.string.runtime_installed)
            }
            runtimeDetail.text = if (pid > 0) {
                getString(R.string.runtime_ready_detail, pid, number("restarts"), number("crashes"))
            } else {
                getString(R.string.runtime_no_process)
            }
        } else {
            val missing = status?.get("missing") as? String
            runtimeTitle.text = getString(R.string.runtime_missing_title)
            runtimeDetail.text = if (!missing.isNullOrEmpty()) {
                getString(R.string.runtime_missing_named, missing)
            } else {
                getString(R.string.runtime_missing_generic)
            }
        }

        primaryAction.text = if (state == "STOPPED" || state == null) {
            getString(R.string.action_start)
        } else {
            getString(R.string.action_stop)
        }
    }

    private fun renderUpdate() {
        when (val current = updateState) {
            UpdateState.Idle -> {
                updateTitle.text = getString(R.string.update_updates)
                updateDetail.text = getString(R.string.update_installed, BuildConfig.VERSION_NAME)
                updateAction.text = getString(R.string.update_check_now)
                updateAction.alpha = 1f
            }
            UpdateState.Checking -> {
                updateTitle.text = getString(R.string.update_checking_title)
                updateDetail.text = getString(R.string.update_checking_detail)
                updateAction.text = getString(R.string.update_checking_button)
                updateAction.alpha = 0.45f
            }
            UpdateState.Current -> {
                updateTitle.text = getString(R.string.update_current)
                updateDetail.text = getString(R.string.update_installed, BuildConfig.VERSION_NAME)
                updateAction.text = getString(R.string.update_check_again)
                updateAction.alpha = 1f
            }
            is UpdateState.Available -> {
                updateTitle.text = getString(R.string.update_available, current.release.version)
                val notes = current.release.notes.trim()
                updateDetail.text = if (notes.isEmpty()) {
                    getString(R.string.update_installed, BuildConfig.VERSION_NAME)
                } else {
                    notes
                }
                updateAction.text = if (current.release.apkUrl != null) {
                    getString(R.string.update_download)
                } else {
                    getString(R.string.update_open_release)
                }
                updateAction.alpha = 1f
            }
            is UpdateState.Failed -> {
                updateTitle.text = getString(R.string.update_failed)
                updateDetail.text = current.reason
                updateAction.text = getString(R.string.update_try_again)
                updateAction.alpha = 1f
            }
        }
    }

    private fun checkForUpdates() {
        if (updateState is UpdateState.Checking) return
        updateState = UpdateState.Checking
        renderUpdate()
        val current = BuildConfig.VERSION_NAME
        val serverRole = BuildConfig.GROMSERVER
        Thread({
            val result = UpdateChecker.check(current, serverRole)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                updateState = result.fold(
                    onSuccess = { release ->
                        if (release == null) UpdateState.Current else UpdateState.Available(release)
                    },
                    onFailure = { failure ->
                        UpdateState.Failed(failure.message ?: getString(R.string.update_unknown_failure))
                    }
                )
                renderUpdate()
            }
        }, "grom-update-check").start()
    }

    private fun openUrl(url: String?) {
        if (url.isNullOrEmpty()) {
            toast(getString(R.string.toast_no_download_link))
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast(getString(R.string.toast_no_browser))
        }
    }

    // ------------------------------------------------------------------- helpers

    private fun state(): String? = status?.get("state") as? String

    private fun number(key: String): Long = (status?.get(key) as? Number)?.toLong() ?: -1L

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REFRESH_MS = 1_000L
        private const val REQUEST_NOTIFICATIONS = 47001
    }
}
