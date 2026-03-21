package com.attius.homefrontalert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider

enum class AlertType {
    URGENT,   // Rocket / UAV
    CAUTION,  // Pre-warning (approximate alerts)
    CALM      // All Clear (incident finished)
}

enum class WaveType { SINE, TRIANGLE }

/**
 * Dynamically synthesizes audio tones based on distance and alert category.
 *
 * Audio decisions (branch: feature/audio-redesign):
 *   CALM    → Two-note resolve: 330 Hz pickup (180ms) → 523 Hz sustained (720ms)
 *   CAUTION → Wobble (tremolo LFO): Remote = 3 beats 392/466 Hz @ 6 Hz; Local = 5 beats 340/440 Hz @ 9 Hz
 *   URGENT  → Whisper: triangle wave, 420–780 Hz range, dynamic volume scaling, 30ms attack bloom
 */
class DynamicToneGenerator(private val context: Context) {

    private val sampleRate = 44100
    private var currentAudioTrack: AudioTrack? = null
    private val audioLock = Any()

    // Distance bounds (km)
    private val maxDistanceMapKm = 500.0
    private val minDistanceMapKm = 0.0

    // Whisper frequency range — narrower than full 300–1500 Hz, avoids piercing top end
    private val whisperMinFreq = 420.0
    private val whisperMaxFreq = 780.0

    private var mediaSession: MediaSession? = null

    // ── Volume Provider ───────────────────────────────────────────────────────

