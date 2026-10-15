package id.twsanalyzer.audio

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

private class Biquad(
    private val b0: Double, private val b1: Double, private val b2: Double,
    private val a1: Double, private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0
    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }
}

/**
 * Pengukur loudness ITU-R BS.1770 / EBU R128:
 * Momentary (400 ms), Short-term (3 s) dan Integrated (dengan gating -70 LUFS / -10 LU).
 */
class LoudnessMeter(sampleRate: Int) {
    private val shelfL: Biquad
    private val shelfR: Biquad
    private val hpL: Biquad
    private val hpR: Biquad

    init {
        val fs = sampleRate.toDouble()
        // Tahap 1: high-shelf (efek kepala)
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / fs)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        val sb0 = (vh + vb * k / q + k * k) / a0
        val sb1 = 2.0 * (k * k - vh) / a0
        val sb2 = (vh - vb * k / q + k * k) / a0
        val sa1 = 2.0 * (k * k - 1.0) / a0
        val sa2 = (1.0 - k / q + k * k) / a0
        // Tahap 2: high-pass RLB
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / fs)
        a0 = 1.0 + k / q + k * k
        val ha1 = 2.0 * (k * k - 1.0) / a0
        val ha2 = (1.0 - k / q + k * k) / a0
        shelfL = Biquad(sb0, sb1, sb2, sa1, sa2)
        shelfR = Biquad(sb0, sb1, sb2, sa1, sa2)
        hpL = Biquad(1.0, -2.0, 1.0, ha1, ha2)
        hpR = Biquad(1.0, -2.0, 1.0, ha1, ha2)
    }

    private var acc = 0.0
    private var accN = 0
    private var accDur = 0.0
    private val sub = ArrayDeque<Double>()          // blok 100 ms
    private val gatingBlocks = ArrayList<Double>()  // blok 400 ms (overlap 75%)

    var momentary = Float.NEGATIVE_INFINITY; private set
    var shortTerm = Float.NEGATIVE_INFINITY; private set
    var integrated = Float.NEGATIVE_INFINITY; private set

    fun process(l: FloatArray, r: FloatArray, n: Int, durationSec: Double) {
        for (i in 0 until n) {
            val yl = hpL.process(shelfL.process(l[i].toDouble()))
            val yr = hpR.process(shelfR.process(r[i].toDouble()))
            acc += yl * yl + yr * yr
        }
        accN += n
        accDur += durationSec
        while (accDur >= 0.1 && accN > 0) {
            sub.addLast(acc / accN)
            acc = 0.0; accN = 0
            accDur -= 0.1
            while (sub.size > 30) sub.removeFirst()

            momentary = lufs(meanLast(4))
            shortTerm = lufs(meanLast(30))
            if (sub.size >= 4) {
                gatingBlocks.add(meanLast(4))
                if (gatingBlocks.size > 72_000) gatingBlocks.removeAt(0) // ~2 jam
                integrated = computeIntegrated()
            }
        }
    }

    fun resetIntegrated() {
        gatingBlocks.clear()
        integrated = Float.NEGATIVE_INFINITY
    }

    private fun meanLast(count: Int): Double {
        val c = minOf(count, sub.size)
        if (c == 0) return 0.0
        var s = 0.0
        for (i in sub.size - c until sub.size) s += sub[i]
        return s / c
    }

    private fun lufs(p: Double): Float =
        if (p <= 1e-20) Float.NEGATIVE_INFINITY else (-0.691 + 10.0 * log10(p)).toFloat()

    private fun computeIntegrated(): Float {
        val absGate = 10.0.pow((-70.0 + 0.691) / 10.0)
        var sum = 0.0; var cnt = 0
        for (p in gatingBlocks) if (p > absGate) { sum += p; cnt++ }
        if (cnt == 0) return Float.NEGATIVE_INFINITY
        val relGate = (sum / cnt) * 0.1 // -10 LU
        sum = 0.0; cnt = 0
        for (p in gatingBlocks) if (p > absGate && p > relGate) { sum += p; cnt++ }
        return if (cnt == 0) Float.NEGATIVE_INFINITY else lufs(sum / cnt)
    }
}
