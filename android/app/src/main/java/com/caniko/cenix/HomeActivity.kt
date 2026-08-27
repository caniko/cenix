package com.caniko.cenix

import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ApplicationInfo
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
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
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.caniko.cenix.uniffi.ContainerRef
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.Folder
import com.caniko.cenix.uniffi.FolderMember
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileDescriptor
import com.caniko.cenix.uniffi.ProfileItemProjection
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ProfileSurface
import com.caniko.cenix.uniffi.projectProfileItem
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WorkspaceItem
import com.caniko.cenix.uniffi.WorkspaceSnapshot
import com.caniko.cenix.uniffi.WorkspaceTransition
import com.caniko.cenix.db.WidgetItemEntity

class HomeActivity : AppCompatActivity() {
    private lateinit var app: CenixApplication
    private lateinit var catalog: AppCatalog
    private lateinit var profiles: ProfileController
    private lateinit var profileReceiver: BroadcastReceiver
    private lateinit var shortcutCatalog: ShortcutCatalog
    private lateinit var packageSessions: PackageSessionController
    private lateinit var themedIcons: ThemedIconRenderer
    private lateinit var appearance: WallpaperAppearanceController
    private lateinit var search: SearchController
    private lateinit var root: LauncherRoot
    private lateinit var homeSurface: HomeSurface
    private lateinit var allAppsContainer: AllAppsContainer
    private lateinit var emergencyOverlay: View
    private lateinit var searchField: EditText
    private lateinit var appList: AllAppsView
    private lateinit var privateAppList: AllAppsView
    private lateinit var profileTabs: View
    private lateinit var personalTab: Button
    private lateinit var workTab: Button
    private lateinit var workProfileState: View
    private lateinit var workProfileMessage: TextView
    private lateinit var workProfileToggle: Button
    private lateinit var privateSpaceContainer: View
    private lateinit var privateSpaceState: TextView
    private lateinit var privateSpaceToggle: Button
    private lateinit var dragLayer: DragLayer
    private lateinit var pager: WorkspacePager
    private lateinit var hotseat: HotseatView
    private lateinit var pageIndicator: PageIndicator
    private lateinit var widgetHost: WidgetHostController
    private var folderPopup: FolderPopup? = null
    private var contextPopup: ContextPopup? = null
    private var controller: WorkspaceController? = null
    private var rendered = emptySnapshot()
    private var selectedPageId: ULong? = null
    private val apps = mutableListOf<LaunchableApp>()
    private val visible = mutableListOf<LaunchableApp>()
    private val privateVisible = mutableListOf<LaunchableApp>()
    private var profileSnapshot = emptyList<AndroidProfile>()
    private var activeProfileSection = ProfileKind.PERSONAL
    private val shortcuts = mutableMapOf<ShortcutId, LauncherShortcut>()
    private val widgetBindings = mutableMapOf<Long, WidgetItemEntity>()
    private var contextQueryToken = 0
    private var notificationSubscription: AutoCloseable? = null

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
        WallpaperAppearanceController.applyTheme(this)
        super.onCreate(savedInstanceState)
        app = application as CenixApplication
        profiles = ProfileController(this)
        catalog = AppCatalog(this, profiles)
        shortcutCatalog = ShortcutCatalog(this, catalog, profiles)
        themedIcons = ThemedIconRenderer(this)
        appearance = WallpaperAppearanceController(this) {
            themedIcons.invalidate(null)
            scheduleReload("appearance")
        }
        packageSessions = PackageSessionController(this, profiles, ::scheduleReload, ::autoPlace)
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
        notificationSubscription = NotificationDotStore.subscribe { runOnUiThread(::refreshDots) }
        widgetHost = WidgetHostController(
            this,
            { app.database },
            { controller },
            { rendered },
            { pager.currentLayout },
            { scheduleReload("widget") },
            profiles::isAvailable,
            profiles::userForSerial,
        )
        packageSessions.start()
        profileReceiver = profiles.register(this) { scheduleReload("profile") }
        scheduleReload("create")
    }

    private fun bindViews() {
        root = findViewById(R.id.launcherRoot)
        homeSurface = findViewById(R.id.homeSurface)
        allAppsContainer = findViewById(R.id.allAppsContainer)
        emergencyOverlay = findViewById(R.id.emergencyOverlay)
        searchField = findViewById(R.id.searchField)
        appList = findViewById(R.id.appList)
        privateAppList = findViewById(R.id.privateAppList)
        profileTabs = findViewById(R.id.profileTabs)
        personalTab = findViewById(R.id.personalTab)
        workTab = findViewById(R.id.workTab)
        workProfileState = findViewById(R.id.workProfileState)
        workProfileMessage = findViewById(R.id.workProfileMessage)
        workProfileToggle = findViewById(R.id.workProfileToggle)
        privateSpaceContainer = findViewById(R.id.privateSpaceContainer)
        privateSpaceState = findViewById(R.id.privateSpaceState)
        privateSpaceToggle = findViewById(R.id.privateSpaceToggle)
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
        findViewById<Button>(R.id.launcherSettings).setOnClickListener { openLauncherSettings() }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = applyFilter()
        })
        searchField.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN || event.action != KeyEvent.ACTION_DOWN || visible.isEmpty()) {
                return@setOnKeyListener false
            }
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(searchField.windowToken, 0)
            searchField.clearFocus()
            appList.requestFocus()
            appList.setSelection(0)
            true
        }
        appList.adapter = AppAdapter(visible)
        privateAppList.adapter = AppAdapter(privateVisible)
        appList.setOnItemClickListener { _, _, position, _ -> launch(visible[position]) }
        privateAppList.setOnItemClickListener { _, _, position, _ -> launch(privateVisible[position]) }
        privateAppList.setOnItemLongClickListener { _, view, position, _ ->
            if (app.emergency || !privateVisible[position].canPlace) false else openContext(view, privateVisible[position], null, null)
        }
        personalTab.setOnClickListener { selectProfileSection(ProfileKind.PERSONAL) }
        workTab.setOnClickListener { selectProfileSection(ProfileKind.WORK) }
        workProfileToggle.setOnClickListener { toggleProfile(ProfileKind.WORK) }
        privateSpaceToggle.setOnClickListener { toggleProfile(ProfileKind.PRIVATE) }
        val allAppsLongPress = LongPressDragPolicy(android.view.ViewConfiguration.get(this).scaledTouchSlop.toFloat())
        var allAppsSource: View? = null
        var allAppsItem: LaunchableApp? = null
        appList.setOnItemLongClickListener { _, view, position, _ ->
            if (app.emergency || !visible[position].canPlace) return@setOnItemLongClickListener false
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
                    if (source != null && item?.canPlace == true) {
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
        appearance.start()
        widgetHost.start()
        scheduleReload("start")
        applyForceNativeFailure()
    }

    override fun onStop() {
        appearance.stop()
        widgetHost.stop()
        super.onStop()
    }

    @Deprecated("Activity result is required by AppWidgetHost configuration")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgetHost.onActivityResult(requestCode, resultCode, data)) super.onActivityResult(requestCode, resultCode, data)
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
        packageSessions.stop()
        notificationSubscription?.close()
        unregisterReceiver(profileReceiver)
        search.close()
        widgetHost.destroy()
        super.onDestroy()
    }

    private fun scheduleReload(reason: String, packageKey: PackageKey? = null) {
        if (reason in PACKAGE_INVALIDATIONS) themedIcons.invalidate(packageKey)
        if (reason == "remove" && packageKey != null) NotificationDotStore.remove(packageKey)
        search.cancel()
        val query = if (this::searchField.isInitialized) searchField.text?.toString().orEmpty() else ""
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val database = app.database ?: return@io
            val workspace = WorkspaceController(LauncherRepository(database)) { !app.emergency }.also { controller = it }
            try {
                val profileChange = profiles.refresh()
                widgetHost.recover()
                val availableProfileIds = profileChange.profiles
                    .filter { it.descriptor.access == ProfileAccess.AVAILABLE }
                    .map { it.descriptor.profileId.toLong() }
                    .toSet()
                val settings = checkNotNull(database.dao().launcherSettings())
                NotificationDotStore.setEnabled(settings.notificationDots && !app.emergency)
                val loaded = packageSessions.decorate(
                    catalog.load(profileChange.profiles),
                    database.dao().workspaceApplications(),
                ).map { item -> item.copy(icon = themedIcons.icon(item, settings.themedIcons, appearance.generation)) }
                val inaccessible = profileChange.newlyInaccessibleProfileIds + profileChange.removedProfileIds
                NotificationDotStore.removeProfiles(inaccessible)
                themedIcons.invalidateProfiles(inaccessible)
                dragLayer.post {
                    val invalidating = reason in setOf(
                        "remove", "unavailable", "shortcuts", "profile", "change", "suspended", "loading", "session",
                    )
                    dragLayer.cancel(if (invalidating) "profile-or-package" else "reload")
                    if (invalidating) {
                        closeContext("catalog")
                    }
                    if (inaccessible.isNotEmpty()) closeFolder("profile")
                    widgetHost.invalidateProfiles(inaccessible, widgetBindings)
                }
                workspace.removeProfiles(profileChange.removedProfileIds)
                workspace.dropMissing(loaded, availableProfileIds)
                var state = workspace.snapshot()
                val resolvedShortcuts = shortcutCatalog.resolve(state.shortcutIds())
                val liveShortcuts = resolvedShortcuts.keys + retainedShortcutIds(state.shortcutIds(), loaded)
                state = workspace.reconcileShortcuts(liveShortcuts, availableProfileIds)?.asSnapshot()?.also {
                    CenixLog.event(EventId.SHORTCUT_RECONCILE, Severity.INFO, mapOf("count" to resolvedShortcuts.size.toString()))
                } ?: state
                shortcutCatalog.pin(state.shortcutIds())
                val bindings = database.dao().workspaceWidgets()
                val matches = try {
                    app.activeFilter().filter(loaded, query, availableProfileIds)
                } catch (_: Throwable) {
                    app.requestEmergency()
                    EmergencyAppFilter.filter(loaded, query, availableProfileIds)
                }
                app.markHealthy()
                CenixLog.event(EventId.CATALOG_REFRESH, Severity.INFO, mapOf("count" to loaded.size.toString(), "reason" to reason))
                CenixLog.event(
                    EventId.PROFILE_DISCOVERY,
                    Severity.INFO,
                    mapOf("count" to profileChange.profiles.size.toString()),
                )
                if (profileChange.newlyInaccessibleProfileIds.isNotEmpty()) {
                    CenixLog.event(
                        EventId.PROFILE_UNAVAILABLE,
                        Severity.INFO,
                        mapOf("count" to profileChange.newlyInaccessibleProfileIds.size.toString()),
                    )
                    if (profileChange.profiles.any {
                            it.descriptor.profileId.toLong() in profileChange.newlyInaccessibleProfileIds &&
                                it.descriptor.kind == ProfileKind.PRIVATE
                        }
                    ) {
                        CenixLog.event(EventId.PRIVATE_LOCKED, Severity.INFO)
                    }
                }
                if (profileChange.newlyAvailableProfileIds.isNotEmpty()) {
                    CenixLog.event(
                        EventId.PROFILE_AVAILABLE,
                        Severity.INFO,
                        mapOf("count" to profileChange.newlyAvailableProfileIds.size.toString()),
                    )
                }
                runOnUiThread {
                    profileSnapshot = profileChange.profiles
                    apps.clear()
                    apps.addAll(loaded)
                    shortcuts.clear()
                    shortcuts.putAll(resolvedShortcuts)
                    widgetBindings.clear()
                    widgetBindings.putAll(bindings.associateBy { it.itemId })
                    bindList(matches)
                    render(state)
                    widgetHost.refreshOptions(state, widgetBindings)
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
                for (y in 0 until rows) for (x in 0 until columns) {
                    val item = items.firstOrNull {
                        x >= it.cell.cellX && x < it.cell.cellX + it.cell.spanX &&
                            y >= it.cell.cellY && y < it.cell.cellY + it.cell.spanY
                    }
                    if (item == null) {
                        addCell(createCell(null, null, byComponent, "page ${pageIndex + 1}", x, y, page.pageId), x, y)
                    } else if (item.cell.cellX == x && item.cell.cellY == y) {
                        addCell(
                            createCell(item, folders[item.itemId], byComponent, "page ${pageIndex + 1}", x, y, page.pageId),
                            x,
                            y,
                            item.cell.spanX,
                            item.cell.spanY,
                        )
                    }
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
            hotseat.addCell(createCell(item, folders[item?.itemId], byComponent, "hotseat", x, 0, null), x, 0)
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
        pageId: ULong?,
    ): View {
        val profileId = when (val payload = item?.payload) {
            is ItemPayload.Application -> payload.component.profileId.toLong()
            is ItemPayload.Shortcut -> payload.shortcut.profileId.toLong()
            is ItemPayload.Widget -> payload.provider.profileId.toLong()
            is ItemPayload.Folder -> folder?.members?.firstOrNull()?.payload?.profileId()
            null -> null
        }
        if (item != null && profileId != null) {
            val projection = profileProjection(profileId, ProfileSurface.WORKSPACE)
            if (projection != ProfileItemProjection.VISIBLE) {
                return profilePlaceholder(item, location, x, y, projection)
            }
        }
        if (item != null && folder != null && item.payload is ItemPayload.Folder) {
            return FolderIconView(this).apply {
                bind(
                    folder.title,
                    folder.members.size,
                    folder.members.take(4).map { member -> member.icon(byComponent) },
                    folder.members.any { it.hasDot() },
                )
                contentDescription = "$contentDescription, $location, row ${y + 1}, column ${x + 1}"
                tag = CellTarget(item.itemId, folder.folderId)
                setOnClickListener { openFolder(folder.folderId) }
                setOnLongClickListener { dragLayer.beginDrag(this, LauncherDrag(null, item.itemId, isFolder = true)) }
                addAccessibilityMoves(this, item, x, y, folder.folderId)
            }
        }
        if (item?.payload is ItemPayload.Widget) {
            val binding = widgetBindings[item.itemId.toLong()]
            val remove = { widgetHost.remove(item.itemId, binding, item.cell, checkNotNull(pageId)) }
            val hosted = widgetHost.view(item.itemId, binding, checkNotNull(pageId), item.cell, remove)
            return WidgetFrame(this).apply {
                tag = CellTarget(item.itemId)
                contentDescription = "${hosted.contentDescription}, $location, row ${y + 1}, column ${x + 1}"
                isFocusable = true
                bind(
                    hosted,
                    onMove = { dragLayer.beginDrag(this, LauncherDrag(null, item.itemId, isWidget = true)) },
                    onResize = { edge, deltaX, deltaY -> widgetHost.resize(item.itemId, binding, edge, deltaX, deltaY) },
                    onRemove = remove,
                )
                addAccessibilityMoves(this, item, x, y, onRemove = remove, allowDock = false)
            }
        }
        val appItem = (item?.payload as? ItemPayload.Application)?.component?.let { byComponent[it.key()] }
        val shortcutItem = (item?.payload as? ItemPayload.Shortcut)?.shortcut?.let(shortcuts::get)
        val view = LayoutInflater.from(this).inflate(R.layout.workspace_cell, null, false)
        view.findViewById<ImageView>(R.id.cellIcon).setImageDrawable(appItem?.displayIcon() ?: shortcutItem?.displayIcon())
        view.findViewById<TextView>(R.id.cellLabel).text = appItem?.label ?: shortcutItem?.label.orEmpty()
        val label = appItem?.label ?: shortcutItem?.label
        val packageStatus = appItem?.let(::packageStatus)
        view.contentDescription = if (label == null) "Empty, $location, row ${y + 1}, column ${x + 1}"
        else listOfNotNull(
            label,
            packageStatus,
            if (appItem?.hasDot() == true || shortcutItem?.hasDot() == true) getString(R.string.notifications_available) else null,
            location,
            "row ${y + 1}",
            "column ${x + 1}",
        ).joinToString()
        if (packageStatus != null) view.alpha = 0.55f
        view.isFocusable = label != null
        if (item == null && pageId != null) {
            view.setOnLongClickListener { showWorkspaceOptions(pageId, x, y); true }
            view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_add_widget, getString(R.string.add_widget)))
                }

                override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                    if (action == R.id.action_add_widget) {
                        widgetHost.pick(pageId, x, y)
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        }
        if (appItem != null) {
            view.tag = CellTarget(item.itemId)
            view.setOnClickListener { launch(appItem) }
            if (appItem.canPlace) {
                attachLongPress(
                    view,
                    onPopup = { openContext(view, appItem, item.itemId, null) },
                    onDrag = { dragLayer.beginDrag(view, LauncherDrag(appItem, item.itemId)) },
                )
            }
            addAccessibilityMoves(
                view,
                item,
                x,
                y,
                onContext = if (appItem.canPlace) ({ openContext(view, appItem, item.itemId, null) }) else null,
            )
        } else if (shortcutItem != null) {
            view.tag = CellTarget(item!!.itemId)
            view.setOnClickListener { launch(shortcutItem) }
            attachLongPress(
                view,
                onPopup = { openShortcutContext(view, shortcutItem, item.itemId, null) },
                onDrag = { dragLayer.beginDrag(view, LauncherDrag(null, item.itemId, shortcut = shortcutItem)) },
            )
            addAccessibilityMoves(view, item, x, y, onContext = { openShortcutContext(view, shortcutItem, item.itemId, null) })
        }
        return view
    }

    private fun profilePlaceholder(
        item: WorkspaceItem,
        location: String,
        x: Int,
        y: Int,
        projection: ProfileItemProjection,
    ): View = LayoutInflater.from(this).inflate(R.layout.workspace_cell, null, false).apply {
        findViewById<ImageView>(R.id.cellIcon).setImageDrawable(null)
        importantForAccessibility = if (projection == ProfileItemProjection.HIDDEN) {
            findViewById<TextView>(R.id.cellLabel).text = ""
            contentDescription = null
            tag = null
            isFocusable = false
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        } else {
            findViewById<TextView>(R.id.cellLabel).text = getString(R.string.profile_item_unavailable)
            contentDescription = getString(R.string.profile_item_unavailable) +
                ", $location, row ${y + 1}, column ${x + 1}"
            tag = CellTarget(item.itemId)
            isFocusable = true
            View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
    }

    private fun profileProjection(profileId: Long, surface: ProfileSurface): ProfileItemProjection {
        val descriptor = profileSnapshot.firstOrNull { it.descriptor.profileId.toLong() == profileId }?.descriptor
            ?: return ProfileItemProjection.HIDDEN
        if (!app.emergency) return projectProfileItem(descriptor, surface)
        return when {
            descriptor.access == ProfileAccess.AVAILABLE -> ProfileItemProjection.VISIBLE
            descriptor.kind != ProfileKind.PRIVATE && surface == ProfileSurface.WORKSPACE -> ProfileItemProjection.PLACEHOLDER
            else -> ProfileItemProjection.HIDDEN
        }
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
        onRemove: (() -> Unit)? = null,
        allowDock: Boolean = true,
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
                if (allowDock) info.addAction(AccessibilityNodeInfo.AccessibilityAction(if (item.container is ContainerRef.Hotseat) R.id.action_undock else R.id.action_dock, if (item.container is ContainerRef.Hotseat) "Undock" else "Dock"))
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
                    AccessibilityNodeInfo.ACTION_DISMISS -> { onRemove?.invoke() ?: mutate { it.remove(item.itemId) }; return true }
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
            FolderEntry(this, it.label, it.displayIcon(), app = it)
        }
        is ItemPayload.Shortcut -> shortcuts[memberPayload.shortcut]?.let {
            FolderEntry(this, it.label, it.displayIcon(), shortcut = it)
        }
        is ItemPayload.Folder, is ItemPayload.Widget -> null
    }

    private fun FolderMember.icon(byComponent: Map<Triple<String, String, Long>, LaunchableApp>) = when (val memberPayload = payload) {
        is ItemPayload.Application -> byComponent[memberPayload.component.key()]?.displayIcon()
        is ItemPayload.Shortcut -> shortcuts[memberPayload.shortcut]?.displayIcon()
        is ItemPayload.Folder, is ItemPayload.Widget -> null
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
                    popup.bind(
                        appItem.label,
                        entries,
                        canUninstall(appItem),
                        itemId != null,
                        appItem.profileKind != ProfileKind.PRIVATE,
                    )
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
        popup.bind(
            shortcut.label,
            listOf(shortcut),
            canUninstall(parent),
            true,
            parent.profileKind != ProfileKind.PRIVATE,
        )
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
        popup.bind(
            label,
            emptyList(),
            canUninstall(appItem),
            itemId != null,
            appItem.profileKind != ProfileKind.PRIVATE,
        )
    }

    private fun closeContext(reason: String) {
        val popup = contextPopup ?: return
        contextQueryToken++
        dragLayer.removeView(popup)
        contextPopup = null
        CenixLog.event(EventId.CONTEXT_POPUP_CLOSE, Severity.INFO, mapOf("category" to reason))
    }

    private fun pinShortcut(shortcut: LauncherShortcut) {
        if (profileProjection(shortcut.id.profileId.toLong(), ProfileSurface.WORKSPACE) != ProfileItemProjection.VISIBLE) {
            return
        }
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
        val user = appItem.user ?: catalog.userForSerial(appItem.profileId) ?: return false
        appItem.packageName != packageName && profiles.isAvailable(appItem.profileId) &&
            getSystemService(LauncherApps::class.java)
                .getApplicationInfo(appItem.packageName, 0, user).flags and ApplicationInfo.FLAG_SYSTEM == 0
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
        val profileId = payload.app?.profileId ?: payload.shortcut?.id?.profileId?.toLong()
        if (profileId != null && profileProjection(profileId, ProfileSurface.WORKSPACE) != ProfileItemProjection.VISIBLE) {
            CenixLog.event(EventId.DRAG_CANCEL, Severity.INFO, mapOf("category" to "profile-policy"))
            return
        }
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
        if (activeProfileSection == ProfileKind.WORK && profileSnapshot.none { it.descriptor.kind == ProfileKind.WORK }) {
            activeProfileSection = ProfileKind.PERSONAL
        }
        visible.clear()
        privateVisible.clear()
        if (activeProfileSection == ProfileKind.WORK) {
            visible.addAll(matches.filter { it.profileKind == ProfileKind.WORK })
        } else {
            visible.addAll(matches.filter { it.profileKind == ProfileKind.PERSONAL || it.profileKind == ProfileKind.OTHER })
            privateVisible.addAll(matches.filter { it.profileKind == ProfileKind.PRIVATE })
        }
        (appList.adapter as AppAdapter).notifyDataSetChanged()
        (privateAppList.adapter as AppAdapter).notifyDataSetChanged()
        bindProfileChrome()
    }

    private fun applyFilter() {
        val query = searchField.text?.toString().orEmpty()
        search.submit(apps.toList(), query, catalog.visibleProfiles())
    }

    private fun selectProfileSection(kind: ProfileKind) {
        if (kind != ProfileKind.PERSONAL && kind != ProfileKind.WORK) return
        activeProfileSection = kind
        applyFilter()
        appList.requestFocus()
    }

    private fun toggleProfile(kind: ProfileKind) {
        val profile = profileSnapshot.firstOrNull { it.descriptor.kind == kind } ?: return
        val makeAvailable = profile.descriptor.access != ProfileAccess.AVAILABLE
        if (kind == ProfileKind.PRIVATE && !makeAvailable) {
            search.cancel()
            closeContext("private-lock")
            closeFolder("private-lock")
            dragLayer.cancel("private-lock")
            privateVisible.clear()
            (privateAppList.adapter as AppAdapter).notifyDataSetChanged()
            privateAppList.visibility = View.GONE
        }
        CenixLog.event(
            if (kind == ProfileKind.PRIVATE) EventId.PRIVATE_UNLOCK_REQUEST else EventId.PROFILE_UI_INVALIDATED,
            Severity.INFO,
            mapOf("kind" to kind.name, "result" to "requested"),
        )
        CenixExecutors.io {
            val accepted = profiles.requestAvailable(profile.descriptor.profileId.toLong(), makeAvailable)
            CenixLog.event(
                EventId.PROFILE_OPERATION_RESULT,
                if (accepted) Severity.INFO else Severity.WARN,
                mapOf("kind" to kind.name, "result" to if (accepted) "accepted" else "rejected"),
            )
            scheduleReload("profile")
        }
    }

    private fun bindProfileChrome() {
        val work = profileSnapshot.firstOrNull { it.descriptor.kind == ProfileKind.WORK }
        workTab.visibility = if (work == null) View.GONE else View.VISIBLE
        personalTab.isSelected = activeProfileSection == ProfileKind.PERSONAL
        workTab.isSelected = activeProfileSection == ProfileKind.WORK
        if (work == null && activeProfileSection == ProfileKind.WORK) activeProfileSection = ProfileKind.PERSONAL
        workProfileState.visibility = if (activeProfileSection == ProfileKind.WORK && work != null) View.VISIBLE else View.GONE
        if (work != null) {
            val available = work.descriptor.access == ProfileAccess.AVAILABLE
            workProfileMessage.setText(if (available) R.string.work_apps_available else R.string.work_apps_paused)
            workProfileToggle.setText(if (available) R.string.turn_work_off else R.string.turn_work_on)
            workProfileToggle.contentDescription = workProfileToggle.text
        }

        val privateProfile = profileSnapshot.firstOrNull { it.descriptor.kind == ProfileKind.PRIVATE }
        privateSpaceContainer.visibility = if (activeProfileSection == ProfileKind.PERSONAL && privateProfile != null) View.VISIBLE else View.GONE
        if (privateProfile != null) {
            val available = privateProfile.descriptor.access == ProfileAccess.AVAILABLE
            privateSpaceState.setText(if (available) R.string.private_space_available else R.string.private_space_locked)
            privateSpaceToggle.setText(if (available) R.string.lock_private_space else R.string.unlock_private_space)
            privateSpaceToggle.contentDescription = privateSpaceToggle.text
            privateAppList.visibility = if (available) View.VISIBLE else View.GONE
            if (!available) {
                privateVisible.clear()
                (privateAppList.adapter as AppAdapter).notifyDataSetChanged()
            }
        }
    }

    private fun applySurface() {
        if (app.emergency) NotificationDotStore.setEnabled(false)
        if (app.emergency) widgetHost.stop()
        else if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) widgetHost.start()
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
                R.id.action_open_launcher_settings -> { openLauncherSettings(); true }
                R.id.action_export_diagnostics -> { exportDiagnostics.launch("cenix-diagnostics.txt"); true }
                else -> super.performAccessibilityAction(host, action, args)
            }
        }
    }

    private fun launch(appItem: LaunchableApp) {
        closeFolder("launch")
        closeContext("launch")
        if (!profiles.isAvailable(appItem.profileId) || !appItem.canLaunch) {
            return Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_state)
            .setMessage(R.string.reset_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_state) { _, _ -> resetStateConfirmed() }
            .show()
    }

    private fun resetStateConfirmed() {
        widgetHost.deleteHost()
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            app.resetLocalState()
            controller = app.database?.let { WorkspaceController(LauncherRepository(it)) { !app.emergency } }
            selectedPageId = null
            scheduleReload("reset")
        }
    }

    private fun openLauncherSettings() {
        startActivity(Intent(this, LauncherSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private inner class AppAdapter(private val items: List<LaunchableApp>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].let { 31L * it.packageName.hashCode() + it.profileId }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(this@HomeActivity).inflate(R.layout.app_row, parent, false)
            val item = items[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(item.displayIcon())
            view.findViewById<TextView>(R.id.appLabel).text = item.label
            val profile = profileLabel(item.profileKind)
            view.findViewById<TextView>(R.id.appProfile).text = profile
            val packageStatus = packageStatus(item)
            view.findViewById<TextView>(R.id.appStatus).apply {
                text = packageStatus
                visibility = if (packageStatus == null) View.GONE else View.VISIBLE
            }
            view.findViewById<ProgressBar>(R.id.appProgress).apply {
                val show = item.packageState == PackageState.INSTALLING || item.packageState == PackageState.UPDATING
                visibility = if (show) View.VISIBLE else View.GONE
                progress = item.installProgress ?: 0
            }
            view.alpha = if (item.packageState == PackageState.READY) 1f else 0.65f
            view.contentDescription = listOfNotNull(
                item.label,
                profile,
                packageStatus,
                if (item.hasDot()) getString(R.string.notifications_available) else null,
            ).joinToString()
            return view
        }
    }

    private fun packageStatus(item: LaunchableApp): String? = when (item.packageState) {
        PackageState.READY -> null
        PackageState.INSTALLING -> getString(R.string.package_installing, item.installProgress ?: 0)
        PackageState.UPDATING -> getString(R.string.package_updating, item.installProgress ?: 0)
        PackageState.SUSPENDED -> getString(R.string.package_suspended)
        PackageState.DISABLED -> getString(R.string.package_disabled)
        PackageState.ARCHIVED -> getString(R.string.package_archived)
        PackageState.TEMPORARILY_UNAVAILABLE -> getString(R.string.package_temporarily_unavailable)
    }

    private fun refreshDots() {
        if (!this::root.isInitialized || isFinishing || isDestroyed) return
        (appList.adapter as? AppAdapter)?.notifyDataSetChanged()
        (privateAppList.adapter as? AppAdapter)?.notifyDataSetChanged()
        render(rendered)
    }

    private fun LaunchableApp.hasDot(): Boolean = NotificationDotStore.dot(PackageKey(packageName, profileId)) != null

    private fun LauncherShortcut.hasDot(): Boolean = NotificationDotStore.dot(this) != null

    private fun LaunchableApp.displayIcon(): android.graphics.drawable.Drawable? = icon?.let {
        if (hasDot()) NotificationDotDrawable(it, getColor(R.color.notification_dot)) else it
    }

    private fun LauncherShortcut.displayIcon(): android.graphics.drawable.Drawable? = icon?.let {
        if (hasDot()) NotificationDotDrawable(it, getColor(R.color.notification_dot)) else it
    }

    private fun FolderMember.hasDot(): Boolean = when (val value = payload) {
        is ItemPayload.Application -> NotificationDotStore.dot(PackageKey(value.component.`package`, value.component.profileId.toLong())) != null
        is ItemPayload.Shortcut -> shortcuts[value.shortcut]?.hasDot() == true
        is ItemPayload.Folder, is ItemPayload.Widget -> false
    }

    private fun showWorkspaceOptions(pageId: ULong, x: Int, y: Int) {
        val labels = arrayOf(getString(R.string.wallpaper), getString(R.string.add_widget), getString(R.string.open_all_apps), getString(R.string.launcher_settings))
        AlertDialog.Builder(this)
            .setTitle(R.string.workspace_options)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> if (!SystemWallpaperPicker.open(this)) Toast.makeText(this, R.string.wallpaper_unavailable, Toast.LENGTH_SHORT).show()
                    1 -> widgetHost.pick(pageId, x, y)
                    2 -> setSurface(LauncherSurface.ALL_APPS)
                    3 -> openLauncherSettings()
                }
            }
            .show()
    }

    private fun autoPlace(key: PackageKey) {
        CenixExecutors.io {
            if (!app.awaitReady() || app.emergency) return@io
            val database = app.database ?: return@io
            if (database.dao().launcherSettings()?.autoAddApps != true) return@io
            val profile = profiles.profile(key.profileId)
                ?.takeIf { it.descriptor.access == ProfileAccess.AVAILABLE && it.descriptor.kind != ProfileKind.PRIVATE }
                ?: return@io
            val item = catalog.load(listOf(profile))
                .filter { it.packageName == key.packageName && it.canPlace }
                .minByOrNull { it.className } ?: return@io
            val workspace = controller ?: WorkspaceController(LauncherRepository(database)) { !app.emergency }
            workspace.autoPlace(item)?.let { state -> runOnUiThread { render(state.asSnapshot()) } }
        }
    }

    private fun profileLabel(kind: ProfileKind): String = getString(
        when (kind) {
            ProfileKind.PERSONAL -> R.string.profile_personal
            ProfileKind.WORK -> R.string.profile_work
            ProfileKind.PRIVATE -> R.string.profile_private
            ProfileKind.OTHER -> R.string.profile_other
        },
    )

    private fun WorkspaceTransition.asSnapshot() = WorkspaceSnapshot(generation, grid, pages, items, folders)

    companion object {
        const val EXTRA_FORCE_NATIVE_FAILURE = "com.caniko.cenix.FORCE_NATIVE_FAILURE"
        private const val SURFACE_ANIMATION_MS = 220L
        private const val STATE_PAGE_ID = "workspace.pageId"
        private val PACKAGE_INVALIDATIONS = setOf("add", "remove", "change", "available", "unavailable", "suspended", "unsuspended", "loading", "session", "session-finished")
        private fun emptySnapshot() = WorkspaceSnapshot(0UL, com.caniko.cenix.uniffi.GridSpec(1, 1, 1), emptyList(), emptyList(), emptyList())
    }
}

internal fun WorkspaceSnapshot.shortcutIds(): List<ShortcutId> =
    (items.mapNotNull { (it.payload as? ItemPayload.Shortcut)?.shortcut } +
        folders.flatMap { folder -> folder.members.mapNotNull { (it.payload as? ItemPayload.Shortcut)?.shortcut } })
        .distinct()

private fun ItemPayload.profileId(): Long? = when (this) {
    is ItemPayload.Application -> component.profileId.toLong()
    is ItemPayload.Shortcut -> shortcut.profileId.toLong()
    is ItemPayload.Widget -> provider.profileId.toLong()
    is ItemPayload.Folder -> null
}

internal fun retainedShortcutIds(current: List<ShortcutId>, apps: List<LaunchableApp>): List<ShortcutId> {
    val retained = apps.filter { it.packageState != PackageState.READY }
        .map { PackageKey(it.packageName, it.profileId) }
        .toSet()
    return current.filter { PackageKey(it.`package`, it.profileId.toLong()) in retained }
}
