package com.caniko.cenix

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.DragEvent
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    private lateinit var app: CenixApplication
    private lateinit var catalog: AppCatalog
    private lateinit var statusTitle: TextView
    private lateinit var emergencyBanner: TextView
    private lateinit var searchField: EditText
    private lateinit var appList: ListView
    private lateinit var workspaceGrid: GridView
    private lateinit var hotseatGrid: GridView
    private lateinit var grid: PhoneGrid
    private lateinit var search: SearchController
    private var workspace: Workspace? = null
    private var screen = 0
    private val apps = mutableListOf<LaunchableApp>()
    private val visible = mutableListOf<LaunchableApp>()
    private val slots = mutableListOf<LaunchableApp?>()
    private val dock = mutableListOf<LaunchableApp?>()
    private var dragFromPin = false
    private var dragLanded = false
    private var dragStartScreen = 0
    private var dragStartX = 0
    private var dragStartY = 0

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = scheduleReload("add")
        override fun onPackageRemoved(packageName: String, user: UserHandle) = scheduleReload("remove")
        override fun onPackageChanged(packageName: String, user: UserHandle) = scheduleReload("change")
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload("available")
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleReload("unavailable")
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = scheduleReload("suspended")
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = scheduleReload("unsuspended")
    }

    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { out ->
            DiagnosticStore.export(
                out,
                mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "buildType" to BuildConfig.BUILD_TYPE,
                    "commit" to BuildConfig.GIT_COMMIT,
                    "schema" to com.caniko.cenix.db.CenixDatabase.VERSION.toString(),
                ),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as CenixApplication
        catalog = AppCatalog(this)
        val dm = resources.displayMetrics
        grid = PhoneGrid.pick(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
        search = SearchController(
            filterOf = { app.activeFilter() },
            onEmergency = {
                CenixExecutors.io {
                    if (app.awaitReady()) app.requestEmergency()
                    runOnUiThread { applyChrome() }
                }
            },
            onResult = { matches -> runOnUiThread { bindList(matches) } },
        )
        setContentView(R.layout.activity_home)
        statusTitle = findViewById(R.id.statusTitle)
        emergencyBanner = findViewById(R.id.emergencyBanner)
        searchField = findViewById(R.id.searchField)
        appList = findViewById(R.id.appList)
        workspaceGrid = findViewById(R.id.workspaceGrid)
        hotseatGrid = findViewById(R.id.hotseatGrid)
        findViewById<Button>(R.id.retryNative).setOnClickListener { retryNative() }
        findViewById<Button>(R.id.resetState).setOnClickListener { resetState() }
        findViewById<Button>(R.id.setDefaultHome).setOnClickListener { promptDefaultHome() }
        statusTitle.setOnLongClickListener {
            exportDiagnostics.launch("cenix-diagnostics.txt")
            true
        }
        val cell = (48 * resources.displayMetrics.density).toInt()
        val reserved = (200 * resources.displayMetrics.density).toInt()
        workspaceGrid.numColumns = grid.cols
        workspaceGrid.layoutParams = workspaceGrid.layoutParams.apply {
            val budget = (resources.displayMetrics.heightPixels - reserved - cell).coerceAtLeast(cell)
            height = minOf(grid.rows * cell, budget / cell * cell)
        }
        workspaceGrid.adapter = WorkspaceAdapter()
        workspaceGrid.setOnItemClickListener { _, _, position, _ -> slots[position]?.let(::launch) }
        workspaceGrid.setOnItemLongClickListener { _, view, position, _ ->
            slots[position]?.let { beginDrag(view, it, fromPin = true) }
            true
        }
        workspaceGrid.setOnDragListener { view, event ->
            onGridDrag(view, event, screen, grid.cols, grid.rows)
        }
        statusTitle.setOnDragListener { _, event -> onOffGridDrag(event) }
        searchField.setOnDragListener { _, event -> onOffGridDrag(event) }
        hotseatGrid.numColumns = grid.cols
        hotseatGrid.layoutParams = hotseatGrid.layoutParams.apply { height = cell }
        hotseatGrid.adapter = HotseatAdapter()
        hotseatGrid.setOnItemClickListener { _, _, position, _ -> dock[position]?.let(::launch) }
        hotseatGrid.setOnItemLongClickListener { _, view, position, _ ->
            dock[position]?.let { beginDrag(view, it, fromPin = true) }
            true
        }
        hotseatGrid.setOnDragListener { view, event ->
            onGridDrag(view, event, Workspace.HOTSEAT, grid.cols, 1)
        }
        val fling = ViewConfiguration.get(this).scaledMinimumFlingVelocity
        val pager = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                    if (kotlin.math.abs(vx) <= kotlin.math.abs(vy) || kotlin.math.abs(vx) < fling) return false
                    turn(if (vx < 0) 1 else -1)
                    return true
                }
            },
        )
        workspaceGrid.setOnTouchListener { _, event ->
            pager.onTouchEvent(event)
            false
        }
        appList.adapter = AppAdapter()
        appList.setOnItemClickListener { _, _, position, _ -> launch(visible[position]) }
        appList.setOnItemLongClickListener { _, _, position, _ ->
            pin(visible[position])
            true
        }
        searchField.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter()
                }
            },
        )
        catalog.register(packageCallback)
        scheduleReload("create")
    }

    override fun onStart() {
        super.onStart()
        scheduleReload("start")
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) {
            CenixExecutors.io {
                if (app.awaitReady()) app.requestEmergency()
                runOnUiThread { applyChrome() }
            }
        }
    }

    override fun onDestroy() {
        catalog.unregister(packageCallback)
        search.close()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) {
            CenixExecutors.io {
                if (app.awaitReady()) app.requestEmergency()
                runOnUiThread { applyChrome() }
            }
        }
    }

    private fun scheduleReload(reason: String) {
        val query = if (this::searchField.isInitialized) searchField.text?.toString().orEmpty() else ""
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) {
                app.requestEmergency()
            }
            workspace = app.database?.let { Workspace(it) { !app.emergency } }
            try {
                val loaded = catalog.load()
                workspace?.dropMissing(loaded.map { Triple(it.packageName, it.className, it.profileId) }.toSet())
                val items = workspace?.items().orEmpty()
                val profiles = catalog.visibleProfiles()
                val matches = try {
                    app.activeFilter().filter(loaded, query, profiles)
                } catch (_: Throwable) {
                    app.requestEmergency()
                    EmergencyAppFilter.filter(loaded, query, profiles)
                }
                app.markHealthy()
                CenixLog.event(
                    EventId.CATALOG_REFRESH,
                    Severity.INFO,
                    mapOf("count" to loaded.size.toString(), "reason" to reason),
                )
                if (reason != "create" && reason != "start") {
                    CenixLog.event(EventId.PACKAGE_CALLBACK, Severity.INFO, mapOf("category" to reason))
                }
                runOnUiThread {
                    apps.clear()
                    apps.addAll(loaded)
                    bindList(matches)
                    bindPinsFrom(items)
                    applyChrome()
                }
            } catch (_: Throwable) {
                app.requestEmergency()
                runOnUiThread { applyChrome() }
            }
        }
    }

    private fun bindWorkspaceFrom(items: List<com.caniko.cenix.db.WorkspaceItemEntity>) {
        val byCell = items
            .filter { it.screen == screen && grid.inBounds(it.cellX, it.cellY) }
            .associateBy { it.cellY * grid.cols + it.cellX }
        val catalogIndex = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
        slots.clear()
        for (i in 0 until grid.cells) {
            val item = byCell[i]
            slots.add(item?.let { catalogIndex[Triple(it.packageName, it.className, it.profileId)] })
        }
        (workspaceGrid.adapter as WorkspaceAdapter).notifyDataSetChanged()
        workspaceGrid.contentDescription = "${getString(R.string.workspace)} $screen"
    }

    private fun bindHotseatFrom(items: List<com.caniko.cenix.db.WorkspaceItemEntity>) {
        val byCell = items
            .filter { it.screen == Workspace.HOTSEAT && it.cellX in 0 until grid.cols }
            .associateBy { it.cellX }
        val catalogIndex = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
        dock.clear()
        for (x in 0 until grid.cols) {
            dock.add(byCell[x]?.let { catalogIndex[Triple(it.packageName, it.className, it.profileId)] })
        }
        (hotseatGrid.adapter as HotseatAdapter).notifyDataSetChanged()
    }

    private fun bindPinsFrom(items: List<com.caniko.cenix.db.WorkspaceItemEntity>) {
        bindWorkspaceFrom(items)
        bindHotseatFrom(items)
    }

    private fun bindList(matches: List<LaunchableApp>) {
        val query = searchField.text?.toString().orEmpty()
        val showPins = query.isBlank()
        workspaceGrid.visibility = if (showPins) View.VISIBLE else View.GONE
        hotseatGrid.visibility = if (showPins) View.VISIBLE else View.GONE
        visible.clear()
        visible.addAll(matches)
        (appList.adapter as AppAdapter).notifyDataSetChanged()
    }

    private fun turn(delta: Int) {
        val next = screen + delta
        if (next !in 0 until Workspace.SCREENS) return
        screen = next
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val items = workspace?.items().orEmpty()
            runOnUiThread { bindWorkspaceFrom(items) }
        }
    }

    private fun beginDrag(view: View, item: LaunchableApp, fromPin: Boolean) {
        dragFromPin = fromPin
        dragLanded = false
        val dockAt = dock.indexOfFirst { it?.packageName == item.packageName && it.className == item.className && it.profileId == item.profileId }
        val slotAt = slots.indexOfFirst { it?.packageName == item.packageName && it.className == item.className && it.profileId == item.profileId }
        if (dockAt >= 0) {
            dragStartScreen = Workspace.HOTSEAT
            dragStartX = dockAt
            dragStartY = 0
        } else {
            dragStartScreen = screen
            dragStartX = if (slotAt >= 0) slotAt % grid.cols else 0
            dragStartY = if (slotAt >= 0) slotAt / grid.cols else 0
        }
        view.startDragAndDrop(ClipData.newPlainText(item.packageName, item.className), View.DragShadowBuilder(view), item, 0)
    }

    private fun onGridDrag(view: View, event: DragEvent, destScreen: Int, cols: Int, rows: Int): Boolean {
        val item = event.localState as? LaunchableApp ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DROP -> {
                dragLanded = true
                val cw = (view.width / cols).coerceAtLeast(1)
                val ch = (view.height / rows).coerceAtLeast(1)
                val x = (event.x / cw).toInt().coerceIn(0, cols - 1)
                val y = (event.y / ch).toInt().coerceIn(0, rows - 1)
                mutatePins { store ->
                    if (destScreen == dragStartScreen && x == dragStartX && y == dragStartY) {
                        store.unpin(item)
                    } else {
                        store.place(item, destScreen, x, y, cols, rows)
                    }
                    null
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag(item)
            else -> true
        }
    }

    private fun onOffGridDrag(event: DragEvent): Boolean {
        val item = event.localState as? LaunchableApp ?: return false
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> true
            DragEvent.ACTION_DROP -> {
                dragLanded = true
                if (dragFromPin) mutatePins { it.unpin(item); null }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> endDrag(item)
            else -> true
        }
    }

    private fun endDrag(item: LaunchableApp): Boolean {
        if (dragFromPin && !dragLanded) mutatePins { it.unpin(item); null }
        dragFromPin = false
        return true
    }

    private fun pin(appItem: LaunchableApp) {
        mutatePins { store ->
            val placed = store.items().firstOrNull {
                it.packageName == appItem.packageName && it.className == appItem.className && it.profileId == appItem.profileId
            }
            val ok = when {
                placed?.screen == Workspace.HOTSEAT -> true
                placed != null -> store.dock(appItem, grid.cols)
                else -> store.pin(appItem, grid, screen)
            }
            if (ok) {
                store.items().firstOrNull {
                    it.packageName == appItem.packageName && it.className == appItem.className && it.profileId == appItem.profileId
                }?.let { if (it.screen != Workspace.HOTSEAT) screen = it.screen }
            }
            if (ok) null else if (placed != null) R.string.hotseat_full else R.string.workspace_full
        }
    }

    private fun mutatePins(block: (Workspace) -> Int?) {
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val store = workspace ?: return@io
            val fail = block(store)
            val items = store.items()
            runOnUiThread {
                if (fail != null) Toast.makeText(this, fail, Toast.LENGTH_SHORT).show()
                bindPinsFrom(items)
            }
        }
    }

    private fun applyChrome() {
        val emergency = app.emergency
        statusTitle.setText(if (emergency) R.string.status_emergency else R.string.status_ready)
        emergencyBanner.visibility = if (emergency) View.VISIBLE else View.GONE
    }

    private fun applyFilter() {
        val query = searchField.text?.toString().orEmpty()
        workspaceGrid.visibility = if (query.isBlank()) View.VISIBLE else View.GONE
        hotseatGrid.visibility = if (query.isBlank()) View.VISIBLE else View.GONE
        search.submit(apps.toList(), query, catalog.visibleProfiles())
    }

    private fun launch(appItem: LaunchableApp) {
        val user = appItem.user ?: catalog.userForSerial(appItem.profileId)
        if (user == null) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val launcher = getSystemService(LauncherApps::class.java)
            launcher.startMainActivity(ComponentName(appItem.packageName, appItem.className), user, null, null)
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun retryNative() {
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            app.retryNative()
            workspace = app.database?.let { Workspace(it) { !app.emergency } }
            runOnUiThread {
                applyChrome()
                applyFilter()
            }
        }
    }

    private fun resetState() {
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            app.resetLocalState()
            workspace = app.database?.let { Workspace(it) { !app.emergency } }
            screen = 0
            scheduleReload("reset")
        }
    }

    private fun promptDefaultHome() {
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private inner class WorkspaceAdapter : BaseAdapter() {
        override fun getCount() = grid.cells
        override fun getItem(position: Int) = slots.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@HomeActivity).inflate(R.layout.workspace_cell, parent, false)
            val item = slots.getOrNull(position)
            view.findViewById<ImageView>(R.id.cellIcon).setImageDrawable(item?.icon)
            view.findViewById<TextView>(R.id.cellLabel).text = item?.label.orEmpty()
            view.contentDescription = item?.let { "${it.label}|${it.packageName}|${it.profileId}" }
                ?: getString(R.string.workspace_empty)
            return view
        }
    }

    private inner class HotseatAdapter : BaseAdapter() {
        override fun getCount() = grid.cols
        override fun getItem(position: Int) = dock.getOrNull(position)
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@HomeActivity).inflate(R.layout.workspace_cell, parent, false)
            val item = dock.getOrNull(position)
            view.findViewById<ImageView>(R.id.cellIcon).setImageDrawable(item?.icon)
            view.findViewById<TextView>(R.id.cellLabel).text = item?.label.orEmpty()
            view.contentDescription = item?.let { "${it.label}|${it.packageName}|${it.profileId}" }
                ?: getString(R.string.workspace_empty)
            return view
        }
    }

    private inner class AppAdapter : ArrayAdapter<LaunchableApp>(this, 0, visible) {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.app_row, parent, false)
            val item = visible[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.appLabel).text = item.label
            view.findViewById<TextView>(R.id.appProfile).text = profileLabel(item.profileId)
            view.contentDescription = "${item.label}|${item.packageName}|${item.profileId}"
            return view
        }
    }

    private fun profileLabel(profileId: Long): String {
        val personal = catalog.serial(android.os.Process.myUserHandle())
        return when (profileId) {
            personal -> getString(R.string.profile_personal)
            else -> getString(R.string.profile_other)
        }
    }

    companion object {
        const val EXTRA_FORCE_NATIVE_FAILURE = "com.caniko.cenix.FORCE_NATIVE_FAILURE"
    }
}
