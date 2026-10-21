package com.akane.akastudio.feature.studio.ui.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.akane.akastudio.core.color.api.ImagePixels
import com.akane.akastudio.core.color.api.RenderScene
import com.akane.akastudio.core.color.api.StoryRenderer
import com.akane.akastudio.core.composer.api.CanvasSize
import com.akane.akastudio.core.composer.api.CellOverride
import com.akane.akastudio.feature.studio.R
import com.akane.akastudio.feature.studio.mvi.ScopeMode
import com.akane.akastudio.feature.studio.mvi.StudioIntent
import com.akane.akastudio.feature.studio.ui.canvas.scope.ScopeOverlay
import com.akane.akastudio.feature.studio.ui.theme.DarkroomTheme
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlin.math.roundToInt

private val SELECTION_STROKE = 1.5.dp
private const val SCOPE_WIDTH_FRACTION = 0.4f
private const val GHOST_FILL_ALPHA = 0.16f
private const val PICKED_DIM_ALPHA = 0.45f
private const val TARGET_ALPHA = 0.6f
private const val FALLBACK_REFRESH_HZ = 60f

/** A long-press drag of the cell in slot [from], from view point [start] to [current], over slot [target]. */
@Immutable
internal data class SlotDrag(val from: Int, val start: Offset, val current: Offset, val target: Int?)

/**
 * The live 9:16 story canvas: the scene aspect-fit and centred on the canvas backdrop, rendered by [renderer] into a
 * GPU surface, with direct-manipulation gestures on top.
 *
 * - Tap selects a cell (outlined); double-tap selects it and resets its crop override.
 * - Long-press then drag swaps the picked cell with the one it is dropped on ([StudioIntent.SwapSlots]).
 * - Two-finger pinch or pan zooms (1..4) and pans (-1..1) the cell under the fingers, or the selected cell, sending
 *   at most one [StudioIntent.SetCellOverride] per display frame.
 * - With [compare] set, a draggable split handle sends [StudioIntent.SetCompare]; "Before"/"After" mark each side.
 *
 * With a null [renderer] (previews, screenshot tests) the canvas draws the scene's cell layout as hairline outlines
 * instead of a GPU surface.
 *
 * @param scene The preview scene, or null before a story exists.
 * @param revision The scene revision; each new value (or [compare] change) triggers one render.
 * @param hitTest Slot index under a point in canvas pixels, or null (`CollageComposer.hitTest` for the current scene).
 * @param photoIds Candidate ids in slot order, used to address [StudioIntent.SetCellOverride].
 * @param overrides The current per-photo overrides; a pinch starts from the cell's current override.
 * @param scope The scope to dock at the top-right of the canvas, or [ScopeMode.OFF].
 * @param scopePixels The scope readback for [scope].
 */
@Composable
fun StoryCanvas(
    scene: RenderScene?,
    revision: Long,
    renderer: StoryRenderer?,
    compare: Float?,
    onIntent: (StudioIntent) -> Unit,
    hitTest: (Float, Float) -> Int?,
    modifier: Modifier = Modifier,
    photoIds: ImmutableList<String> = persistentListOf(),
    overrides: ImmutableMap<String, CellOverride> = persistentMapOf(),
    scope: ScopeMode = ScopeMode.OFF,
    scopePixels: ImagePixels? = null,
) {
    var selectedSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    StoryCanvasLayout(
        scene = scene,
        revision = revision,
        renderer = renderer,
        compare = compare,
        onIntent = onIntent,
        hitTest = hitTest,
        selectedSlot = selectedSlot,
        onSelect = { selectedSlot = it },
        photoIds = photoIds,
        overrides = overrides,
        scope = scope,
        scopePixels = scopePixels,
        modifier = modifier,
    )
}

