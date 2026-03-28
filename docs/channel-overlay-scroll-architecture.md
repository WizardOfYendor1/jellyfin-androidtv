# Quick Channel Changer Scroll Architecture

This document covers the scrolling implementation for the quick channel changer overlay (and chapter selector) in `CustomPlaybackOverlayFragment`. It records the Leanback scrolling internals discovered during development, the approaches attempted, why each failed or succeeded, and the final implementation.

## Context

The quick channel changer is a horizontal row of channel cards that appears when the user presses DPAD-down during live TV playback. It uses a `CircularObjectAdapter` wrapping the real channel list at 1000x to simulate infinite circular scrolling, presented in a `RowsSupportFragment` via `PositionableListRowPresenter`.

The goal: when the user holds DPAD left/right, items scroll at a comfortable reading speed — smooth, consistent, and not too fast.

## Key Files

| File | Role |
|------|------|
| `PositionableListRowPresenter.kt` | Custom `ListRowPresenter` with focus trapping, scroll speed control, and `isScrolling` property |
| `CustomPlaybackOverlayFragment.java` | Overlay UI — hosts the popup row, selection listener, debounced text updates |
| `CircularObjectAdapter.kt` | Wraps a real adapter at `realSize * 1000` for circular scrolling. `centerPosition(idx)` places the user at the middle of the virtual range |
| `CustomListRowPresenter.kt` | Base presenter — disables shadows, overrides `onSelectLevelChanged` to `Unit` |
| `ChannelCardView.java` | Individual channel card view |
| `view_card_channel.xml` | Card layout (200dp wide, 14dp padding) |
| `channel_card_background.xml` | Selector drawable — white 2dp border on focus state |

## Leanback Scrolling Internals

### How DPAD Navigation Works in HorizontalGridView

1. Key event arrives at `BaseGridView.dispatchKeyEvent()`
2. `OnKeyInterceptListener` runs first — returning `true` consumes the event
3. If not consumed, `super.dispatchKeyEvent()` → RecyclerView handles focus navigation
4. `GridLayoutManager` moves focus to the adjacent item via **instant focus snap** (not smooth scroll)
5. The grid may scroll to center the newly focused item, but this centering is also fast/instant for adjacent items

### PendingMoveSmoothScroller (Held-Key Scrolling)

When Leanback receives held-key repeats through its normal dispatch path, `GridLayoutManager.processPendingMovement()` creates a `PendingMoveSmoothScroller`:

- Starts with `pendingMoves = 1`
- Each additional key repeat calls `increasePendingMoves()` (capped at `maxPendingMoves`)
- As each item transition completes, `pendingMoves` decrements
- If `pendingMoves > 0` when a target is reached, the scroller **continues without stopping** — no IDLE gap
- If `pendingMoves == 0`, the scroller stops → `SCROLL_STATE_IDLE`

This is the **only mechanism** in Leanback that provides smooth, continuous item-to-item scrolling. All other navigation (single DPAD press, `setSelectedPosition`) uses instant focus changes.

### Key APIs

| API | Effect |
|-----|--------|
| `setSmoothScrollMaxPendingMoves(n)` | Caps `pendingMoves` at `n`. Limits coast-after-release and controls effective speed |
| `setSmoothScrollSpeedFactor(f)` | Multiplies smooth scroll animation duration by `f`. Higher = slower per-item transitions. Only affects smooth scrollers, NOT instant focus changes |
| `setItemViewCacheSize(n)` | Extra off-screen views kept pre-created. Reduces bind overhead during scrolling |
| `setSelectedPositionSmooth(pos)` | Starts a `GridLinearSmoothScroller` to `pos`. **Unreliable from key interceptors** — see failed approaches below |

### The Fundamental Limitation

Leanback's DPAD navigation always uses **instant focus snapping** for the first key press. The `PendingMoveSmoothScroller` only kicks in for subsequent held-key repeats. This means:

- First item transition: instant (0ms)
- Subsequent transitions: smooth (duration affected by `smoothScrollSpeedFactor`)

At high speed factors (e.g., 5x+), this creates a visible "fast then slow" contrast on the first item.

## Approaches Attempted

### 1. Time-Based Key Repeat Throttle (Original)

**Concept**: In the key interceptor, track `lastScrollTime`. Consume DPAD repeats that arrive within `KEY_REPEAT_THROTTLE_MS` of the last allowed event.

**Result**: Worked but **jerky**. Each item transition is an instant focus snap. The throttle just controls the interval between snaps. At any interval, the visual is: snap → pause → snap → pause.

