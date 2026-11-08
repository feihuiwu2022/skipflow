package com.skipflow.app.service

import android.content.Context
import android.media.AudioManager

/** 广告期间静音媒体音量。只恢复自己静的音：用户原本就静音的话不去动它。 */
class AudioController(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private var mutedByUs = false

    fun mute() {
        if (mutedByUs || am.isStreamMute(AudioManager.STREAM_MUSIC)) return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        mutedByUs = true
    }

    fun restore() {
        if (!mutedByUs) return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        mutedByUs = false
    }
}
