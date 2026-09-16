package com.caniko.cenix

import android.app.Activity
import android.app.AlertDialog
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal class IconPickerDialog(
    private val activity: Activity,
    private val packs: IconPackManager,
    private val preferredPack: String?,
    private val onSelected: (PackIconRef?) -> Unit,
) {
    private val search = EditText(activity).apply { hint = activity.getString(R.string.icon_search); isSingleLine = true }
    private val selector = Spinner(activity).apply { contentDescription = activity.getString(R.string.icon_pack) }
    private val status = TextView(activity).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private val grid = GridView(activity).apply { numColumns = GridView.AUTO_FIT; columnWidth = dp(88); stretchMode = GridView.STRETCH_COLUMN_WIDTH }
    private var selectedPack: String? = null
    private var names = emptyList<String>()
    private var visible = emptyList<String>()
    @Volatile private var generation = 0
    @Volatile private var closed = false

    fun show() {
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
            addView(selector, LinearLayout.LayoutParams(-1, dp(48)))
            addView(search, LinearLayout.LayoutParams(-1, dp(48)))
            addView(status, LinearLayout.LayoutParams(-1, -2))
            addView(grid, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels / 3).coerceAtLeast(dp(96))))
        }
        val dialog = AlertDialog.Builder(activity).setTitle(R.string.icon_pick).setView(body)
            .setNeutralButton(R.string.icon_reset) { _, _ -> onSelected(null) }
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnDismissListener { closed = true; generation++ }
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = visible.size
            override fun getItem(position: Int) = visible[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val cell = convertView ?: LayoutInflater.from(activity).inflate(R.layout.app_grid_cell, parent, false)
                val name = visible[position]
                val pack = selectedPack ?: return cell
                val key = "$pack/$name"
                val token = generation
                cell.tag = key
                cell.contentDescription = name
                cell.isScreenReaderFocusable = true
                cell.findViewById<TextView>(R.id.appLabel).apply { text = name; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                val image = cell.findViewById<ImageView>(R.id.appIcon)
                image.setImageDrawable(null)
                CenixExecutors.io {
                    if (closed || token != generation) return@io
                    val icon = packs.drawable(pack, name)
                    activity.runOnUiThread {
                        if (!closed && token == generation && cell.tag == key) image.setImageDrawable(icon)
                    }
                }
                return cell
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            selectedPack?.let { onSelected(PackIconRef(it, visible[position])) }
            dialog.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = filter()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        search.setOnKeyListener { _, code, event ->
            if (code == android.view.KeyEvent.KEYCODE_DPAD_DOWN && event.action == android.view.KeyEvent.ACTION_DOWN && visible.isNotEmpty()) {
                val focused = grid.requestFocus()
                if (focused) grid.setSelection(0)
                focused
            } else false
        }
        status.setText(R.string.icon_loading)
        dialog.show()
        CenixExecutors.io {
            val installed = packs.discover()
            activity.runOnUiThread {
                if (closed || activity.isDestroyed) return@runOnUiThread
                selector.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, installed.map { it.label })
                if (installed.isEmpty()) status.setText(R.string.icon_pack_unavailable)
                selector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = load(installed[position].packageName)
                }
                selector.setSelection(installed.indexOfFirst { it.packageName == preferredPack }.coerceAtLeast(0))
            }
        }
    }

    private fun load(pack: String) {
        selectedPack = pack
        val token = ++generation
        names = emptyList()
        filter()
        status.setText(R.string.icon_loading)
        CenixExecutors.io {
            val catalog = packs.catalog(pack)
            activity.runOnUiThread {
                if (closed || token != generation || activity.isDestroyed) return@runOnUiThread
                names = catalog
                filter()
            }
        }
    }

    private fun filter() {
        visible = matching(names, search.text.toString())
        (grid.adapter as BaseAdapter).notifyDataSetChanged()
        status.text = activity.getString(R.string.icon_results, visible.size)
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        internal fun matching(names: List<String>, query: String): List<String> =
            names.filter { it.contains(query.trim(), ignoreCase = true) }
    }
}
