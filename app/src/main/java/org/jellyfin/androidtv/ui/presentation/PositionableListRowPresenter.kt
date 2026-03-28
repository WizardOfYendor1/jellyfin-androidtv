package org.jellyfin.androidtv.ui.presentation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ObjectAdapter
import androidx.leanback.widget.RowPresenter
import androidx.leanback.widget.HorizontalGridView

private const val DEFAULT_PAGE_SIZE = 5
private const val PAGE_START_DELAY_MS = 350L
private const val PAGE_REPEAT_INTERVAL_MS = 650L
private const val PAGE_REPEAT_SAFETY_MS = 900L

class PositionableListRowPresenter : CustomListRowPresenter {
	enum class HoldNavigationMode {
		NONE,
		QUICK_CHANNEL_PAGING,
	}

	private var viewHolder: ViewHolder? = null
	private var rowAdapter: ObjectAdapter? = null
	private var pendingPosition: Int = -1
	private val trapFocus: Boolean
	private val holdHandler = Handler(Looper.getMainLooper())
	private var holdNavigationMode = HoldNavigationMode.NONE
	private var holdPagingActive = false
	private var holdPagingInitialJumpPending = false
	private var holdPagingDirection = 0
	private var lastHoldRepeatTime = 0L

	private val holdPagingRunnable = object : Runnable {
		override fun run() {
			val grid = viewHolder?.gridView ?: return
			if (!holdPagingActive) return
			if (SystemClock.uptimeMillis() - lastHoldRepeatTime > PAGE_REPEAT_SAFETY_MS) {
				stopHoldPaging()
				return
			}

			val includeInitialOffset = holdPagingInitialJumpPending
			pageSelection(grid, includeInitialOffset = includeInitialOffset)
			holdPagingInitialJumpPending = false
			holdHandler.postDelayed(this, PAGE_REPEAT_INTERVAL_MS)
		}
	}

	constructor() : this(padding = null, trapFocus = false)
	constructor(padding: Int?) : this(padding, trapFocus = false)
	constructor(padding: Int? = null, trapFocus: Boolean = false) : super(padding) {
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
		rowAdapter = (item as? ListRow)?.adapter
		val grid = holder.gridView
		if (trapFocus) {
			// Prevent focus from escaping the grid at either boundary so the user
			// stays inside the popup (channel changer / chapter selector).
			// Uses the adapter size to detect boundaries rather than hard-coding
			// position 0, so this works regardless of the adapter's centering strategy.
			grid.setOnKeyInterceptListener { event ->
				if (handleHoldPagingEvent(grid, event)) {
					return@setOnKeyInterceptListener true
				}

				val pos = grid.selectedPosition
				val size = rowAdapter?.size() ?: 0
				when (event.keyCode) {
					KeyEvent.KEYCODE_DPAD_LEFT -> pos <= 0
					KeyEvent.KEYCODE_DPAD_RIGHT -> size > 0 && pos >= size - 1
					else -> false
				}
			}
		}

		if (pendingPosition >= 0) {
			val pos = pendingPosition
			pendingPosition = -1
			// Defer until after layout so the grid has items to scroll to.
			grid.post {
				grid.selectedPosition = pos
			}
		}
	}

	override fun onUnbindRowViewHolder(holder: RowPresenter.ViewHolder) {
		stopHoldPaging()
		if (holder === viewHolder) viewHolder = null
		rowAdapter = null
		super.onUnbindRowViewHolder(holder)
	}

