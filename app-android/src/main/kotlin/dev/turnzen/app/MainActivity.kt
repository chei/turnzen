package dev.turnzen.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.turnzen.app.notify.OpenRequest
import dev.turnzen.app.ui.TurnzenApp
import dev.turnzen.app.ui.common.ACTION_SAVE_SGF
import dev.turnzen.app.ui.common.SGF_MIME_TYPE
import dev.turnzen.app.ui.common.lastSharedSgf
import dev.turnzen.app.ui.common.saveSgf

class MainActivity : ComponentActivity() {
    private val container: AppContainer
        get() = (application as TurnzenApplication).container

    /** Android's "Save as" picker, for the share sheet's "Save to device" (see [shareSgf]). */
    private val saveSgfAs =
        registerForActivityResult(ActivityResultContracts.CreateDocument(SGF_MIME_TYPE)) { target ->
            val sgf = lastSharedSgf(this)
            if (target != null && sgf != null) saveSgf(this, target, sgf)
        }

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
        if (intent?.action == ACTION_SAVE_SGF) {
            lastSharedSgf(this)?.let { saveSgfAs.launch(it.name) }
            return
        }
        OpenRequest.fromIntent(intent)?.let { container.openRequests.value = it }
    }
}
