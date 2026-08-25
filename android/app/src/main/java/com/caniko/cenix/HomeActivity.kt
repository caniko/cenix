package com.caniko.cenix

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import kotlin.math.max
import kotlin.math.min

class HomeActivity : AppCompatActivity() {
    private lateinit var app: CenixApplication
    private lateinit var catalog: AppCatalog
    private lateinit var search: SearchController
    private lateinit var root: LauncherRoot
    private lateinit var homeSurface: HomeSurface
    private lateinit var allAppsContainer: AllAppsContainer
    private lateinit var emergencyOverlay: View
    private lateinit var searchField: EditText
    private lateinit var appList: AllAppsView
    private lateinit var dragLayer: DragLayer
    private lateinit var pager: WorkspacePager
    private lateinit var hotseat: HotseatView
    private lateinit var pageIndicator: PageIndicator
    private var controller: WorkspaceController? = null
    private var rendered = emptySnapshot()
    private var selectedPageId: ULong? = null
    private val apps = mutableListOf<LaunchableApp>()
    private val visible = mutableListOf<LaunchableApp>()

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = scheduleReload("add")
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) = scheduleReload("remove")
        override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = scheduleReload("change")
        override fun onPackagesAvailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = scheduleReload("available")
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = scheduleReload("unavailable")
        override fun onPackagesSuspended(packageNames: Array<out String>, user: android.os.UserHandle) = scheduleReload("suspended")
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: android.os.UserHandle) = scheduleReload("unsuspended")
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
        selectedPageId = savedInstanceState?.getLong(STATE_PAGE_ID)?.toULong()
        search = SearchController(
            filterOf = { app.activeFilter() },
            onEmergency = {
                CenixExecutors.io {
                    if (app.awaitReady()) app.requestEmergency()
                    runOnUiThread(::applySurface)
                }
            },
            onResult = { matches -> runOnUiThread { bindList(matches) } },
        )
        setContentView(R.layout.activity_home)
        bindViews()
        catalog.register(packageCallback)
        scheduleReload("create")
    }

    private fun bindViews() {
        root = findViewById(R.id.launcherRoot)
        homeSurface = findViewById(R.id.homeSurface)
        allAppsContainer = findViewById(R.id.allAppsContainer)
        emergencyOverlay = findViewById(R.id.emergencyOverlay)
        searchField = findViewById(R.id.searchField)
        appList = findViewById(R.id.appList)
        dragLayer = findViewById(R.id.dragLayer)
        pager = findViewById(R.id.workspaceGrid)
        hotseat = findViewById(R.id.hotseatGrid)
        pageIndicator = findViewById(R.id.pageIndicator)
        dragLayer.pager = pager
        dragLayer.hotseat = hotseat
        dragLayer.removeTarget = findViewById(R.id.removeTarget)
        dragLayer.onDrop = ::onDrop
        root.onSurfaceRequested = { target ->
            if (target != LauncherSurface.HOME || !appList.canScrollVertically(-1)) setSurface(target)
        }
        addRootAccessibilityActions()
        pager.onPageChanged = { page ->
            rendered.pages.getOrNull(page)?.let { selectedPageId = it.pageId }
            pageIndicator.current = page
        }
        pager.onBeyondEdge = {
            mutate { workspace ->
                workspace.addPage()?.also { transition -> selectedPageId = transition.createdPageIds.lastOrNull() }
            }
        }
        findViewById<Button>(R.id.retryNative).setOnClickListener { retryNative() }
        findViewById<Button>(R.id.resetState).setOnClickListener { resetState() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        appList.adapter = AppAdapter()
        appList.setOnItemClickListener { _, _, position, _ -> launch(visible[position]) }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: android.view.MotionEvent) = true

            override fun onLongPress(event: android.view.MotionEvent) {
                if (app.emergency) return
                val position = appList.pointToPosition(event.x.toInt(), event.y.toInt())
                if (position == android.widget.AdapterView.INVALID_POSITION) return
                val view = appList.getChildAt(position - appList.firstVisiblePosition) ?: return
                val payload = LauncherDrag(visible[position], null)
                getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
                searchField.clearFocus()
                setSurface(LauncherSurface.HOME, false)
                dragLayer.beginDrag(view, payload)
            }
        })
        appList.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            false
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })
    }

    override fun onStart() {
        super.onStart()
        scheduleReload("start")
        applyForceNativeFailure()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        selectedPageId?.let { outState.putLong(STATE_PAGE_ID, it.toLong()) }
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        selectedPageId = rendered.pages.firstOrNull()?.pageId
        pager.setCurrentPage(0, false)
        setSurface(LauncherShell.homeIntent(app.emergency), false)
        applyForceNativeFailure()
    }

    override fun onDestroy() {
        dragLayer.cancel("lifecycle")
        catalog.unregister(packageCallback)
        search.close()
        super.onDestroy()
    }

    private fun scheduleReload(reason: String) {
        val query = if (this::searchField.isInitialized) searchField.text?.toString().orEmpty() else ""
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val database = app.database ?: return@io
            val workspace = controller ?: WorkspaceController(LauncherRepository(database)) { !app.emergency }.also { controller = it }
            try {
                val loaded = catalog.load()
                dragLayer.post { dragLayer.cancel(if (reason == "remove" || reason == "unavailable") "package" else "reload") }
                workspace.dropMissing(loaded)
                val metrics = resources.displayMetrics
                val widthDp = metrics.widthPixels / metrics.density
                val heightDp = metrics.heightPixels / metrics.density
                val desired = PhoneGrid.pick(min(widthDp, heightDp), max(widthDp, heightDp))
                val state = workspace.setGrid(desired.cols, desired.rows)?.asSnapshot() ?: workspace.snapshot()
                val profiles = catalog.visibleProfiles()
                val matches = try {
                    app.activeFilter().filter(loaded, query, profiles)
                } catch (_: Throwable) {
                    app.requestEmergency()
                    EmergencyAppFilter.filter(loaded, query, profiles)
                }
                app.markHealthy()
                CenixLog.event(EventId.CATALOG_REFRESH, Severity.INFO, mapOf("count" to loaded.size.toString(), "reason" to reason))
                runOnUiThread {
                    apps.clear()
                    apps.addAll(loaded)
                    bindList(matches)
                    render(state)
                    applySurface()
                }
            } catch (_: Throwable) {
                app.requestEmergency()
                runOnUiThread(::applySurface)
            }
        }
    }

    private fun render(snapshot: WorkspaceSnapshot) {
        rendered = snapshot
        val byComponent = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
        val pageLayouts = snapshot.pages.mapIndexed { pageIndex, page ->
            CellLayout(this).apply {
                columns = snapshot.grid.cols
                rows = snapshot.grid.rows
                contentDescription = "Workspace $pageIndex"
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                val items = snapshot.items.filter { (it.container as? ContainerRef.Workspace)?.pageId == page.pageId }
                    .associateBy { it.cell.cellX to it.cell.cellY }
                for (y in 0 until rows) for (x in 0 until columns) {
                    val item = items[x to y]
                    val appItem = item?.let { byComponent[Triple(it.component.`package`, it.component.`class`, it.component.profileId.toLong())] }
                    addCell(createCell(appItem, item, "page ${pageIndex + 1}", x, y), x, y)
                }
            }
        }
        val selected = snapshot.pages.indexOfFirst { it.pageId == selectedPageId }.takeIf { it >= 0 } ?: 0
        pager.replacePages(pageLayouts, selected)
        pager.setCurrentPage(selected, false)
        selectedPageId = snapshot.pages.getOrNull(selected)?.pageId
        pageIndicator.pages = snapshot.pages.size
        pageIndicator.current = selected

        hotseat.removeAllViews()
        hotseat.columns = snapshot.grid.hotseatCols
        hotseat.rows = 1
        val dock = snapshot.items.filter { it.container is ContainerRef.Hotseat }.associateBy { it.cell.cellX }
        for (x in 0 until hotseat.columns) {
            val item = dock[x]
            val appItem = item?.let { byComponent[Triple(it.component.`package`, it.component.`class`, it.component.profileId.toLong())] }
            hotseat.addCell(createCell(appItem, item, "hotseat", x, 0), x, 0)
        }
    }

    private fun createCell(appItem: LaunchableApp?, item: WorkspaceItem?, location: String, x: Int, y: Int): View {
        val view = LayoutInflater.from(this).inflate(R.layout.workspace_cell, null, false)
        view.findViewById<ImageView>(R.id.cellIcon).setImageDrawable(appItem?.icon)
        view.findViewById<TextView>(R.id.cellLabel).text = appItem?.label.orEmpty()
        view.contentDescription = if (appItem == null) "Empty, $location, row ${y + 1}, column ${x + 1}"
        else "${appItem.label}, $location, row ${y + 1}, column ${x + 1}"
        view.isFocusable = appItem != null
        if (appItem != null && item != null) {
            val payload = LauncherDrag(appItem, item.itemId)
            view.setOnClickListener { launch(appItem) }
            view.setOnLongClickListener { dragLayer.beginDrag(view, payload) }
            addAccessibilityMoves(view, item, x, y)
        }
        return view
    }

    private fun addAccessibilityMoves(view: View, item: WorkspaceItem, x: Int, y: Int) {
        view.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_left, "Move left"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_right, "Move right"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_up, "Move up"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_down, "Move down"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_previous_page, "Move to previous page"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_next_page, "Move to next page"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(if (item.container is ContainerRef.Hotseat) R.id.action_undock else R.id.action_dock, if (item.container is ContainerRef.Hotseat) "Undock" else "Dock"))
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_DISMISS, "Remove"))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                val destination = when (action) {
                    R.id.action_move_left -> x - 1 to y
                    R.id.action_move_right -> x + 1 to y
                    R.id.action_move_up -> x to y - 1
                    R.id.action_move_down -> x to y + 1
                    R.id.action_move_previous_page -> { movePage(item, -1); return true }
                    R.id.action_move_next_page -> { movePage(item, 1); return true }
                    R.id.action_dock -> { mutate { it.dock(item.itemId) }; return true }
                    R.id.action_undock -> { rendered.pages.getOrNull(pager.currentPage)?.let { page -> mutate { it.undock(item.itemId, page.pageId) } }; return true }
                    AccessibilityNodeInfo.ACTION_DISMISS -> { mutate { it.remove(item.itemId) }; return true }
                    else -> return super.performAccessibilityAction(host, action, args)
                }
                val container = item.container
                mutate { it.move(item.itemId, container, destination.first, destination.second) }
                return true
            }
        }
        view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            val destination = when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> x - 1 to y
                KeyEvent.KEYCODE_DPAD_RIGHT -> x + 1 to y
                KeyEvent.KEYCODE_DPAD_UP -> x to y - 1
                KeyEvent.KEYCODE_DPAD_DOWN -> x to y + 1
                KeyEvent.KEYCODE_ESCAPE -> { dragLayer.cancel("keyboard"); return@setOnKeyListener true }
                else -> return@setOnKeyListener false
            }
            mutate { it.move(item.itemId, item.container, destination.first, destination.second) }
            true
        }
    }

    private fun movePage(item: WorkspaceItem, delta: Int) {
        val currentId = (item.container as? ContainerRef.Workspace)?.pageId ?: return
        val current = rendered.pages.indexOfFirst { it.pageId == currentId }
        val page = rendered.pages.getOrNull(current + delta) ?: return
        mutate { it.move(item.itemId, ContainerRef.Workspace(page.pageId), item.cell.cellX, item.cell.cellY) }
    }

    private fun onDrop(payload: LauncherDrag, destination: DropDestination) {
        when {
            destination.remove && payload.itemId != null -> mutate { it.remove(payload.itemId) }
            destination.container === hotseat && payload.itemId != null -> mutate {
                it.move(payload.itemId, ContainerRef.Hotseat, destination.cellX, 0)
            }
            destination.container is CellLayout -> {
                val pageId = rendered.pages.getOrNull(pager.currentPage)?.pageId ?: return
                if (payload.itemId == null) mutate { it.placeFromAllApps(payload.app, pageId, destination.cellX, destination.cellY) }
                else mutate { it.move(payload.itemId, ContainerRef.Workspace(pageId), destination.cellX, destination.cellY) }
            }
            else -> CenixLog.event(EventId.DRAG_CANCEL, Severity.INFO, mapOf("category" to "invalid-target"))
        }
    }

    private fun mutate(block: (WorkspaceController) -> com.caniko.cenix.uniffi.WorkspaceTransition?) {
        CenixExecutors.io {
            val workspace = controller ?: return@io
            block(workspace)?.let { state -> runOnUiThread { render(state.asSnapshot()) } }
        }
    }

    private fun bindList(matches: List<LaunchableApp>) {
        visible.clear()
        visible.addAll(matches)
        (appList.adapter as AppAdapter).notifyDataSetChanged()
    }

    private fun applyFilter() {
        val query = searchField.text?.toString().orEmpty()
        search.submit(apps.toList(), query, catalog.visibleProfiles())
    }

    private fun applySurface() {
        val target = when {
            app.emergency -> LauncherSurface.EMERGENCY
            root.surface == LauncherSurface.EMERGENCY -> LauncherSurface.HOME
            else -> root.surface
        }
        setSurface(target, false)
    }

    private fun setSurface(target: LauncherSurface, animate: Boolean = true) {
        if ((target == LauncherSurface.EMERGENCY) != app.emergency) return
        root.surface = target
        allAppsContainer.animate().cancel()
        when (target) {
            LauncherSurface.HOME -> {
                homeSurface.visibility = View.VISIBLE
                homeSurface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                emergencyOverlay.visibility = View.GONE
                allAppsContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                searchField.clearFocus()
                hideKeyboard()
                searchField.text?.clear()
                if (animate && allAppsContainer.visibility == View.VISIBLE) {
                    allAppsContainer.animate().translationY(root.height.toFloat()).setDuration(SURFACE_ANIMATION_MS)
                        .withEndAction {
                            if (root.surface == LauncherSurface.HOME) {
                                allAppsContainer.visibility = View.GONE
                                allAppsContainer.translationY = 0f
                            }
                        }.start()
                } else {
                    allAppsContainer.visibility = View.GONE
                    allAppsContainer.translationY = 0f
                }
            }
            LauncherSurface.ALL_APPS -> {
                homeSurface.visibility = View.VISIBLE
                homeSurface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                emergencyOverlay.visibility = View.GONE
                setAllAppsTopPadding()
                allAppsContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                allAppsContainer.visibility = View.VISIBLE
                if (animate) {
                    allAppsContainer.translationY = root.height.toFloat()
                    allAppsContainer.animate().translationY(0f).setDuration(SURFACE_ANIMATION_MS).start()
                } else {
                    allAppsContainer.translationY = 0f
                }
            }
            LauncherSurface.EMERGENCY -> {
                homeSurface.visibility = View.GONE
                allAppsContainer.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                allAppsContainer.visibility = View.VISIBLE
                allAppsContainer.translationY = 0f
                emergencyOverlay.visibility = View.VISIBLE
                emergencyOverlay.post { setAllAppsTopPadding(emergencyOverlay.height) }
            }
        }
        root.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun setAllAppsTopPadding(extraTop: Int = 0) {
        val edge = (16 * resources.displayMetrics.density).toInt()
        allAppsContainer.setPadding(edge, edge + extraTop, edge, edge)
    }

    private fun handleBack() {
        if (dragLayer.isDragging) return dragLayer.cancel("back")
        val imeVisible = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        if (root.surface == LauncherSurface.ALL_APPS && imeVisible) {
            searchField.clearFocus()
            hideKeyboard()
            return
        }
        setSurface(LauncherShell.backTarget(root.surface, imeVisible))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
    }

    private fun addRootAccessibilityActions() {
        root.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                val surfaceAction = when (root.surface) {
                    LauncherSurface.HOME -> AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_all_apps, getString(R.string.open_all_apps))
                    LauncherSurface.ALL_APPS -> AccessibilityNodeInfo.AccessibilityAction(R.id.action_close_all_apps, getString(R.string.close_all_apps))
                    LauncherSurface.EMERGENCY -> null
                }
                surfaceAction?.let(info::addAction)
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        R.id.action_open_launcher_settings,
                        getString(R.string.open_launcher_settings),
                    ),
                )
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_export_diagnostics, getString(R.string.export_diagnostics)))
            }

            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                R.id.action_open_all_apps -> { setSurface(LauncherSurface.ALL_APPS); true }
                R.id.action_close_all_apps -> { setSurface(LauncherSurface.HOME); true }
                R.id.action_open_launcher_settings -> { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)); true }
                R.id.action_export_diagnostics -> { exportDiagnostics.launch("cenix-diagnostics.txt"); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    private fun launch(appItem: LaunchableApp) {
        val user = appItem.user ?: catalog.userForSerial(appItem.profileId)
        if (user == null) return Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        try {
            getSystemService(LauncherApps::class.java).startMainActivity(
                ComponentName(appItem.packageName, appItem.className), user, null, null,
            )
        } catch (_: Throwable) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyForceNativeFailure() {
        if (!BuildConfig.DEBUG || !intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) return
        intent.removeExtra(EXTRA_FORCE_NATIVE_FAILURE)
        CenixExecutors.io {
            if (app.awaitReady()) app.requestEmergency()
            runOnUiThread(::applySurface)
        }
    }

    private fun retryNative() {
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val restored = app.retryNative()
            runOnUiThread {
                if (restored) setSurface(LauncherSurface.HOME, false) else applySurface()
                scheduleReload("retry")
            }
        }
    }

    private fun resetState() {
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            app.resetLocalState()
            controller = app.database?.let { WorkspaceController(LauncherRepository(it)) { !app.emergency } }
            selectedPageId = null
            scheduleReload("reset")
        }
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = visible[position].let { 31L * it.packageName.hashCode() + it.profileId }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@HomeActivity).inflate(R.layout.app_row, parent, false)
            val item = visible[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.icon)
            view.findViewById<TextView>(R.id.appLabel).text = item.label
            val profile = profileLabel(item.profileId)
            view.findViewById<TextView>(R.id.appProfile).text = profile
            view.contentDescription = "${item.label}, $profile"
            return view
        }
    }

    private fun profileLabel(profileId: Long): String {
        val personal = android.os.Process.myUserHandle().let { getSystemService(android.os.UserManager::class.java).getSerialNumberForUser(it) }
        return getString(if (profileId == personal) R.string.profile_personal else R.string.profile_other)
    }

    private fun WorkspaceTransition.asSnapshot() = WorkspaceSnapshot(generation, grid, pages, items)

    companion object {
        const val EXTRA_FORCE_NATIVE_FAILURE = "com.caniko.cenix.FORCE_NATIVE_FAILURE"
        private const val SURFACE_ANIMATION_MS = 220L
        private const val STATE_PAGE_ID = "workspace.pageId"
        private fun emptySnapshot() = WorkspaceSnapshot(0UL, com.caniko.cenix.uniffi.GridSpec(1, 1, 1), emptyList(), emptyList())
    }
}
