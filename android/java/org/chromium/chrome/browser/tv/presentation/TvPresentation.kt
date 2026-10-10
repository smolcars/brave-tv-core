// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv.presentation

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** Presentation only: callbacks retain native tab, profile and security ownership. */
object TvPresentation {
    private val background = Color(0xFF151719)
    private val surface = Color(0xFF25282B)
    private val text = Color(0xFFF3EFE7)
    private val secondary = Color(0xFFBBB9B3)
    private val accent = Color(0xFFC7D8AF)

    class Action(val label: String, val enabled: Boolean, val run: Runnable)

    @JvmStatic
    fun home(
        context: Context,
        title: String,
        subtitle: String,
        attribution: String,
        actions: List<Action>,
        links: List<Action>,
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool)
        setContent {
            Theme {
                val initial = remember { FocusRequester() }
                Column(
                    Modifier.fillMaxSize()
                        .background(Brush.linearGradient(listOf(Color(0xFF222827), background)))
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Text(title, fontSize = 36.sp, fontWeight = FontWeight.Medium)
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

    @Composable
    private fun Theme(content: @Composable () -> Unit) {
        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = accent, onPrimary = background, background = background,
                onBackground = text, surface = surface, onSurface = text,
            ),
            content = content,
        )
    }

    @Composable
    private fun ActionButton(action: Action, modifier: Modifier = Modifier) {
        Button(
            onClick = { action.run.run() },
            enabled = action.enabled,
            modifier = modifier,
            scale = ButtonDefaults.scale(focusedScale = 1.02f),
            colors = ButtonDefaults.colors(
                containerColor = surface, contentColor = text,
                focusedContainerColor = accent, focusedContentColor = background,
            ),
        ) {
            Text(action.label, fontSize = 18.sp, maxLines = 2,
                modifier = Modifier.padding(vertical = 8.dp))
        }
    }
}