**Why abandoned**: User found the snap-pause-snap pattern unpleasant. Wanted smooth sliding rather than item jumping.

### 2. Scroll-State Gating (Consume During Non-IDLE)

**Concept**: Consume held-key repeats while `scrollState != IDLE`. Let one repeat through only when the previous scroll animation finishes.

**Result**: One item at a time, consistent speed, but still **jerky** — same snap-pause-snap pattern as approach 1. Adding `smoothScrollSpeedFactor` didn't help because the centering animation for adjacent items is too short to be visually smooth.

**Why abandoned**: Same visual problem as approach 1 — items jump rather than slide.

### 3. Scroll-State Gating (Consume During IDLE)

**Concept**: Inverse of approach 2 — consume repeats while IDLE (before smooth scroller starts), let them through once scrolling.

**Result**: **Broke scrolling entirely** — moved one spot then stopped. For adjacent items in Leanback, `scrollState` often stays IDLE because no actual pixel scrolling is needed. All repeats were consumed indefinitely.

**Why abandoned**: Fundamental misunderstanding of when scrollState changes for adjacent-item focus changes.

### 4. Manual setSelectedPositionSmooth from Timer

**Concept**: Consume ALL DPAD events. Use a `Handler` timer to call `setSelectedPositionSmooth()` at a controlled interval.

**Result**: **Didn't scroll at all**. `setSelectedPositionSmooth` updates `GridLayoutManager.mFocusPosition` but the smooth scroller doesn't reliably start when called outside of Leanback's normal key dispatch pipeline. `isRecyclerViewReady()` returns false during pending layouts, silently dropping the smooth scroll. Subsequent calls see `mFocusPosition` already at the target and become no-ops.

**Why abandoned**: `setSelectedPositionSmooth` is fundamentally unreliable when called manually. Tried calling it directly, via `grid.post{}`, via `grid.postDelayed()`, and from Handler runnables — all failed the same way.

### 5. ZOOM_FACTOR_NONE for Focus Zoom Suppression

**Concept**: Pass `FocusHighlight.ZOOM_FACTOR_NONE` to `ListRowPresenter` constructor for `trapFocus` mode to disable the focus zoom animation during scrolling.

**Result**: **Lost the white focus border** and zoom didn't work when re-applied manually via `zoomFocusedChild()`. The `grid.findFocus()` returns Leanback's internal wrapper view rather than the `ChannelCardView`, and the interaction between `ZOOM_FACTOR_NONE` and Leanback's focus state machinery disrupted the drawable selector's `state_focused` behavior.

**Why abandoned**: Couldn't reliably control focus visuals from outside Leanback's internal focus highlight system.

### 6. No Gating + PendingMoveSmoothScroller

**Concept**: Let ALL key events pass through to Leanback's native handling. Use `smoothScrollSpeedFactor` to slow down the smooth scroll animations. Use `maxPendingMoves` to limit queue depth and coast-after-release.

**Result**: **Smooth continuous scrolling** at a controlled speed. The only imperfection is the instant first item (inherent Leanback limitation). The `maxPendingMoves` value is critical:

- `maxPendingMoves = 1`: Scroller frequently hits 0 pending moves and stops. Next repeat creates a new scroller with an instant focus snap, causing a repeating **fast-slow-fast-slow cycle**.
- `maxPendingMoves = 2`: Scroller has enough buffer. By the time one move completes (~900ms at 4.5x factor), the next key repeat has already refilled the queue. Scroller runs continuously with no restart cycles.

**Why abandoned**: While smooth, still subject to Leanback's item-based focus model — focus zoom/border flickers between cards during scrolling. Could not suppress focus visuals without losing them entirely (see approach 5).

### 7. Pixel-Based scrollBy with Handler Timer (Final Approach)

**Concept**: Bypass Leanback's item-based navigation entirely during held keys. Intercept DPAD repeats, call `RecyclerView.scrollBy()` from a ~30fps Handler timer for continuous pixel motion. Block child focus during scrolling with `FOCUS_BLOCK_DESCENDANTS` + `grid.requestFocus()`. Draw a fixed center indicator via `ItemDecoration.onDrawOver()`. On key release, snap to the nearest item and restore Leanback focus.

**How it works**:

