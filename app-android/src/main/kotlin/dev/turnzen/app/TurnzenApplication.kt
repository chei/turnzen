package dev.turnzen.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TurnzenApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        // The background check follows the notification settings, from the first start on;
        // a change the user makes also runs a check at once.
        MainScope().launch {
            var first = true
            container.settings.settings
                .map { it.notifications }
                .distinctUntilChanged()
                .collect { settings ->
                    container.checkSchedule.apply(settings, checkNow = !first)
                    first = false
                }
        }
        // Any screen of the app coming to the foreground counts as seeing the notifications.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.onForeground()

                override fun onStop(owner: LifecycleOwner) {
                    container.inForeground = false
                }
            },
        )
    }
}
