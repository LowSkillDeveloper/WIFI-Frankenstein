package com.lsd.wififrankenstein.util

import android.util.Log as AndroidLog

object Log {
    const val VERBOSE = AndroidLog.VERBOSE
    const val DEBUG = AndroidLog.DEBUG
    const val INFO = AndroidLog.INFO
    const val WARN = AndroidLog.WARN
    const val ERROR = AndroidLog.ERROR
    const val ASSERT = AndroidLog.ASSERT

    @Volatile
    var suppressedTags: Set<String> = emptySet()

    private fun isSuppressed(tag: String): Boolean =
        tag in suppressedTags

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        if (!isSuppressed(tag)) FileLogger.d(tag, msg)
        return 0
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        if (!isSuppressed(tag)) FileLogger.i(tag, msg)
        return 0
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        if (!isSuppressed(tag)) FileLogger.w(tag, msg)
        return 0
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, tr.message ?: "Throwable", tr)
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, msg)
        return 0
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun v(tag: String, msg: String): Int {
        if (!isSuppressed(tag)) FileLogger.v(tag, msg)
        return 0
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable): Int {
        if (!isSuppressed(tag)) FileLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun wtf(tag: String, msg: String): Int {
        FileLogger.wtf(tag, msg)
        return 0
    }

    @JvmStatic
    fun wtf(tag: String, tr: Throwable): Int {
        FileLogger.wtf(tag, tr.message ?: "WTF Throwable")
        return 0
    }

    @JvmStatic
    fun wtf(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return 0
    }

    @JvmStatic
    fun getStackTraceString(tr: Throwable): String {
        return AndroidLog.getStackTraceString(tr)
    }

    @JvmStatic
    fun isLoggable(tag: String, level: Int): Boolean {
        return AndroidLog.isLoggable(tag, level)
    }

    @JvmStatic
    fun println(priority: Int, tag: String, msg: String): Int {
        if (!isSuppressed(tag)) {
            when (priority) {
                VERBOSE -> FileLogger.v(tag, msg)
                DEBUG -> FileLogger.d(tag, msg)
                INFO -> FileLogger.i(tag, msg)
                WARN -> FileLogger.w(tag, msg)
                ERROR -> FileLogger.e(tag, msg)
                else -> FileLogger.i(tag, msg)
            }
        }
        return AndroidLog.println(priority, tag, msg)
    }
}