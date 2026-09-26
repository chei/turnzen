package dev.turnzen.app.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.turnzen.app.TurnzenApplication
import dev.turnzen.app.data.Accounts
import dev.turnzen.app.data.AuthState
import dev.turnzen.app.data.NotificationSettings
import dev.turnzen.goserver.GameSummary
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.ServerId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * One background check: asks every signed-in server for the games awaiting the user, lets
 * [TurnWatch] decide, and does what it says. Run by [TurnCheckWorker], periodically and at
 * the moment the next clock will cross the low-time line.
 */
class TurnCheck(
    private val accounts: Accounts,
    private val settings: () -> NotificationSettings,
    private val store: WatchStateStore,
    private val notifier: Notifier,
    private val schedule: CheckSchedule,
    private val inForeground: () -> Boolean,
) {
    private val lock = Mutex()

    /** [asLowTimeJob]: this run is the scheduled low-time wake-up itself. */
    suspend fun run(asLowTimeJob: Boolean = false) =
        // The server clients are called from the main thread everywhere else in the app; the
        // check keeps to the same thread so it never races the screens over a session.
        withContext(Dispatchers.Main) {
            lock.withLock { check(asLowTimeJob) }
        }

    private suspend fun check(asLowTimeJob: Boolean) {
        val prefs = settings()
        if (!prefs.anyOn) return
        // Started by WorkManager, the process has not restored its sessions yet.
        accounts.restore()

        val answers =
            coroutineScope {
                accounts.sessions
                    .filter { it.isSignedIn }
                    .map { session ->
                        async {
                            session.serverId to
                                try {
                                    Result.success(session.call { it.gamesAwaitingMe() })
                                } catch (e: GoServerException) {
                                    Result.failure<List<GameSummary>>(e)
                                }
                        }
                    }.awaitAll()
            }
        val answered = answers.mapNotNull { (server, r) -> r.getOrNull()?.let { server to it } }.toMap()
        // A session that has signed the user out says why; one the user signed out of does not.
        val signedOut =
            accounts.sessions
                .filter { (it.state.value as? AuthState.SignedOut)?.reason != null }
                .map { it.serverId }
                .toSet()
        val failed = answers.filter { it.second.isFailure }.map { it.first }.toSet() - signedOut

        val input =
            CheckInput(
                answered = answered,
                failed = failed,
                signedOut = signedOut,
                yourMoveOn = prefs.yourMove,
                lowTimeOn = prefs.lowTime,
                inForeground = inForeground(),
                summaryShown = notifier.summaryShown,
                now = Instant.now(),
            )
        lateinit var outcome: CheckOutcome
        store.update { state -> TurnWatch.check(state, input).also { outcome = it }.state }

        val name = { server: ServerId -> accounts.session(server).serverName }
        when (val summary = outcome.summary) {
            SummaryAction.Keep -> Unit
            SummaryAction.Cancel -> notifier.cancelSummary()
            is SummaryAction.Post -> notifier.postSummary(summary.games, summary.flagged, summary.alert, name)
        }
        outcome.lowTimeCancels.forEach(notifier::cancelLowTime)
        outcome.lowTimeAlerts.forEach { notifier.postLowTime(it, name(it.id.server)) }
        outcome.signedOutAlerts.forEach { notifier.postSignedOut(it, name(it)) }
        schedule.lowTimeAt(outcome.nextLowTimeCheck, input.now, asLowTimeJob)
    }
}

/** WorkManager's handle on [TurnCheck]. */
class TurnCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as TurnzenApplication).container
        container.turnCheck.run(asLowTimeJob = inputData.getBoolean(CheckSchedule.KEY_LOW_TIME, false))
        return Result.success()
    }
}

/**
 * The two kinds of scheduled check: the periodic one, at the interval from Settings, and one
 * at the moment the next clock crosses the low-time line.
 */
class CheckSchedule(
    context: Context,
) {
    private val work = WorkManager.getInstance(context.applicationContext)

    // A check is a few kilobytes: any network will do.
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Starts, reschedules or stops the periodic check to match [settings]. [checkNow]: the
     * user has just changed them, so one check runs straight away rather than a period later
     * — which also sets the low-time wake-up without waiting.
     */
    fun apply(
        settings: NotificationSettings,
        checkNow: Boolean = false,
    ) {
        if (!settings.anyOn) {
            work.cancelUniqueWork(PERIODIC)
            work.cancelUniqueWork(LOW_TIME)
            work.cancelUniqueWork(NOW)
            return
        }
        work.enqueueUniquePeriodicWork(
            PERIODIC,
            // Keeps the running schedule unless the interval changed.
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TurnCheckWorker>(settings.checkEvery.minutes, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build(),
        )
        if (checkNow) {
            work.enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<TurnCheckWorker>().setConstraints(constraints).build(),
            )
        }
    }

    /**
     * Wakes a check at [at], or cancels the wake-up when null. [fromLowTimeJob]: asked by the
     * low-time job itself, which would cancel itself by replacing its own unique work; the
     * next one is chained after it instead, and its delay starts when this one ends.
     */
    fun lowTimeAt(
        at: Instant?,
        now: Instant,
        fromLowTimeJob: Boolean,
    ) {
        if (at == null) {
            if (!fromLowTimeJob) work.cancelUniqueWork(LOW_TIME)
            return
        }
        val request =
            OneTimeWorkRequestBuilder<TurnCheckWorker>()
                .setInitialDelay(Duration.between(now, at).coerceAtLeast(Duration.ZERO))
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_LOW_TIME to true))
                .build()
        val policy = if (fromLowTimeJob) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        work.enqueueUniqueWork(LOW_TIME, policy, request)
    }

    companion object {
        const val KEY_LOW_TIME = "low_time"
        private const val PERIODIC = "turn-check"
        private const val LOW_TIME = "turn-check-low-time"
        private const val NOW = "turn-check-now"
    }
}
