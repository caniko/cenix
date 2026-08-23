package com.caniko.cenix

import android.content.Intent
import android.os.Bundle
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
    private var requestSeq = 0L

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
        app.database?.dao()?.bumpGeneration(System.currentTimeMillis())
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
        val matches = if (app.emergency || !NativeBridge.loaded) {
            EmergencyFilter.filter(apps, query, profiles)
        } else {
            val requestId = (++requestSeq).toString()
            val outcome = NativeBridge.filter(FilterProtocol.encodeRequest(requestId, query, profiles, apps))
            if (!outcome.ok) {
                app.requestEmergency()
                applyChrome()
                EmergencyFilter.filter(apps, query, profiles)
            } else {
                val index = apps.associateBy { Triple(it.packageName, it.className, it.profileId) }
                outcome.matches.mapNotNull { match ->
                    index[Triple(match.packageName, match.className, match.profileId)]
                }
            }
        }
        visible.clear()
        visible.addAll(matches)
        (appList.adapter as AppAdapter).notifyDataSetChanged()
    }

    private fun launch(appItem: LaunchableApp) {
        try {
            val launcher = getSystemService(android.content.pm.LauncherApps::class.java)
            launcher.startMainActivity(android.content.ComponentName(appItem.packageName, appItem.className), appItem.user, null, null)
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
        startActivity(Intent(android.provider.Settings.ACTION_HOME_SETTINGS))
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
        val personal = android.os.Process.myUserHandle().hashCode().toLong()
        return when (profileId) {
            personal -> getString(R.string.profile_personal)
            else -> getString(R.string.profile_other)
        }
    }

    companion object {
        const val EXTRA_FORCE_NATIVE_FAILURE = "com.caniko.cenix.FORCE_NATIVE_FAILURE"
    }
}
