// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv.presentation

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
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
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** Presentation only: callbacks retain native tab, profile and security ownership. */
object TvPresentation {
    private val backgroundColor = Color(0xFF151719)
    private val surface = Color(0xFF25282B)
    private val text = Color(0xFFF3EFE7)
    private val secondary = Color(0xFFBBB9B3)
    private val accent = Color(0xFFC7D8AF)

    class Action @JvmOverloads constructor(
        val label: String, val enabled: Boolean, val run: Runnable, val key: Int = 0,
        val icon: Int = 0,
    )

    private class ControlsState(preferred: Int) {
        var expanded by mutableStateOf(false)
        var preferredKey by mutableStateOf(preferred)
    }

    /** Called only by this panel's dialog window, after the IME/native window owner. */
    @JvmStatic
    fun back(view: View, event: KeyEvent): Boolean {
        val state = view.tag as? ControlsState ?: return false
        if (event.keyCode != KeyEvent.KEYCODE_BACK || !state.expanded) return false
        if (event.action == KeyEvent.ACTION_UP) {
            state.expanded = false
            state.preferredKey = Int.MIN_VALUE
        }
        return true
    }

    private fun view(context: Context): ComposeView = ComposeView(context).apply {
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
    ): View = view(context).apply {
        require(actions.isNotEmpty() && actions.first().enabled)
        setContent {
            Theme {
                val initial = remember { FocusRequester() }
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
                    actions.firstOrNull()?.let {
                        ActionButton(it, Modifier.fillMaxWidth().focusRequester(initial))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        actions.drop(1).forEach { ActionButton(it, Modifier.weight(1f)) }
                    }
                    Text(attribution, color = secondary, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 48.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        links.forEach { ActionButton(it) }
                    }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    initial.requestFocus()
                }
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
        actions: List<Action>,
        preferredKey: Int,
        moreLabel: String,
    ): View = view(context).apply {
        val state = ControlsState(preferredKey)
        tag = state
        setContent {
            Theme {
                val initial = remember { FocusRequester() }
                val toggle = Action(if (state.expanded) title else moreLabel, true, Runnable {
                    state.expanded = !state.expanded
                    state.preferredKey = if (state.expanded) actions.getOrNull(7)?.key ?: 0
                        else Int.MIN_VALUE
                }, Int.MIN_VALUE)
                val visible = (if (state.expanded) actions.drop(7) else actions.take(7)) + toggle
                val selected = visible.firstOrNull { it.key == state.preferredKey && it.enabled }
                    ?: if (actions.any { it.key == state.preferredKey }) toggle
                    else visible.firstOrNull { it.enabled }
                Column(
                    Modifier.fillMaxWidth().background(backgroundColor)
                        .verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.semantics { heading() })
                    if (subtitle.isNotEmpty()) Text(subtitle, color = secondary,
                        fontSize = 16.sp, maxLines = 2)
                    visible.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { action ->
                                ActionButton(action, Modifier.weight(1f).then(
                                    if (action === selected) Modifier.focusRequester(initial)
                                    else Modifier))
                            }
                        }
                    }
                }
                LaunchedEffect(state.expanded) {
                    withFrameNanos { }
                    if (selected != null) initial.requestFocus()
                }
            }
        }
    }

    /** UI-thread disposal for a View returned by these factories. */
    @JvmStatic
    fun dispose(view: View) {
        (view as ComposeView).disposeComposition()
    }

    @Composable
    private fun Theme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent, onPrimary = backgroundColor, background = backgroundColor,
                onBackground = text, surface = surface, onSurface = text,
            ),
            content = { CompositionLocalProvider(LocalContentColor provides text, content = content) },
        )
    }

    @Composable
    private fun ActionButton(action: Action, modifier: Modifier = Modifier) {
        var hasFocus by remember { mutableStateOf(false) }
        Button(
            onClick = { action.run.run() },
            enabled = action.enabled,
            modifier = modifier.onFocusChanged { hasFocus = it.isFocused }.clearAndSetSemantics {
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
                containerColor = surface, contentColor = text,
                focusedContainerColor = accent, focusedContentColor = backgroundColor,
            ),
        ) {
            if (action.icon != 0) {
                Image(painterResource(action.icon), contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    colorFilter = ColorFilter.tint(LocalContentColor.current))
                Spacer(Modifier.width(10.dp))
            }
            Text(action.label, fontSize = 18.sp, maxLines = 2,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
