package org.jellyfin.androidtv.ui.presentation

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.RowPresenter
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

private const val ITEM_VIEW_CACHE_SIZE = 11

/** Scroll speed in dp/sec during held-key pixel scrolling. */
private const val SCROLL_SPEED_DP_PER_SEC = 600f

/** Frame interval for pixel scroll timer (~30fps). */
private const val SCROLL_FRAME_MS = 33L

/** Stop pixel scrolling if no key repeats arrive within this window. */
private const val KEY_REPEAT_SAFETY_MS = 200L

class PositionableListRowPresenter : CustomListRowPresenter {
	private var viewHolder: ViewHolder? = null
	private var pendingPosition: Int = -1
	private val trapFocus: Boolean
	private var scrollState: Int = RecyclerView.SCROLL_STATE_IDLE

	// Pixel scrolling state
	private var pixelScrollActive = false
	private var pixelScrollDirection = 0
	private var pixelScrollPerFrame = 0
	private var lastKeyRepeatTime = 0L
	private var savedDescendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
	private var savedGridFocusable = false
	private val scrollHandler = Handler(Looper.getMainLooper())
	private var scrollListener: RecyclerView.OnScrollListener? = null
	private var centerDecoration: RecyclerView.ItemDecoration? = null

	/** Called when pixel scrolling starts. Use to clear transient UI (e.g. guide text). */
	var onScrollStart: Runnable? = null

	private val scrollTick = object : Runnable {
		override fun run() {
			val grid = viewHolder?.gridView ?: return
			if (!pixelScrollActive) return
			if (System.currentTimeMillis() - lastKeyRepeatTime > KEY_REPEAT_SAFETY_MS) {
				stopPixelScroll(grid)
				return
			}
			grid.scrollBy(pixelScrollDirection * pixelScrollPerFrame, 0)
			scrollHandler.postDelayed(this, SCROLL_FRAME_MS)
		}
	}

	/** True while the user is actively pixel scrolling or the grid is animating. */
	val isScrolling: Boolean
		get() = pixelScrollActive || scrollState != RecyclerView.SCROLL_STATE_IDLE

	constructor() : this(padding = null, trapFocus = false)
	constructor(padding: Int?) : this(padding, trapFocus = false)
	constructor(padding: Int? = null, trapFocus: Boolean = false) : super(
		padding,
		if (trapFocus) FocusHighlight.ZOOM_FACTOR_NONE else FocusHighlight.ZOOM_FACTOR_LARGE,
		false
	) {
		this.trapFocus = trapFocus
	}

	init {
		shadowEnabled = false
	}

	override fun isUsingDefaultShadow() = false

	override fun onSelectLevelChanged(holder: RowPresenter.ViewHolder) = Unit

