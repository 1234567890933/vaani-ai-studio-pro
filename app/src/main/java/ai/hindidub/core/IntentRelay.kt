package ai.hindidub.core

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries a shared video from the Activity to the ViewModel.
 *
 * v3 read `activity.intent` inside a LaunchedEffect(Unit), so a second "share to HindiDubber"
 * while the app was alive did nothing. This relay is updated from onNewIntent and the UI
 * collects it, so every share lands.
 */
object IntentRelay {

    data class Incoming(val uri: Uri, val name: String, val at: Long = System.currentTimeMillis())

    private val _incoming = MutableStateFlow<Incoming?>(null)
    val incoming: StateFlow<Incoming?> = _incoming.asStateFlow()

    fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        @Suppress("DEPRECATION")
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val mime = intent.type.orEmpty()
        if (!mime.startsWith("video/")) return
        _incoming.value = Incoming(uri, uri.lastPathSegment ?: "shared video")
    }

    fun consume() {
        _incoming.value = null
    }
}
