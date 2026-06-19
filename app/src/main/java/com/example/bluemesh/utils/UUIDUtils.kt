package com.example.bluemesh.utils

/**
 * Normalizes a UUID string by stripping dashes and converting to lowercase.
 */
fun normalizeUuid(uuid: String): String {
    return uuid.replace("-", "").lowercase()
}

/**
 * Checks if two UUID strings match, supporting short (16-char) and full-length UUID representations.
 */
fun uuidsMatch(a: String, b: String): Boolean {
    val s = normalizeUuid(a)
    val p = normalizeUuid(b)
    return s == p || (p.length == 16 && s.startsWith(p)) || (s.length == 16 && p.startsWith(s))
}
