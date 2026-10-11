// Copyright (c) 2026 brave-tv contributors.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

package org.chromium.chrome.browser.tv.presentation

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.util.function.Consumer

/** Unsaved native text editing; browser classification and suggestions stay with the caller. */
object TvAddressEditor {
    const val MAX_TEXT_LENGTH = 8192
    const val MAX_SUGGESTIONS = 6

    class State(initial: String) {
        init { require(initial.length <= MAX_TEXT_LENGTH) }
        internal val field = TextFieldState(initial, TextRange(0, initial.length))
        var error by mutableStateOf("")
        var suggestions by mutableStateOf(emptyList<TvPresentation.Action>())
        fun clear() {
            field.edit { replace(0, length, "") }
            field.undoState.clearHistory()
            error = ""
            suggestions = emptyList()
        }
    }

    class Labels(
        val title: String, val description: String, val go: String,
        val clear: String, val cancel: String, val saved: String,
        val hint: String, val tooLong: String,
    )

    /** UI thread, lifecycle/saved-state owning context; dialog owner disposes the returned view.
     * Callbacks and State belong only to this editor instance. Suggestions are bounded to six. */
    @OptIn(ExperimentalComposeUiApi::class)
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
                    onSubmit.accept(state.field.text.toString())
                }) }
                go.enabled = state.field.text.isNotBlank()
                val clear = remember { TvPresentation.Action(labels.clear, true, Runnable {
                    state.clear()
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
                    InterceptPlatformTextInput(interceptor = remember {
                        PlatformTextInputInterceptor { request, next ->
                            next.startInputMethod(object : PlatformTextInputMethodRequest {
                                override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                                    val connection = request.createInputConnection(outAttributes)
                                    outAttributes.imeOptions = outAttributes.imeOptions or
                                        EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
                                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
                                    return connection
                                }
                            })
                        }
                    }) {
                        BasicTextField(
                            state = state.field,
                            inputTransformation = InputTransformation {
                                if (length > MAX_TEXT_LENGTH) {
                                    revertAllChanges()
                                    state.error = labels.tooLong
                                } else state.error = ""
                            },
                            lineLimits = TextFieldLineLimits.SingleLine,
                            textStyle = TextStyle(color = TvPresentation.text, fontSize = 20.sp),
                            cursorBrush = SolidColor(TvPresentation.accent),
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Text, imeAction = ImeAction.Go),
                            onKeyboardAction = { if (go.enabled) go.run.run() },
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
                    }
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
                        state.suggestions.take(MAX_SUGGESTIONS).forEach { action ->
                            key(action) {
                                TvPresentation.ActionButton(action, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                LaunchedEffect(state) {
                    snapshotFlow { state.field.text.toString() }.collect { onChanged.accept(it) }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    editorFocus.requestFocus()
                }
            }
        }
    }
}
