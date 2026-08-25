package com.caniko.cenix

import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.ShortcutId

class PinShortcutActivity : AppCompatActivity() {
    private var request: LauncherApps.PinItemRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.getParcelableExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, LauncherApps.PinItemRequest::class.java)
            ?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT && it.isValid }
        val shortcut = request?.shortcutInfo
        if (shortcut == null) {
            finish()
            return
        }
        val root = LinearLayout(this).apply {
            id = R.id.pin_confirmation
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(this@PinShortcutActivity).apply {
                text = getString(R.string.pin_shortcut_title)
                textSize = 20f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@PinShortcutActivity).apply {
                text = ShortcutCatalog.safeLabel(shortcut.shortLabel)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Button(this@PinShortcutActivity).apply {
                id = R.id.pin_confirm
                text = getString(R.string.add)
                setOnClickListener { confirm() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(Button(this@PinShortcutActivity).apply {
                id = R.id.pin_cancel
                text = getString(R.string.cancel)
                setOnClickListener { cancel() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        setContentView(root)
    }

    private fun confirm() {
        val pending = request?.takeIf { it.isValid } ?: return finish()
        val info = pending.shortcutInfo ?: return finish()
        val application = application as CenixApplication
        CenixExecutors.io {
            if (!application.awaitReady() || application.emergency) return@io rejected()
            val database = application.database ?: return@io rejected()
            val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(info.userHandle)
            if (serial < 0) return@io rejected()
            val controller = WorkspaceController(LauncherRepository(database)) { !application.emergency }
            val state = controller.snapshot()
            val destination = state.pages.asSequence().flatMap { page ->
                val occupied = state.items.filter { (it.container as? ContainerRef.Workspace)?.pageId == page.pageId }
                    .map { it.cell.cellX to it.cell.cellY }.toSet()
                (0 until state.grid.rows).asSequence().flatMap { y ->
                    (0 until state.grid.cols).asSequence().map { x -> Triple(page.pageId, x, y) }
                }.filter { (_, x, y) -> x to y !in occupied }
            }.firstOrNull() ?: return@io rejected(R.string.workspace_full)
            val identity = ShortcutId(info.`package`, info.id, serial.toULong())
            val priorShortcuts = state.shortcutIds()
            if (identity in priorShortcuts) return@io rejected()
            val accepted = try {
                pending.isValid && pending.accept()
            } catch (_: RuntimeException) {
                false
            }
            if (!accepted) return@io rejected()
            val transition = try {
                controller.placeShortcut(
                    identity,
                    ContainerRef.Workspace(destination.first),
                    destination.second,
                    destination.third,
                )
            } catch (_: RuntimeException) {
                null
            }
            if (transition == null) {
                restorePins(identity, info.userHandle, priorShortcuts)
                return@io rejected()
            }
            CenixLog.event(
                EventId.SHORTCUT_PIN,
                Severity.INFO,
                mapOf("result" to "accepted", "changed" to transition.changedItemIds.size.toString()),
            )
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
        }
    }

    private fun cancel() {
        CenixLog.event(EventId.SHORTCUT_PIN, Severity.INFO, mapOf("result" to "cancelled"))
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun rejected(message: Int = R.string.shortcut_unavailable) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun restorePins(identity: ShortcutId, user: android.os.UserHandle, prior: Collection<ShortcutId>) {
        try {
            getSystemService(LauncherApps::class.java).pinShortcuts(
                identity.`package`,
                prior.filter { it.`package` == identity.`package` && it.profileId == identity.profileId }
                    .map { it.shortcutId },
                user,
            )
        } catch (_: RuntimeException) {
            Unit
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
