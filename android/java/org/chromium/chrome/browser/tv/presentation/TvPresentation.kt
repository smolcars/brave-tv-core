// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv.presentation

import android.content.Context
import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.semantics.selected
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

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
        icon: Int = 0, val group: String = "",
    ) {
        var label by mutableStateOf(label)
        var enabled by mutableStateOf(enabled)
        var icon by mutableStateOf(icon)
        var displayLabel by mutableStateOf<String?>(null)
        var announceChanges = false
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
        var preferredKey by mutableStateOf(preferred)
    }

    /** Bounded presentation state; the attached native Home owns model observations. */
    class HomeState @JvmOverloads constructor(
        val showSections: Boolean,
        val favoritesTitle: String,
        val favoritesEmpty: String,
        val recentTitle: String,
        val recentEmpty: String,
        val loadingLabel: String,
        val wallpaper: Int = 0,
        val brand: String = "",
        val photoCredit: String = "",
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
            Theme(ambient = true) {
                val initial = remember { FocusRequester() }
                val header = remember { FocusRequester() }
                val favoritesFocus = remember { FocusRequester() }
                val recentFocus = remember { FocusRequester() }
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    AmbientBackdrop(context, state.wallpaper)
                    // Fixed scrims bound contrast even over the brightest parts of the photo.
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(
                        Color(0xAD081E1C), Color(0x70091C1B),
                        Color(0xD1071918), Color(0xFF102825)))))
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            .padding(start = 40.dp, end = 40.dp, top = 16.dp, bottom = 48.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (state.brand.isNotEmpty()) Text(state.brand,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold)
                            Column(Modifier.weight(1f)) {
                                ActionRows(actions.drop(1).take(4), 4, 8.dp, minimum = 108.dp,
                                    weight = { if (it === actions.getOrNull(4)) 1.5f else 1f }) { action, modifier ->
                                    ActionButton(action, modifier.then(
                                        if (action === actions.getOrNull(1)) Modifier.focusRequester(header)
                                        else Modifier), quiet = true, compact = true,
                                        hideIcon = action !== actions.getOrNull(4),
                                        current = action === actions.getOrNull(1))
                                }
                            }
                            actions.drop(5).forEach { action ->
                                ActionButton(action, compact = true, quiet = true, iconOnly = true)
                            }
                            LocalClock(context)
                        }
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(title, style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.semantics { heading() })
                            if (subtitle.isNotEmpty() && !state.showSections) Text(subtitle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge)
                            ActionButton(actions.first(), Modifier.widthIn(max = 680.dp).fillMaxWidth()
                                .focusRequester(initial).focusProperties {
                                    up = header
                                    down = when {
                                        state.favorites.isNotEmpty() -> favoritesFocus
                                        state.recentTabs.isNotEmpty() -> recentFocus
                                        else -> FocusRequester.Default
                                    }
                                },
                                search = true)
                        }
                        if (state.showSections) {
                            HomeSection(state.favoritesTitle,
                                if (state.loadingFavorites) state.loadingLabel else state.favoritesEmpty,
                                state.favorites, 6, initial = favoritesFocus)
                            HomeSection(state.recentTitle, state.recentEmpty, state.recentTabs, 4, recent = true, initial = recentFocus)
                        }
                        if (attribution.isNotEmpty() || links.isNotEmpty())
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (attribution.isNotEmpty()) Text(attribution,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            links.forEach { action -> ActionButton(action, quiet = true, compact = true) }
                        }
                    }
                    if (state.photoCredit.isNotEmpty()) Text(state.photoCredit,
                        modifier = Modifier.align(Alignment.BottomStart)
                            .padding(horizontal = 40.dp, vertical = 16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelMedium)
                }
                LaunchedEffect(state.focusRequest) {
                    withFrameNanos { }
                    initial.requestFocus()
                }
            }
        }
    }

    // A single bounded, process-owned bitmap; no Activity or live feed is retained.
    @Volatile private var ambientBitmap: ImageBitmap? = null

    @Composable
    private fun AmbientBackdrop(context: Context, resource: Int) {
        var bitmap by remember { mutableStateOf(ambientBitmap) }
        LaunchedEffect(resource) {
            if (resource != 0 && bitmap == null) {
                val resources = context.applicationContext.resources
                bitmap = withContext(Dispatchers.IO) {
                    synchronized(TvPresentation) {
                        ambientBitmap ?: BitmapFactory.decodeResource(resources, resource,
                            BitmapFactory.Options().apply { inScaled = false })
                            ?.asImageBitmap()?.also { ambientBitmap = it }
                    }
                }
            }
        }
        bitmap?.let { Image(it, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }

    @Composable
    private fun LocalClock(context: Context) {
        var now by remember { mutableStateOf(Date()) }
        LaunchedEffect(Unit) {
            while (true) { delay(30_000); now = Date() }
        }
        Text(DateFormat.getTimeFormat(context).format(now),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    private fun HomeSection(
        title: String, emptyLabel: String, actions: List<Action>, columns: Int,
        recent: Boolean = false, initial: FocusRequester? = null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() })
            if (actions.isEmpty()) Text(emptyLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            ActionRows(actions, columns, 12.dp, minimum = 120.dp) { action, modifier ->
                ActionButton(action, modifier.then(
                    if (initial != null && action === actions.firstOrNull()) Modifier.focusRequester(initial)
                    else Modifier), card = true, recentCard = recent)
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
    ): View = view(context).apply {
        val state = ControlsState(preferredKey, subtitle, status, actions)
        tag = state
        setContent {
            Theme(ambient = true) {
                val initial = remember { FocusRequester() }
                val selected = state.actions.firstOrNull { it.key == state.preferredKey && it.enabled }
                    ?: state.actions.firstOrNull { it.enabled }
                val metrics = context.resources.displayMetrics
                val maxHeight = (metrics.heightPixels * 0.9f / metrics.density).dp
                Column(
                    Modifier.fillMaxWidth().heightIn(max = maxHeight)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(24.dp))
                        .semantics { paneTitle = title }.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    if (state.subtitle.isNotEmpty()) Text(state.subtitle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (state.status.isNotEmpty()) Text(state.status,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        state.actions.groupBy { it.group }.forEach { (group, actions) ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                if (group.isNotEmpty()) Text(group,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.semantics { heading() })
                                ActionRows(actions, 3, 10.dp, equalHeight = true) { action, modifier ->
                                    ActionButton(action, modifier.then(
                                        if (action === selected) Modifier.focusRequester(initial)
                                        else Modifier), menu = true)
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
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
    @JvmOverloads
    fun panel(
        context: Context, title: String, subtitle: String, backHint: String,
        entries: List<PanelEntry>, preferredKey: Int, restorePreferredFocus: Boolean,
        onFocusRestored: Runnable, ambient: Boolean = false,
    ): View = view(context).apply {
        val actions = entries.filterIsInstance<ActionRow>().flatMap { it.actions }
        val metrics = context.resources.displayMetrics
        val maxHeight = (metrics.heightPixels * 0.9f / metrics.density).dp
        setContent {
            Theme(ambient = ambient) {
                val initialTargets = remember { actions.associateWith { FocusRequester() } }
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(24.dp)).padding(24.dp).semantics { paneTitle = title },
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(title, fontSize = 26.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (subtitle.isNotEmpty()) Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        entries.forEach { entry ->
                            when (entry) {
                                is Description -> Text(entry.text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp,
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
                    Text(backHint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
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
    internal fun Theme(ambient: Boolean = false, content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = if (ambient) Color(0xFFDCEBDC) else accent,
                onPrimary = if (ambient) Color(0xFF19362E) else backgroundColor,
                background = if (ambient) Color(0xFF142726) else backgroundColor,
                onBackground = text, surface = if (ambient) Color(0xFF28413E) else surface,
                onSurface = text, onSurfaceVariant = if (ambient) Color(0xFFD0DFD8) else secondary,
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
        actions: List<Action>, maxColumns: Int, spacing: Dp, minimum: Dp = 140.dp,
        equalHeight: Boolean = false,
        weight: (Action) -> Float = { 1f },
        content: @Composable (Action, Modifier) -> Unit,
    ) {
        if (actions.isEmpty()) return
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val minimumWidth = minimum * LocalDensity.current.fontScale.coerceAtLeast(1f)
            val columns = ((maxWidth + spacing) / (minimumWidth + spacing)).toInt()
                .coerceIn(1, maxColumns)
            Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
                actions.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().then(
                        if (equalHeight) Modifier.height(IntrinsicSize.Min) else Modifier),
                        horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        row.forEach { action ->
                            key(if (action.key != 0) action.key else action) {
                                content(action, Modifier.weight(weight(action)).then(
                                    if (equalHeight) Modifier.fillMaxHeight() else Modifier))
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    @Composable
    private fun SiteInitial(host: String, logo: Int = 0) {
        if (logo != 0) {
            Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(28.dp))
            return
        }
        Box(Modifier.size(28.dp).background(LocalContentColor.current.copy(alpha = 0.12f),
            RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Text(host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "",
                style = MaterialTheme.typography.titleMedium)
        }
    }

    @Composable
    internal fun ActionButton(
        action: Action, modifier: Modifier = Modifier, quiet: Boolean = false,
        compact: Boolean = false, search: Boolean = false, card: Boolean = false,
        iconOnly: Boolean = false, hideIcon: Boolean = false,
        current: Boolean = false, recentCard: Boolean = false, menu: Boolean = false,
    ) {
        val caption = action.displayLabel ?: action.label
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
                contentDescription = if (action.label.contains(caption, ignoreCase = true))
                    action.label else "$caption · ${action.label}"
                if (current) selected = true
                if (action.announceChanges) liveRegion = LiveRegionMode.Polite
                focused = hasFocus
                if (!action.enabled) disabled()
                onClick {
                    if (action.enabled) action.run.run()
                    action.enabled
                }
            },
            shape = ButtonDefaults.shape(shape = RoundedCornerShape(if (search || compact) 32.dp
                else if (MaterialTheme.colorScheme.primary == accent) 12.dp else 14.dp)),
            border = ButtonDefaults.border(focusedBorder = Border(BorderStroke(2.dp,
                if (search) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface))),
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            colors = ButtonDefaults.colors(
                containerColor = if (search) text else if (quiet && !current) Color.Transparent
                    else MaterialTheme.colorScheme.surface,
                contentColor = if (search) MaterialTheme.colorScheme.background else text,
                focusedContainerColor = if (search) text else MaterialTheme.colorScheme.primary,
                focusedContentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (action.icon != 0 && !hideIcon && !card) {
                Image(painterResource(action.icon), contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(LocalContentColor.current))
                if (!iconOnly) Spacer(Modifier.width(10.dp))
            }
            if (card) {
                val lines = action.label.split("\n", limit = 2)
                if (recentCard) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        SiteInitial(lines.last(), action.icon)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(lines.first(), style = MaterialTheme.typography.titleMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (lines.size > 1) Text(lines.last(),
                                style = MaterialTheme.typography.bodySmall,
                                color = LocalContentColor.current.copy(alpha = 0.85f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SiteInitial(lines.last(), action.icon)
                        Text(lines.first(), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else if (!iconOnly) Text(caption,
                style = if (compact) MaterialTheme.typography.labelLarge
                    else if (search) MaterialTheme.typography.titleLarge
                    else MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp),
                maxLines = if (menu) 3 else 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(vertical = if (search) 4.dp else 8.dp)
                    .then(if (search) Modifier.weight(1f) else Modifier))
        }
    }
}
