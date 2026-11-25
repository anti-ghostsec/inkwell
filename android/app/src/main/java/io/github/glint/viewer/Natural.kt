package io.github.glint.viewer

/**
 * Names as people count: "page2" before "page10", capitals or not. Runs of
 * digits compare as numbers (leading zeros don't count); ties fall back to
 * the plain order, so no two different names are ever "the same".
 */
object Natural : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isAsciiDigit() && b[j].isAsciiDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isAsciiDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isAsciiDigit()) ej++
                var si = i
                while (si < ei && a[si] == '0') si++
                var sj = j
                while (sj < ej && b[sj] == '0') sj++
                // More digits is a bigger number; then digit by digit.
                val la = ei - si
                val lb = ej - sj
                if (la != lb) return la.compareTo(lb)
                for (k in 0 until la) {
                    val d = a[si + k].compareTo(b[sj + k])
                    if (d != 0) return d
                }
                i = ei
                j = ej
            } else {
                val d = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (d != 0) return d
                i++
                j++
            }
        }
        val rest = (a.length - i).compareTo(b.length - j)
        return if (rest != 0) rest else a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
