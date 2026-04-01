package com.attius.homefrontalert

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

object YeelightController {
    private const val TAG = "YeelightController"
    private const val TCP_PORT = 55443
    private const val UDP_MULTICAST = "239.255.255.250"
    private const val UDP_PORT = 1982
    private const val MIIO_UDP_PORT = 54321

    // ── UDP Auto-Discovery (Standard Yeelight Only) ──────────────────────────

    fun discoverDevice(callback: (String?) -> Unit) {
        thread {
            var socket: MulticastSocket? = null
            try {
                socket = MulticastSocket()
                socket.soTimeout = 3000

                val message = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1982\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "ST: wifi_bulb\r\n"

                val sendData = message.toByteArray()
                val group = InetAddress.getByName(UDP_MULTICAST)
                val sendPacket = DatagramPacket(sendData, sendData.size, group, UDP_PORT)
                socket.send(sendPacket)

                val receiveData = ByteArray(1024)
                val receivePacket = DatagramPacket(receiveData, receiveData.size)
                
                socket.receive(receivePacket)
                callback(receivePacket.address.hostAddress)
            } catch (e: Exception) {
                Log.e(TAG, "Yeelight Discovery failed", e)
                callback(null)
            } finally {
                socket?.close()
            }
        }
    }

    // ── Command Dispatcher ───────────────────────────────────────────────────

    private fun sendCommand(ip: String, token: String?, vararg commandStrs: String) {
        thread {
            for (commandStr in commandStrs) {
                if (!token.isNullOrBlank() && token.length == 32) {
                    sendMiioCommandSync(ip, token, commandStr)
                } else {
                    sendTcpCommandSync(ip, commandStr)
                }
                Thread.sleep(150) // 150ms spacer allows bulb CPU to process JSON before next packet hits
            }
        }
    }

    // ── Standard TCP JSON Command ──────────────────────────────────────────────

    private fun sendTcpCommandSync(ip: String, commandStr: String) {
        var socket: Socket? = null
        try {
            Log.d(TAG, "Sending Unencrypted TCP to $ip: $commandStr")
            socket = Socket(ip, TCP_PORT)
            socket.soTimeout = 2000
            val out = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            out.write(commandStr + "\r\n")
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Failed TCP command to $ip", e)
        } finally {
            try { socket?.close() } catch (e: Exception) {}
        }
    }

    // ── Encrypted Miio UDP Command (Crypto Logic) ────────────────────────────

