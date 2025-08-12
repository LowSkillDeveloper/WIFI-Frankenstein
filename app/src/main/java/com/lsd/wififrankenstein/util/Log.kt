package com.lsd.wififrankenstein.util

import android.util.Log as AndroidLog

object Log {
    const val VERBOSE = AndroidLog.VERBOSE
    const val DEBUG = AndroidLog.DEBUG
    const val INFO = AndroidLog.INFO
    const val WARN = AndroidLog.WARN
    const val ERROR = AndroidLog.ERROR
    const val ASSERT = AndroidLog.ASSERT

    @JvmStatic
    fun d(tag: String, msg: String): Int {
        FileLogger.d(tag, msg)
        return AndroidLog.d(tag, msg)
    }

    @JvmStatic
    fun d(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return AndroidLog.d(tag, msg, tr)
    }

    @JvmStatic
    fun i(tag: String, msg: String): Int {
        FileLogger.i(tag, msg)
        return AndroidLog.i(tag, msg)
    }

    @JvmStatic
    fun i(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return AndroidLog.i(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, msg: String): Int {
        FileLogger.w(tag, msg)
        return AndroidLog.w(tag, msg)
    }

    @JvmStatic
    fun w(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return AndroidLog.w(tag, msg, tr)
    }

    @JvmStatic
    fun w(tag: String, tr: Throwable): Int {
        FileLogger.e(tag, tr.message ?: "Throwable", tr)
        return AndroidLog.w(tag, tr)
    }

    @JvmStatic
    fun e(tag: String, msg: String): Int {
        FileLogger.e(tag, msg)
        return AndroidLog.e(tag, msg)
    }

    @JvmStatic
    fun e(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return AndroidLog.e(tag, msg, tr)
    }

    @JvmStatic
    fun v(tag: String, msg: String): Int {
        FileLogger.v(tag, msg)
        return AndroidLog.v(tag, msg)
    }

    @JvmStatic
    fun v(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return AndroidLog.v(tag, msg, tr)
    }

    @JvmStatic
    fun wtf(tag: String, msg: String): Int {
        FileLogger.wtf(tag, msg)
        return AndroidLog.wtf(tag, msg)
    }

    @JvmStatic
    fun wtf(tag: String, tr: Throwable): Int {
        FileLogger.wtf(tag, tr.message ?: "WTF Throwable")
        return AndroidLog.wtf(tag, tr)
    }

    @JvmStatic
    fun wtf(tag: String, msg: String, tr: Throwable): Int {
        FileLogger.e(tag, msg, tr)
        return AndroidLog.wtf(tag, msg, tr)
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
        when (priority) {
            VERBOSE -> FileLogger.v(tag, msg)
            DEBUG -> FileLogger.d(tag, msg)
            INFO -> FileLogger.i(tag, msg)
            WARN -> FileLogger.w(tag, msg)
            ERROR -> FileLogger.e(tag, msg)
            else -> FileLogger.i(tag, msg)
        }
        return AndroidLog.println(priority, tag, msg)
    }
}