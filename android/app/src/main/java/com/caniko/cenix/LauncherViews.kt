package com.caniko.cenix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.OverScroller
import kotlin.math.abs

class LauncherRoot @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    init {
        orientation = VERTICAL
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val dragLayer = findViewById<DragLayer>(R.id.dragLayer)
        return if (dragLayer?.isDragging == true && event.actionMasked != MotionEvent.ACTION_DOWN) {
            dragLayer.handleMotionEvent(event)
        } else {
            super.dispatchTouchEvent(event)
        }
    }
}

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
                val direction = if (delta < 0) 1 else -1
                val logical = if (layoutDirection == LAYOUT_DIRECTION_RTL) -direction else direction
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
        for (index in 0 until pages) {
            paint.alpha = if (index == current) 255 else 80
            paint.color = currentTextColor()
            canvas.drawCircle(start + index * gap, height / 2f, if (index == current) 4.5f else 3f, paint)
        }
    }

    private fun currentTextColor(): Int {
        val values = intArrayOf(android.R.attr.textColorPrimary)
        return context.obtainStyledAttributes(values).use { it.getColor(0, 0xff666666.toInt()) }
    }
}

class AllAppsView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ListView(context, attrs)

data class LauncherDrag(val app: LaunchableApp, val itemId: ULong?)
data class DropDestination(val container: CellLayout?, val cellX: Int = 0, val cellY: Int = 0, val remove: Boolean = false)

class DragLayer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    var pager: WorkspacePager? = null
    var hotseat: HotseatView? = null
    var removeTarget: View? = null
    var onDrop: ((LauncherDrag, DropDestination) -> Unit)? = null
    private var drag: LauncherDrag? = null
    private var source: View? = null
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
            }
            MotionEvent.ACTION_UP -> {
                val destination = destination(event.rawX, event.rawY)
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
        pager?.resetEdge()
    }

    private fun destination(rawX: Float, rawY: Float): DropDestination {
        if (removeTarget?.contains(rawX, rawY) == true) return DropDestination(null, remove = true)
        hotseat?.cellAt(rawX, rawY)?.let { return DropDestination(hotseat, it.first, it.second) }
        pager?.currentLayout?.cellAt(rawX, rawY)?.let { return DropDestination(pager?.currentLayout, it.first, it.second) }
        return DropDestination(null)
    }

    private fun View.contains(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + width, location[1] + height).contains(rawX.toInt(), rawY.toInt())
    }
}
