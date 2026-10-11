// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv.presentation

import android.content.Context
import android.view.KeyEvent
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.util.function.Consumer

/** Unsaved native text editing; browser classification and suggestions stay with the caller. */
object TvAddressEditor {
    class State(initial: String) {
        init { require(initial.length <= 8192) }
        internal var value by mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
        var error by mutableStateOf("")
        var suggestions by mutableStateOf(emptyList<TvPresentation.Action>())
    }

    class Labels(
        val title: String, val description: String, val go: String,
        val clear: String, val cancel: String, val saved: String,
        val hint: String, val tooLong: String,
    )

    /** UI thread, lifecycle/saved-state owning context; dialog owner disposes the returned view.
     * Callbacks and State belong only to this editor instance. Suggestions are bounded to six. */
    @JvmStatic
    fun create(
        context: Context, state: State, labels: Labels,
        onChanged: Consumer<String>, onSubmit: Consumer<String>, onCancel: Runnable,
    ): View = TvPresentation.view(context).apply {
        val metrics = context.resources.displayMetrics
        val maxHeight = (metrics.heightPixels * 0.9f / metrics.density).dp
        setContent {
            TvPresentation.Theme {
                val editorFocus = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                val go = remember { TvPresentation.Action(labels.go, false, Runnable {
                    keyboard?.hide()
                    onSubmit.accept(state.value.text)
                }) }
                go.enabled = state.value.text.isNotBlank()
                val clear = remember { TvPresentation.Action(labels.clear, true, Runnable {
                    state.value = TextFieldValue("")
                    state.error = ""
                    onChanged.accept("")
                    editorFocus.requestFocus()
                }) }
                val cancel = remember { TvPresentation.Action(labels.cancel, true, onCancel) }
                Column(Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    .background(TvPresentation.backgroundColor).padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(labels.title, color = TvPresentation.text, fontSize = 26.sp,
                        modifier = Modifier.semantics { heading() })
                    Text(labels.description, color = TvPresentation.secondary, fontSize = 16.sp)
                    BasicTextField(
                        value = state.value,
                        onValueChange = { value ->
                            if (value.text.length > 8192) {
                                state.error = labels.tooLong
                            } else {
                                val changed = value.text != state.value.text
                                state.value = value
                                state.error = ""
                                if (changed) onChanged.accept(value.text)
                            }
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = TvPresentation.text, fontSize = 20.sp),
                        cursorBrush = SolidColor(TvPresentation.accent),
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (go.enabled) go.run.run()
                        }),
                        modifier = Modifier.fillMaxWidth().focusRequester(editorFocus)
                            .onPreviewKeyEvent {
                                val event = it.nativeKeyEvent
                                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                    if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                                        keyboard?.hide()
                                        (if (go.enabled) go else clear).requestFocus()
                                    }
                                    true
                                } else false
                            }.semantics { contentDescription = labels.title }
                            .background(TvPresentation.surface, RoundedCornerShape(10.dp))
                            .border(1.dp, TvPresentation.secondary, RoundedCornerShape(10.dp))
                            .padding(16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(go, clear, cancel).forEach {
                            TvPresentation.ActionButton(it, Modifier.weight(1f))
                        }
                    }
                    if (state.error.isNotEmpty()) Text(state.error, color = TvPresentation.text,
                        fontSize = 16.sp)
                    Text(labels.hint, color = TvPresentation.secondary, fontSize = 14.sp)
                    if (state.suggestions.isNotEmpty()) {
                        Text(labels.saved, color = TvPresentation.secondary, fontSize = 16.sp)
                        state.suggestions.take(6).forEach { action ->
                            key(action) {
                                TvPresentation.ActionButton(action, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    editorFocus.requestFocus()
                }
            }
        }
    }
}
