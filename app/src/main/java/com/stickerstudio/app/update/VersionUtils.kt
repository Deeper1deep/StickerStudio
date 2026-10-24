package com.stickerstudio.app.update

object VersionUtils {

    /** "v1.2.3-beta" -> [1, 2, 3]; null when the name has no numeric dotted form. */
    fun parse(name: String): List<Int>? {
        var s = name.trim()
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1)
        val end = s.indexOfFirst { it == '-' || it == '+' || it == ' ' }
        if (end >= 0) s = s.substring(0, end)
        if (s.isEmpty()) return null
        val parts = s.split('.')
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        return if (numbers.any { it < 0 }) null else numbers
    }

    /** Negative if a < b, 0 if equal, positive if a > b; null if either name is unparsable. */
    fun compareNames(a: String, b: String): Int? {
        val pa = parse(a) ?: return null
        val pb = parse(b) ?: return null
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /** Android only installs an update over an installed app when versionCode increases. */
    fun isNewerCode(remoteCode: Int, installedCode: Int): Boolean = remoteCode > installedCode
}
