package com.caniko.cenix

import android.app.role.RoleManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import com.caniko.cenix.db.CenixDatabase
import com.caniko.cenix.db.LauncherSettingsEntity
import com.caniko.cenix.db.RestoreSource
import com.caniko.cenix.uniffi.BackupImportPlan
import com.caniko.cenix.uniffi.BackupImportTarget
import com.caniko.cenix.uniffi.BackupProfileRef
import com.caniko.cenix.uniffi.ComponentId
import com.caniko.cenix.uniffi.ItemPayload
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind
import com.caniko.cenix.uniffi.ProfileMapping
import com.caniko.cenix.uniffi.ShortcutId
import com.caniko.cenix.uniffi.WidgetProviderId
import com.caniko.cenix.uniffi.WorkspaceException
import com.caniko.cenix.uniffi.planBackupImport
import java.io.ByteArrayInputStream

class LauncherSettingsActivity : AppCompatActivity() {
    private data class PendingImport(val payload: String, val plan: BackupImportPlan)

    private lateinit var app: CenixApplication
    private lateinit var options: RadioGroup
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var homeRole: Button
    private lateinit var notificationDots: Switch
    private lateinit var themedIcons: Switch
    private lateinit var autoAddApps: Switch
    private lateinit var appearance: WallpaperAppearanceController
    private var binding = false

