// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv.presentation

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.Button
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** Presentation only: callbacks retain native tab, profile and security ownership. */
object TvPresentation {
    const val MAX_HOME_FAVORITES = 6
    const val MAX_HOME_RECENT_TABS = 4
    const val MAX_HOME_BOOKMARK_CANDIDATES = 64
    internal val backgroundColor = Color(0xFF151719)
    internal val surface = Color(0xFF25282B)
    internal val text = Color(0xFFF3EFE7)
    internal val secondary = Color(0xFFBBB9B3)
    internal val accent = Color(0xFFC7D8AF)

    /** UI-thread action state, owned by its native panel; never saved across sessions. */
    class Action @JvmOverloads constructor(
        label: String, enabled: Boolean, val run: Runnable, val key: Int = 0,
        icon: Int = 0,
    ) {
        var label by mutableStateOf(label)
        var enabled by mutableStateOf(enabled)
        var icon by mutableStateOf(icon)
        internal var focused = false
        internal var focusRequest by mutableStateOf(0)
        fun hasFocus(): Boolean = focused
        fun requestFocus() { focusRequest++ }
    }

    sealed interface PanelEntry
    class Description(text: String, val announceChanges: Boolean) : PanelEntry {
        var text by mutableStateOf(text)
    }
    /** UI-thread engine progress; never persisted or estimated from elapsed time. */
    class LoadingState {
        internal var progress by mutableStateOf(0f)
        fun setProgress(value: Float) {
            progress = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
        }
    }