	private fun handleHoldPagingEvent(grid: HorizontalGridView, event: KeyEvent): Boolean {
		if (holdNavigationMode != HoldNavigationMode.QUICK_CHANNEL_PAGING) {
			return false
		}

		val direction = when (event.keyCode) {
			KeyEvent.KEYCODE_DPAD_LEFT -> -1
			KeyEvent.KEYCODE_DPAD_RIGHT -> 1
			else -> 0
		}

		if (direction == 0) {
			if (holdPagingActive && event.action == KeyEvent.ACTION_DOWN) {
				stopHoldPaging()
			}
			return false
		}

		return when (event.action) {
			KeyEvent.ACTION_DOWN -> {
				if (event.repeatCount == 0) {
					if (holdPagingActive && holdPagingDirection != direction) {
						stopHoldPaging()
					}
					false
				} else {
					lastHoldRepeatTime = SystemClock.uptimeMillis()
					if (!holdPagingActive || holdPagingDirection != direction) {
						startHoldPaging(grid, direction)
					}
					true
				}
			}
			KeyEvent.ACTION_UP -> {
				if (holdPagingActive && holdPagingDirection == direction) {
					stopHoldPaging()
				}
				false
			}
			else -> false
		}
	}

	private fun startHoldPaging(grid: HorizontalGridView, direction: Int) {
		stopHoldPaging()
		holdPagingActive = true
		holdPagingInitialJumpPending = true
		holdPagingDirection = direction
		lastHoldRepeatTime = SystemClock.uptimeMillis()
		holdHandler.postDelayed(holdPagingRunnable, PAGE_START_DELAY_MS)
	}

	private fun stopHoldPaging() {
		holdPagingActive = false
		holdPagingInitialJumpPending = false
		holdPagingDirection = 0
		holdHandler.removeCallbacks(holdPagingRunnable)
	}

	private fun pageSelection(grid: HorizontalGridView, includeInitialOffset: Boolean) {
		val size = rowAdapter?.size() ?: return
		if (size <= 0) return

		val pageSize = calculateVisiblePageSize(grid)
		val step = if (includeInitialOffset) {
			(pageSize - 1).coerceAtLeast(1)
		} else {
			pageSize
		}

		val target = (grid.selectedPosition + (step * holdPagingDirection))
			.coerceIn(0, size - 1)
		if (target != grid.selectedPosition) {
			grid.selectedPosition = target
		}
	}

	private fun calculateVisiblePageSize(grid: HorizontalGridView): Int {
		if (grid.width <= 0 || grid.childCount == 0) return DEFAULT_PAGE_SIZE

		val visibleLeft = grid.paddingLeft
		val visibleRight = grid.width - grid.paddingRight
		var fullyVisibleChildren = 0

		for (index in 0 until grid.childCount) {
			val child = grid.getChildAt(index) ?: continue
			if (child.left >= visibleLeft && child.right <= visibleRight) {
				fullyVisibleChildren++
			}
		}

		return if (fullyVisibleChildren > 0) fullyVisibleChildren else DEFAULT_PAGE_SIZE
	}

	/**
	 * Clear the cached viewHolder so the next [position] set falls through
	 * to [pendingPosition]. Call this after removing a row from the adapter
	 * when RecyclerView defers the actual unbind to the next layout pass.
	 */
	fun invalidate() {
		stopHoldPaging()
		viewHolder = null
		rowAdapter = null
	}

	fun setHoldNavigationMode(mode: HoldNavigationMode) {
		holdNavigationMode = mode
		if (mode != HoldNavigationMode.QUICK_CHANNEL_PAGING) {
			stopHoldPaging()
		}
	}

	var position: Int
		get() = viewHolder?.gridView?.selectedPosition ?: pendingPosition
		set(value) {
			val grid = viewHolder?.gridView
			if (grid != null && grid.isAttachedToWindow) {
				grid.selectedPosition = value
				pendingPosition = -1
			} else if (grid != null) {
				// Grid is bound but not yet attached to the window (e.g. the
				// row was just added to the adapter and layout hasn't run).
				// Post so the position is applied once the grid is laid out.
				pendingPosition = -1
				grid.post { grid.selectedPosition = value }
			} else {
				// Grid not bound yet — store for onBindRowViewHolder.
				pendingPosition = value
			}
		}
}