/** [StoryCanvas] with the selection hoisted, so previews can show a selected cell. */
@Composable
internal fun StoryCanvasLayout(
    scene: RenderScene?,
    revision: Long,
    renderer: StoryRenderer?,
    compare: Float?,
    onIntent: (StudioIntent) -> Unit,
    hitTest: (Float, Float) -> Int?,
    selectedSlot: Int?,
    onSelect: (Int?) -> Unit,
    photoIds: ImmutableList<String>,
    overrides: ImmutableMap<String, CellOverride>,
    scope: ScopeMode,
    scopePixels: ImagePixels?,
    modifier: Modifier = Modifier,
) {
    val cellCount = scene?.layers?.size ?: 0
    val selected = selectedSlot?.takeIf { it in 0 until cellCount }
    val canvasWidth = (scene?.canvasWidth ?: CanvasSize.STORY_1080.width).toFloat()
    val canvasHeight = (scene?.canvasHeight ?: CanvasSize.STORY_1080.height).toFloat()
    val density = LocalDensity.current
    val spacing = DarkroomTheme.spacing
    val dragState = remember { mutableStateOf<SlotDrag?>(null) }

    BoxWithConstraints(modifier.background(DarkroomTheme.colors.canvasBackdrop)) {
        val viewWidth = if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else 0f
        val viewHeight = if (constraints.hasBoundedHeight) {
            constraints.maxHeight.toFloat()
        } else {
            viewWidth * canvasHeight / canvasWidth
        }
        val transform = remember(viewWidth, viewHeight, canvasWidth, canvasHeight) {
            CanvasTransform(viewWidth, viewHeight, canvasWidth, canvasHeight)
        }
        val viewSize = with(density) { DpSize(viewWidth.toDp(), viewHeight.toDp()) }
        val gestures = rememberCanvasGestures(
            transform = transform,
            scene = scene,
            selectedSlot = selected,
            onSelect = onSelect,
            onIntent = onIntent,
            hitTest = hitTest,
            photoIds = photoIds,
            overrides = overrides,
            dragState = dragState,
        )
        Box(
            Modifier
                .size(viewSize)
                .canvasSemantics(cellCount, selected, onSelect, onIntent)
                .then(gestures),
        ) {
            val frame = transform.frame ?: return@Box
            val frameSize = with(density) { DpSize(frame.width.toDp(), frame.height.toDp()) }
            Box(
                Modifier
                    .offset { IntOffset(frame.left.roundToInt(), frame.top.roundToInt()) }
                    .size(frameSize)
                    .clipToBounds(),
            ) {
                if (renderer != null) {
                    PreviewSurface(scene, revision, compare, renderer, Modifier.fillMaxSize())
                }
                if (renderer == null || scene == null) {
                    PlaceholderScene(scene, canvasWidth, canvasHeight, Modifier.fillMaxSize())
                }
                SelectionOverlay(scene, canvasWidth, canvasHeight, selected, dragState.value, Modifier.fillMaxSize())
                if (compare != null) {
                    CompareHandle(compare, { onIntent(StudioIntent.SetCompare(it)) }, Modifier.fillMaxSize())
                    CompareLabel(before = true, modifier = Modifier.align(Alignment.TopStart).padding(spacing.s))
                }
                Column(
                    modifier = Modifier.align(Alignment.TopEnd).padding(spacing.s),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(spacing.s),
                ) {
                    if (compare != null) CompareLabel(before = false)
                    if (scope != ScopeMode.OFF) {
                        ScopeOverlay(
                            pixels = scopePixels,
                            mode = scope,
                            modifier = Modifier.width(frameSize.width * SCOPE_WIDTH_FRACTION),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Modifier.canvasSemantics(
    cellCount: Int,
    selected: Int?,
    onSelect: (Int?) -> Unit,
    onIntent: (StudioIntent) -> Unit,
): Modifier {
    val description = stringResource(R.string.canvas_description)
    val state = if (selected != null) {
        stringResource(R.string.canvas_state_selected, selected + 1, cellCount)
    } else {
        stringResource(R.string.canvas_state_cells, cellCount)
    }
    val swapLabel = stringResource(R.string.canvas_action_swap_next)
    return semantics {
        contentDescription = description
        stateDescription = state
        if (cellCount >= 2) {
            customActions = listOf(
                CustomAccessibilityAction(swapLabel) {
                    val from = selected ?: 0
                    val to = (from + 1) % cellCount
                    onIntent(StudioIntent.SwapSlots(from, to))
                    onSelect(to)
                    true
                },
            )
        }
    }
}

/** Builds the pointer-input modifier for tap, double-tap, long-press drag and two-finger transform. */
@Composable
private fun rememberCanvasGestures(
    transform: CanvasTransform,
    scene: RenderScene?,
    selectedSlot: Int?,
    onSelect: (Int?) -> Unit,
    onIntent: (StudioIntent) -> Unit,
    hitTest: (Float, Float) -> Int?,
    photoIds: ImmutableList<String>,
    overrides: ImmutableMap<String, CellOverride>,
    dragState: MutableState<SlotDrag?>,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val view = LocalView.current
    val currentTransform by rememberUpdatedState(transform)
    val currentScene by rememberUpdatedState(scene)
    val currentSelected by rememberUpdatedState(selectedSlot)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnIntent by rememberUpdatedState(onIntent)
    val currentHitTest by rememberUpdatedState(hitTest)
    val currentPhotoIds by rememberUpdatedState(photoIds)
    val currentOverrides by rememberUpdatedState(overrides)

    fun slotAt(point: Offset): Int? {
        val cells = currentScene?.layers?.size ?: return null
        val canvasPoint = currentTransform.viewToCanvasInside(point.x, point.y) ?: return null
        return currentHitTest(canvasPoint.x, canvasPoint.y)?.takeIf { it in 0 until cells }
    }

    return Modifier
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentOnSelect(slotAt(it)) },
                onDoubleTap = { point ->
                    val slot = slotAt(point) ?: return@detectTapGestures
                    currentOnSelect(slot)
                    currentPhotoIds.getOrNull(slot)?.let { currentOnIntent(StudioIntent.SetCellOverride(it, null)) }
                },
            )
        }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { point ->
                    val slot = slotAt(point) ?: return@detectDragGesturesAfterLongPress
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    dragState.value = SlotDrag(from = slot, start = point, current = point, target = null)
                },
                onDrag = { change, _ ->
                    val active = dragState.value ?: return@detectDragGesturesAfterLongPress
                    change.consume()
                    dragState.value = active.copy(current = change.position, target = slotAt(change.position))
                },
                onDragEnd = {
                    val active = dragState.value
                    dragState.value = null
                    val target = active?.let { slotAt(it.current) }
                    if (active != null && target != null && target != active.from) {
                        currentOnIntent(StudioIntent.SwapSlots(active.from, target))
                        currentOnSelect(target)
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    }
                },
                onDragCancel = { dragState.value = null },
            )
        }
        .pointerInput(view) {
            // Read lazily: pointer input starts on the first touch, so previews never query the display.
            val frameNanos = FrameThrottle.frameIntervalNanos(view.display?.refreshRate ?: FALLBACK_REFRESH_HZ)
            var tracker: CellPinchTracker? = null
            detectTwoFingerTransform(
                onStart = { centroid ->
                    tracker = null
                    if (dragState.value != null) return@detectTwoFingerTransform false
                    val slot = slotAt(centroid) ?: currentSelected ?: return@detectTwoFingerTransform false
                    val cell = currentScene?.layers?.getOrNull(slot)?.dst ?: return@detectTwoFingerTransform false
                    val photoId = currentPhotoIds.getOrNull(slot) ?: return@detectTwoFingerTransform false
                    currentOnSelect(slot)
                    tracker = CellPinchTracker(
                        photoId = photoId,
                        base = currentOverrides[photoId] ?: CellOverride(),
                        cellWidthPx = cell.width,
                        cellHeightPx = cell.height,
                        throttle = FrameThrottle(frameNanos, System::nanoTime),
                    )
                    true
                },
                onTransform = { zoom, pan ->
                    val active = tracker
                    val scale = currentTransform.scale
                    if (active != null && scale > 0f) {
                        active.update(zoom, pan.x / scale, pan.y / scale)?.let {
                            currentOnIntent(StudioIntent.SetCellOverride(active.photoId, it))
                        }
                    }
                },
                onEnd = {
                    tracker?.let { active ->
                        active.finish()?.let { currentOnIntent(StudioIntent.SetCellOverride(active.photoId, it)) }
                    }
                    tracker = null
                },
            )
        }
}

/**
 * Two-finger pinch/pan: once a second pointer is down, [onStart] decides (once per gesture) whether to track it, then
 * every pointer event's zoom factor and centroid pan go to [onTransform] and their changes are consumed. A single
 * finger is never consumed here, so taps and long-press drags stay with their own detectors.
 */
private suspend fun PointerInputScope.detectTwoFingerTransform(
    onStart: (centroid: Offset) -> Boolean,
    onTransform: (zoom: Float, pan: Offset) -> Unit,
    onEnd: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var tracking: Boolean? = null
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                if (tracking == null) tracking = onStart(event.calculateCentroid(useCurrent = true))
                if (tracking == true) {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) onTransform(zoom, pan)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        if (tracking == true) onEnd()
    }
}

/** The neutral stand-in for the GPU preview: the frame and each cell as a hairline rectangle. */
@Composable
private fun PlaceholderScene(scene: RenderScene?, canvasWidth: Float, canvasHeight: Float, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Canvas(modifier) {
        drawRect(scheme.surfaceContainerLowest)
        val local = CanvasTransform(size.width, size.height, canvasWidth, canvasHeight)
        scene?.layers?.forEach { layer ->
            val rect = local.canvasToView(layer.dst) ?: return@forEach
            val radius = CornerRadius(layer.cornerRadiusPx * local.scale)
            drawRoundRect(scheme.surfaceContainer, rect.topLeft, rect.size, radius)
            drawRoundRect(scheme.outline, rect.topLeft, rect.size, radius, style = Stroke(Stroke.HairlineWidth))
        }
        drawRect(scheme.outline, style = Stroke(Stroke.HairlineWidth))
    }
}

/** Selection outline plus, while dragging, a dimmed source cell, a ghost under the finger and the drop target. */
@Composable
private fun SelectionOverlay(
    scene: RenderScene?,
    canvasWidth: Float,
    canvasHeight: Float,
    selected: Int?,
    drag: SlotDrag?,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    if (scene == null || (selected == null && drag == null)) return
    Canvas(modifier) {
        val local = CanvasTransform(size.width, size.height, canvasWidth, canvasHeight)
        fun cell(slot: Int): Pair<Rect, CornerRadius>? {
            val layer = scene.layers.getOrNull(slot) ?: return null
            val rect = local.canvasToView(layer.dst) ?: return null
            return rect to CornerRadius(layer.cornerRadiusPx * local.scale)
        }
        val stroke = SELECTION_STROKE.toPx()
        if (drag != null) {
            cell(drag.from)?.let { (rect, radius) ->
                drawRoundRect(scheme.scrim.copy(alpha = PICKED_DIM_ALPHA), rect.topLeft, rect.size, radius)
                drag.target?.takeIf { it != drag.from }?.let(::cell)?.let { (target, targetRadius) ->
                    outline(target, targetRadius, scheme.primary.copy(alpha = TARGET_ALPHA), stroke)
                }
                val ghost = rect.translate(drag.current - drag.start)
                drawRoundRect(scheme.primary.copy(alpha = GHOST_FILL_ALPHA), ghost.topLeft, ghost.size, radius)
                outline(ghost, radius, scheme.primary, stroke)
            }
        } else if (selected != null) {
            cell(selected)?.let { (rect, radius) -> outline(rect, radius, scheme.primary, stroke) }
        }
    }
}

private fun DrawScope.outline(rect: Rect, radius: CornerRadius, colour: Color, stroke: Float) {
    val inset = rect.deflate(stroke / 2f)
    drawRoundRect(colour, inset.topLeft, inset.size, radius, style = Stroke(stroke))
}