	override fun onBindRowViewHolder(holder: RowPresenter.ViewHolder, item: Any) {
		super.onBindRowViewHolder(holder, item)
		if (holder !is ViewHolder) return

		viewHolder = holder
		val grid = holder.gridView
		scrollState = RecyclerView.SCROLL_STATE_IDLE
		if (trapFocus) {
			grid.setItemViewCacheSize(ITEM_VIEW_CACHE_SIZE)
			grid.setHasFixedSize(true)

			val density = grid.resources.displayMetrics.density
			pixelScrollPerFrame = (SCROLL_SPEED_DP_PER_SEC * density / 30f).toInt().coerceAtLeast(1)

			scrollListener?.let { grid.removeOnScrollListener(it) }
			val listener = object : RecyclerView.OnScrollListener() {
				override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
					scrollState = newState
				}
			}
			scrollListener = listener
			grid.addOnScrollListener(listener)

			centerDecoration?.let { grid.removeItemDecoration(it) }
			val strokeWidth = 2f * density
			val decoration = object : RecyclerView.ItemDecoration() {
				private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
					style = Paint.Style.STROKE
					color = Color.WHITE
					this.strokeWidth = strokeWidth
				}

				override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
					if (!pixelScrollActive || parent.childCount == 0) return
					val child = parent.getChildAt(0) ?: return
					val cardWidth = child.width
					val centerX = parent.width / 2f
					val half = strokeWidth / 2f
					val left = centerX - cardWidth / 2f + half
					val top = child.top.toFloat() + half
					val right = centerX + cardWidth / 2f - half
					val bottom = child.bottom.toFloat() - half
					c.drawRect(left, top, right, bottom, paint)
				}
			}
			centerDecoration = decoration
			grid.addItemDecoration(decoration)

			grid.setOnKeyInterceptListener { event -> handleKeyEvent(grid, event) }
		}

		if (pendingPosition >= 0) {
			val pos = pendingPosition
			pendingPosition = -1
			grid.post { grid.selectedPosition = pos }
		}
	}

	private fun handleKeyEvent(grid: HorizontalGridView, event: KeyEvent): Boolean {
		val isDpadLR = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
			event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

		if (!isDpadLR) {
			if (pixelScrollActive && event.action == KeyEvent.ACTION_DOWN) {
				stopPixelScroll(grid)
			}
			return false
		}

		val pos = grid.selectedPosition
		val size = grid.adapter?.itemCount ?: 0
		val direction = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1

		val atBoundary = when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT -> pos <= 0
			KeyEvent.KEYCODE_DPAD_RIGHT -> size > 0 && pos >= size - 1
			else -> false
		}
		if (atBoundary) return true

		return when {
			// First press: let Leanback handle normally (instant focus snap)
			event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> false

			// Held key repeat: start/continue pixel scrolling
			event.action == KeyEvent.ACTION_DOWN && event.repeatCount > 0 -> {
				lastKeyRepeatTime = System.currentTimeMillis()
				if (!pixelScrollActive) {
					startPixelScroll(grid, direction)
				} else if (pixelScrollDirection != direction) {
					pixelScrollDirection = direction
				}
				true
			}

			// Key release: stop pixel scrolling and snap to nearest item
			event.action == KeyEvent.ACTION_UP -> {
				if (pixelScrollActive) {
					stopPixelScroll(grid)
				}
				false
			}

			else -> false
		}
	}

	private fun startPixelScroll(grid: HorizontalGridView, direction: Int) {
		pixelScrollActive = true
		pixelScrollDirection = direction
		grid.stopScroll()
		savedDescendantFocusability = grid.descendantFocusability
		savedGridFocusable = grid.isFocusable
		grid.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
		// Move focus from the child card to the grid itself so the white border
		// and zoom disappear during scrolling.
		grid.isFocusable = true
		grid.requestFocus()
		onScrollStart?.run()
		scrollHandler.post(scrollTick)
	}

	private fun stopPixelScroll(grid: HorizontalGridView) {
		pixelScrollActive = false
		pixelScrollDirection = 0
		lastKeyRepeatTime = 0L
		scrollHandler.removeCallbacks(scrollTick)
		grid.descendantFocusability = savedDescendantFocusability
		grid.isFocusable = savedGridFocusable
		snapToNearestItem(grid)
	}

	private fun snapToNearestItem(grid: HorizontalGridView) {
		val centerX = grid.width / 2
		var closestPos = RecyclerView.NO_POSITION
		var closestDist = Int.MAX_VALUE

		for (i in 0 until grid.childCount) {
			val child = grid.getChildAt(i)
			val pos = grid.getChildAdapterPosition(child)
			if (pos == RecyclerView.NO_POSITION) continue
			val childCenter = (child.left + child.right) / 2
			val dist = abs(childCenter - centerX)
			if (dist < closestDist) {
				closestDist = dist
				closestPos = pos
			}
		}

		if (closestPos != RecyclerView.NO_POSITION) {
			grid.selectedPosition = closestPos
		}
	}

	override fun onUnbindRowViewHolder(holder: RowPresenter.ViewHolder) {
		if (holder === viewHolder) {
			cleanupPixelScroll()
			viewHolder?.gridView?.let { grid ->
				scrollListener?.let { grid.removeOnScrollListener(it) }
				centerDecoration?.let { grid.removeItemDecoration(it) }
			}
			scrollListener = null
			centerDecoration = null
			viewHolder = null
		}
		super.onUnbindRowViewHolder(holder)
	}

	private fun cleanupPixelScroll() {
		if (pixelScrollActive) {
			pixelScrollActive = false
			pixelScrollDirection = 0
			scrollHandler.removeCallbacks(scrollTick)
			viewHolder?.gridView?.let {
				it.descendantFocusability = savedDescendantFocusability
				it.isFocusable = savedGridFocusable
			}
		}
	}

	/** Clear cached viewHolder so the next [position] set uses [pendingPosition]. */
	fun invalidate() {
		cleanupPixelScroll()
		viewHolder = null
	}

	var position: Int
		get() = viewHolder?.gridView?.selectedPosition ?: pendingPosition
		set(value) {
			val grid = viewHolder?.gridView
			if (grid != null && grid.isAttachedToWindow) {
				grid.selectedPosition = value
				pendingPosition = -1
			} else if (grid != null) {
				pendingPosition = -1
				grid.post { grid.selectedPosition = value }
			} else {
				pendingPosition = value
			}
		}
}
