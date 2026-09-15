package dev.turnzen.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.turnzen.goserver.GameId
import dev.turnzen.goserver.GoServerException
import dev.turnzen.goserver.LadderStanding
import dev.turnzen.goserver.TournamentRef

/**
 * Ladder standings for the game info sheet, cached per ladder and kept across app restarts.
 *
 * Why persisted rather than a field: reading a standing costs a whole standings page (~54 KB
 * gzipped), and Android reclaims the app process constantly, so an in-memory cache empties
 * itself several times a day and the cost stops depending on anything the app chose. Four
 * running games on one ladder, opened daily for a week, cost 7 fetches this way and up to 28
 * without. See `docs/dgs-ladder-standings.md` §6a.
 *
 * What is stored is the handful of parsed pairs, never the page: a ladder's entry is a
 * timestamp and a line per game, on the order of a hundred bytes.
 *
 * The [ttl] is long on purpose. A rank only moves when somebody finishes a ladder game, which
 * on a correspondence ladder is days apart, and a rank that is a day stale is a cosmetic error
 * in decoration. Two cheaper signals refresh it sooner: a game we are asked about that the
 * entry has never heard of, and a game of ours on that ladder ending.
 */
class LadderStore internal constructor(
    private val prefs: SharedPreferences,
    private val session: SessionManager,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val now: () -> Long = System::currentTimeMillis,
) : LadderStandings {
    constructor(context: Context, session: SessionManager) :
        this(context.getSharedPreferences("turnzen_ladders", Context.MODE_PRIVATE), session)

    companion object {
        private const val DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000

        /** How long a just-written entry is trusted even about games it does not mention. */
        private const val COOLDOWN_MILLIS = 10L * 60 * 1000
    }

    private class Entry(
        val standings: Map<GameId, LadderStanding>,
        val age: Long,
    )

    /**
     * Where the user stands in [id], fetching the ladder once if what is cached cannot answer.
     *
     * Returns null for everything that is simply not a ladder standing: a casual game, a round
     * robin, a ladder the user does not play in, a fetch that failed. Nothing is thrown, a dead
     * session included — this is decoration, and the sheet's own load is what tells the user
     * about a session that has gone.
     */
    override suspend fun standing(
        tournament: TournamentRef?,
        id: GameId,
    ): LadderStanding? {
        if (tournament == null || !tournament.isLadder) return null
        val entry = cached(tournament.id)
        if (entry != null) {
            entry.standings[id]?.let { return it }
            // A miss can mean the entry predates a challenge that has since started, so a miss
            // is allowed to refetch rather than wait out the TTL. But only after a cool-down:
            // otherwise a game that is genuinely absent — the parse found no row of the user's,
            // say — would refetch the whole page on every single sheet opening.
            if (entry.age < COOLDOWN_MILLIS) return null
        }
        val fetched =
            try {
                session.call { it.ladderStandings(tournament) }
            } catch (e: GoServerException) {
                // Offline, signed out, or a page we could not read. The sheet shows no standing.
                return null
            }
        write(tournament.id, fetched)
        return fetched[id]
    }

    /**
     * Forgets a ladder, because a game on it ended — which is exactly the event that moves
     * ranks, the user's own among them. Cheap: the next sheet that needs it pays for one fetch.
     */
    override fun invalidate(tournament: TournamentRef?) {
        val tid = tournament?.id ?: return
        prefs.edit().remove(tid).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private fun cached(tid: String): Entry? {
        val raw = prefs.getString(tid, null) ?: return null
        val lines = raw.split('\n')
        val writtenAt = lines.firstOrNull()?.toLongOrNull() ?: return null
        val age = now() - writtenAt
        // Expiry is exclusive, so an entry is good for the TTL and not a millisecond longer;
        // a negative age means the clock moved backwards under us, and is also expired.
        if (age < 0 || age >= ttlMillis) return null
        val standings =
            lines
                .drop(1)
                .mapNotNull { line ->
                    val parts = line.split(' ')
                    if (parts.size != 4) return@mapNotNull null
                    val your = parts[1].toIntOrNull() ?: return@mapNotNull null
                    val opponent = parts[2].toIntOrNull() ?: return@mapNotNull null
                    GameId(parts[0]) to LadderStanding(your, opponent, parts[3] == "C")
                }.toMap()
        return Entry(standings, age)
    }

    /**
     * One line per game after a timestamp. A hand-rolled format rather than a serialisation
     * library: four fields that never leave this file do not earn a dependency, and a cache
     * that cannot be read back is simply a cache miss.
     */
    private fun write(
        tid: String,
        standings: Map<GameId, LadderStanding>,
    ) {
        val text =
            buildString {
                append(now())
                for ((id, standing) in standings) {
                    append('\n').append(id.value)
                    append(' ').append(standing.yourRank)
                    append(' ').append(standing.opponentRank)
                    append(' ').append(if (standing.youAreChallenger) "C" else "D")
                }
            }
        prefs.edit().putString(tid, text).apply()
    }
}

/**
 * The slice of [LadderStore] the board needs, split out so a view-model test can fake it
 * without an Android context — the same reason `ServerSelection` exists next door.
 */
interface LadderStandings {
    suspend fun standing(
        tournament: TournamentRef?,
        id: GameId,
    ): LadderStanding?

    fun invalidate(tournament: TournamentRef?)
}
