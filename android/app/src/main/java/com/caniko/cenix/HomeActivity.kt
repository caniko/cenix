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
import android.widget.Button
import android.widget.EditText
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
    private val apps = mutableListOf<LaunchableApp>()
    private val visible = mutableListOf<LaunchableApp>()

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
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FORCE_NATIVE_FAILURE, false)) {
            app.requestEmergency()
        }
        setContentView(R.layout.activity_home)
        statusTitle = findViewById(R.id.statusTitle)
        emergencyBanner = findViewById(R.id.emergencyBanner)
        searchField = findViewById(R.id.searchField)
        appList = findViewById(R.id.appList)
        findViewById<Button>(R.id.retryNative).setOnClickListener { retryNative() }
        findViewById<Button>(R.id.resetState).setOnClickListener { resetState() }
        findViewById<Button>(R.id.setDefaultHome).setOnClickListener { promptDefaultHome() }
        appList.adapter = AppAdapter()
        appList.setOnItemClickListener { _, _, position, _ -> launch(visible[position]) }
        searchField.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    applyFilter()
                }
            },
        )
        reload()
        if (!app.emergency) {
            app.markHealthy()
        }
    }

    override fun onStart() {
        super.onStart()
        catalog.register(packageCallback)
    }

    override fun onStop() {
        catalog.unregister(packageCallback)
        super.onStop()
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
        apps.clear()
        apps.addAll(catalog.load())
        applyChrome()
        applyFilter()
    }

    private fun applyChrome() {
        val emergency = app.emergency
        statusTitle.setText(if (emergency) R.string.status_emergency else R.string.status_ready)
        emergencyBanner.visibility = if (emergency) View.VISIBLE else View.GONE
    }

    private fun applyFilter() {
        val query = searchField.text?.toString().orEmpty()
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
        if (app.retryNative()) {
            applyChrome()
            applyFilter()
        }
    }

    private fun resetState() {
        app.resetLocalState()
        reload()
    }

    private fun promptDefaultHome() {
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
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
            view.contentDescription = item.label
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
