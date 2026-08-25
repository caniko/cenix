package com.caniko.cenix

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.util.SizeF
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.caniko.cenix.db.PendingWidgetOperationEntity
import com.caniko.cenix.db.WidgetOperationKind
import com.caniko.cenix.db.WidgetOperationPhase
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.WidgetProviderId
import kotlin.math.ceil

@Suppress("DEPRECATION")
class PinWidgetActivity : AppCompatActivity() {
    private val host by lazy { AppWidgetHost(this, WidgetHostController.HOST_ID) }
    private val manager by lazy { getSystemService(AppWidgetManager::class.java) }
    private var request: LauncherApps.PinItemRequest? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request = intent.getParcelableExtra(LauncherApps.EXTRA_PIN_ITEM_REQUEST, LauncherApps.PinItemRequest::class.java)
            ?.takeIf { it.requestType == LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET && it.isValid }
        val info = request?.getAppWidgetProviderInfo(this)
        if (info == null) return finish()
        setContentView(LinearLayout(this).apply {
            id = R.id.pin_confirmation
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(this@PinWidgetActivity).apply {
                text = getString(R.string.pin_widget_title)
                textSize = 20f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(TextView(this@PinWidgetActivity).apply {
                text = info.loadLabel(packageManager)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(Button(this@PinWidgetActivity).apply {
                id = R.id.pin_confirm
                text = getString(R.string.add)
                setOnClickListener { confirm(info) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(Button(this@PinWidgetActivity).apply {
                id = R.id.pin_cancel
                text = getString(R.string.cancel)
                setOnClickListener { cancel() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != REQUEST_BIND) return super.onActivityResult(requestCode, resultCode, data)
        CenixExecutors.io {
            val app = application as CenixApplication
            val operation = app.database?.dao()?.pendingWidgetOperations()?.singleOrNull { it.kind == WidgetOperationKind.PIN }
                ?: return@io rejected()
            if (resultCode != RESULT_OK || manager.getAppWidgetInfo(operation.appWidgetId ?: -1) == null) {
                return@io rollback(operation)
            }
            acceptAndCommit(operation)
        }
    }

    private fun confirm(info: AppWidgetProviderInfo) {
        val pending = request?.takeIf { it.isValid } ?: return finish()
        val app = application as CenixApplication
        CenixExecutors.io {
            if (!app.awaitReady() || app.emergency) return@io rejected()
            val database = app.database ?: return@io rejected()
            val controller = WorkspaceController(LauncherRepository(database)) { !app.emergency }
            val state = controller.snapshot()
            val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(info.profile)
            if (serial < 0) return@io rejected()
            val cellWidth = (resources.displayMetrics.widthPixels / state.grid.cols).coerceAtLeast(1)
            val cellHeight = (resources.displayMetrics.heightPixels / state.grid.rows).coerceAtLeast(1)
            val spanX = (if (info.targetCellWidth > 0) info.targetCellWidth else ceil(info.minWidth.toDouble() / cellWidth).toInt())
                .coerceIn(1, state.grid.cols)
            val spanY = (if (info.targetCellHeight > 0) info.targetCellHeight else ceil(info.minHeight.toDouble() / cellHeight).toInt())
                .coerceIn(1, state.grid.rows)
            val destination = state.pages.asSequence().flatMap { page ->
                (0 until state.grid.rows).asSequence().flatMap { y ->
                    (0 until state.grid.cols).asSequence().map { x -> Triple(page.pageId, x, y) }
                }
            }.firstOrNull { (pageId, x, y) ->
                val candidate = CellRect(x, y, spanX, spanY)
                x + spanX <= state.grid.cols && y + spanY <= state.grid.rows && state.items.none {
                    (it.container as? ContainerRef.Workspace)?.pageId == pageId && overlaps(it.cell, candidate)
                }
            } ?: return@io rejected(R.string.workspace_full)
            val operation = PendingWidgetOperationEntity(
                database.dao().workspaceMetadata()?.nextItemId ?: return@io rejected(),
                WidgetOperationKind.PIN,
                WidgetOperationPhase.ALLOCATING,
                null,
                null,
                info.provider.packageName,
                info.provider.className,
                serial,
                destination.first.toLong(),
                destination.second,
                destination.third,
                spanX,
                spanY,
                System.currentTimeMillis(),
            )
            database.dao().upsertPendingWidgetOperation(operation)
            val appWidgetId = try {
                host.allocateAppWidgetId()
            } catch (_: RuntimeException) {
                database.dao().deletePendingWidgetOperation(operation.itemId)
                return@io rejected()
            }
            val allocated = operation.copy(
                phase = WidgetOperationPhase.ALLOCATED,
                appWidgetId = appWidgetId,
                updatedAt = System.currentTimeMillis(),
            )
            database.dao().upsertPendingWidgetOperation(allocated)
            val options = sizeOptions(spanX, spanY, cellWidth, cellHeight)
            if (manager.bindAppWidgetIdIfAllowed(appWidgetId, info.profile, info.provider, options)) {
                acceptAndCommit(allocated)
            } else {
                database.dao().upsertPendingWidgetOperation(allocated.copy(phase = WidgetOperationPhase.BIND_PERMISSION_PENDING))
                runOnUiThread {
                    startActivityForResult(
                        Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options),
                        REQUEST_BIND,
                    )
                }
            }
        }
    }

    private fun acceptAndCommit(operation: PendingWidgetOperationEntity) {
        val pending = request?.takeIf { it.isValid } ?: return rollback(operation)
        val appWidgetId = operation.appWidgetId ?: return rollback(operation)
        val accepted = try {
            pending.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId) })
        } catch (_: RuntimeException) {
            false
        }
        if (!accepted) return rollback(operation)
        val app = application as CenixApplication
        val database = app.database ?: return rollback(operation)
        val committing = operation.copy(phase = WidgetOperationPhase.COMMITTING, updatedAt = System.currentTimeMillis())
        database.dao().upsertPendingWidgetOperation(committing)
        val transition = WorkspaceController(LauncherRepository(database)) { !app.emergency }.placeWidget(
            committing.itemId.toULong(),
            WidgetProviderId(committing.packageName, committing.className, committing.profileId.toULong()),
            appWidgetId,
            committing.pageId.toULong(),
            CellRect(committing.cellX, committing.cellY, committing.spanX, committing.spanY),
        )
        if (transition == null) {
            runOnUiThread {
                setResult(RESULT_OK)
                finish()
            }
            return
        }
        database.dao().deletePendingWidgetOperation(committing.itemId)
        runOnUiThread {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun rollback(operation: PendingWidgetOperationEntity) {
        val dao = (application as CenixApplication).database?.dao()
        dao?.upsertPendingWidgetOperation(operation.copy(phase = WidgetOperationPhase.ROLLING_BACK))
        operation.appWidgetId?.let(host::deleteAppWidgetId)
        dao?.deletePendingWidgetOperation(operation.itemId)
        rejected()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun rejected(message: Int = R.string.widget_add_cancelled) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    private fun sizeOptions(spanX: Int, spanY: Int, cellWidth: Int, cellHeight: Int) = Bundle().apply {
        val density = resources.displayMetrics.density
        val width = (cellWidth * spanX / density).toInt()
        val height = (cellHeight * spanY / density).toInt()
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
    }

    private fun overlaps(left: CellRect, right: CellRect) =
        left.cellX < right.cellX + right.spanX && right.cellX < left.cellX + left.spanX &&
            left.cellY < right.cellY + right.spanY && right.cellY < left.cellY + left.spanY

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_BIND = 4201
    }
}
