package com.lsd.wififrankenstein.ui.localnetwork

import android.content.Context
import android.os.PowerManager
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object WakeOnLan {

    private const val TAG = "WakeOnLan"
    private const val PORT = 9
    private const val MAGIC_PACKET_LENGTH = 102

    internal fun normalizeMac(macAddress: String): String =
        macAddress.replace(":", "").replace("-", "").replace(".", "")

    internal fun buildMagicPacket(cleanMac: String): ByteArray? {
        if (cleanMac.length != 12) return null
        val macBytes = ByteArray(6)
        for (i in 0..5) {
            val byteValue = cleanMac.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: return null
            macBytes[i] = byteValue.toByte()
        }

        val magicPacket = ByteArray(MAGIC_PACKET_LENGTH)
        for (i in 0..5) {
            magicPacket[i] = 0xFF.toByte()
        }
        for (i in 6 until MAGIC_PACKET_LENGTH) {
            magicPacket[i] = macBytes[i % 6]
        }
        return magicPacket
    }

    fun send(
        context: Context,
        macAddress: String,
        broadcastIp: String = "255.255.255.255"
    ): Pair<Boolean, String> {
        try {
            val cleanMac = normalizeMac(macAddress)
            val magicPacket = buildMagicPacket(cleanMac)
            if (magicPacket == null) {
                return Pair(false, context.getString(R.string.nat_invalid_mac, cleanMac.length))
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
                return Pair(true, context.getString(R.string.nat_wol_sent, macAddress))
            } finally {
                wakeLock.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WoL failed", e)
            return Pair(false, context.getString(R.string.nat_wol_failed, e.message))
        }
    }
}
