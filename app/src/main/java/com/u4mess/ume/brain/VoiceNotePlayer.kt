package com.u4mess.ume.brain

import android.media.MediaPlayer

object VoiceNotePlayer {

    private var player: MediaPlayer? = null

    fun play(path: String) {
        stop()
        val mediaPlayer = MediaPlayer()
        runCatching {
            mediaPlayer.setDataSource(path)
            mediaPlayer.setOnCompletionListener {
                it.release()
                if (player === it) player = null
            }
            mediaPlayer.prepare()
            mediaPlayer.start()
            player = mediaPlayer
        }.onFailure {
            mediaPlayer.release()
        }
    }

    fun stop() {
        player?.let { active ->
            runCatching { active.stop() }
            active.release()
        }
        player = null
    }
}