    private fun sendMiioCommandSync(ip: String, tokenHex: String, commandStr: String) {
        try {
            Log.d(TAG, "Sending Encrypted UDP to $ip: $commandStr")
            val token = hexStringToByteArray(tokenHex)
            val md5 = MessageDigest.getInstance("MD5")
            val key = md5.digest(token)
            md5.reset()
            
            val ivInput = ByteArray(key.size + token.size)
            System.arraycopy(key, 0, ivInput, 0, key.size)
            System.arraycopy(token, 0, ivInput, key.size, token.size)
            val iv = md5.digest(ivInput)
            md5.reset()

            val socket = MulticastSocket()
            socket.soTimeout = 3000
            val address = InetAddress.getByName(ip)

            // Handshake
            val hello = hexStringToByteArray("21310020ffffffffffffffffffffffffffffffffffffffffffffffffffffffff")
            socket.send(DatagramPacket(hello, hello.size, address, MIIO_UDP_PORT))

            val receiveData = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveData, receiveData.size)
            socket.receive(receivePacket)

            // Extract Details
            val resp = receivePacket.data
            val deviceId = ByteArray(4)
            System.arraycopy(resp, 8, deviceId, 0, 4)
            
            val stampBuf = ByteBuffer.allocate(4)
            stampBuf.put(resp, 12, 4)
            val stamp = stampBuf.getInt(0)

            // Encrypt Payload
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val encryptedPayload = cipher.doFinal(commandStr.toByteArray())

            // Build Final Packet Header
            val packetLen = 32 + encryptedPayload.size
            val header = ByteBuffer.allocate(32)
            header.put(0x21).put(0x31)
            header.putShort(packetLen.toShort())
            header.putInt(0) // Unknown
            header.put(deviceId)
            header.putInt(stamp + 1) // Advance stamp
            header.position(0)
            val headerBytes = ByteArray(16)
            header.get(headerBytes)

            // Calculate Checksum: MD5(Header + Token + EncryptedPayload)
            md5.update(headerBytes)
            md5.update(token)
            md5.update(encryptedPayload)
            val checksum = md5.digest()

            // Final Assembly: Header + Checksum + EncryptedPayload
            val finalPacket = ByteBuffer.allocate(packetLen)
            finalPacket.put(headerBytes)
            finalPacket.put(checksum)
            finalPacket.put(encryptedPayload)

            val payloadBytes = finalPacket.array()
            socket.send(DatagramPacket(payloadBytes, payloadBytes.size, address, MIIO_UDP_PORT))
            socket.close()

        } catch (e: Exception) {
            Log.e(TAG, "Failed Miio Encrypted UDP command to $ip", e)
        }
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
        }
        return data
    }

    // ── Application Alert Triggers ────────────────────────────────────────────

    fun triggerOff(context: Context) {
        val prefs = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("yeelight_enabled", false)) return

        val ip = prefs.getString("yeelight_ip", null)
        val token = prefs.getString("yeelight_token", null)?.trim()
        
        if (!ip.isNullOrBlank()) {
            val isAlreadyOff = prefs.getBoolean("yeelight_is_off", false)
            if (isAlreadyOff) return
            
            val allClearFlow = "5000, 1, 65280, 100, 30000, 2, 4000, 20"
            sendCommand(ip, token, 
                buildJsonCommand(1, "set_power", JSONArray().put("on").put("smooth").put(1000)),
                buildJsonCommand(2, "start_cf", JSONArray().put(1).put(1).put(allClearFlow)),
                buildJsonCommand(3, "cron_add", JSONArray().put(0).put(10))
            )
            
            prefs.edit().putBoolean("yeelight_is_off", true).apply()
        }
    }

    fun triggerAlert(context: Context, type: AlertType, isLocal: Boolean) {
        val prefs = context.getSharedPreferences("HomeFrontAlertsPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("yeelight_enabled", false)) return

        val ip = prefs.getString("yeelight_ip", null)
        if (ip.isNullOrBlank()) {
            Log.w(TAG, "Yeelight enabled but IP is blank. Skipping.")
            return
        }

        prefs.edit().putBoolean("yeelight_is_off", false).apply()
        val token = prefs.getString("yeelight_token", null)?.trim()

        when (type) {
            AlertType.URGENT -> {
                val brightRed = 16724736
                val redPulse = "1000, 1, $brightRed, 100, 1000, 1, $brightRed, 60"
                sendCommand(ip, token, 
                    buildJsonCommand(1, "set_power", JSONArray().put("on").put("sudden").put(0)),
                    buildJsonCommand(2, "start_cf", JSONArray().put(30).put(1).put(redPulse)),
                    buildJsonCommand(3, "cron_add", JSONArray().put(0).put(45))
                )
            }
            AlertType.CAUTION -> {
                if (isLocal) {
                    val doubleFlashOrange = "500, 1, 16753920, 100, 500, 1, 16753920, 10"
                    sendCommand(ip, token, 
                        buildJsonCommand(1, "set_power", JSONArray().put("on").put("sudden").put(0)),
                        buildJsonCommand(2, "start_cf", JSONArray().put(2).put(1).put(doubleFlashOrange)),
                        buildJsonCommand(3, "cron_add", JSONArray().put(0).put(45))
                    )
                } else {
                    sendCommand(ip, token, 
                        buildJsonCommand(1, "set_power", JSONArray().put("on").put("smooth").put(1000)),
                        buildJsonCommand(2, "set_scene", JSONArray().put("ct").put(3500).put(50)),
                        buildJsonCommand(3, "cron_add", JSONArray().put(0).put(45))
                    )
                }
            }
            AlertType.CALM -> {
                // Handled intentionally natively via Status Dashboard dropping to GREEN and firing triggerOff() !
            }
        }
    }

    private fun buildJsonCommand(id: Int, method: String, params: JSONArray): String {
        val json = JSONObject()
        json.put("id", id)
        json.put("method", method)
        json.put("params", params)
        return json.toString()
    }
}
