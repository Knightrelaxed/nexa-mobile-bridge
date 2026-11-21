package com.nexa.mobilebridge.ui.terminal

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nexa.mobilebridge.data.terminal.ModifierState

private const val SENTINEL = "\u200B"

/**
 * Hidden IME input sink verified on Samsung Galaxy A33 (One UI 8.0 / Android 16, Tahap 0):
 * - KeyboardType.Password + VisualTransformation.None + autoCorrectEnabled = false
 *   disables Samsung Keyboard word suggestions, swipe typing, and composing underlines.
 * - Shares a single ModifierState instance from TerminalViewModel (FR-TERM-06, FR-TERM-07).
 * - Emits Enter = 0x0D (\r), Backspace = 0x7F, Tab = 0x09, and CTRL/ALT modified bytes.
 */
@Composable
fun TerminalInputSink(
    modifiers: ModifierState,
    focusRequester: FocusRequester,
    onSendBytes: (ByteArray) -> Unit,
    modifier: Modifier = Modifier
) {
    var tfv by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    BasicTextField(
        value = tfv,
        onValueChange = { new ->
            val text = new.text
            when {
                text.isEmpty() -> {
                    // Backspace deleted the sentinel character
                    onSendBytes(byteArrayOf(0x7F))
                }
                !text.startsWith(SENTINEL) -> {
                    // Clipboard paste or IME text replacement replaced the sentinel
                    val pasted = text.replace(SENTINEL, "")
                    if (pasted.isNotEmpty()) {
                        onSendBytes(modifiers.encodeInput(pasted))
                    }
                }
                text.length > SENTINEL.length -> {
                    val typed = text.removePrefix(SENTINEL)
                    onSendBytes(modifiers.encodeInput(typed))
                }
            }
            tfv = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
        },
        visualTransformation = VisualTransformation.None,
        modifier = modifier
            .focusRequester(focusRequester)
            .size(1.dp)
            .alpha(0.01f)
            .onPreviewKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    when (keyEvent.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onSendBytes(byteArrayOf(0x0D))
                            true
                        }
                        Key.Tab -> {
                            onSendBytes(byteArrayOf(0x09))
                            true
                        }
                        Key.Backspace -> {
                            onSendBytes(byteArrayOf(0x7F))
                            true
                        }
                        Key.Escape -> {
                            onSendBytes(byteArrayOf(0x1B))
                            true
                        }
                        else -> false
                    }
                } else {
                    false
                }
            },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.None
        )
    )
}
