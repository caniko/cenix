package com.caniko.cenix

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.UserHandle
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.PendingWidgetOperationEntity
import com.caniko.cenix.db.RestorePhase
import com.caniko.cenix.db.WidgetItemEntity
import com.caniko.cenix.db.WidgetOperationKind
import com.caniko.cenix.db.WidgetOperationPhase
import com.caniko.cenix.uniffi.CellRect
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.WidgetProviderId
import com.caniko.cenix.uniffi.WidgetMinimumSpan
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import kotlin.math.ceil
import kotlin.math.roundToInt

fun widgetMinimumSpans(
    context: Context,
    database: CenixDatabase,
    state: WorkspaceSnapshot,
    grid: PhoneGrid,
): List<WidgetMinimumSpan> {
    val manager = context.getSystemService(AppWidgetManager::class.java)
    val bindings = database.dao().workspaceWidgets().associateBy { it.itemId }
    val metrics = context.resources.displayMetrics
    val shortEdge = minOf(metrics.widthPixels, metrics.heightPixels)
    val cellWidth = ((shortEdge - 32 * metrics.density) / grid.cols).toInt().coerceAtLeast(1)
    val cellHeight = ((shortEdge - 120 * metrics.density) / grid.rows).toInt().coerceAtLeast(1)
    return state.items.mapNotNull { item ->
        if (item.payload !is com.caniko.cenix.uniffi.ItemPayload.Widget) return@mapNotNull null
        val info = bindings[item.itemId.toLong()]?.appWidgetId?.let(manager::getAppWidgetInfo)
        val spanX = info?.let {
            ceil((if (it.minResizeWidth > 0) it.minResizeWidth else it.minWidth).toDouble() / cellWidth).toInt()
        }?.coerceAtLeast(1) ?: item.cell.spanX
        val spanY = info?.let {
            ceil((if (it.minResizeHeight > 0) it.minResizeHeight else it.minHeight).toDouble() / cellHeight).toInt()
        }?.coerceAtLeast(1) ?: item.cell.spanY
        WidgetMinimumSpan(item.itemId, spanX, spanY)
    }
}

