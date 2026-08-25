package com.caniko.cenix

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ApplicationInfo
import android.content.pm.ShortcutInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.Folder
import com.caniko.cenix.uniffi.FolderMember
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import kotlin.math.max
import kotlin.math.min

class HomeActivity : AppCompatActivity() {
    private lateinit var app: CenixApplication
    private lateinit var catalog: AppCatalog
    private lateinit var shortcutCatalog: ShortcutCatalog
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
    private var folderPopup: FolderPopup? = null
    private var contextPopup: ContextPopup? = null
    private var controller: WorkspaceController? = null
    private var rendered = emptySnapshot()
    private var selectedPageId: ULong? = null
    private val apps = mutableListOf<LaunchableApp>()
    private val visible = mutableListOf<LaunchableApp>()
    private val shortcuts = mutableMapOf<ShortcutId, LauncherShortcut>()
    private var contextQueryToken = 0

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: android.os.UserHandle) = scheduleReload("add")
        override fun onPackageRemoved(packageName: String, user: android.os.UserHandle) = scheduleReload("remove")
        override fun onPackageChanged(packageName: String, user: android.os.UserHandle) = scheduleReload("change")
        override fun onPackagesAvailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = scheduleReload("available")
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: android.os.UserHandle, replacing: Boolean) = scheduleReload("unavailable")
        override fun onPackagesSuspended(packageNames: Array<out String>, user: android.os.UserHandle) = scheduleReload("suspended")
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: android.os.UserHandle) = scheduleReload("unsuspended")
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: android.os.UserHandle) = scheduleReload("shortcuts")
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
        shortcutCatalog = ShortcutCatalog(this, catalog)
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
        dragLayer.onFolderHover = ::openFolder
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
        val allAppsLongPress = LongPressDragPolicy(android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        var allAppsSource: View? = null
        var allAppsItem: LaunchableApp? = null
        appList.setOnItemLongClickListener { _, view, position, _ ->
            if (app.emergency) return@setOnItemLongClickListener false
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
            searchField.clearFocus()
            allAppsLongPress.longPress()
            openContext(view, visible[position], null, null)
        }
        appList.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    allAppsLongPress.down(event.x, event.y)
                    val position = appList.pointToPosition(event.x.toInt(), event.y.toInt())
                    allAppsSource = appList.getChildAt(position - appList.firstVisiblePosition)
                    allAppsItem = visible.getOrNull(position)
                }
                android.view.MotionEvent.ACTION_MOVE -> if (
                    allAppsLongPress.move(event.x, event.y) == LongPressDragPolicy.State.DRAG_STARTED
                ) {
                    val source = allAppsSource
                    val item = allAppsItem
                    if (source != null && item != null) {
                        closeContext("drag")
                        setSurface(LauncherSurface.HOME, false)
                        if (dragLayer.beginDrag(source, LauncherDrag(item, null))) dragLayer.handleMotionEvent(event)
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> allAppsLongPress.cancel()
                android.view.MotionEvent.ACTION_UP -> allAppsLongPress.finish()
            }
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
        closeFolder("home")
        closeContext("home")
        selectedPageId = rendered.pages.firstOrNull()?.pageId
        pager.setCurrentPage(0, false)
        setSurface(LauncherShell.homeIntent(app.emergency), false)
        applyForceNativeFailure()
    }

    override fun onDestroy() {
        closeFolder("lifecycle")
        closeContext("lifecycle")
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
                dragLayer.post {
                    dragLayer.cancel(if (reason == "remove" || reason == "unavailable" || reason == "shortcuts") "package" else "reload")
                    if (reason in setOf("remove", "unavailable", "shortcuts")) closeContext("catalog")
                }
                workspace.dropMissing(loaded)
                val metrics = resources.displayMetrics
                val widthDp = metrics.widthPixels / metrics.density
                val heightDp = metrics.heightPixels / metrics.density
                val desired = PhoneGrid.pick(min(widthDp, heightDp), max(widthDp, heightDp))
                var state = workspace.setGrid(desired.cols, desired.rows)?.asSnapshot() ?: workspace.snapshot()
                val resolvedShortcuts = shortcutCatalog.resolve(state.shortcutIds())
                state = workspace.reconcileShortcuts(resolvedShortcuts.keys)?.asSnapshot()?.also {
                    CenixLog.event(EventId.SHORTCUT_RECONCILE, Severity.INFO, mapOf("count" to resolvedShortcuts.size.toString()))
                } ?: state
                shortcutCatalog.pin(state.shortcutIds())
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
                    shortcuts.clear()
                    shortcuts.putAll(resolvedShortcuts)
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
        val folders = snapshot.folders.associateBy { it.folderId }
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
                    addCell(createCell(item, folders[item?.itemId], byComponent, "page ${pageIndex + 1}", x, y), x, y)
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
            hotseat.addCell(createCell(item, folders[item?.itemId], byComponent, "hotseat", x, 0), x, 0)
        }
        folderPopup?.let { popup ->
            folders[popup.folderId]?.let { bindFolderPopup(popup, it, byComponent) } ?: closeFolder("dissolved")
        }
    }

    private fun createCell(
        item: WorkspaceItem?,
        folder: Folder?,
        byComponent: Map<Triple<String, String, Long>, LaunchableApp>,
        location: String,
        x: Int,
        y: Int,
    ): View {
        if (item != null && folder != null && item.payload is ItemPayload.Folder) {
            return FolderIconView(this).apply {
                bind(
                    folder.title,
                    folder.members.size,
                    folder.members.take(4).map { member -> member.icon(byComponent) },
                )
                contentDescription = "$contentDescription, $location, row ${y + 1}, column ${x + 1}"
                tag = CellTarget(item.itemId, folder.folderId)
                setOnClickListener { openFolder(folder.folderId) }
                setOnLongClickListener { dragLayer.beginDrag(this, LauncherDrag(null, item.itemId, isFolder = true)) }
                addAccessibilityMoves(this, item, x, y, folder.folderId)
            }
        }
        val appItem = (item?.payload as? ItemPayload.Application)?.component?.let { byComponent[it.key()] }
        val shortcutItem = (item?.payload as? ItemPayload.Shortcut)?.shortcut?.let(shortcuts::get)
        val view = LayoutInflater.from(this).inflate(R.layout.workspace_cell, null, false)
        view.findViewById<ImageView>(R.id.cellIcon).setImageDrawable(appItem?.icon ?: shortcutItem?.icon)
        view.findViewById<TextView>(R.id.cellLabel).text = appItem?.label ?: shortcutItem?.label.orEmpty()
        val label = appItem?.label ?: shortcutItem?.label
        view.contentDescription = if (label == null) "Empty, $location, row ${y + 1}, column ${x + 1}"
        else "$label, $location, row ${y + 1}, column ${x + 1}"
        view.isFocusable = label != null
        if (appItem != null) {
            view.tag = CellTarget(item.itemId)
            view.setOnClickListener { launch(appItem) }
            attachLongPress(
                view,
                onPopup = { openContext(view, appItem, item.itemId, null) },
                onDrag = { dragLayer.beginDrag(view, LauncherDrag(appItem, item.itemId)) },
            )
            addAccessibilityMoves(view, item, x, y) { openContext(view, appItem, item.itemId, null) }
        } else if (shortcutItem != null) {
            view.tag = CellTarget(item!!.itemId)
            view.setOnClickListener { launch(shortcutItem) }
            attachLongPress(
                view,
                onPopup = { openShortcutContext(view, shortcutItem, item.itemId, null) },
                onDrag = { dragLayer.beginDrag(view, LauncherDrag(null, item.itemId, shortcut = shortcutItem)) },
            )
            addAccessibilityMoves(view, item, x, y) { openShortcutContext(view, shortcutItem, item.itemId, null) }
        }
        return view
    }

    private fun attachLongPress(view: View, onPopup: () -> Boolean, onDrag: () -> Boolean) {
        val policy = LongPressDragPolicy(android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> policy.down(event.x, event.y)
                android.view.MotionEvent.ACTION_MOVE -> if (
                    policy.move(event.x, event.y) == LongPressDragPolicy.State.DRAG_STARTED
                ) {
                    closeContext("drag")
                    if (onDrag()) dragLayer.handleMotionEvent(event)
                }
                android.view.MotionEvent.ACTION_CANCEL -> policy.cancel()
                android.view.MotionEvent.ACTION_UP -> policy.finish()
            }
            false
        }
        view.setOnLongClickListener {
            policy.longPress()
            onPopup()
        }
    }

    private fun addAccessibilityMoves(
        view: View,
        item: WorkspaceItem,
        x: Int,
        y: Int,
        folderId: ULong? = null,
        onContext: (() -> Unit)? = null,
    ) {
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
                if (folderId != null) {
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_folder, getString(R.string.open_folder)))
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_rename_folder, getString(R.string.rename_folder)))
                }
                if (onContext != null) info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(R.id.action_open_context, getString(R.string.open_context_actions)),
                )
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
                    R.id.action_open_folder -> { folderId?.let(::openFolder); return true }
                    R.id.action_rename_folder -> {
                        folderId?.let(::openFolder)
                        folderPopup?.focusTitle()
                        return true
                    }
                    R.id.action_open_context -> { onContext?.invoke(); return true }
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

    private fun openFolder(folderId: ULong) {
        if (folderPopup?.folderId == folderId) return
        val folder = rendered.folders.firstOrNull { it.folderId == folderId } ?: return
        closeContext("folder")
        closeFolder("replace")
        val popup = FolderPopup(this).apply {
            onClose = { closeFolder("action") }
            onRename = { next -> mutate { it.renameFolder(folderId, next) } }
            onLaunch = { entry -> entry.app?.let(::launch) ?: entry.shortcut?.let(::launch) }
            onDirectDrag = { view, entry ->
                closeContext("drag")
                val payload = entry.app?.let { LauncherDrag(it, entry.member.itemId, sourceFolderId = folderId) }
                    ?: entry.shortcut?.let {
                        LauncherDrag(null, entry.member.itemId, shortcut = it, sourceFolderId = folderId)
                    }
                payload?.let { dragLayer.beginDrag(view, it) }
            }
            onMove = { member, rank -> mutate { it.moveFolderMember(folderId, member.itemId, rank) } }
            onRemove = { member -> removeMemberFromFolder(folderId, member) }
        }
        folderPopup = popup
        dragLayer.folderPopup = popup
        homeSurface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val byComponent = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
        bindFolderPopup(popup, folder, byComponent)
        dragLayer.addView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        CenixLog.event(EventId.FOLDER_OPEN, Severity.INFO, mapOf("members" to folder.members.size.toString()))
        popup.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun bindFolderPopup(
        popup: FolderPopup,
        folder: Folder,
        byComponent: Map<Triple<String, String, Long>, LaunchableApp>,
    ) {
        popup.bind(
            folder.folderId,
            folder.title,
            folder.members.mapNotNull { member -> member.entry(byComponent) },
        )
    }

    private fun FolderMember.entry(
        byComponent: Map<Triple<String, String, Long>, LaunchableApp>,
    ): FolderEntry? = when (val memberPayload = payload) {
        is ItemPayload.Application -> byComponent[memberPayload.component.key()]?.let {
            FolderEntry(this, it.label, it.icon, app = it)
        }
        is ItemPayload.Shortcut -> shortcuts[memberPayload.shortcut]?.let {
            FolderEntry(this, it.label, it.icon, shortcut = it)
        }
        is ItemPayload.Folder -> null
    }

    private fun FolderMember.icon(byComponent: Map<Triple<String, String, Long>, LaunchableApp>) = when (val memberPayload = payload) {
        is ItemPayload.Application -> byComponent[memberPayload.component.key()]?.icon
        is ItemPayload.Shortcut -> shortcuts[memberPayload.shortcut]?.icon
        is ItemPayload.Folder -> null
    }

    private fun openContext(source: View, appItem: LaunchableApp, itemId: ULong?, sourceFolderId: ULong?) : Boolean {
        if (app.emergency) return false
        closeFolder("context")
        closeContext("replace")
        val popup = ContextPopup(this)
        contextPopup = popup
        val token = ++contextQueryToken
        configureContext(popup, source, appItem, appItem.label, itemId, sourceFolderId)
        dragLayer.addView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popup.anchor(source)
        CenixLog.event(EventId.CONTEXT_POPUP_OPEN, Severity.INFO)
        CenixExecutors.io {
            val entries = shortcutCatalog.published(appItem)
            CenixLog.event(EventId.SHORTCUT_QUERY, Severity.INFO, mapOf("count" to entries.size.toString()))
            runOnUiThread {
                if (contextPopup === popup && contextQueryToken == token) {
                    popup.bind(appItem.label, entries, canUninstall(appItem), itemId != null)
                }
            }
        }
        return true
    }

    private fun openShortcutContext(
        source: View,
        shortcut: LauncherShortcut,
        itemId: ULong,
        sourceFolderId: ULong?,
    ): Boolean {
        val parent = apps.firstOrNull {
            it.packageName == shortcut.id.`package` && it.profileId.toULong() == shortcut.id.profileId
        } ?: return false
        closeFolder("context")
        closeContext("replace")
        val popup = ContextPopup(this)
        contextPopup = popup
        configureContext(popup, source, parent, shortcut.label, itemId, sourceFolderId)
        popup.bind(shortcut.label, listOf(shortcut), canUninstall(parent), true)
        dragLayer.addView(popup, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popup.anchor(source)
        CenixLog.event(EventId.CONTEXT_POPUP_OPEN, Severity.INFO)
        return true
    }

    private fun configureContext(
        popup: ContextPopup,
        source: View,
        appItem: LaunchableApp,
        label: String,
        itemId: ULong?,
        sourceFolderId: ULong?,
    ) {
        popup.onClose = { closeContext("outside") }
        popup.onDragApp = {
            closeContext("drag")
            setSurface(LauncherSurface.HOME, false)
            dragLayer.beginDrag(source, LauncherDrag(appItem, itemId, sourceFolderId = sourceFolderId))
        }
        popup.onAppInfo = { closeContext("app-info"); openAppInfo(appItem) }
        popup.onUninstall = { closeContext("uninstall"); requestUninstall(appItem) }
        popup.onRemove = { itemId?.let { mutate { workspace -> workspace.remove(it) } }; closeContext("remove") }
        popup.onLaunchShortcut = ::launch
        popup.onDragShortcut = { row, shortcut ->
            closeContext("shortcut-drag")
            setSurface(LauncherSurface.HOME, false)
            dragLayer.beginDrag(row, LauncherDrag(null, null, shortcut = shortcut))
        }
        popup.onPinShortcut = { shortcut -> pinShortcut(shortcut); closeContext("shortcut-pin") }
        popup.bind(label, emptyList(), canUninstall(appItem), itemId != null)
    }

    private fun closeContext(reason: String) {
        val popup = contextPopup ?: return
        contextQueryToken++
        dragLayer.removeView(popup)
        contextPopup = null
        CenixLog.event(EventId.CONTEXT_POPUP_CLOSE, Severity.INFO, mapOf("category" to reason))
    }

    private fun pinShortcut(shortcut: LauncherShortcut) {
        CenixExecutors.io {
            val workspace = controller ?: return@io
            val state = workspace.snapshot()
            val page = state.pages.getOrNull(pager.currentPage) ?: return@io
            val occupied = state.items.filter { (it.container as? ContainerRef.Workspace)?.pageId == page.pageId }
                .map { it.cell.cellX to it.cell.cellY }.toSet()
            val cell = (0 until state.grid.rows).flatMap { y -> (0 until state.grid.cols).map { x -> x to y } }
                .firstOrNull { it !in occupied }
            if (cell == null) {
                runOnUiThread { Toast.makeText(this, R.string.workspace_full, Toast.LENGTH_SHORT).show() }
                return@io
            }
            val transition = workspace.placeShortcut(
                shortcut.id,
                ContainerRef.Workspace(page.pageId),
                cell.first,
                cell.second,
            ) ?: return@io
            shortcutCatalog.pin(transition.asSnapshot().shortcutIds())
            CenixLog.event(EventId.SHORTCUT_PIN, Severity.INFO, mapOf("result" to "accepted"))
            runOnUiThread {
                shortcuts[shortcut.id] = shortcut
                render(transition.asSnapshot())
            }
        }
    }

    private fun openAppInfo(appItem: LaunchableApp) {
        val user = appItem.user ?: catalog.userForSerial(appItem.profileId) ?: return
        try {
            getSystemService(LauncherApps::class.java).startAppDetailsActivity(
                ComponentName(appItem.packageName, appItem.className), user, null, null,
            )
            CenixLog.event(EventId.PLATFORM_ACTION, Severity.INFO, mapOf("category" to "app-info", "result" to "accepted"))
        } catch (_: RuntimeException) {
            CenixLog.event(EventId.PLATFORM_ACTION, Severity.WARN, mapOf("category" to "app-info", "result" to "rejected"))
        }
    }

    private fun canUninstall(appItem: LaunchableApp): Boolean = try {
        appItem.packageName != packageName &&
            packageManager.getApplicationInfo(appItem.packageName, 0).flags and ApplicationInfo.FLAG_SYSTEM == 0
    } catch (_: RuntimeException) {
        false
    }

    private fun requestUninstall(appItem: LaunchableApp) {
        if (!canUninstall(appItem)) return
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:${appItem.packageName}"))
            .putExtra(Intent.EXTRA_USER, appItem.user ?: catalog.userForSerial(appItem.profileId))
        try {
            startActivity(intent)
            CenixLog.event(EventId.PLATFORM_ACTION, Severity.INFO, mapOf("category" to "uninstall", "result" to "accepted"))
        } catch (_: RuntimeException) {
            CenixLog.event(EventId.PLATFORM_ACTION, Severity.WARN, mapOf("category" to "uninstall", "result" to "rejected"))
        }
    }

    private fun closeFolder(reason: String) {
        val popup = folderPopup ?: return
        popup.clearTitleFocus()
        dragLayer.folderPopup = null
        dragLayer.removeView(popup)
        folderPopup = null
        if (root.surface == LauncherSurface.HOME) homeSurface.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        hideKeyboard()
        CenixLog.event(EventId.FOLDER_CLOSE, Severity.INFO, mapOf("category" to reason))
    }

    private fun removeMemberFromFolder(folderId: ULong, member: FolderMember) {
        val page = rendered.pages.getOrNull(pager.currentPage) ?: return
        val occupied = rendered.items
            .filter { (it.container as? ContainerRef.Workspace)?.pageId == page.pageId }
            .map { it.cell.cellX to it.cell.cellY }
            .toSet()
        val cell = (0 until rendered.grid.rows).flatMap { y -> (0 until rendered.grid.cols).map { x -> x to y } }
            .firstOrNull { it !in occupied }
            ?: return Toast.makeText(this, R.string.workspace_full, Toast.LENGTH_SHORT).show()
        mutate { it.removeItemFromFolder(folderId, member.itemId, ContainerRef.Workspace(page.pageId), cell.first, cell.second) }
    }

    private fun ComponentId.key() = Triple(`package`, `class`, profileId.toLong())

    private fun onDrop(payload: LauncherDrag, destination: DropDestination) {
        payload.shortcut?.let { shortcuts[it.id] = it }
        when {
            destination.remove && payload.itemId != null && payload.sourceFolderId == null -> mutate { it.remove(payload.itemId) }
            destination.folderId != null && !payload.isFolder -> {
                val folder = rendered.folders.firstOrNull { it.folderId == destination.folderId } ?: return
                val rank = destination.folderRank ?: folder.members.size.toUInt()
                when {
                    payload.itemId == null && payload.shortcut != null -> mutate {
                        it.addShortcutToFolder(payload.shortcut.id, folder.folderId, rank)
                    }
                    payload.itemId == null && payload.app != null -> mutate { it.addFromAllAppsToFolder(payload.app, folder.folderId, rank) }
                    payload.sourceFolderId == folder.folderId && destination.folderRank != null -> mutate {
                        it.moveFolderMember(folder.folderId, checkNotNull(payload.itemId), rank)
                    }
                    payload.itemId != null -> mutate { it.addItemToFolder(payload.itemId, folder.folderId, rank) }
                }
            }
            destination.createFolder && destination.targetItemId != null && payload.itemId != null && payload.sourceFolderId == null && !payload.isFolder -> mutate {
                it.createFolder(payload.itemId, destination.targetItemId)
            }
            destination.container === hotseat && payload.itemId != null -> mutate {
                if (payload.sourceFolderId != null) {
                    it.removeItemFromFolder(payload.sourceFolderId, payload.itemId, ContainerRef.Hotseat, destination.cellX, 0)
                } else {
                    it.move(payload.itemId, ContainerRef.Hotseat, destination.cellX, 0)
                }
            }
            destination.container === hotseat && payload.shortcut != null -> mutate {
                it.placeShortcut(payload.shortcut.id, ContainerRef.Hotseat, destination.cellX, 0)
            }
            destination.container is CellLayout -> {
                val pageId = rendered.pages.getOrNull(pager.currentPage)?.pageId ?: return
                when {
                    payload.itemId == null && payload.shortcut != null -> mutate {
                        it.placeShortcut(payload.shortcut.id, ContainerRef.Workspace(pageId), destination.cellX, destination.cellY)
                    }
                    payload.itemId == null && payload.app != null -> mutate { it.placeFromAllApps(payload.app, pageId, destination.cellX, destination.cellY) }
                    payload.sourceFolderId != null && payload.itemId != null -> mutate {
                        it.removeItemFromFolder(payload.sourceFolderId, payload.itemId, ContainerRef.Workspace(pageId), destination.cellX, destination.cellY)
                    }
                    payload.itemId != null -> mutate { it.move(payload.itemId, ContainerRef.Workspace(pageId), destination.cellX, destination.cellY) }
                }
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
        if (target != LauncherSurface.HOME) closeFolder("surface")
        if (contextPopup != null && target != root.surface) closeContext("surface")
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
        contextPopup?.let { closeContext("back"); return }
        val imeVisible = root.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        folderPopup?.let { popup ->
            if (imeVisible) {
                popup.clearTitleFocus()
                hideKeyboard()
            } else {
                closeFolder("back")
            }
            return
        }
        if (root.surface == LauncherSurface.ALL_APPS && imeVisible) {
            searchField.clearFocus()
            hideKeyboard()
            return
        }
        setSurface(LauncherShell.backTarget(root.surface, imeVisible))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(root.windowToken, 0)
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
        closeFolder("launch")
        closeContext("launch")
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

    private fun launch(shortcut: LauncherShortcut) {
        closeFolder("launch")
        closeContext("launch")
        val launched = shortcutCatalog.launch(shortcut)
        CenixLog.event(
            EventId.SHORTCUT_LAUNCH,
            if (launched) Severity.INFO else Severity.WARN,
            mapOf("result" to if (launched) "accepted" else "rejected"),
        )
        if (!launched) Toast.makeText(this, R.string.shortcut_unavailable, Toast.LENGTH_SHORT).show()
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

    private fun WorkspaceTransition.asSnapshot() = WorkspaceSnapshot(generation, grid, pages, items, folders)

    companion object {
        const val EXTRA_FORCE_NATIVE_FAILURE = "com.caniko.cenix.FORCE_NATIVE_FAILURE"
        private const val SURFACE_ANIMATION_MS = 220L
        private const val STATE_PAGE_ID = "workspace.pageId"
        private fun emptySnapshot() = WorkspaceSnapshot(0UL, com.caniko.cenix.uniffi.GridSpec(1, 1, 1), emptyList(), emptyList(), emptyList())
    }
}

internal fun WorkspaceSnapshot.shortcutIds(): List<ShortcutId> =
    (items.mapNotNull { (it.payload as? ItemPayload.Shortcut)?.shortcut } +
        folders.flatMap { folder -> folder.members.mapNotNull { (it.payload as? ItemPayload.Shortcut)?.shortcut } })
        .distinct()
