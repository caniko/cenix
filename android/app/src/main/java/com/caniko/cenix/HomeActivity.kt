package com.caniko.cenix

import android.content.ComponentName
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
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
import androidx.appcompat.app.AppCompatActivity

class HomeActivity : AppCompatActivity() {
    private lateinit var app: CenixApplication
    private lateinit var catalog: AppCatalog
    private lateinit var statusTitle: TextView
    private lateinit var emergencyBanner: TextView
    private lateinit var searchField: EditText
    private lateinit var appList: ListView
    private lateinit var workspaceGrid: GridView
    private lateinit var grid: PhoneGrid
    private var workspace: Workspace? = null
    private val apps = mutableListOf<LaunchableApp>()
    private val visible = mutableListOf<LaunchableApp>()
    private val slots = mutableListOf<LaunchableApp?>()

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = reload()
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as CenixApplication
        catalog = AppCatalog(this)
        workspace = app.database?.let(::Workspace)
        val dm = resources.displayMetrics
        grid = PhoneGrid.pick(dm.widthPixels / dm.density, dm.heightPixels / dm.density)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) {
            app.requestEmergency()
        }
        setContentView(R.layout.activity_home)
        statusTitle = findViewById(R.id.statusTitle)
        emergencyBanner = findViewById(R.id.emergencyBanner)
        searchField = findViewById(R.id.searchField)
        appList = findViewById(R.id.appList)
        workspaceGrid = findViewById(R.id.workspaceGrid)
        findViewById<Button>(R.id.retryNative).setOnClickListener { retryNative() }
        findViewById<Button>(R.id.resetState).setOnClickListener { resetState() }
        findViewById<Button>(R.id.setDefaultHome).setOnClickListener { promptDefaultHome() }
        workspaceGrid.numColumns = grid.cols
        workspaceGrid.layoutParams = workspaceGrid.layoutParams.apply {
            val cell = (56 * resources.displayMetrics.density).toInt()
            val maxH = (resources.displayMetrics.heightPixels * 2) / 5
            height = minOf(grid.rows * cell, maxH)
        }
        workspaceGrid.adapter = WorkspaceAdapter()
        workspaceGrid.setOnItemClickListener { _, _, position, _ -> slots[position]?.let(::launch) }
        workspaceGrid.setOnItemLongClickListener { _, _, position, _ ->
            slots[position]?.let {
                workspace?.unpin(it)
                bindWorkspace()
            }
            true
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
        reload()
    }

    override fun onStart() {
        super.onStart()
        reload()
    }

    override fun onDestroy() {
        catalog.unregister(packageCallback)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) {
            app.requestEmergency()
            applyChrome()
        }
    }

    private fun reload() {
        try {
            apps.clear()
            apps.addAll(catalog.load())
            workspace?.dropMissing(apps.map { Triple(it.packageName, it.className, it.profileId) }.toSet())
            applyFilter()
            bindWorkspace()
            applyChrome()
            app.markHealthy()
        } catch (_: Throwable) {
            app.requestEmergency()
            applyChrome()
        }
    }

    private fun bindWorkspace() {
        val byCell = workspace?.items().orEmpty()
            .filter { grid.inBounds(it.cellX, it.cellY) }
            .associateBy { it.cellY * grid.cols + it.cellX }
        val catalogIndex = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
        slots.clear()
        for (i in 0 until grid.cells) {
            val item = byCell[i]
            slots.add(item?.let { catalogIndex[Triple(it.packageName, it.className, it.profileId)] })
        }
        (workspaceGrid.adapter as WorkspaceAdapter).notifyDataSetChanged()
    }

    private fun pin(appItem: LaunchableApp) {
        val store = workspace ?: return
        if (!store.pin(appItem, grid)) {
            Toast.makeText(this, R.string.workspace_full, Toast.LENGTH_SHORT).show()
            return
        }
        bindWorkspace()
    }

    private fun applyChrome() {
        val emergency = app.emergency
        statusTitle.setText(if (emergency) R.string.status_emergency else R.string.status_ready)
        emergencyBanner.visibility = if (emergency) View.VISIBLE else View.GONE
    }

    private fun applyFilter() {
        val query = searchField.text?.toString().orEmpty()
        workspaceGrid.visibility = if (query.isBlank()) View.VISIBLE else View.GONE
        val profiles = catalog.visibleProfiles()
        val matches = try {
            app.activeFilter().filter(apps, query, profiles)
        } catch (_: Throwable) {
            app.requestEmergency()
            applyChrome()
            EmergencyAppFilter.filter(apps, query, profiles)
        }
        visible.clear()
        visible.addAll(matches)
        (appList.adapter as AppAdapter).notifyDataSetChanged()
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
        app.retryNative()
        applyChrome()
        applyFilter()
    }

    private fun resetState() {
        app.resetLocalState()
        workspace = app.database?.let(::Workspace)
        reload()
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
            view.contentDescription = item?.label ?: getString(R.string.workspace_empty)
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
