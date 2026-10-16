package dev.dt.kinopub.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

data class MenuAction(val label: String, val hint: String? = null, val run: () -> Unit)

/**
 * Long-press menu: a short vertical list, first action focused, OK runs it, Back closes.
 * Callers pass only the actions that make sense for the current state.
 */
@Composable
fun ActionMenu(title: String, actions: List<MenuAction>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    // The menu opens while OK is still held (long-press). Ignore that key's repeats and release,
    // otherwise letting go of the button would run the first action. Arm only after a fresh press.
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        repeat(10) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .onPreviewKeyEvent { e ->
                    val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                    if (!ok) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) armed = true
                    !armed // swallow everything until armed
                }
                .width(520.dp).clip(RoundedCornerShape(12.dp)).background(K.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                title, style = MaterialTheme.typography.titleMedium, color = K.text, maxLines = 2,
                modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
            )
            actions.forEachIndexed { i, a ->
                ListItem(
                    selected = false,
                    onClick = { onDismiss(); a.run() },
                    headlineContent = { Text(a.label) },
                    supportingContent = a.hint?.let { { Text(it) } },
                    colors = ListItemDefaults.colors(
                        focusedContainerColor = K.accent,
                        focusedContentColor = K.onAccent,
                    ),
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}
