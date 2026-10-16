package dev.dt.kinopub.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.ui.theme.K
import kotlinx.coroutines.delay

/**
 * Device-code login: the TV shows a code, you enter it at kino.pub/device on the phone/Mac.
 * Your password never touches this app.
 */
@Composable
fun AuthScreen(c: AppContainer, onDone: () -> Unit) {
    var userCode by remember { mutableStateOf<String?>(null) }
    var uri by remember { mutableStateOf("kino.pub/device") }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            try {
                val dc = c.api.requestDeviceCode()
                userCode = dc.userCode
                uri = dc.verificationUri.removePrefix("https://").removePrefix("http://")
                error = null
                val deadline = System.currentTimeMillis() + dc.expiresIn * 1000L
                while (System.currentTimeMillis() < deadline) {
                    delay(dc.interval * 1000L)
                    if (c.api.pollDeviceToken(dc.code) != null) {
                        c.registration.afterLogin()
                        onDone()
                        return@LaunchedEffect
                    }
                }
                // Code expired: loop and fetch a fresh one.
            } catch (e: Exception) {
                error = e.message
                delay(5_000)
            }
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Войти в kino.pub", style = MaterialTheme.typography.headlineSmall, color = K.text)
        Text("Откройте $uri и введите код", style = MaterialTheme.typography.titleMedium, color = K.muted)
        Text(
            userCode ?: "······",
            fontSize = 72.sp, letterSpacing = 12.sp, color = K.accent,
            style = MaterialTheme.typography.displaySmall,
        )
        error?.let { Text("Ошибка: $it", color = K.badge, style = MaterialTheme.typography.bodyMedium) }
    }
}