1. First DPAD press passes through to Leanback normally (instant focus snap to adjacent item)
2. On first held-key repeat (`repeatCount > 0`), the key interceptor starts pixel scrolling:
   - Saves and overrides `descendantFocusability` → `FOCUS_BLOCK_DESCENDANTS`
   - Saves and overrides `grid.isFocusable` → `true`, then `grid.requestFocus()` to steal focus from the child card (removes white border + zoom)
   - Starts a Handler timer that calls `grid.scrollBy(direction * pixelsPerFrame, 0)` every 33ms
   - Fires `onScrollStart` callback so the fragment can clear guide text
3. A `RecyclerView.ItemDecoration` draws a fixed white 2dp stroke rectangle at the grid's center on every draw pass (only when `pixelScrollActive` is true). This overlay stays perfectly still while cards scroll underneath — no lag or jumping.
4. Subsequent held-key repeats update `lastKeyRepeatTime`. A safety timeout (`KEY_REPEAT_SAFETY_MS = 200ms`) auto-stops scrolling if `ACTION_UP` is missed.
5. On key release (or safety timeout, or DPAD_UP/DOWN), pixel scrolling stops:
   - `descendantFocusability` and `isFocusable` restored to saved values
   - `snapToNearestItem()` finds the child closest to the grid center and sets `selectedPosition`, restoring Leanback's full focus border + zoom
   - The `ItemDecoration` stops drawing (early return when `!pixelScrollActive`)

**Result**: Genuinely smooth pixel-level scrolling at a consistent speed. No focus flicker, no card-to-card jumping. The fixed center indicator provides a stable visual reference. Speed is dp-based (`SCROLL_SPEED_DP_PER_SEC`), so it's consistent across all screen densities.

**Current implementation**: `SCROLL_SPEED_DP_PER_SEC = 750f`, `SCROLL_FRAME_MS = 33L` (~30fps), `ITEM_VIEW_CACHE_SIZE = 11`.

## Guide Text Debounce

### Problem

When the user holds DPAD left/right, the program header/description text would flash — briefly appearing then disappearing as each new item was selected.

### Root Cause

`RecyclerView.SCROLL_STATE_IDLE` briefly appears between item transitions during held-key scrolling. A debounce timer scheduled during one selection would fire during this IDLE gap, showing text that was immediately cleared by the next selection.

### Solution

Two-part fix:

1. **`isScrolling` property**: Checks `pixelScrollActive || scrollState != IDLE`. The `pixelScrollActive` flag stays true throughout the entire pixel scroll session, bridging any brief IDLE gaps from `scrollBy()` calls.

2. **Self-rescheduling debounce**: The debounce callback checks `isScrolling()`. If still scrolling, it reschedules itself instead of showing text. When the user finally stops (no more key repeats and scroll state IDLE), the callback fires and shows the text.

The `onScrollStart` callback on the presenter clears guide text immediately when pixel scrolling begins. The selection listener only clears text during scrolling — when stationary (single press), existing text remains until the debounce replaces it. This preserves the initial text set by `positionQuickChannelIfReady()` when the overlay first appears.

## Tuning Guide

| Constant | Location | Effect |
|----------|----------|--------|
| `SCROLL_SPEED_DP_PER_SEC` | `PositionableListRowPresenter.kt` | Scroll speed in dp/sec. Higher = faster. Converted to pixels at runtime using device density, so cards-per-second is consistent across resolutions. 750 feels comfortable |
| `SCROLL_FRAME_MS` | `PositionableListRowPresenter.kt` | Timer interval in ms. 33ms = ~30fps. Lower = smoother but more CPU. The per-frame pixel distance adjusts proportionally |
| `KEY_REPEAT_SAFETY_MS` | `PositionableListRowPresenter.kt` | Auto-stop timeout if ACTION_UP is missed. Must exceed DPAD repeat interval (~50ms). 200ms works well |
| `ITEM_VIEW_CACHE_SIZE` | `PositionableListRowPresenter.kt` | Off-screen cached views. With 5 visible cards, 11 prevents stutters from view creation during fast scrolling |
| `OVERLAY_GUIDE_TEXT_DEBOUNCE_MS` | `CustomPlaybackOverlayFragment.java` | Delay before showing program text after selection change. 300ms works well |

## CircularObjectAdapter Impact

The `CircularObjectAdapter` creates `realSize * 1000` virtual items. This does NOT affect scroll speed — `scrollBy()` operates on raw pixels, and the dp-based speed constant ensures consistent motion regardless of adapter size. The large virtual size means the boundary guards in the key interceptor (`pos <= 0` and `pos >= size - 1`) effectively never fire, allowing the user to scroll indefinitely in either direction.
