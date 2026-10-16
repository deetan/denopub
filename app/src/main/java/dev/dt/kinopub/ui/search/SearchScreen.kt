package dev.dt.kinopub.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.api.Item
import dev.dt.kinopub.ui.common.PosterCard
import dev.dt.kinopub.ui.common.splitTitle
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

private val TYPES = listOf(null to "Всё", "movie" to "Фильмы", "serial" to "Сериалы")

/** Search kino.pub: type with the TV keyboard, filter by kind, OK on a poster opens its card. */
@Composable
fun SearchScreen(c: AppContainer, onOpen: (Long) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }
    LaunchedEffect(query, type) {
        if (query.trim().length < 2) { results = emptyList(); status = null; return@LaunchedEffect }
        delay(400) // debounce typing
        status = "Ищу…"
        runCatching { c.repository.search(query.trim(), type) }
            .onSuccess { results = it; status = if (it.isEmpty()) "Ничего не нашлось" else null }
            .onFailure { status = "Ошибка: ${it.message}" }
    }

    Column(
        Modifier.fillMaxSize().padding(horizontal = K.screenPadH, vertical = K.screenPadV),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = K.text, fontSize = 22.sp),
                cursorBrush = SolidColor(K.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { /* debounce already fired */ }),
                modifier = Modifier
                    .width(520.dp)
                    .focusRequester(fieldFocus)
                    // A text field swallows the D-pad; let ↓ / → leave it for the chips and results.
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            Key.DirectionRight -> if (query.isEmpty()) focusManager.moveFocus(FocusDirection.Right) else false
                            else -> false
                        }
                    }
                    .onFocusChanged { fieldFocused = it.isFocused }
                    .background(K.surface, RoundedCornerShape(8.dp))
                    .border(2.dp, if (fieldFocused) K.accent else K.surfaceHi, RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Название фильма или сериала", color = K.muted, fontSize = 22.sp)
                        inner()
                    }
                },
            )
            TYPES.forEach { (t, label) ->
                FilterChip(
                    selected = type == t,
                    onClick = { type = t },
                    colors = FilterChipDefaults.colors(
                        selectedContainerColor = K.surfaceHi,
                        selectedContentColor = K.text,
                        focusedContainerColor = K.accent,
                        focusedContentColor = K.onAccent,
                        focusedSelectedContainerColor = K.accent,
                        focusedSelectedContentColor = K.onAccent,
                    ),
                ) { Text(label) }
            }
        }
        status?.let { Text(it, color = K.muted, style = MaterialTheme.typography.bodyMedium) }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(K.cardGap),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(results, key = { it.id }) { item ->
                PosterCard(
                    title = splitTitle(item.title).first,
                    image = item.posters?.medium,
                    subtitle = listOfNotNull(
                        item.year?.toString(),
                        if (item.type in Item.SERIAL_TYPES) "сериал" else null,
                        item.imdbRating?.let { "IMDb %.1f".format(it) },
                    ).joinToString(" · "),
                    onClick = { onOpen(item.id) },
                )
            }
        }
    }
}
