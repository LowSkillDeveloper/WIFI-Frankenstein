package com.lsd.wififrankenstein.util

import com.topjohnwu.superuser.Shell

object ChrootCommandExecutor {
    private const val TAG = "ChrootCommandExecutor"

    fun executeWithDetailedLogging(chrootPath: String, command: String): Shell.Result {
        Log.d(TAG, "=== CHROOT COMMAND EXECUTION START ===")
        Log.d(TAG, "Chroot path: $chrootPath")
        Log.d(TAG, "Command: $command")
        Log.d(TAG, "Full command: chroot $chrootPath $command")

        val startTime = System.currentTimeMillis()
        val result = Shell.cmd("chroot $chrootPath $command").exec()
        val executionTime = System.currentTimeMillis() - startTime

        Log.d(TAG, "Execution time: ${executionTime}ms")
        Log.d(TAG, "Exit code: ${result.code}")
        Log.d(TAG, "Is success: ${result.isSuccess}")

        Log.d(TAG, "=== STDOUT (${result.out.size} lines) ===")
        if (result.out.isEmpty()) {
            Log.d(TAG, "<no stdout output>")
        } else {
            result.out.forEachIndexed { index, line ->
                Log.d(TAG, "OUT[$index]: $line")
            }
        }

        Log.d(TAG, "=== STDERR (${result.err.size} lines) ===")
        if (result.err.isEmpty()) {
            Log.d(TAG, "<no stderr output>")
        } else {
            result.err.forEachIndexed { index, line ->
                Log.w(TAG, "ERR[$index]: $line")
            }
        }

        Log.d(TAG, "=== CHROOT COMMAND EXECUTION END ===")
        return result
    }
}