@Suppress("DEPRECATION")
class WidgetHostController(
    private val activity: HomeActivity,
    private val database: () -> CenixDatabase?,
    private val workspace: () -> WorkspaceController?,
    private val snapshot: () -> WorkspaceSnapshot,
    private val currentLayout: () -> CellLayout?,
    private val onCommitted: (WorkspaceSnapshot) -> Unit,
    private val profileAvailable: (Long) -> Boolean,
    private val profileUser: (Long) -> UserHandle?,
) {
    private val manager = activity.getSystemService(AppWidgetManager::class.java)
    private val host = object : AppWidgetHost(activity, HOST_ID) {
        override fun onCreateView(context: android.content.Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo) =
            AppWidgetHostView(context)
    }
    private var listening = false
    private var recovered = false
    private val views = mutableMapOf<Long, AppWidgetHostView>()

    fun start() {
        if (listening || (activity.application as CenixApplication).emergency) return
        try {
            host.startListening()
            listening = true
        } catch (_: RuntimeException) {
            Unit
        }
    }

    fun stop() {
        if (!listening) return
        host.stopListening()
        listening = false
    }

    fun destroy() {
        stop()
        views.clear()
    }

    fun pick(pageId: ULong, cellX: Int, cellY: Int) {
        if ((activity.application as CenixApplication).emergency) return
        activity.startActivityForResult(
            Intent(activity, WidgetPickerActivity::class.java)
                .putExtra(WidgetPickerActivity.EXTRA_PAGE, pageId.toLong())
                .putExtra(WidgetPickerActivity.EXTRA_CELL_X, cellX)
                .putExtra(WidgetPickerActivity.EXTRA_CELL_Y, cellY),
            REQUEST_PICK,
        )
    }

    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQUEST_PICK -> if (resultCode == Activity.RESULT_OK && data != null) beginAdd(data) else return true
            REQUEST_BIND -> resumeAfterBind(resultCode == Activity.RESULT_OK)
            REQUEST_CONFIGURE -> resumeAfterConfigure(resultCode == Activity.RESULT_OK)
            else -> return false
        }
        return true
    }

    fun recover() {
        if (recovered) return
        recovered = true
        CenixExecutors.io {
            val dao = database()?.dao() ?: return@io
            val operations = dao.pendingWidgetOperations()
            val committed = dao.workspaceWidgets()
            val knownIds = (committed.mapNotNull { it.appWidgetId } + operations.mapNotNull { it.appWidgetId }).toSet()
            val restore = dao.pendingRestore()
            host.appWidgetIds.filter { it !in knownIds }.forEach(host::deleteAppWidgetId)
            operations.forEach { operation ->
                val row = committed.firstOrNull { it.itemId == operation.itemId }
                when {
                    row?.appWidgetId == operation.appWidgetId -> dao.deletePendingWidgetOperation(operation.itemId)
                    operation.phase == WidgetOperationPhase.COMMITTING && validBinding(operation) -> commit(operation)
                    else -> rollback(operation, false)
                }
            }
            if (restore?.phase == RestorePhase.PLATFORM_RECONCILE) {
                restore.committedGeneration?.let(dao::completeRestore)
            }
        }
    }

    fun view(itemId: ULong, binding: WidgetItemEntity?, pageId: ULong, cell: CellRect, onRemove: () -> Unit): View {
        if (binding != null && !profileAvailable(binding.profileId)) {
            views.remove(itemId.toLong())?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return placeholder(itemId, binding, pageId, cell, onRemove)
        }
        val info = binding?.appWidgetId?.let(manager::getAppWidgetInfo)
        if (binding == null || info == null || (activity.application as CenixApplication).emergency) {
            views.remove(itemId.toLong())?.let { (it.parent as? ViewGroup)?.removeView(it) }
            return placeholder(itemId, binding, pageId, cell, onRemove)
        }
        val view = views.getOrPut(itemId.toLong()) {
            host.createView(activity, binding.appWidgetId, info).apply {
                setAppWidget(binding.appWidgetId, info)
            }
        }
        (view.parent as? ViewGroup)?.removeView(view)
        view.contentDescription = info.loadLabel(activity.packageManager).toString()
        return view
    }

    fun remove(itemId: ULong, binding: WidgetItemEntity?, itemCell: CellRect, pageId: ULong) {
        CenixExecutors.io {
            val dao = database()?.dao() ?: return@io
            if (binding != null) dao.upsertPendingWidgetOperation(
                PendingWidgetOperationEntity(
                    itemId.toLong(), WidgetOperationKind.ADD, WidgetOperationPhase.ROLLING_BACK,
                    binding.appWidgetId, null, binding.packageName, binding.className, binding.profileId,
                    pageId.toLong(), itemCell.cellX, itemCell.cellY, itemCell.spanX, itemCell.spanY,
                    System.currentTimeMillis(),
                ),
            )
            val transition = workspace()?.remove(itemId)
            if (transition != null) {
                binding?.appWidgetId?.let(host::deleteAppWidgetId)
                dao.deletePendingWidgetOperation(itemId.toLong())
                views.remove(itemId.toLong())
                activity.runOnUiThread { onCommitted(transition.toSnapshot()) }
            }
        }
    }

    fun resize(itemId: ULong, binding: WidgetItemEntity?, edge: WidgetResizeEdge, deltaX: Float, deltaY: Float) {
        val row = binding ?: return
        val info = row.appWidgetId?.let(manager::getAppWidgetInfo) ?: return
        val state = snapshot()
        val item = state.items.firstOrNull { it.itemId == itemId } ?: return
        val layout = currentLayout() ?: return
        val cellWidth = (layout.width / state.grid.cols).coerceAtLeast(1)
        val cellHeight = (layout.height / state.grid.rows).coerceAtLeast(1)
        val horizontal = info.resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0
        val vertical = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        if ((edge == WidgetResizeEdge.LEFT || edge == WidgetResizeEdge.RIGHT) && !horizontal) return
        if ((edge == WidgetResizeEdge.TOP || edge == WidgetResizeEdge.BOTTOM) && !vertical) return
        val dx = ((if (layout.layoutDirection == View.LAYOUT_DIRECTION_RTL) -deltaX else deltaX) / cellWidth).roundToInt()
        val dy = (deltaY / cellHeight).roundToInt()
        val next = when (edge) {
            WidgetResizeEdge.LEFT -> CellRect(item.cell.cellX + dx, item.cell.cellY, item.cell.spanX - dx, item.cell.spanY)
            WidgetResizeEdge.RIGHT -> CellRect(item.cell.cellX, item.cell.cellY, item.cell.spanX + dx, item.cell.spanY)
            WidgetResizeEdge.TOP -> CellRect(item.cell.cellX, item.cell.cellY + dy, item.cell.spanX, item.cell.spanY - dy)
            WidgetResizeEdge.BOTTOM -> CellRect(item.cell.cellX, item.cell.cellY, item.cell.spanX, item.cell.spanY + dy)
        }
        val minX = ceil(info.minResizeWidth.toDouble() / cellWidth).toInt().coerceAtLeast(1)
        val minY = ceil(info.minResizeHeight.toDouble() / cellHeight).toInt().coerceAtLeast(1)
        val maxX = if (info.maxResizeWidth > 0) (info.maxResizeWidth / cellWidth).coerceAtLeast(minX) else state.grid.cols
        val maxY = if (info.maxResizeHeight > 0) (info.maxResizeHeight / cellHeight).coerceAtLeast(minY) else state.grid.rows
        if (next == item.cell || next.spanX !in minX..maxX || next.spanY !in minY..maxY) return
        CenixExecutors.io {
            val transition = workspace()?.resizeWidget(itemId, next) ?: return@io
            manager.updateAppWidgetOptions(row.appWidgetId, sizeOptions(next))
            activity.runOnUiThread { onCommitted(transition.toSnapshot()) }
        }
    }

    fun rebind(itemId: ULong, binding: WidgetItemEntity, pageId: ULong, cell: CellRect) {
        if (!profileAvailable(binding.profileId)) return
        val user = user(binding.profileId) ?: return
        val provider = manager.getInstalledProvidersForProfile(user)
            .firstOrNull { it.provider == ComponentName(binding.packageName, binding.className) } ?: return
        val operation = PendingWidgetOperationEntity(
            itemId.toLong(), WidgetOperationKind.ADD, WidgetOperationPhase.ALLOCATING,
            null, binding.appWidgetId, binding.packageName, binding.className, binding.profileId,
            pageId.toLong(), cell.cellX, cell.cellY, cell.spanX, cell.spanY, System.currentTimeMillis(),
        )
        CenixExecutors.io { allocateAndBind(operation, provider, user) }
    }

    fun deleteHost() {
        stop()
        host.deleteHost()
        views.clear()
    }

    fun invalidateProfiles(profileIds: Set<Long>, bindings: Map<Long, WidgetItemEntity>) {
        bindings.filterValues { it.profileId in profileIds }.keys.forEach { itemId ->
            views.remove(itemId)?.let { (it.parent as? ViewGroup)?.removeView(it) }
        }
        if (profileIds.isNotEmpty()) CenixExecutors.io {
            database()?.dao()?.pendingWidgetOperations()
                ?.filter { it.profileId in profileIds }
                ?.forEach { rollback(it, false) }
        }
    }

    fun refreshOptions(state: WorkspaceSnapshot, bindings: Map<Long, WidgetItemEntity>) {
        state.items.forEach { item ->
            if (item.payload is com.caniko.cenix.uniffi.ItemPayload.Widget) {
                bindings[item.itemId.toLong()]?.appWidgetId?.let { manager.updateAppWidgetOptions(it, sizeOptions(item.cell)) }
            }
        }
    }

    private fun beginAdd(data: Intent) {
        val packageName = data.getStringExtra(WidgetPickerActivity.EXTRA_PACKAGE) ?: return
        val className = data.getStringExtra(WidgetPickerActivity.EXTRA_CLASS) ?: return
        val profileId = data.getLongExtra(WidgetPickerActivity.EXTRA_PROFILE, -1)
        val pageId = data.getLongExtra(WidgetPickerActivity.EXTRA_PAGE, -1)
        if (!profileAvailable(profileId)) return
        val user = user(profileId) ?: return
        val provider = manager.getInstalledProvidersForProfile(user)
            .firstOrNull { it.provider == ComponentName(packageName, className) } ?: return
        val state = snapshot()
        val span = defaultSpan(provider, state)
        val cell = findPlacement(
            state,
            pageId.toULong(),
            data.getIntExtra(WidgetPickerActivity.EXTRA_CELL_X, 0),
            data.getIntExtra(WidgetPickerActivity.EXTRA_CELL_Y, 0),
            span.first,
            span.second,
        ) ?: return Toast.makeText(activity, R.string.workspace_full, Toast.LENGTH_SHORT).show()
        CenixExecutors.io {
            val dao = database()?.dao() ?: return@io
            val operation = PendingWidgetOperationEntity(
                itemId = dao.workspaceMetadata()?.nextItemId ?: return@io,
                kind = WidgetOperationKind.ADD,
                phase = WidgetOperationPhase.ALLOCATING,
                appWidgetId = null,
                replacementAppWidgetId = null,
                packageName = packageName,
                className = className,
                profileId = profileId,
                pageId = pageId,
                cellX = cell.cellX,
                cellY = cell.cellY,
                spanX = cell.spanX,
                spanY = cell.spanY,
                updatedAt = System.currentTimeMillis(),
            )
            allocateAndBind(operation, provider, user)
        }
    }

    private fun allocateAndBind(
        operation: PendingWidgetOperationEntity,
        provider: AppWidgetProviderInfo,
        user: UserHandle,
    ) {
        val dao = database()?.dao() ?: return
        dao.upsertPendingWidgetOperation(operation)
        val appWidgetId = try {
            host.allocateAppWidgetId()
        } catch (_: RuntimeException) {
            dao.deletePendingWidgetOperation(operation.itemId)
            return
        }
        val allocated = operation.copy(
            phase = WidgetOperationPhase.ALLOCATED,
            appWidgetId = appWidgetId,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertPendingWidgetOperation(allocated)
        val options = sizeOptions(allocated)
        if (manager.bindAppWidgetIdIfAllowed(appWidgetId, user, provider.provider, options)) {
            configureOrCommit(allocated.copy(phase = WidgetOperationPhase.BOUND))
        } else {
            val pending = allocated.copy(phase = WidgetOperationPhase.BIND_PERMISSION_PENDING)
            dao.upsertPendingWidgetOperation(pending)
            activity.runOnUiThread {
                activity.startActivityForResult(
                    Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options),
                    REQUEST_BIND,
                )
            }
        }
    }

    private fun resumeAfterBind(accepted: Boolean) {
        CenixExecutors.io {
            val operation = pending(WidgetOperationPhase.BIND_PERMISSION_PENDING) ?: return@io
            if (!accepted || !validBinding(operation)) rollback(operation) else configureOrCommit(
                operation.copy(phase = WidgetOperationPhase.BOUND, updatedAt = System.currentTimeMillis()),
            )
        }
    }

    private fun resumeAfterConfigure(accepted: Boolean) {
        CenixExecutors.io {
            val operation = pending(WidgetOperationPhase.CONFIGURATION_PENDING) ?: return@io
            if (!accepted || !validBinding(operation)) rollback(operation) else commit(operation)
        }
    }

    private fun configureOrCommit(operation: PendingWidgetOperationEntity) {
        val dao = database()?.dao() ?: return
        dao.upsertPendingWidgetOperation(operation)
        val appWidgetId = operation.appWidgetId ?: return rollback(operation)
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return rollback(operation)
        val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0 &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
        if (info.configure == null || optional) return commit(operation)
        val pending = operation.copy(
            phase = WidgetOperationPhase.CONFIGURATION_PENDING,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertPendingWidgetOperation(pending)
        activity.runOnUiThread {
            try {
                host.startAppWidgetConfigureActivityForResult(activity, appWidgetId, 0, REQUEST_CONFIGURE, null)
            } catch (_: RuntimeException) {
                CenixExecutors.io { rollback(pending) }
            }
        }
    }

    private fun commit(operation: PendingWidgetOperationEntity) {
        val dao = database()?.dao() ?: return
        val appWidgetId = operation.appWidgetId ?: return rollback(operation)
        val accepted = operation.copy(phase = WidgetOperationPhase.PLATFORM_ACCEPTED, updatedAt = System.currentTimeMillis())
        dao.upsertPendingWidgetOperation(accepted)
        val committing = accepted.copy(phase = WidgetOperationPhase.COMMITTING, updatedAt = System.currentTimeMillis())
        dao.upsertPendingWidgetOperation(committing)
        if (dao.workspaceWidgets().any { it.itemId == committing.itemId }) {
            dao.updateWidgetBinding(committing.itemId, appWidgetId)
            committing.replacementAppWidgetId?.let(host::deleteAppWidgetId)
            dao.deletePendingWidgetOperation(committing.itemId)
            activity.runOnUiThread { onCommitted(snapshot()) }
            return
        }
        val transition = workspace()?.placeWidget(
            committing.itemId.toULong(),
            WidgetProviderId(committing.packageName, committing.className, committing.profileId.toULong()),
            appWidgetId,
            committing.pageId.toULong(),
            CellRect(committing.cellX, committing.cellY, committing.spanX, committing.spanY),
        ) ?: return rollback(committing)
        dao.deletePendingWidgetOperation(committing.itemId)
        activity.runOnUiThread { onCommitted(transition.toSnapshot()) }
    }

    private fun rollback(operation: PendingWidgetOperationEntity, notify: Boolean = true) {
        val dao = database()?.dao() ?: return
        dao.upsertPendingWidgetOperation(operation.copy(phase = WidgetOperationPhase.ROLLING_BACK))
        operation.appWidgetId?.let {
            try {
                host.deleteAppWidgetId(it)
            } catch (_: RuntimeException) {
                Unit
            }
        }
        dao.deletePendingWidgetOperation(operation.itemId)
        if (notify) activity.runOnUiThread {
            Toast.makeText(activity, R.string.widget_add_cancelled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun pending(phase: WidgetOperationPhase): PendingWidgetOperationEntity? =
        database()?.dao()?.pendingWidgetOperations()?.singleOrNull { it.phase == phase }

    private fun validBinding(operation: PendingWidgetOperationEntity): Boolean {
        val info = operation.appWidgetId?.let(manager::getAppWidgetInfo) ?: return false
        return profileAvailable(operation.profileId) &&
            info.provider == ComponentName(operation.packageName, operation.className) &&
            info.profile == user(operation.profileId)
    }

    private fun user(profileId: Long): UserHandle? = profileUser(profileId)

    private fun defaultSpan(info: AppWidgetProviderInfo, state: WorkspaceSnapshot): Pair<Int, Int> {
        val layout = currentLayout()
        val cellWidth = ((layout?.width ?: 0) / state.grid.cols).coerceAtLeast(1)
        val cellHeight = ((layout?.height ?: 0) / state.grid.rows).coerceAtLeast(1)
        val spanX = (if (info.targetCellWidth > 0) info.targetCellWidth else ceil(info.minWidth.toDouble() / cellWidth).toInt())
            .coerceIn(1, state.grid.cols)
        val spanY = (if (info.targetCellHeight > 0) info.targetCellHeight else ceil(info.minHeight.toDouble() / cellHeight).toInt())
            .coerceIn(1, state.grid.rows)
        return spanX to spanY
    }

    private fun findPlacement(
        state: WorkspaceSnapshot,
        pageId: ULong,
        requestedX: Int,
        requestedY: Int,
        spanX: Int,
        spanY: Int,
    ): CellRect? {
        val occupied = state.items.filter { (it.container as? ContainerRef.Workspace)?.pageId == pageId }
        val candidates = sequenceOf(requestedX to requestedY) + (0 until state.grid.rows).asSequence().flatMap { y ->
            (0 until state.grid.cols).asSequence().map { x -> x to y }
        }
        return candidates.distinct().map { (x, y) -> CellRect(x, y, spanX, spanY) }.firstOrNull { cell ->
            cell.cellX >= 0 && cell.cellY >= 0 && cell.cellX + cell.spanX <= state.grid.cols &&
                cell.cellY + cell.spanY <= state.grid.rows && occupied.none { overlaps(it.cell, cell) }
        }
    }

    private fun sizeOptions(operation: PendingWidgetOperationEntity): Bundle {
        return sizeOptions(CellRect(operation.cellX, operation.cellY, operation.spanX, operation.spanY))
    }

    private fun sizeOptions(cell: CellRect): Bundle {
        val state = snapshot()
        val layout = currentLayout()
        val density = activity.resources.displayMetrics.density
        val width = ((layout?.width ?: 0) / state.grid.cols * cell.spanX / density).toInt().coerceAtLeast(1)
        val height = ((layout?.height ?: 0) / state.grid.rows * cell.spanY / density).toInt().coerceAtLeast(1)
        return Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
            putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(width.toFloat(), height.toFloat())))
        }
    }

    private fun placeholder(
        itemId: ULong,
        binding: WidgetItemEntity?,
        pageId: ULong,
        cell: CellRect,
        onRemove: () -> Unit,
    ) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.argb(28, 127, 127, 127))
        contentDescription = activity.getString(R.string.widget_unavailable)
        addView(TextView(activity).apply { text = activity.getString(R.string.widget_unavailable) })
        if (binding != null && profileAvailable(binding.profileId) && user(binding.profileId) != null) addView(Button(activity).apply {
            text = activity.getString(R.string.rebind_widget)
            setOnClickListener { rebind(itemId, binding, pageId, cell) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(Button(activity).apply {
            text = activity.getString(R.string.remove)
            contentDescription = activity.getString(R.string.remove)
            setOnClickListener { onRemove() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        tag = CellTarget(itemId)
    }

    private fun overlaps(left: CellRect, right: CellRect): Boolean =
        left.cellX < right.cellX + right.spanX && right.cellX < left.cellX + left.spanX &&
            left.cellY < right.cellY + right.spanY && right.cellY < left.cellY + left.spanY

    private fun WorkspaceTransition.toSnapshot() = WorkspaceSnapshot(generation, grid, pages, items, folders)

    companion object {
        const val HOST_ID = 0x43454e49
        private const val REQUEST_PICK = 4101
        private const val REQUEST_BIND = 4102
        private const val REQUEST_CONFIGURE = 4103
    }
}
