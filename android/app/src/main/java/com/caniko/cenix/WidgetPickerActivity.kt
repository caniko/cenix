package com.caniko.cenix

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.caniko.cenix.uniffi.ProfileAccess
import com.caniko.cenix.uniffi.ProfileKind

class WidgetPickerActivity : AppCompatActivity() {
    private data class Entry(val info: AppWidgetProviderInfo, val profileId: Long, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profiles = ProfileController(this).also { it.refresh() }
        val manager = getSystemService(AppWidgetManager::class.java)
        val entries = profiles.profiles().filter {
            it.descriptor.access == ProfileAccess.AVAILABLE && it.descriptor.kind != ProfileKind.PRIVATE
        }.flatMap { profile ->
            val profileId = profile.descriptor.profileId.toLong()
            manager.getInstalledProvidersForProfile(profile.user).mapNotNull { info ->
                val home = info.widgetCategory == 0 || info.widgetCategory and AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0
                if (!home) null else Entry(
                    info,
                    profileId,
                    info.loadLabel(packageManager).toString() + if (profile.descriptor.kind == ProfileKind.PERSONAL) {
                        ""
                    } else {
                        " - ${profileLabel(profile.descriptor.kind)}"
                    },
                )
            }
        }.sortedWith(compareBy<Entry> { it.label.lowercase() }.thenBy { it.info.provider.flattenToString() }.thenBy { it.profileId })
        if (entries.isEmpty()) {
            setContentView(TextView(this).apply {
                gravity = Gravity.CENTER
                text = getString(R.string.no_widgets)
            })
            return
        }
        val visible = entries.toMutableList()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_2,
            android.R.id.text1,
            visible.map { it.label }.toMutableList(),
        )
        val list = ListView(this).apply {
            id = R.id.widget_picker
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                val entry = visible[position]
                setResult(
                    RESULT_OK,
                    Intent()
                        .putExtras(intent.extras ?: Bundle())
                        .putExtra(EXTRA_PACKAGE, entry.info.provider.packageName)
                        .putExtra(EXTRA_CLASS, entry.info.provider.className)
                        .putExtra(EXTRA_PROFILE, entry.profileId),
                )
                finish()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(EditText(this@WidgetPickerActivity).apply {
                id = R.id.widget_search
                hint = getString(R.string.search_widgets)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val query = s?.toString().orEmpty()
                        visible.clear()
                        visible.addAll(entries.filter { it.label.contains(query, ignoreCase = true) })
                        adapter.clear()
                        adapter.addAll(visible.map { it.label })
                        adapter.notifyDataSetChanged()
                    }
                })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        })
    }

    private fun profileLabel(kind: ProfileKind) = getString(
        when (kind) {
            ProfileKind.PERSONAL -> R.string.profile_personal
            ProfileKind.WORK -> R.string.profile_work
            ProfileKind.PRIVATE -> R.string.profile_private
            ProfileKind.OTHER -> R.string.profile_other
        },
    )

    companion object {
        const val EXTRA_PACKAGE = "widget.package"
        const val EXTRA_CLASS = "widget.class"
        const val EXTRA_PROFILE = "widget.profile"
        const val EXTRA_PAGE = "widget.page"
        const val EXTRA_CELL_X = "widget.cellX"
        const val EXTRA_CELL_Y = "widget.cellY"
    }
}
