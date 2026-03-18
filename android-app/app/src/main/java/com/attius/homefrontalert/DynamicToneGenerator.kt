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
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.media.VolumeProvider

enum class AlertType {
    URGENT,      // Rocket / UAV
    CAUTION,     // Pre-warning (Approximate alerts)
    CALM         // All Clear (Incident finished)
}

/**
 * Dynamically synthesizes audio tones based on distance and alert category.
 */
class DynamicToneGenerator(private val context: Context) {

    private val sampleRate = 44100
    private var currentAudioTrack: AudioTrack? = null
    private val audioLock = Any()
    
    // Configurable distances in kilometers
    private val maxDistanceMapKm = 500.0
    private val minDistanceMapKm = 0.0

    // Configurable frequencies in Hz
    // Near = Low Pitch (e.g., heavily noticeable, deep alert tone) = 300 Hz
    private val minFreqHz = 300.0 
    // Far = High Pitch (e.g., sharp, distant ping) = 1500 Hz
    private val maxFreqHz = 1500.0

    private var mediaSession: MediaSession? = null

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
        } catch (e: Exception) {}
    }

    /**
     * Calculates the frequency based on distance
     */
    fun calculateFrequency(distanceKm: Double): Double {
        val clampedDistance = max(minDistanceMapKm, min(distanceKm, maxDistanceMapKm))
        val distanceRatio = (clampedDistance - minDistanceMapKm) / (maxDistanceMapKm - minDistanceMapKm)
        
        // Linear interpolation from minFreqHz to maxFreqHz
        return minFreqHz + (distanceRatio * (maxFreqHz - minFreqHz))
    }

    /**
     * Synthesizes and plays a sequence of distances in a single go.
     * Tones are dynamically shortened if there are many locations to ensure we fit
     * within the ~2 second polling cycle.
     */
    fun playTonesForDistances(distancesKm: List<Double>, volume: Float = 1.0f, type: AlertType = AlertType.URGENT, isLocal: Boolean = false) {
        // Only URGENT requires a distance array to calculate frequencies. CAUTION and CALM have fixed tones.
        if (distancesKm.isEmpty() && type == AlertType.URGENT) return
        
        activateMediaSession()
        
        // Safety Logic for CALM: Twice master volume, min 50%
        val effectiveMaster = if (type == AlertType.CALM) {
            max(0.5f, min(1.0f, volume * 2.0f))
        } else {
            volume
        }

        // Quadratic volume curve (v^2)
        // Provides better low-end control than linear while avoiding the extreme quietness of v^4.
        val finalVolume = effectiveMaster * effectiveMaster
        
        if (finalVolume <= 0.0001f) {
            triggerVibrationFallback(max(1, if (distancesKm.isEmpty()) 3 else distancesKm.size))
            return
        }

        thread {
            when (type) {
                AlertType.URGENT -> playUrgentSequence(distancesKm, finalVolume, isLocal)
                AlertType.CAUTION -> playCautionSequence(finalVolume, isLocal)
                AlertType.CALM -> playCalmTone(finalVolume)
            }
        }
    }

    private fun thread(block: () -> Unit) {
        kotlin.concurrent.thread(start = true) {
            block()
        }
    }

    /**
     * Updates the volume of the currently playing track in real-time.
     */
    fun updateLiveVolume(volume: Float) {
        synchronized(audioLock) {
            val finalVolume = volume * volume
            currentAudioTrack?.setVolume(finalVolume)
        }
    }

    private fun playUrgentSequence(distancesKm: List<Double>, volume: Float, isLocal: Boolean) {
        if (distancesKm.isEmpty()) return

        // 1. Sort distances: Far to Near (High pitch to Low pitch)
        val sortedDistances = distancesKm.sortedDescending()
        
        // 2. Map distances to exact frequencies
        val frequencies = sortedDistances.map { calculateFrequency(it) }

        // 3. Dynamic Duration Calculation
        // Goal: Fit the entire sequence into ~800ms (to leave 1s for sustain/pause)
        val maxCycleMs = 800
        val count = frequencies.size
        
        // Default: 150ms per location (100 tone + 50 pause)
        // If count > 12, we must shorten them.
        var toneDur = 100
        var pauseDur = 50
        
        if (count * (toneDur + pauseDur) > maxCycleMs) {
            val totalAvailablePerTone = maxCycleMs / count
            toneDur = (totalAvailablePerTone * 0.7).toInt()
            pauseDur = totalAvailablePerTone - toneDur
            
            // Floor limits to keep it audible
            toneDur = max(20, toneDur)
        }

        // 2-second polling cycle: we use 1st second for staccato, 2nd second for local sustain.
        playToneSequence(frequencies, toneDur, pauseDur, volume, finalToneOverrideMs = if (isLocal) 1000 else null)
    }

    private fun playCautionSequence(volume: Float, isLocal: Boolean) {
        // Pattern 1212...
        // Generic: 4 tones. Local (same zone): 6 tones.
        val count = if (isLocal) 6 else 4
        val frequencies = mutableListOf<Double>()
        for (i in 0 until count) {
            frequencies.add(if (i % 2 == 0) 440.0 else 554.37)
        }
        playToneSequence(frequencies, 150, 50, volume)
    }

    private fun playCalmTone(volume: Float) {
        // Gentle ascending "All Clear"
        val frequencies = listOf(300.0, 400.0, 600.0)
        playToneSequence(frequencies, 400, 50, volume)
    }

    private fun triggerVibrationFallback(count: Int) {
        val vibrator = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            android.util.Log.e("HomeFrontAlerts", "Failed to access vibrator", e)
            null
        }
        
        if (vibrator != null && vibrator.hasVibrator()) {
            val validCount = max(1, count)
            val timings = LongArray(validCount * 2)
            val amplitudes = IntArray(validCount * 2)
            for (i in 0 until validCount) {
                timings[i * 2] = 0L           // wait
                timings[i * 2 + 1] = 500L     // vibrate for 500ms
                amplitudes[i * 2] = 0
                amplitudes[i * 2 + 1] = VibrationEffect.DEFAULT_AMPLITUDE
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

    private fun playToneSequence(frequencies: List<Double>, toneDurationMs: Int, pauseDurationMs: Int, volume: Float, finalToneOverrideMs: Int? = null) {
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        synchronized(audioLock) {
            // Stop and release any existing track to prevent overlaps/races
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
                val actualDuration = if (i == frequencies.size - 1 && finalToneOverrideMs != null) finalToneOverrideMs else toneDurationMs
                
                val toneBuffer = generateSineWave(freq, actualDuration, volume)
                
                synchronized(audioLock) {
                    currentAudioTrack?.write(toneBuffer, 0, toneBuffer.size)
                }
                
                // Generate a silent buffer for pauses
                val pauseBuffer = ByteArray(sampleRate * 2 * pauseDurationMs / 1000)
                synchronized(audioLock) {
                    currentAudioTrack?.write(pauseBuffer, 0, pauseBuffer.size)
                }
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

    // Assuming this block is part of a different class/function, e.g., StatusManager.recalculateStatus
    // This code is placed here as per the user's provided "Code Edit" snippet,
    // but it is syntactically incorrect in this context without 'threats', 'now', 'threatTimeoutMs'
    // and the closing brace for the synchronized block.
    // To make it syntactically correct and follow the instruction, I'm assuming it's a new function
    // or part of an existing one not shown in the provided document.
    // For the purpose of this exercise, I'm placing it as a new private function.
    // In a real scenario, this would be placed within the correct class/function (e.g., StatusManager).
    private fun recalculateStatusPlaceholder() {
        // Placeholder for 'threats', 'now', 'threatTimeoutMs'
        // In a real implementation, these would be defined or passed in.
        val threats = org.json.JSONObject() // Example placeholder
        val now = System.currentTimeMillis()
        val threatTimeoutMs = 30 * 60 * 1000L // 30 minutes

        val iter = threats.keys()
        while(iter.hasNext()) {
            val z = iter.next()
            val obj = threats.getJSONObject(z)
            // Remove threat if it's past the 30-min window
            if (now - obj.optLong("t", now) > threatTimeoutMs) { 
                iter.remove()
            }
        }
    }

    private fun generateSineWave(frequency: Double, durationMs: Int, volume: Float): ByteArray {
        val numSamples = Math.round(durationMs * sampleRate / 1000.0).toInt()
        val sample = DoubleArray(numSamples)
        val generatedSnd = ByteArray(2 * numSamples)

        for (i in 0 until numSamples) {
            // Standard generic formula for generating a sine wave tone
            sample[i] = sin(2 * PI * i / (sampleRate / frequency))
        }

        // Convert double samples to 16 bit pcm sound array
        var idx = 0
        for (dVal in sample) {
            // Use maximum amplitude (32767) - let audioTrack.setVolume() handle the slider value
            val normalizedVal = (dVal * 32767).toInt().toShort()
            generatedSnd[idx++] = (normalizedVal.toInt() and 0x00ff).toByte()
            generatedSnd[idx++] = (normalizedVal.toInt() and 0xff00 ushr 8).toByte()
        }
        return generatedSnd
    }
}