    /** Display-only status. Native Tab observation owns attachment and completion. */
    @JvmStatic
    fun loading(context: Context, label: String, state: LoadingState): View = view(context).apply {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setContent {
            Theme {
                Column(
                    Modifier.fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (state.progress > 0f) {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        )
                    }
                }
            }
        }
    }
    class ActionRow(val actions: List<Action>) : PanelEntry
    class NativeContent(val view: View, val heightDp: Int) : PanelEntry

    private class ControlsState(preferred: Int, subtitle: String, status: String, actions: List<Action>) {
        var subtitle by mutableStateOf(subtitle)
        var status by mutableStateOf(status)
        var actions by mutableStateOf(actions)
        var expanded by mutableStateOf(false)
        var preferredKey by mutableStateOf(preferred)
    }

    /** Bounded presentation state; the attached native Home owns model observations. */
    class HomeState(
        val showSections: Boolean,
        val favoritesTitle: String,
        val favoritesEmpty: String,
        val recentTitle: String,
        val recentEmpty: String,
        val loadingLabel: String,
    ) {
        internal var focusRequest by mutableStateOf(0)
        internal var favorites by mutableStateOf<List<Action>>(emptyList())
        internal var recentTabs by mutableStateOf<List<Action>>(emptyList())
        internal var loadingFavorites by mutableStateOf(true)

        /** UI thread only. Retain Action identities for unchanged native bookmark IDs.
         * Throws IllegalArgumentException above MAX_HOME_FAVORITES or for nonempty private state.
         * Restores retained focus after movement, or Address when the focused item disappears. */
        fun setFavorites(actions: List<Action>, loading: Boolean) {
            require(actions.size <= MAX_HOME_FAVORITES && (showSections || actions.isEmpty()))
            if (favorites != actions) restoreSectionFocus(favorites, actions)
            favorites = actions
            loadingFavorites = loading
        }

        /** UI thread only. Retain Action identities for unchanged native tab IDs.
         * Throws IllegalArgumentException above MAX_HOME_RECENT_TABS or for nonempty private state.
         * Restores retained focus after movement, or Address when the focused item disappears. */
        fun setRecentTabs(actions: List<Action>) {
            require(actions.size <= MAX_HOME_RECENT_TABS && (showSections || actions.isEmpty()))
            if (recentTabs != actions) restoreSectionFocus(recentTabs, actions)
            recentTabs = actions
        }

        private fun restoreSectionFocus(previous: List<Action>, next: List<Action>) {
            previous.firstOrNull { it.hasFocus() }?.let {
                if (it in next) it.requestFocus() else focusRequest++
            }
        }
    }

    /** UI thread only, for a factory-created home view. Schedules Address focus in composition. */
    @JvmStatic
    fun focusHome(view: View) {
        (view.tag as? HomeState)?.let { it.focusRequest++ }
    }

    /** Called only by this panel's dialog window, after the IME/native window owner. */
    @JvmStatic
    fun back(view: View, event: KeyEvent): Boolean {
        val state = view.tag as? ControlsState ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !state.expanded) return false
        if (event.action == KeyEvent.ACTION_UP) collapseControls(view)
        return true
    }

    /** UI thread only. Dialog Back first collapses More and restores its invoker focus. */
    @JvmStatic
    fun collapseControls(view: View): Boolean {
        val state = view.tag as? ControlsState ?: return false
        if (!state.expanded) return false
        state.expanded = false
        state.preferredKey = Int.MIN_VALUE
        return true
    }

    internal fun view(context: Context): ComposeView = ComposeView(context).apply {
        setViewTreeLifecycleOwner(context as LifecycleOwner)
        setViewTreeSavedStateRegistryOwner(context as SavedStateRegistryOwner)
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
    }

    /** UI thread only. Context must own lifecycle/saved state (BraveActivity).
     * First action must be enabled; caller disposes when the native page dies. */
    @JvmStatic
    fun home(
        context: Context,
        title: String,
        subtitle: String,
        attribution: String,
        actions: List<Action>,
        links: List<Action>,
        state: HomeState,
    ): View = view(context).apply {
        require(actions.isNotEmpty() && actions.first().enabled)
        tag = state
        setContent {
            Theme {
                val initial = remember { FocusRequester() }
                val shortcuts = remember { FocusRequester() }
                Column(
                    Modifier.fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF222827), backgroundColor)))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(title, fontSize = 36.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.semantics { heading() })
                    Text(subtitle, color = secondary, fontSize = 18.sp)
                    ActionButton(actions.first(),
                        Modifier.fillMaxWidth().focusRequester(initial).focusProperties {
                            if (actions.size > 1) down = shortcuts
                        })
                    ActionRows(actions.drop(1), 4, 16.dp) { action, modifier ->
                        ActionButton(action, modifier.then(
                            if (action === actions.getOrNull(1)) Modifier.focusRequester(shortcuts)
                            else Modifier), quiet = true)
                    }
                    if (state.showSections) {
                        HomeSection(state.favoritesTitle,
                            if (state.loadingFavorites) state.loadingLabel else state.favoritesEmpty,
                            state.favorites, 3)
                        HomeSection(state.recentTitle, state.recentEmpty, state.recentTabs, 2)
                    }
                    Text(attribution, color = secondary, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 16.dp))
                    ActionRows(links, links.size, 16.dp) { action, modifier ->
                        ActionButton(action, modifier, quiet = true)
                    }
                }
                LaunchedEffect(state.focusRequest) {
                    withFrameNanos { }
                    initial.requestFocus()
                }
            }
        }
    }

    @Composable
    private fun HomeSection(title: String, emptyLabel: String, actions: List<Action>, columns: Int) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { heading() })
            if (actions.isEmpty()) Text(emptyLabel, color = secondary, fontSize = 16.sp,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            ActionRows(actions, columns, 16.dp) { action, modifier ->
                ActionButton(action, modifier)
            }
        }
    }

    /** UI thread only, with a lifecycle/saved-state owning context (BraveActivity).
     * A modal sibling to Chromium content; native dialog owns Back and dismissal. */
    @JvmStatic
    fun controls(
        context: Context,
        title: String,
        subtitle: String,
        status: String,
        actions: List<Action>,
        preferredKey: Int,
        moreLabel: String,
    ): View = view(context).apply {
        val state = ControlsState(preferredKey, subtitle, status, actions)
        tag = state
        setContent {
            Theme {
                val initial = remember { FocusRequester() }
                val actions = state.actions
                val toggle = Action(if (state.expanded) title else moreLabel, true, Runnable {
                    state.expanded = !state.expanded
                    state.preferredKey = if (state.expanded) actions.getOrNull(7)?.key ?: 0
                        else Int.MIN_VALUE
                }, Int.MIN_VALUE)
                val visible = (if (state.expanded) actions.drop(7) else actions.take(7)) + toggle
                val selected = visible.firstOrNull { it.key == state.preferredKey && it.enabled }
                    ?: if (!state.expanded && actions.any { it.key == state.preferredKey }) toggle
                    else visible.firstOrNull { it.enabled }
                Column(
                    Modifier.fillMaxWidth().background(backgroundColor)
                        .semantics { paneTitle = title }
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.semantics { heading() })
                    if (state.subtitle.isNotEmpty()) Text(state.subtitle, color = secondary,
                        fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.status.isNotEmpty()) Text(state.status, color = secondary,
                        fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ActionRows(visible, 4, 12.dp) { action, modifier ->
                        ActionButton(action, modifier.then(
                            if (action === selected) Modifier.focusRequester(initial)
                            else Modifier))
                    }
                }
                LaunchedEffect(state.expanded) {
                    withFrameNanos { }
                    if (selected != null) initial.requestFocus()
                }
            }
        }
    }

    /** Native observer updates retain the dialog and keyed focus targets. UI thread only. */
    @JvmStatic
    fun updateControls(view: View, subtitle: String, status: String, actions: List<Action>) {
        val state = view.tag as? ControlsState ?: return
        state.subtitle = subtitle
        state.status = status
        state.actions = actions
    }

    /** UI thread only. Entries are complete before creation; native dialog owns their lifetime.
     * The context owns lifecycle/saved state. Actions and descriptions may update while shown.
     * Restoration may focus a disabled preferred action; activation remains blocked.
     * Ordinary entry selects the preferred enabled action, then the first enabled action.
     * onFocusRestored runs on the UI thread after the initial frame and successful focus.
     * No selection, failed focus, or disposal before restoration leaves it uncalled.
     * Native observers preserve captured selection until that acknowledgement. */
    @JvmStatic
    fun panel(
        context: Context, title: String, subtitle: String, backHint: String,
        entries: List<PanelEntry>, preferredKey: Int, restorePreferredFocus: Boolean,
        onFocusRestored: Runnable,
    ): View = view(context).apply {
        val actions = entries.filterIsInstance<ActionRow>().flatMap { it.actions }
        val metrics = context.resources.displayMetrics
        val maxHeight = (metrics.heightPixels * 0.9f / metrics.density).dp
        setContent {
            Theme {
                val initialTargets = remember { actions.associateWith { FocusRequester() } }
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    .background(backgroundColor).padding(24.dp).semantics { paneTitle = title },
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (subtitle.isNotEmpty()) Text(subtitle, color = secondary, fontSize = 16.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        entries.forEach { entry ->
                            when (entry) {
                                is Description -> Text(entry.text, color = secondary, fontSize = 16.sp,
                                    modifier = Modifier.semantics {
                                        if (entry.announceChanges) liveRegion = LiveRegionMode.Polite
                                    })
                                is NativeContent -> AndroidView(factory = { entry.view },
                                    modifier = Modifier.fillMaxWidth().height(entry.heightDp.dp))
                                is ActionRow -> ActionRows(entry.actions, entry.actions.size, 12.dp) {
                                    action, modifier ->
                                    ActionButton(action, modifier
                                        .focusRequester(initialTargets.getValue(action)))
                                }
                            }
                        }
                    }
                    Text(backHint, color = secondary, fontSize = 14.sp)
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    // Native media state can disable an action before the first frame.
                    val selected = actions.firstOrNull {
                        it.key == preferredKey && (it.enabled || restorePreferredFocus)
                    } ?: actions.firstOrNull { it.enabled }
                    selected?.let {
                        if (initialTargets.getValue(it).requestFocus(FocusDirection.Enter)) {
                            onFocusRestored.run()
                        }
                    }
                }
            }
        }
    }

    /** UI thread only. Context must own lifecycle/saved state; caller must dispose this view.
     * Does not request keyboard focus. Pointer/accessibility activation runs open on the UI
     * thread; D-pad Disconnect remains in native browser controls. */
    @JvmStatic
    fun connectionBadge(context: Context, label: String, open: Runnable): View = view(context).apply {
        isFocusable = false
        setOnClickListener { open.run() }
        setContent {
            Theme {
                Text(
                    label,
                    Modifier.background(surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            role = Role.Button
                            onClick { open.run(); true }
                        },
                    color = accent, fontSize = 16.sp,
                )
            }
        }
    }

    /** UI-thread disposal for a View returned by these factories. */
    @JvmStatic
    fun dispose(view: View) {
        (view as ComposeView).disposeComposition()
    }

    @Composable
    internal fun Theme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent, onPrimary = backgroundColor, background = backgroundColor,
                onBackground = text, surface = surface, onSurface = text,
            ),
            content = {
                CompositionLocalProvider(
                    LocalContentColor provides text,
                    LocalTextStyle provides LocalTextStyle.current.copy(
                        textDirection = TextDirection.Content),
                    content = content,
                )
            },
        )
    }

    @Composable
    private fun ActionRows(
        actions: List<Action>, maxColumns: Int, spacing: Dp,
        content: @Composable (Action, Modifier) -> Unit,
    ) {
        if (actions.isEmpty()) return
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumWidth = 140.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt()
                .coerceIn(1, maxColumns)
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                actions.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        row.forEach { action ->
                            key(if (action.key != 0) action.key else action) {
                                content(action, Modifier.weight(1f))
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    @Composable
    internal fun ActionButton(action: Action, modifier: Modifier = Modifier, quiet: Boolean = false) {
        var hasFocus by remember { mutableStateOf(false) }
        var size by remember { mutableStateOf(IntSize.Zero) }
        val requested = remember { FocusRequester() }
        val visible = remember { BringIntoViewRequester() }
        val focusSpace = with(LocalDensity.current) { 24.dp.toPx() }
        LaunchedEffect(hasFocus, size) {
            if (hasFocus && size != IntSize.Zero) {
                withFrameNanos { }
                visible.bringIntoView(Rect(0f, -focusSpace,
                    size.width.toFloat(), size.height + focusSpace))
            }
        }
        LaunchedEffect(action.focusRequest) {
            if (action.focusRequest > 0 && action.enabled) {
                withFrameNanos { }
                requested.requestFocus()
            }
        }
        Button(
            onClick = { action.run.run() },
            enabled = action.enabled,
            modifier = modifier.bringIntoViewRequester(visible).onSizeChanged { size = it }
                .focusRequester(requested).onFocusChanged {
                hasFocus = it.isFocused
                action.focused = it.isFocused
            }.clearAndSetSemantics {
                role = Role.Button
                contentDescription = action.label
                focused = hasFocus
                if (!action.enabled) disabled()
                onClick {
                    if (action.enabled) action.run.run()
                    action.enabled
                }
            },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(12.dp)),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp, text))),
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            colors = ButtonDefaults.colors(
                containerColor = if (quiet) Color.Transparent else surface, contentColor = text,
                focusedContainerColor = accent, focusedContentColor = backgroundColor,
            ),
        ) {
            if (action.icon != 0) {
                Image(painterResource(action.icon), contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(LocalContentColor.current))
                Spacer(Modifier.width(10.dp))
            }
            Text(action.label, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
