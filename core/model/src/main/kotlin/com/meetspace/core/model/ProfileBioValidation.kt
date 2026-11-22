package com.meetspace.core.model

object ProfileBioValidation {
    const val MAX_CHARACTERS = 500

    fun normalize(value: String): String = value.replace("\r\n", "\n")

    fun characterCount(value: String): Int = normalize(value).let { it.codePointCount(0, it.length) }

    fun fit(value: String): String = buildString {
        normalize(value).codePoints().filter(::allowed).limit(MAX_CHARACTERS.toLong()).forEachOrdered { appendCodePoint(it) }
    }

    fun isValid(normalized: String, limit: Int = MAX_CHARACTERS): Boolean =
        normalized.codePointCount(0, normalized.length) <= limit && normalized.codePoints().allMatch(::allowed)

    private fun allowed(codePoint: Int): Boolean =
        codePoint !in Character.MIN_SURROGATE.code..Character.MAX_SURROGATE.code &&
            (!Character.isISOControl(codePoint) || codePoint == '\n'.code || codePoint == '\t'.code)
}
