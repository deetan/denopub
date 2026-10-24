package dev.dt.kinopub.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.tv.material3.ButtonDefaults
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.PaddingValues
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.api.ImdbRater
import dev.dt.kinopub.data.api.RateResult
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class RateStep { PICK, SENDING, PAIR, DONE, FAILED }

/**
 * "Rate it on IMDb": ten buttons 1–10, focus on the current rating (or 7), OK sends it to the
 * owner's IMDb account via [ImdbRater]. If this TV isn't paired yet, asks once for the pairing
 * code and then sends the picked value. Back closes without rating. [onDone] gets the new value
 * (null = closed without rating).
 */
@Composable
fun RateDialog(c: AppContainer, imdbId: Long, title: String, current: Int?, onDone: (Int?) -> Unit) {
    val tconst = remember(imdbId) { ImdbRater.tconst(imdbId) }
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(RateStep.PICK) }
    var picked by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }

    fun send(v: Int) {
        picked = v
        step = RateStep.SENDING
        scope.launch {
            when (val r = c.rater.rate(tconst, v)) {
                is RateResult.Ok -> { step = RateStep.DONE; delay(1_500); onDone(r.value) }
                RateResult.NeedPair -> step = RateStep.PAIR
                is RateResult.Failed -> { error = r.message; step = RateStep.FAILED }
            }
        }
    }
    fun claim() {
        if (code.isBlank()) return
        scope.launch {
            step = RateStep.SENDING
            val why = c.rater.pair(code.trim())
            if (why == null) send(picked ?: return@launch) else { error = why; code = ""; step = RateStep.PAIR }
        }
    }

    // requestFocus() "succeeds" before the dialog window has focus, and the first button then
    // took it: keep asking until the wanted control reports focus.
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(step) {
        val target = if (step == RateStep.PAIR) codeFocus else focus
        focused = false
        if (step == RateStep.PICK || step == RateStep.PAIR || step == RateStep.FAILED)
            repeat(40) { runCatching { target.requestFocus() }; delay(30); if (focused) return@LaunchedEffect }
    }
    val trackFocus = Modifier.onFocusChanged { if (it.isFocused) focused = true }

    // Not the platform default width: it clipped the row of ten buttons.
    Dialog(onDismissRequest = { onDone(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
      // While pairing, sit at the top: the TV keyboard covers the lower half of the screen.
      Box(
          Modifier.fillMaxSize().padding(vertical = K.screenPadV),
          contentAlignment = if (step == RateStep.PAIR) Alignment.TopCenter else Alignment.Center,
      ) {
        Column(
            Modifier.width(860.dp).clip(RoundedCornerShape(12.dp)).background(K.surface).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Оценка на IMDb", style = MaterialTheme.typography.labelMedium, color = K.muted)
            Text(title, style = MaterialTheme.typography.headlineSmall, color = K.text, maxLines = 2)
            when (step) {
                RateStep.PICK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        (1..10).forEach { n ->
                            val m = Modifier.size(64.dp).then(if (n == (current ?: 7)) Modifier.focusRequester(focus).then(trackFocus) else Modifier)
                            val pad = PaddingValues(0.dp)
                            val label: @Composable () -> Unit = {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("$n", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                                }
                            }
                            if (n == current) Button(
                                onClick = { send(n) }, modifier = m, contentPadding = pad,
                                colors = ButtonDefaults.colors(containerColor = K.accent, contentColor = K.onAccent),
                            ) { label() }
                            else OutlinedButton(onClick = { send(n) }, modifier = m, contentPadding = pad) { label() }
                        }
                    }
                    Text(
                        current?.let { "Сейчас: ★ $it · OK — сохранить, Back — не оценивать" } ?: "OK — сохранить, Back — не оценивать",
                        style = MaterialTheme.typography.labelMedium, color = K.muted,
                    )
                }
                RateStep.SENDING -> Text("Отправляю${picked?.let { " ★ $it" } ?: ""}…", style = MaterialTheme.typography.titleMedium, color = K.muted)
                RateStep.DONE -> Text("★ $picked — сохранено в IMDb", style = MaterialTheme.typography.headlineSmall, color = K.accent)
                RateStep.FAILED -> {
                    Text("Не получилось: $error", style = MaterialTheme.typography.titleMedium, color = K.badge)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { picked?.let { send(it) } }, modifier = Modifier.focusRequester(focus).then(trackFocus)) { Text("Ещё раз") }
                        OutlinedButton(onClick = { onDone(null) }) { Text("Закрыть") }
                    }
                }
                RateStep.PAIR -> {
                    Text(
                        "Один раз подключите телевизор: на ${ImdbRater.BASE.removePrefix("https://")} → Ratings & sync → " +
                            "«Add another device» и введите код сюда.",
                        style = MaterialTheme.typography.bodyMedium, color = K.text,
                    )
                    error?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = K.badge) }
                    BasicTextField(
                        value = code,
                        onValueChange = {
                            code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(8)
                            if (code.length == 8) claim() // no need to find "Done" on the TV keyboard
                        },
                        singleLine = true,
                        textStyle = TextStyle(color = K.text, fontSize = 32.sp, letterSpacing = 6.sp),
                        cursorBrush = SolidColor(K.accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { claim() }),
                        modifier = Modifier.width(320.dp).focusRequester(codeFocus).then(trackFocus)
                            .border(2.dp, K.accent, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                    Text("Код из 8 символов, действует 10 минут", style = MaterialTheme.typography.labelMedium, color = K.muted)
                }
            }
        }
      }
    }
}
