package ai.hindidub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import ai.hindidub.core.IntentRelay
import ai.hindidub.ui.AppRoot
import ai.hindidub.ui.AppViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: AppViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel = ViewModelProvider(
            this, AppViewModel.factory(application)
        )[AppViewModel::class.java]
        IntentRelay.handle(intent)
        setContent { AppRoot(viewModel) }
    }

    /** Every "share to HindiDubber" lands here, including while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IntentRelay.handle(intent)
    }
}