    private fun setupVolumeProvider() {
        val prefs = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        val maxVol = 100
        val currentVol = (prefs.getFloat("alert_volume", 1.0f) * 100).toInt()

        val volumeProvider = object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, maxVol, currentVol) {
            override fun onSetVolumeTo(v: Int) {
                super.onSetVolumeTo(v)
                setCurrentVolume(v)
                updateSharedVolume(v)
            }
            override fun onAdjustVolume(direction: Int) {
                val currentFloat = prefs.getFloat("alert_volume", 1.0f)
                var newFloat = currentFloat
                if (direction > 0) newFloat += 0.05f
                else if (direction < 0) newFloat -= 0.05f
                newFloat = max(0.0f, min(1.0f, newFloat))
                val newInt = (newFloat * 100).toInt()
                setCurrentVolume(newInt)
                updateSharedVolume(newInt)
            }
        }
        mediaSession?.setPlaybackToRemote(volumeProvider)
    }

    private fun updateSharedVolume(volInt: Int) {
        val volF = volInt / 100f
        context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
            .edit().putFloat("alert_volume", volF).apply()
        updateLiveVolume(volF)
    }

    private fun activateMediaSession() {
        try {
            if (mediaSession == null) {
                mediaSession = MediaSession(context, "AlertVolumeSession")
                setupVolumeProvider()
            }
            mediaSession?.isActive = true
            val state = PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .build()
            mediaSession?.setPlaybackState(state)
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "MediaSession activation failed", e)
        }
    }

    private fun deactivateMediaSession() {
        try {
            if (mediaSession?.isActive == true) {
                val state = PlaybackState.Builder()
                    .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build()
                mediaSession?.setPlaybackState(state)
                mediaSession?.isActive = false
            }
        } catch (e: Exception) { /* ignore */ }
    }

    // ── Frequency Calculations ────────────────────────────────────────────────

    /**
     * Full 300–1500 Hz mapping — preserved for future use / diagnostics.
     */
    fun calculateFrequency(distanceKm: Double): Double {
        val clamped = max(minDistanceMapKm, min(distanceKm, maxDistanceMapKm))
        val ratio = (clamped - minDistanceMapKm) / (maxDistanceMapKm - minDistanceMapKm)
        return 300.0 + ratio * (1500.0 - 300.0)
    }

    /**
     * Whisper frequency mapping: 420–780 Hz.
     * Narrower range reduces perceptual harshness for rapid staccato sequences.
     */
    private fun calculateWhisperFrequency(distanceKm: Double): Double {
        val clamped = max(minDistanceMapKm, min(distanceKm, maxDistanceMapKm))
        val ratio = (clamped - minDistanceMapKm) / (maxDistanceMapKm - minDistanceMapKm)
        return whisperMinFreq + ratio * (whisperMaxFreq - whisperMinFreq)
    }

    /**
     * Dynamic volume scale for large zone batches.
     * Formula: min(1.0, 5 / sqrt(max(5, zoneCount)))
     * Result: ~25 zones = 100%, 100 zones = 50%, 300 zones = 29%, 610 zones = 20%
     */
    private fun calculateVolumeScale(zoneCount: Int): Float {
        return min(1.0f, 5.0f / sqrt(max(5, zoneCount).toFloat()))
    }

    // ── Public Entry Point ────────────────────────────────────────────────────

    fun playTonesForDistances(
        distancesKm: List<Double>,
        volume: Float = 1.0f,
        type: AlertType = AlertType.URGENT,
        isLocal: Boolean = false
    ) {
        if (distancesKm.isEmpty() && type == AlertType.URGENT) return

        activateMediaSession()

        // CALM gets a safety boost: doubled, minimum 50%
        val effectiveMaster = if (type == AlertType.CALM) {
            max(0.5f, min(1.0f, volume * 2.0f))
        } else {
            volume
        }

        // Quadratic volume curve for better low-end slider feel
        val finalVolume = effectiveMaster * effectiveMaster

        if (finalVolume <= 0.0001f) {
            triggerVibrationFallback(max(1, if (distancesKm.isEmpty()) 3 else distancesKm.size))
            return
        }

        thread {
            when (type) {
                AlertType.URGENT  -> playUrgentSequence(distancesKm, finalVolume, isLocal)
                AlertType.CAUTION -> playCautionSequence(finalVolume, isLocal)
                AlertType.CALM    -> playCalmTone(finalVolume)
            }
        }
    }

    private fun thread(block: () -> Unit) {
        kotlin.concurrent.thread(start = true) { block() }
    }

    fun updateLiveVolume(volume: Float) {
        synchronized(audioLock) {
            currentAudioTrack?.setVolume(volume * volume)
        }
    }

    // ── URGENT: Whisper Mode ──────────────────────────────────────────────────

    private fun playUrgentSequence(distancesKm: List<Double>, volume: Float, isLocal: Boolean) {
        if (distancesKm.isEmpty()) return

        val sortedDistances = distancesKm.sortedDescending()  // Far→Near (high→low pitch)
        val zoneCount = sortedDistances.size

        // Whisper: volume scales down for large batches
        val scaledVolume = volume * calculateVolumeScale(zoneCount)

        // Whisper: narrow 420–780 Hz range
        val frequencies = sortedDistances.map { calculateWhisperFrequency(it) }

        // Fit all tones within 1s budget
        val maxCycleMs = 1000
        val count = frequencies.size
        var toneDur = 110
        var pauseDur = 60
        if (count * (toneDur + pauseDur) > maxCycleMs) {
            val perSlot = maxCycleMs / count
            toneDur = max(20, (perSlot * 0.62).toInt())
            pauseDur = max(7, perSlot - toneDur)
        }

        // Whisper: triangle waveform, 30ms attack so tones bloom rather than snap
        playToneSequence(
            frequencies, toneDur, pauseDur, scaledVolume,
            finalToneOverrideMs = if (isLocal) 1000 else null,
            attackMs = 30,
            waveType = WaveType.TRIANGLE
        )
    }

    // ── CAUTION: Wobble (Tremolo LFO) ────────────────────────────────────────

    private fun playCautionSequence(volume: Float, isLocal: Boolean) {
        if (isLocal) {
            // Local / same-zone: 5 beats, lower register, faster LFO — more intense
            playWobbleSequence(
                frequencies  = listOf(340.0, 440.0, 340.0, 440.0, 340.0),
                toneDurationMs  = 210,
                pauseDurationMs = 40,
                volume          = volume,
                lfoHz           = 9.0,
                lfoDepth        = 0.24
            )
        } else {
            // Remote: 3 beats, moderate LFO
            playWobbleSequence(
                frequencies  = listOf(392.0, 466.16, 392.0),
                toneDurationMs  = 240,
                pauseDurationMs = 50,
                volume          = volume,
                lfoHz           = 6.0,
                lfoDepth        = 0.17
            )
        }
    }

    // ── CALM: Two-Note Resolve ────────────────────────────────────────────────

    private fun playCalmTone(volume: Float) {
        // Short 330 Hz pickup, then long 523 Hz sustained resolve
        playToneSequence(
            frequencies     = listOf(330.0, 523.0),
            toneDurationMs  = 180,
            pauseDurationMs = 50,
            volume          = volume,
            finalToneOverrideMs = 720,
            attackMs        = 8
        )
    }

    // ── Wobble Playback (CAUTION-specific) ────────────────────────────────────

    private fun playWobbleSequence(
        frequencies: List<Double>,
        toneDurationMs: Int,
        pauseDurationMs: Int,
        volume: Float,
        lfoHz: Double,
        lfoDepth: Double
    ) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        synchronized(audioLock) {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
            currentAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            currentAudioTrack?.setVolume(volume)
            currentAudioTrack?.play()
        }

        try {
            frequencies.forEach { freq ->
                val toneBuffer = generateWobbleBuffer(freq, toneDurationMs, lfoHz, lfoDepth)
                synchronized(audioLock) { currentAudioTrack?.write(toneBuffer, 0, toneBuffer.size) }
                val pauseBuffer = ByteArray(sampleRate * 2 * pauseDurationMs / 1000)
                synchronized(audioLock) { currentAudioTrack?.write(pauseBuffer, 0, pauseBuffer.size) }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "Error during wobble playback", e)
        } finally {
            synchronized(audioLock) {
                currentAudioTrack?.stop()
                currentAudioTrack?.release()
                currentAudioTrack = null
            }
            deactivateMediaSession()
        }
    }

    // ── Vibration Fallback ────────────────────────────────────────────────────

    private fun triggerVibrationFallback(count: Int) {
        val vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "Failed to access vibrator", e)
            null
        }

        if (vibrator != null && vibrator.hasVibrator()) {
            val valid = max(1, count)
            val timings = LongArray(valid * 2)
            val amplitudes = IntArray(valid * 2)
            for (i in 0 until valid) {
                timings[i * 2] = 0L; timings[i * 2 + 1] = 500L
                amplitudes[i * 2] = 0; amplitudes[i * 2 + 1] = VibrationEffect.DEFAULT_AMPLITUDE
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(timings, -1)
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFrontAlerts", "Vibration failed", e)
            }
        }
    }

    // ── Core Playback ─────────────────────────────────────────────────────────

    private fun playToneSequence(
        frequencies: List<Double>,
        toneDurationMs: Int,
        pauseDurationMs: Int,
        volume: Float,
        finalToneOverrideMs: Int? = null,
        attackMs: Int = 8,
        waveType: WaveType = WaveType.SINE
    ) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        synchronized(audioLock) {
            currentAudioTrack?.stop()
            currentAudioTrack?.release()
            currentAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            currentAudioTrack?.setVolume(volume)
            currentAudioTrack?.play()
        }

        try {
            for (i in frequencies.indices) {
                val freq = frequencies[i]
                val dur = if (i == frequencies.size - 1 && finalToneOverrideMs != null)
                    finalToneOverrideMs else toneDurationMs
                val toneBuffer = generateBuffer(freq, dur, waveType, attackMs)
                synchronized(audioLock) { currentAudioTrack?.write(toneBuffer, 0, toneBuffer.size) }
                val pauseBuffer = ByteArray(sampleRate * 2 * pauseDurationMs / 1000)
                synchronized(audioLock) { currentAudioTrack?.write(pauseBuffer, 0, pauseBuffer.size) }
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "Error during tone playback", e)
        } finally {
            synchronized(audioLock) {
                currentAudioTrack?.stop()
                currentAudioTrack?.release()
                currentAudioTrack = null
            }
            deactivateMediaSession()
        }
    }

    // ── PCM Buffer Generators ─────────────────────────────────────────────────

    /**
     * Generates a PCM buffer for sine or triangle wave with attack/release envelope.
     * AudioTrack.setVolume() controls amplitude — max PCM level is used here.
     */
    private fun generateBuffer(
        frequency: Double,
        durationMs: Int,
        waveType: WaveType = WaveType.SINE,
        attackMs: Int = 8
    ): ByteArray {
        val numSamples = Math.round(durationMs * sampleRate / 1000.0).toInt()
        val generatedSnd = ByteArray(2 * numSamples)

        val attackSamples = min(attackMs * sampleRate / 1000, numSamples / 3)
        val releaseSamples = min(attackSamples, numSamples / 3)

        var idx = 0
        for (i in 0 until numSamples) {
            val raw = when (waveType) {
                WaveType.SINE -> sin(2 * PI * i / (sampleRate / frequency))
                WaveType.TRIANGLE -> {
                    // Triangle: -1 at 0, +1 at half-period, -1 at period
                    val t = (i.toDouble() * frequency / sampleRate) % 1.0
                    if (t < 0.5) (4.0 * t - 1.0) else (3.0 - 4.0 * t)
                }
            }

            val env = when {
                attackSamples > 0 && i < attackSamples ->
                    i.toDouble() / attackSamples
                releaseSamples > 0 && i > numSamples - releaseSamples ->
                    (numSamples - i).toDouble() / releaseSamples
                else -> 1.0
            }

            val pcm = (raw * 32767.0 * env).toInt().coerceIn(-32767, 32767).toShort()
            generatedSnd[idx++] = (pcm.toInt() and 0x00ff).toByte()
            generatedSnd[idx++] = (pcm.toInt() and 0xff00 ushr 8).toByte()
        }
        return generatedSnd
    }

    /**
     * Generates a sine wave with LFO amplitude modulation (tremolo/wobble) and attack/release.
     * Used for CAUTION alerts.
     */
    private fun generateWobbleBuffer(
        frequency: Double,
        durationMs: Int,
        lfoHz: Double,
        lfoDepth: Double
    ): ByteArray {
        val numSamples = Math.round(durationMs * sampleRate / 1000.0).toInt()
        val generatedSnd = ByteArray(2 * numSamples)
        val attackSamples = min(8 * sampleRate / 1000, numSamples / 3)
        val releaseSamples = attackSamples

        var idx = 0
        for (i in 0 until numSamples) {
            val carrier = sin(2 * PI * i / (sampleRate / frequency))
            val lfo = lfoDepth * sin(2 * PI * lfoHz * i / sampleRate)
            val raw = carrier * (1.0 + lfo)

            val env = when {
                i < attackSamples -> i.toDouble() / attackSamples.coerceAtLeast(1)
                i > numSamples - releaseSamples -> (numSamples - i).toDouble() / releaseSamples.coerceAtLeast(1)
                else -> 1.0
            }

            val pcm = (raw * 32767.0 * env).coerceIn(-32767.0, 32767.0).toInt().toShort()
            generatedSnd[idx++] = (pcm.toInt() and 0x00ff).toByte()
            generatedSnd[idx++] = (pcm.toInt() and 0xff00 ushr 8).toByte()
        }
        return generatedSnd
    }
}
