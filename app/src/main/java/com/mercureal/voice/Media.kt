package com.mercureal.voice

import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Media transport: play, pause, next, previous, what's playing.
 *
 * With notification access granted, controls go to the active MediaSession (the music app
 * that last played). Without access, play/pause/next/previous fall back to sending the media
 * key event through AudioManager. "What's playing" needs a session, so it asks for access.
 */
object MediaControl {
    /** op: "play", "pause", "next", "previous", "now". */
    fun run(ctx: Context, op: String): Actions.Result {
        if (!NotifyAccess.granted(ctx)) {
            return if (op == "now") {
                Actions.Result(false, "i need notification access to see what's playing. turn on mercureal in notification access")
            } else {
                keyFallback(ctx, op)
            }
        }
        val mgr = ctx.getSystemService(MediaSessionManager::class.java)
            ?: return Actions.Result(false, "media sessions aren't available on this phone")
        val controllers: List<MediaController> = try {
            mgr.getActiveSessions(NotifyAccess.component(ctx))
        } catch (e: SecurityException) {
            return Actions.Result(false, "turn on notification access for mercureal to control media")
        }
        val c = controllers.firstOrNull()
            ?: return Actions.Result(false, "no active music session. start something in a music app first")

        val controls = c.transportControls
        return when (op) {
            "now" -> nowPlaying(ctx, c)
            "play" -> { controls.play(); Actions.Result(true, "playing") }
            "pause" -> { controls.pause(); Actions.Result(true, "paused") }
            "next" -> { controls.skipToNext(); Actions.Result(true, "next track") }
            "previous" -> { controls.skipToPrevious(); Actions.Result(true, "previous track") }
            else -> Actions.Result(false, "sorry, i didn't catch a media command")
        }
    }

    private fun nowPlaying(ctx: Context, c: MediaController): Actions.Result {
        val meta: MediaMetadata? = c.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        val state = c.playbackState?.state
        val app = c.packageName.orEmpty()
        if (title.isEmpty()) {
            val what = if (state == PlaybackState.STATE_PLAYING) "something is playing" else "nothing is playing"
            return Actions.Result(true, "$what in ${appName(ctx, app)}, but no track info")
        }
        val who = if (artist.isEmpty()) "" else " by $artist"
        val verb = if (state == PlaybackState.STATE_PLAYING) "playing" else "paused"
        return Actions.Result(true, "$verb $title$who in ${appName(ctx, app)}")
    }

    /** Sends the media key to the system. Works only as far as the active player reacts to it. */
    private fun keyFallback(ctx: Context, op: String): Actions.Result {
        val code = when (op) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return Actions.Result(false, "sorry, i didn't catch a media command")
        }
        val am = ctx.getSystemService(AudioManager::class.java)
            ?: return Actions.Result(false, "no audio service")
        return try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            Actions.Result(true, "sent $op. turn on notification access for more reliable control")
        } catch (e: SecurityException) {
            Actions.Result(false, "this phone blocked media control")
        }
    }

    private fun appName(ctx: Context, pkg: String): String = try {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }
}