    private val exportDiagnostics = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        contentResolver.openOutputStream(uri)?.use { out ->
            DiagnosticStore.export(
                out,
                mapOf(
                    "version" to BuildConfig.VERSION_NAME,
                    "buildType" to BuildConfig.BUILD_TYPE,
                    "commit" to BuildConfig.GIT_COMMIT,
                    "schema" to CenixDatabase.VERSION.toString(),
                ),
            )
        }
    }

    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri?.let(::writeBackup)
    }

    private val importBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(::readBackup)
    }

    private val requestHomeRole = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        updateHomeRole()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WallpaperAppearanceController.applyTheme(this)
        super.onCreate(savedInstanceState)
        appearance = WallpaperAppearanceController(this) { recreate() }
        app = application as CenixApplication
        setContentView(R.layout.activity_launcher_settings)
        options = findViewById(R.id.gridOptions)
        progress = findViewById(R.id.gridProgress)
        status = findViewById(R.id.gridStatus)
        homeRole = findViewById(R.id.selectHomeRole)
        notificationDots = findViewById(R.id.notificationDots)
        themedIcons = findViewById(R.id.themedIcons)
        autoAddApps = findViewById(R.id.autoAddApps)
        findViewById<Button>(R.id.wallpaper).setOnClickListener {
            if (!SystemWallpaperPicker.open(this)) status.setText(R.string.wallpaper_unavailable)
        }
        findViewById<Button>(R.id.exportDiagnostics).setOnClickListener {
            exportDiagnostics.launch("cenix-diagnostics.txt")
        }
        findViewById<Button>(R.id.exportBackup).setOnClickListener {
            exportBackup.launch("cenix-backup.json")
        }
        findViewById<Button>(R.id.importBackup).setOnClickListener {
            importBackup.launch(arrayOf("application/json", "text/json", "application/octet-stream"))
        }
        findViewById<Button>(R.id.resetLauncher).setOnClickListener { confirmReset() }
        findViewById<TextView>(R.id.buildIdentity).text = getString(
            R.string.build_identity,
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_TYPE,
            BuildConfig.GIT_COMMIT,
        )
        homeRole.setOnClickListener {
            val roles = getSystemService(RoleManager::class.java)
            if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                requestHomeRole.launch(roles.createRequestRoleIntent(RoleManager.ROLE_HOME))
            }
        }
        bindGridOptions()
        bindPreferences()
        updateHomeRole()
    }

    override fun onStart() {
        super.onStart()
        appearance.start()
    }

    override fun onStop() {
        appearance.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateHomeRole()
        updateNotificationAccess()
    }

    private fun bindGridOptions() {
        val metrics = resources.displayMetrics
        val grids = PhoneGrid.compatible(
            minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
            maxOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
        )
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val selected = app.database?.dao()?.launcherSettings()?.gridName.orEmpty()
            runOnUiThread {
                binding = true
                options.removeAllViews()
                grids.forEach { grid ->
                    options.addView(RadioButton(this).apply {
                        id = gridViewId(grid.name)
                        tag = grid.name
                        text = getString(R.string.grid_dimensions, grid.cols, grid.rows)
                        contentDescription = getString(R.string.grid_dimensions, grid.cols, grid.rows)
                        isChecked = grid.name == selected
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                    })
                }
                binding = false
                options.setOnCheckedChangeListener { group, checkedId ->
                    if (!binding) (group.findViewById<RadioButton>(checkedId)?.tag as? String)
                        ?.let(PhoneGrid::named)?.let(::selectGrid)
                }
            }
        }
    }

    private fun bindPreferences() {
        CenixExecutors.io {
            if (!app.awaitReady()) return@io
            val settings = app.database?.dao()?.launcherSettings() ?: return@io
            runOnUiThread {
                binding = true
                notificationDots.isChecked = settings.notificationDots
                themedIcons.isChecked = settings.themedIcons
                autoAddApps.isChecked = settings.autoAddApps
                binding = false
                notificationDots.setOnCheckedChangeListener { _, checked ->
                    if (!binding) updateSettings { it.copy(notificationDots = checked) }
                    NotificationDotStore.setEnabled(checked)
                    if (checked) requestNotificationAccess()
                }
                themedIcons.setOnCheckedChangeListener { _, checked ->
                    if (!binding) updateSettings { it.copy(themedIcons = checked) }
                }
                autoAddApps.setOnCheckedChangeListener { _, checked ->
                    if (!binding) updateSettings { it.copy(autoAddApps = checked) }
                }
                updateNotificationAccess()
            }
        }
    }

    private fun updateSettings(transform: (LauncherSettingsEntity) -> LauncherSettingsEntity) {
        CenixExecutors.io {
            val dao = app.database?.dao() ?: return@io
            dao.launcherSettings()?.let(transform)?.let(dao::upsertLauncherSettings)
        }
    }

    private fun writeBackup(uri: Uri) {
        CenixExecutors.io {
            val success = try {
                if (!app.awaitReady() || app.emergency) false else {
                    val database = app.database ?: throw IllegalStateException("database unavailable")
                    val profiles = ProfileController(this).also { it.refresh() }.profiles()
                        .filter { it.descriptor.kind == ProfileKind.PERSONAL || it.descriptor.kind == ProfileKind.WORK }
                        .map { BackupProfileRef(it.descriptor.profileId, it.descriptor.kind) }
                    val document = BackupRepository(database).buildDocument(
                        profiles,
                        includeWork = false,
                        sourceVersion = BuildConfig.VERSION_NAME,
                        sourceCommit = BuildConfig.GIT_COMMIT,
                    )
                    contentResolver.openOutputStream(uri, "wt")?.use { BackupJsonCodec.write(document, it) } != null
                }
            } catch (_: Throwable) {
                false
            }
            runOnUiThread { status.setText(if (success) R.string.backup_exported else R.string.backup_failed) }
        }
    }

    private fun readBackup(uri: Uri) {
        CenixExecutors.io {
            val pending = try {
                if (!app.awaitReady() || app.emergency) null else prepareImport(uri)
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                if (pending == null) status.setText(R.string.backup_failed) else confirmImport(pending)
            }
        }
    }

    private fun prepareImport(uri: Uri): PendingImport {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readNBytes(BackupJsonCodec.MAX_BYTES + 1) }
            ?: throw IllegalStateException("backup unavailable")
        if (bytes.size > BackupJsonCodec.MAX_BYTES) throw BackupJsonException("oversized")
        val document = BackupJsonCodec.read(ByteArrayInputStream(bytes))
        if (document.profiles.any { it.kind != ProfileKind.PERSONAL }) throw BackupJsonException("work")
        val database = app.database ?: throw IllegalStateException("database unavailable")
        val repository = LauncherRepository(database)
        val profiles = ProfileController(this).also { it.refresh() }
        val liveProfiles = profiles.profiles().filter {
            it.descriptor.access == ProfileAccess.AVAILABLE &&
                (it.descriptor.kind == ProfileKind.PERSONAL || it.descriptor.kind == ProfileKind.WORK)
        }
        val personal = liveProfiles.singleOrNull { it.descriptor.kind == ProfileKind.PERSONAL }
            ?: throw BackupJsonException("profile")
        val profileRefs = liveProfiles.map { BackupProfileRef(it.descriptor.profileId, it.descriptor.kind) }
        val profileIds = profileRefs.mapTo(HashSet()) { it.profileId }
        val catalog = AppCatalog(this, profiles)
        val applications = catalog.load(liveProfiles).filter { it.profileId.toULong() in profileIds }.map {
            ComponentId(it.packageName, it.className, it.profileId.toULong())
        }
        val requestedShortcuts = (
            document.workspace.items.map { it.payload } +
                document.workspace.folders.flatMap { folder -> folder.members.map { it.payload } }
            ).mapNotNull { (it as? ItemPayload.Shortcut)?.shortcut }
            .map { ShortcutId(it.`package`, it.shortcutId, personal.descriptor.profileId) }
        val shortcuts = ShortcutCatalog(this, catalog, profiles).resolve(requestedShortcuts).keys.toList()
        val widgetManager = getSystemService(AppWidgetManager::class.java)
        val widgets = liveProfiles.flatMap { profile ->
            try {
                widgetManager.getInstalledProvidersForProfile(profile.user).mapNotNull { info ->
                    val home = info.widgetCategory == 0 ||
                        info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                    if (!home) null else WidgetProviderId(
                        info.provider.packageName,
                        info.provider.className,
                        profile.descriptor.profileId,
                    )
                }
            } catch (_: RuntimeException) {
                emptyList()
            }
        }
        val generation = repository.snapshot().generation
        val metrics = resources.displayMetrics
        val grids = PhoneGrid.compatible(
            minOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
            maxOf(metrics.widthPixels, metrics.heightPixels) / metrics.density,
        ).map { it.name }
        val plan = planBackupImport(
            document,
            BackupImportTarget(generation, generation, profileRefs, grids, applications, shortcuts, widgets),
            listOf(ProfileMapping(0uL, personal.descriptor.profileId)),
        )
        return PendingImport(String(bytes, Charsets.UTF_8), plan)
    }

    private fun confirmImport(pending: PendingImport) {
        val itemCount = pending.plan.workspace.items.size + pending.plan.workspace.folders.sumOf { it.members.size }
        val unresolved = pending.plan.unresolvedApplications.size + pending.plan.unresolvedShortcuts.size +
            pending.plan.unresolvedWidgets.size
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_import)
            .setMessage(getString(R.string.backup_import_confirmation, itemCount, unresolved))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.backup_replace) { _, _ -> applyImport(pending) }
            .show()
    }

    private fun applyImport(pending: PendingImport) {
        progress.visibility = View.VISIBLE
        CenixExecutors.io {
            val success = try {
                if (app.emergency) false else {
                    val database = app.database ?: throw IllegalStateException("database unavailable")
                    val repository = BackupRepository(database)
                    val expectedGeneration = pending.plan.workspace.generation.toLong() - 1
                    repository.stage(RestoreSource.LOCAL, pending.payload, expectedGeneration, System.currentTimeMillis())
                    repository.confirmLocal()
                    repository.applyLocalPlan(pending.plan, pending.payload)
                    try {
                        AppWidgetHost(this, WidgetHostController.HOST_ID).deleteHost()
                    } catch (_: RuntimeException) {
                        Unit
                    }
                    true
                }
            } catch (_: Throwable) {
                false
            }
            runOnUiThread {
                progress.visibility = View.GONE
                status.setText(if (success) R.string.backup_imported else R.string.backup_failed)
                if (success) {
                    bindGridOptions()
                    bindPreferences()
                }
            }
        }
    }

    private fun requestNotificationAccess() {
        if (hasNotificationAccess()) {
            NotificationListenerService.requestRebind(ComponentName(this, CenixNotificationListener::class.java))
            return
        }
        startActivity(
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(this, CenixNotificationListener::class.java)),
        )
    }

    private fun updateNotificationAccess() {
        if (!this::notificationDots.isInitialized) return
        notificationDots.text = getString(
            if (notificationDots.isChecked && !hasNotificationAccess()) R.string.notification_access_required
            else R.string.notification_dots,
        )
        if (!hasNotificationAccess()) NotificationDotStore.replace(emptyMap())
    }

    private fun hasNotificationAccess(): Boolean =
        packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

    private fun selectGrid(grid: PhoneGrid) {
        progress.visibility = View.VISIBLE
        setGridEnabled(false)
        status.setText(R.string.grid_migrating)
        CenixExecutors.io {
            val database = app.database
            val result = try {
                if (database == null || app.emergency) null else {
                    val repository = LauncherRepository(database)
                    val controller = WorkspaceController(repository) { !app.emergency }
                    controller.setGrid(grid, widgetMinimumSpans(this, database, controller.snapshot(), grid))
                    repository.selectedGridName() == grid.name
                }
            } catch (_: WorkspaceException.WidgetTooLarge) {
                false
            } catch (_: Throwable) {
                null
            }
            runOnUiThread {
                progress.visibility = View.GONE
                setGridEnabled(true)
                status.setText(
                    when (result) {
                        true -> R.string.grid_applied
                        false -> R.string.grid_widget_too_large
                        null -> R.string.grid_failed
                    },
                )
                if (result != true) bindGridOptions()
            }
        }
    }

    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_state)
            .setMessage(R.string.reset_confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_state) { _, _ ->
                AppWidgetHost(this, WidgetHostController.HOST_ID).deleteHost()
                CenixExecutors.io {
                    if (!app.awaitReady()) return@io
                    app.resetLocalState()
                    runOnUiThread {
                        status.setText(R.string.reset_complete)
                        bindGridOptions()
                        bindPreferences()
                    }
                }
            }
            .show()
    }

    private fun updateHomeRole() {
        val roles = getSystemService(RoleManager::class.java)
        val held = roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME)
        homeRole.isEnabled = !held
        homeRole.setText(if (held) R.string.default_home_selected else R.string.set_default_home)
    }

    private fun setGridEnabled(enabled: Boolean) {
        options.isEnabled = enabled
        repeat(options.childCount) { options.getChildAt(it).isEnabled = enabled }
    }

    private fun gridViewId(name: String): Int = when (name) {
        "2_by_2" -> R.id.grid_2_by_2
        "3_by_3" -> R.id.grid_3_by_3
        "4_by_4" -> R.id.grid_4_by_4
        "4_by_5" -> R.id.grid_4_by_5
        "5_by_5" -> R.id.grid_5_by_5
        else -> error("unsupported grid")
    }
}
