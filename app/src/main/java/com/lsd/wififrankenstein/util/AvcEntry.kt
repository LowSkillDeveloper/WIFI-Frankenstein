package com.lsd.wififrankenstein.util

data class AvcEntry(
    val scontext: String,
    val tcontext: String,
    val tclass: String,
    val permissions: String,
    val rawLine: String,
    val processName: String?
) {
    val domain: String get() = scontext.split(":").getOrNull(2) ?: scontext

    fun toReadable(): String = buildString {
        append("$domain -> $tcontext ($tclass): { $permissions }")
        if (processName != null) append(" [comm=$processName]")
    }

    fun toMagiskRule(): String? {
        val dom = domain
        val expanded = expandPermissions(permissions, tclass)
        return when {
            tclass == "capability" -> "allow $dom self capability { $expanded }"
            tclass == "capability2" -> "allow $dom self capability2 { $expanded }"
            tclass == "filesystem" -> "allow $dom $tcontext filesystem { $expanded }"
            tclass == "dir" -> "allow $dom $tcontext dir { $expanded }"
            tclass == "file" -> "allow $dom $tcontext file { $expanded }"
            tclass == "lnk_file" -> "allow $dom $tcontext lnk_file { $expanded }"
            tclass == "blk_file" -> "allow $dom $tcontext blk_file { $expanded }"
            tclass == "chr_file" -> "allow $dom $tcontext chr_file { $expanded }"
            tclass == "fifo_file" -> "allow $dom $tcontext fifo_file { $expanded }"
            tclass == "sock_file" -> "allow $dom $tcontext sock_file { $expanded }"
            tclass == "process" -> "allow $dom $tcontext process { $expanded }"
            else -> "allow $dom $tcontext $tclass { $expanded }"
        }
    }

    private fun expandPermissions(perms: String, tclass: String): String {
        val base = perms.split("\\s+".toRegex()).toSet()
        return when {
            base.intersect(setOf("execute", "execute_no_trans")).isNotEmpty() && tclass == "file" ->
                (base + setOf("execute", "execute_no_trans", "open", "read", "getattr", "map"))
                    .joinToString(" ")

            base.contains("execute") && tclass == "lnk_file" ->
                (base + setOf("read", "getattr")).joinToString(" ")

            base.contains("execute") && tclass == "dir" ->
                (base + setOf("search", "open", "read", "getattr")).joinToString(" ")

            base.contains("execute") && tclass == "fifo_file" ->
                (base + setOf("read", "write", "open", "getattr")).joinToString(" ")

            else -> perms
        }
    }
}
