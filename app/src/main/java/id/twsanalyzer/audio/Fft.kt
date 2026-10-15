package id.twsanalyzer.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Radix-2 FFT in-place (kompleks), maju (e^-i). Ukuran harus pangkat 2. */
class Fft(val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && (n and (n - 1)) == 0) { "Ukuran FFT harus pangkat 2" }
        for (i in 0 until n / 2) {
            cosT[i] = cos(2.0 * PI * i / n).toFloat()
            sinT[i] = sin(2.0 * PI * i / n).toFloat()
        }
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - levels)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var k = 0
                for (j in i until i + half) {
                    val l = j + half
                    val c = cosT[k]
                    val s = sinT[k]
                    val tre = re[l] * c + im[l] * s
                    val tim = -re[l] * s + im[l] * c
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    k += step
                }
                i += size
            }
            size *= 2
        }
    }
}
