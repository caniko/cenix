package com.caniko.cenix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.text.InputFilter
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.Gravity
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.OverScroller
import android.widget.ScrollView
import android.widget.TextView
import com.caniko.cenix.uniffi.FolderMember
import kotlin.math.abs

class LauncherRoot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var shellVelocity: VelocityTracker? = null
    private var shellDownY = 0f
    var surface = LauncherSurface.HOME
    var onSurfaceRequested: ((LauncherSurface) -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val dragLayer = findViewById<DragLayer>(R.id.dragLayer)
        if (dragLayer?.isDragging == true && event.actionMasked != MotionEvent.ACTION_DOWN) {
            shellVelocity?.recycle()
            shellVelocity = null
            return dragLayer.handleMotionEvent(event)
        }
        if (findViewById<View>(R.id.context_popup) != null) {
            shellVelocity?.recycle()
            shellVelocity = null
            return super.dispatchTouchEvent(event)
        }
        shellVelocity = (shellVelocity ?: VelocityTracker.obtain()).also { it.addMovement(event) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> shellDownY = event.y
            MotionEvent.ACTION_MOVE -> requestSurface(event.y - shellDownY, 0f)
            MotionEvent.ACTION_UP -> {
                shellVelocity?.computeCurrentVelocity(1000)
                requestSurface(event.y - shellDownY, shellVelocity?.yVelocity ?: 0f)
                shellVelocity?.recycle()
                shellVelocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                shellVelocity?.recycle()
                shellVelocity = null
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun requestSurface(deltaY: Float, velocityY: Float) {
        LauncherShell.swipeTarget(surface, deltaY, velocityY, height)
            .takeIf { it != surface }
            ?.let {
                CenixLog.event(EventId.SHELL_TRANSITION, Severity.INFO, mapOf("source" to "swipe", "target" to it.name))
                onSurfaceRequested?.invoke(it)
            }
    }
}

class HomeSurface @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs)

class AllAppsContainer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs)

