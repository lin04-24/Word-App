package com.example.moci.update

import java.math.BigInteger

/** SemVer 2.0 precedence, accepting the app's historical major.minor tags. */
@ConsistentCopyVisibility
data class SemanticVersion private constructor(
    private val numbers: List<BigInteger>,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        numbers.zip(other.numbers).forEach { (a, b) ->
            a.compareTo(b).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease == other.prerelease -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        prerelease.zip(other.prerelease).forEach { (a, b) ->
            val numericA = a.all(Char::isDigit)
            val numericB = b.all(Char::isDigit)
            val result = when {
                numericA && numericB -> a.toBigInteger().compareTo(b.toBigInteger())
                numericA -> -1
                numericB -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern = Regex(
            "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?" +
                "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$",
        )

        fun parse(value: String): SemanticVersion {
            val match = pattern.matchEntire(value.trim().removePrefix("v").removePrefix("V"))
                ?: throw UpdateException("版本号格式错误：$value")
            val pre = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.') ?: emptyList()
            require(pre.none { it.all(Char::isDigit) && it.length > 1 && it.startsWith('0') }) {
                "预发布版本号含有前导零：$value"
            }
            return SemanticVersion(
                listOf(match.groupValues[1], match.groupValues[2], match.groupValues[3].ifEmpty { "0" })
                    .map(String::toBigInteger),
                pre,
            )
        }
    }
}
