package dev.turnzen.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.turnzen.app.notify.OpenRequest
import dev.turnzen.app.ui.TurnzenApp

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as TurnzenApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Not again when the activity is only being recreated (rotation): that tap was handled.
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            TurnzenApp(container)
        }
    }

    /** singleTask: a tapped notification reaches the running activity here. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent?) {
        OpenRequest.fromIntent(intent)?.let { container.openRequests.value = it }
    }
}