open class CellLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    var columns = 1
        set(value) { field = value.coerceAtLeast(1); requestLayout() }
    var rows = 1
        set(value) { field = value.coerceAtLeast(1); requestLayout() }

    class CellParams(width: Int, height: Int, val cellX: Int, val cellY: Int, val spanX: Int = 1, val spanY: Int = 1) :
        MarginLayoutParams(width, height)

    fun addCell(view: View, cellX: Int, cellY: Int, spanX: Int = 1, spanY: Int = 1) {
        addView(view, CellParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, cellX, cellY, spanX, spanY))
    }

    fun cellAt(rawX: Float, rawY: Float): Pair<Int, Int>? {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val x = rawX - location[0] - paddingLeft
        val y = rawY - location[1] - paddingTop
        val width = measuredWidth - paddingLeft - paddingRight
        val height = measuredHeight - paddingTop - paddingBottom
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        val visualX = (x * columns / width).toInt().coerceIn(0, columns - 1)
        val cellX = if (layoutDirection == LAYOUT_DIRECTION_RTL) columns - 1 - visualX else visualX
        return cellX to (y * rows / height).toInt().coerceIn(0, rows - 1)
    }

    fun viewAt(cellX: Int, cellY: Int): View? = (0 until childCount)
        .map(::getChildAt)
        .firstOrNull {
            val params = it.layoutParams as? CellParams
            params?.cellX == cellX && params.cellY == cellY
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = resolveSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val cellWidth = (width - paddingLeft - paddingRight) / columns
        val cellHeight = (height - paddingTop - paddingBottom) / rows
        for (index in 0 until childCount) {
            val params = getChildAt(index).layoutParams as CellParams
            getChildAt(index).measure(
                MeasureSpec.makeMeasureSpec(cellWidth * params.spanX, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellHeight * params.spanY, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val cellWidth = (width - paddingLeft - paddingRight) / columns
        val cellHeight = (height - paddingTop - paddingBottom) / rows
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            val params = child.layoutParams as CellParams
            val visualX = if (layoutDirection == LAYOUT_DIRECTION_RTL) columns - params.cellX - params.spanX else params.cellX
            val x = paddingLeft + visualX * cellWidth
            val y = paddingTop + params.cellY * cellHeight
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = CellParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, 0, 0)
    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams = MarginLayoutParams(context, attrs)
    override fun checkLayoutParams(params: LayoutParams?): Boolean = params is CellParams

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.Builder()
            .setRowCount(rows)
            .setColumnCount(columns)
            .setHierarchical(false)
            .build()
    }
}

class HotseatView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : CellLayout(context, attrs)

class WorkspacePager @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    var currentPage = 0
        private set
    var onPageChanged: ((Int) -> Unit)? = null
    var onBeyondEdge: (() -> Unit)? = null
    private var edgeRequestArmed = true

    val currentLayout: CellLayout? get() = getChildAt(currentPage) as? CellLayout

    fun replacePages(pages: List<CellLayout>, selected: Int = currentPage) {
        removeAllViews()
        pages.forEach(::addView)
        currentPage = selected.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        requestLayout()
    }

    fun setCurrentPage(page: Int, animate: Boolean = true) {
        val next = page.coerceIn(0, (childCount - 1).coerceAtLeast(0))
        if (next == currentPage && scrollX == pageOffset(next)) return
        currentPage = next
        val target = pageOffset(next)
        if (animate) scroller.startScroll(scrollX, 0, target - scrollX, 0, 220) else scrollTo(target, 0)
        onPageChanged?.invoke(next)
        invalidate()
    }

    fun edgeHover(x: Float) {
        val threshold = width * 0.12f
        when {
            x < threshold -> edge(if (layoutDirection == LAYOUT_DIRECTION_RTL) 1 else -1)
            x > width - threshold -> edge(if (layoutDirection == LAYOUT_DIRECTION_RTL) -1 else 1)
            else -> edgeRequestArmed = true
        }
    }

    fun resetEdge() { edgeRequestArmed = true }

    private fun edge(delta: Int) {
        val next = currentPage + delta
        if (next in 0 until childCount) setCurrentPage(next)
        else if (delta > 0 && edgeRequestArmed) {
            edgeRequestArmed = false
            onBeyondEdge?.invoke()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
        for (index in 0 until childCount) getChildAt(index).measure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (logical in 0 until childCount) {
            val visual = if (layoutDirection == LAYOUT_DIRECTION_RTL) childCount - 1 - logical else logical
            getChildAt(logical).layout(visual * width, 0, (visual + 1) * width, height)
        }
        scrollTo(pageOffset(currentPage), 0)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (rootView.findViewById<View>(R.id.context_popup) != null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = event.x
            MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > 16) return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocity = (velocity ?: VelocityTracker.obtain()).also { it.addMovement(event) }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> downX = event.x
            MotionEvent.ACTION_MOVE -> scrollTo((pageOffset(currentPage) + downX - event.x).toInt(), 0)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.computeCurrentVelocity(1000)
                val delta = event.x - downX
                val logical = LauncherShell.pageDelta(delta < 0, layoutDirection == LAYOUT_DIRECTION_RTL)
                setCurrentPage(if (abs(delta) > width / 5 || abs(velocity?.xVelocity ?: 0f) > 600) currentPage + logical else currentPage)
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> { setCurrentPage(currentPage + 1); true }
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> { setCurrentPage(currentPage - 1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun pageOffset(logical: Int): Int {
        val visual = if (layoutDirection == LAYOUT_DIRECTION_RTL) childCount - 1 - logical else logical
        return visual.coerceAtLeast(0) * width
    }

    override fun onSaveInstanceState(): Parcelable = SavedState(super.onSaveInstanceState(), currentPage)
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            currentPage = state.page
        } else super.onRestoreInstanceState(state)
    }

    private class SavedState : BaseSavedState {
        val page: Int
        constructor(superState: Parcelable?, page: Int) : super(superState) { this.page = page }
        constructor(parcel: Parcel) : super(parcel) { page = parcel.readInt() }
        override fun writeToParcel(out: Parcel, flags: Int) { super.writeToParcel(out, flags); out.writeInt(page) }
        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(source: Parcel) = SavedState(source)
            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }
}

class PageIndicator @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var pages = 1
        set(value) { field = value.coerceAtLeast(1); updateDescription(); invalidate() }
    var current = 0
        set(value) { field = value.coerceIn(0, pages - 1); updateDescription(); invalidate() }

    private fun updateDescription() {
        contentDescription = "Page ${current + 1} of $pages"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = 18f * resources.displayMetrics.density
        val start = width / 2f - (pages - 1) * gap / 2f
        for (visual in 0 until pages) {
            val index = if (layoutDirection == LAYOUT_DIRECTION_RTL) pages - 1 - visual else visual
            paint.alpha = if (index == current) 255 else 80
            paint.color = currentTextColor()
            canvas.drawCircle(start + visual * gap, height / 2f, if (index == current) 4.5f else 3f, paint)
        }
    }

    private fun currentTextColor(): Int {
        val values = intArrayOf(android.R.attr.textColorPrimary)
        return context.obtainStyledAttributes(values).use { it.getColor(0, 0xff666666.toInt()) }
    }
}

class AllAppsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListView(context, attrs)

enum class WidgetResizeEdge { LEFT, RIGHT, TOP, BOTTOM }

class WidgetFrame(context: Context) : FrameLayout(context) {
    private val controls = FrameLayout(context).apply { visibility = GONE }

    fun bind(
        content: View,
        onMove: (View) -> Unit,
        onResize: (WidgetResizeEdge, Float, Float) -> Unit,
        onRemove: () -> Unit,
    ) {
        removeAllViews()
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        controls.removeAllViews()
        controls.setBackgroundColor(Color.argb(48, 40, 110, 220))
        addHandle("<", "Resize left", Gravity.START or Gravity.CENTER_VERTICAL, WidgetResizeEdge.LEFT, onResize)
        addHandle(">", "Resize right", Gravity.END or Gravity.CENTER_VERTICAL, WidgetResizeEdge.RIGHT, onResize)
        addHandle("^", "Resize top", Gravity.TOP or Gravity.CENTER_HORIZONTAL, WidgetResizeEdge.TOP, onResize)
        addHandle("v", "Resize bottom", Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, WidgetResizeEdge.BOTTOM, onResize)
        controls.addView(Button(context).apply {
            text = context.getString(R.string.move_widget)
            contentDescription = context.getString(R.string.move_widget)
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) onMove(this@WidgetFrame)
                false
            }
        }, LayoutParams(dp(72), dp(48), Gravity.START or Gravity.TOP))
        controls.addView(Button(context).apply {
            text = "×"
            contentDescription = context.getString(R.string.remove_widget)
            setOnClickListener { onRemove() }
        }, LayoutParams(dp(48), dp(48), Gravity.END or Gravity.TOP))
        addView(controls, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        val activate = OnLongClickListener {
            controls.visibility = VISIBLE
            isActivated = true
            true
        }
        setOnLongClickListener(activate)
        content.setOnLongClickListener(activate)
    }

    private fun addHandle(
        text: String,
        description: String,
        gravity: Int,
        edge: WidgetResizeEdge,
        onResize: (WidgetResizeEdge, Float, Float) -> Unit,
    ) {
        var downX = 0f
        var downY = 0f
        controls.addView(Button(context).apply {
            this.text = text
            contentDescription = description
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                    }
                    MotionEvent.ACTION_UP -> onResize(edge, event.rawX - downX, event.rawY - downY)
                }
                true
            }
        }, LayoutParams(dp(48), dp(48), gravity))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class FolderIconView(context: Context) : LinearLayout(context) {
    private val preview = GridLayout(context).apply { columnCount = 2; rowCount = 2 }
    private val label = TextView(context).apply { gravity = android.view.Gravity.CENTER; maxLines = 1 }

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        val size = dp(36)
        addView(preview, LayoutParams(size, size))
        addView(label, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        isFocusable = true
    }

    fun bind(title: String, count: Int, icons: List<Drawable?>) {
        preview.removeAllViews()
        repeat(4) { index ->
            preview.addView(ImageView(context).apply {
                setImageDrawable(icons.getOrNull(index))
                contentDescription = null
            }, GridLayout.LayoutParams().apply { width = dp(18); height = dp(18) })
        }
        label.text = title.ifEmpty { context.getString(R.string.folder) }
        contentDescription = context.getString(R.string.folder_description, label.text, count)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

class FolderPopup(context: Context) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(resolveBackgroundColor())
            cornerRadius = dp(20).toFloat()
        }
        elevation = dp(12).toFloat()
        isClickable = true
    }
    private val title = EditText(context).apply {
        hint = context.getString(R.string.folder)
        maxLines = 1
        imeOptions = EditorInfo.IME_ACTION_DONE
        filters = arrayOf(InputFilter { source, start, end, dest, dstart, dend ->
            val candidate = dest.substring(0, dstart) + source.subSequence(start, end) + dest.substring(dend)
            if (candidate.codePointCount(0, candidate.length) <= 80 && candidate.codePoints().noneMatch(Character::isISOControl)) null else ""
        })
    }
    private val members = GridLayout(context).apply { columnCount = 3 }
    private val memberScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(members, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }
    private val close = Button(context).apply { text = context.getString(R.string.close_folder) }
    var folderId: ULong = 0UL
        private set
    var onClose: (() -> Unit)? = null
    var onRename: ((String) -> Unit)? = null
    var onLaunch: ((FolderEntry) -> Unit)? = null
    var onDirectDrag: ((View, FolderEntry) -> Unit)? = null
    var onMove: ((FolderMember, UInt) -> Unit)? = null
    var onRemove: ((FolderMember) -> Unit)? = null

    init {
        id = R.id.folder_popup
        title.id = R.id.folder_title
        members.id = R.id.folder_members
        isClickable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setOnClickListener { onClose?.invoke() }
        panel.setOnClickListener { }
        panel.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(memberScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(76)))
        panel.addView(close, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(panel, LayoutParams(dp(320), LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER))
        close.setOnClickListener { onClose?.invoke() }
        title.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                commitTitle()
                title.clearFocus()
                true
            } else false
        }
        title.setOnFocusChangeListener { _, focused -> if (!focused) commitTitle() }
    }

    fun bind(
        id: ULong,
        currentTitle: String,
        entries: List<FolderEntry>,
    ) {
        folderId = id
        title.setText(currentTitle)
        title.tag = currentTitle
        members.removeAllViews()
        memberScroll.layoutParams = (memberScroll.layoutParams as LinearLayout.LayoutParams).apply {
            height = (((entries.size + 2) / 3).coerceAtLeast(1) * dp(76)).coerceAtMost(dp(300))
        }
        entries.forEachIndexed { index, entry ->
            val member = entry.member
            val cell = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                isFocusable = true
                contentDescription = "${entry.label}, rank ${index + 1}"
                addView(ImageView(context).apply {
                    setImageDrawable(entry.icon)
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(context).apply {
                    text = entry.label
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1
                }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                setOnClickListener { onLaunch?.invoke(entry) }
                setOnLongClickListener { onDirectDrag?.invoke(this, entry); true }
                accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        if (index > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_left, context.getString(R.string.move_earlier)))
                        if (index + 1 < entries.size) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_move_right, context.getString(R.string.move_later)))
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.action_remove_from_folder, context.getString(R.string.remove_from_folder)))
                    }

                    override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean = when (action) {
                        R.id.action_move_left -> { onMove?.invoke(member, (index - 1).coerceAtLeast(0).toUInt()); true }
                        R.id.action_move_right -> { onMove?.invoke(member, (index + 1).coerceAtMost(entries.lastIndex).toUInt()); true }
                        R.id.action_remove_from_folder -> { onRemove?.invoke(member); true }
                        else -> super.performAccessibilityAction(host, action, args)
                    }
                }
            }
            members.addView(cell, GridLayout.LayoutParams().apply { width = dp(88); height = dp(76) })
        }
        contentDescription = context.getString(R.string.folder_description, currentTitle.ifEmpty { context.getString(R.string.folder) }, entries.size)
    }

    fun rankAt(rawX: Float, rawY: Float): UInt? {
        val location = IntArray(2)
        members.getLocationOnScreen(location)
        if (rawX < location[0] || rawY < location[1] || rawX >= location[0] + members.width || rawY >= location[1] + members.height) return null
        for (index in 0 until members.childCount) {
            val child = members.getChildAt(index)
            child.getLocationOnScreen(location)
            if (Rect(location[0], location[1], location[0] + child.width, location[1] + child.height).contains(rawX.toInt(), rawY.toInt())) {
                return index.toUInt()
            }
        }
        return members.childCount.toUInt()
    }

    fun clearTitleFocus(): Boolean {
        if (!title.hasFocus()) return false
        title.clearFocus()
        return true
    }

    fun focusTitle() {
        title.requestFocus()
        title.setSelection(title.text?.length ?: 0)
        context.getSystemService(InputMethodManager::class.java).showSoftInput(title, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun commitTitle() {
        val previous = title.tag as? String ?: ""
        val next = title.text?.toString().orEmpty()
        if (next != previous) {
            title.tag = next
            onRename?.invoke(next)
        }
    }

    private fun resolveBackgroundColor(): Int = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).use {
        it.getColor(0, Color.WHITE)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

data class FolderEntry(
    val member: FolderMember,
    val label: String,
    val icon: Drawable?,
    val app: LaunchableApp? = null,
    val shortcut: LauncherShortcut? = null,
)

class ContextPopup(context: Context) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = GradientDrawable().apply {
            setColor(resolveBackgroundColor())
            cornerRadius = dp(16).toFloat()
        }
        elevation = dp(12).toFloat()
        isClickable = true
        isFocusable = true
    }
    private val title = TextView(context).apply {
        maxLines = 2
        textSize = 18f
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }
    private val shortcuts = LinearLayout(context).apply {
        id = R.id.context_shortcuts
        orientation = LinearLayout.VERTICAL
    }
    private val drag = action(R.id.context_drag, R.string.drag)
    private val appInfo = action(R.id.context_app_info, R.string.app_info)
    private val uninstall = action(R.id.context_uninstall, R.string.uninstall)
    private val remove = action(R.id.context_remove, R.string.remove)
    var onClose: (() -> Unit)? = null
    var onDragApp: (() -> Unit)? = null
    var onAppInfo: (() -> Unit)? = null
    var onUninstall: (() -> Unit)? = null
    var onRemove: (() -> Unit)? = null
    var onLaunchShortcut: ((LauncherShortcut) -> Unit)? = null
    var onDragShortcut: ((View, LauncherShortcut) -> Unit)? = null
    var onPinShortcut: ((LauncherShortcut) -> Unit)? = null

    init {
        id = R.id.context_popup
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = context.getString(R.string.context_actions)
        setOnClickListener { onClose?.invoke() }
        panel.setOnClickListener { }
        panel.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(shortcuts, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        panel.addView(drag)
        panel.addView(appInfo)
        panel.addView(uninstall)
        panel.addView(remove)
        addView(panel, LayoutParams(dp(280), LayoutParams.WRAP_CONTENT))
        drag.setOnClickListener { onDragApp?.invoke() }
        appInfo.setOnClickListener { onAppInfo?.invoke() }
        uninstall.setOnClickListener { onUninstall?.invoke() }
        remove.setOnClickListener { onRemove?.invoke() }
    }

    fun bind(label: String, entries: List<LauncherShortcut>, canUninstall: Boolean, canRemove: Boolean) {
        title.text = ShortcutCatalog.safeLabel(label)
        shortcuts.removeAllViews()
        entries.forEach { shortcut ->
            val row = LinearLayout(context).apply {
                id = R.id.shortcut_row
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                minimumHeight = dp(48)
                isFocusable = true
                isEnabled = shortcut.enabled
                contentDescription = if (shortcut.enabled) shortcut.label else "${shortcut.label}, ${context.getString(R.string.shortcut_unavailable)}"
                addView(ImageView(context).apply {
                    setImageDrawable(shortcut.icon)
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(context).apply {
                    text = shortcut.label
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    id = R.id.shortcut_pin
                    text = context.getString(R.string.pin_shortcut)
                    isEnabled = shortcut.enabled
                    setOnClickListener { onPinShortcut?.invoke(shortcut) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, dp(48)))
                setOnClickListener { if (shortcut.enabled) onLaunchShortcut?.invoke(shortcut) }
                setOnLongClickListener {
                    if (shortcut.enabled) onDragShortcut?.invoke(this, shortcut)
                    shortcut.enabled
                }
            }
            shortcuts.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        uninstall.visibility = if (canUninstall) VISIBLE else GONE
        remove.visibility = if (canRemove) VISIBLE else GONE
    }

    fun anchor(source: View) {
        post {
            val sourceLocation = IntArray(2)
            val ownLocation = IntArray(2)
            source.getLocationOnScreen(sourceLocation)
            getLocationOnScreen(ownLocation)
            val params = panel.layoutParams as LayoutParams
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
            params.leftMargin = (sourceLocation[0] - ownLocation[0]).coerceIn(0, (width - panel.measuredWidth).coerceAtLeast(0))
            params.topMargin = (sourceLocation[1] - ownLocation[1] + source.height)
                .coerceIn(0, (height - panel.measuredHeight).coerceAtLeast(0))
            panel.layoutParams = params
            panel.requestFocus()
        }
    }

    private fun action(id: Int, label: Int) = Button(context).apply {
        this.id = id
        text = context.getString(label)
        minimumHeight = dp(48)
    }

    private fun resolveBackgroundColor(): Int = context.obtainStyledAttributes(intArrayOf(android.R.attr.colorBackground)).use {
        it.getColor(0, Color.WHITE)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

data class CellTarget(val itemId: ULong, val folderId: ULong? = null)
data class LauncherDrag(
    val app: LaunchableApp?,
    val itemId: ULong?,
    val shortcut: LauncherShortcut? = null,
    val sourceFolderId: ULong? = null,
    val isFolder: Boolean = false,
    val isWidget: Boolean = false,
)
data class DropDestination(
    val container: CellLayout?,
    val cellX: Int = 0,
    val cellY: Int = 0,
    val remove: Boolean = false,
    val targetItemId: ULong? = null,
    val folderId: ULong? = null,
    val folderRank: UInt? = null,
    val createFolder: Boolean = false,
)

class DragLayer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var pager: WorkspacePager? = null
    var hotseat: HotseatView? = null
    var removeTarget: View? = null
    var folderPopup: FolderPopup? = null
    var onDrop: ((LauncherDrag, DropDestination) -> Unit)? = null
    var onFolderHover: ((ULong) -> Unit)? = null
    private var drag: LauncherDrag? = null
    private var source: View? = null
    private var activeTarget: View? = null
    private var hoverFolderId: ULong? = null
    private val handler = Handler(Looper.getMainLooper())
    private val openFolder = Runnable { hoverFolderId?.let { onFolderHover?.invoke(it) } }
    val isDragging: Boolean get() = drag != null

    fun beginDrag(view: View, payload: LauncherDrag): Boolean {
        drag = payload
        source = view
        view.alpha = 0.45f
        removeTarget?.visibility = VISIBLE
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        val payload = drag ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val location = IntArray(2)
                pager?.getLocationOnScreen(location)
                pager?.edgeHover(event.rawX - location[0])
                updateFolderTarget(payload, event.rawX, event.rawY)
            }
            MotionEvent.ACTION_UP -> {
                val destination = destination(payload, event.rawX, event.rawY)
                finishDrag()
                onDrop?.invoke(payload, destination)
            }
            MotionEvent.ACTION_CANCEL -> cancel("touch")
        }
        return true
    }

    fun cancel(category: String) {
        if (drag != null) CenixLog.event(EventId.DRAG_CANCEL, Severity.INFO, mapOf("category" to category))
        finishDrag()
    }

    private fun finishDrag() {
        source?.alpha = 1f
        removeTarget?.visibility = GONE
        drag = null
        source = null
        activeTarget?.isActivated = false
        activeTarget?.scaleX = 1f
        activeTarget?.scaleY = 1f
        activeTarget = null
        hoverFolderId = null
        handler.removeCallbacks(openFolder)
        pager?.resetEdge()
    }

    private fun destination(payload: LauncherDrag, rawX: Float, rawY: Float): DropDestination {
        if (removeTarget?.contains(rawX, rawY) == true) return DropDestination(null, remove = true)
        if (!payload.isWidget) {
            folderPopup?.rankAt(rawX, rawY)?.let { return DropDestination(null, folderId = folderPopup?.folderId, folderRank = it) }
            hotseat?.cellAt(rawX, rawY)?.let { return cellDestination(hotseat, it.first, it.second, rawX, rawY, true) }
        }
        pager?.currentLayout?.cellAt(rawX, rawY)?.let {
            return cellDestination(pager?.currentLayout, it.first, it.second, rawX, rawY, !payload.isWidget)
        }
        return DropDestination(null)
    }

    private fun cellDestination(layout: CellLayout?, x: Int, y: Int, rawX: Float, rawY: Float, allowFolder: Boolean): DropDestination {
        val view = layout?.viewAt(x, y)
        val target = view?.tag as? CellTarget
        return DropDestination(
            container = layout,
            cellX = x,
            cellY = y,
            targetItemId = target?.itemId,
            folderId = target?.folderId,
            createFolder = allowFolder && target != null && target.folderId == null && view.centralContains(rawX, rawY),
        )
    }

    private fun updateFolderTarget(payload: LauncherDrag, rawX: Float, rawY: Float) {
        val destination = destination(payload, rawX, rawY)
        val target = when {
            destination.folderId != null && destination.folderRank == null && !payload.isFolder && !payload.isWidget -> destination.folderId
            else -> null
        }
        val layout = destination.container
        val view = if ((destination.createFolder && !payload.isFolder && !payload.isWidget) || target != null) layout?.viewAt(destination.cellX, destination.cellY) else null
        if (view !== activeTarget) {
            activeTarget?.isActivated = false
            activeTarget?.scaleX = 1f
            activeTarget?.scaleY = 1f
            activeTarget = view
            activeTarget?.isActivated = true
            activeTarget?.scaleX = 1.08f
            activeTarget?.scaleY = 1.08f
        }
        if (target != hoverFolderId) {
            handler.removeCallbacks(openFolder)
            hoverFolderId = target
            if (target != null) handler.postDelayed(openFolder, 800)
        }
    }

    private fun View.contains(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height).contains(rawX.toInt(), rawY.toInt())
    }

    private fun View?.centralContains(rawX: Float, rawY: Float): Boolean {
        this ?: return false
        val location = IntArray(2)
        getLocationOnScreen(location)
        return rawX in (location[0] + width * 0.25f)..(location[0] + width * 0.75f) &&
            rawY in (location[1] + height * 0.25f)..(location[1] + height * 0.75f)
    }
}
