package com.lsd.wififrankenstein.ui.localnetwork

import android.content.Context
import android.os.PowerManager
import com.lsd.wififrankenstein.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {

    private const val TAG = "WakeOnLan"
    private const val PORT = 9
    private const val MAGIC_PACKET_LENGTH = 102

    fun send(
        context: Context,
        macAddress: String,
        broadcastIp: String = "255.255.255.255"
    ): Pair<Boolean, String> {
        try {
            val cleanMac = macAddress.replace(":", "").replace("-", "").replace(".", "")
            if (cleanMac.length != 12) {
                return Pair(false, "Invalid MAC address length: ${cleanMac.length} hex chars")
            }

            val macBytes = ByteArray(6)
            for (i in 0..5) {
                macBytes[i] = cleanMac.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }

            val magicPacket = ByteArray(MAGIC_PACKET_LENGTH)
            for (i in 0..5) {
                magicPacket[i] = 0xFF.toByte()
            }
            for (i in 6 until MAGIC_PACKET_LENGTH) {
                magicPacket[i] = macBytes[i % 6]
            }

            val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .run { newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WakeOnLan:lock") }
            wakeLock.acquire(10_000)

            try {
                val address = InetAddress.getByName(broadcastIp)
                DatagramSocket().use { socket ->
                    socket.send(DatagramPacket(magicPacket, magicPacket.size, address, PORT))
                }
                Log.d(TAG, "WoL sent to $macAddress via $broadcastIp:$PORT")
                return Pair(true, "WoL packet sent to $macAddress")
            } finally {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WoL failed", e)
            return Pair(false, "WoL failed: ${e.message}")
        }
    }
}
