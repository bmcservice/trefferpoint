package de.bmcservice.trefferpoint

import android.util.Base64

/**
 * H.264 Slice-Header `frame_num` Smoother für RtspSdpProxy.
 *
 * **Problem:**
 * SGK GK720X (und ähnliche WLAN-Kameras) macht bei jedem IDR-Frame einen `frame_num`-Reset
 * auf 0. Der SW-Decoder `c2.android.avc.decoder` interpretiert das als unerwarteten Reset
 * und crasht nach mehreren Wiederholungen mit `CodecException 0x80000000`.
 *
 * **Lösung (perspektivisch):**
 * Wir parsen den H.264-Slice-Header, lesen das `frame_num`-Feld und ersetzen es durch
 * einen kontinuierlich wachsenden Wert. Der Decoder sieht nie einen Reset und läuft stabil.
 *
 *   Kamera:   IDR(0) P(1) P(2) ... P(89) | IDR(0) P(1) P(2) ...
 *   Smooth:   IDR(0) P(1) P(2) ... P(89) | IDR(90) P(91) P(92) ...
 *                                          ↑ +90 Offset durch Reset-Detection
 *
 * **Bit-Manipulation:**
 * Slice-Header ist Bit-aligned (Exp-Golomb-codiert für ue(v)-Felder). Wir parsen:
 *   first_mb_in_slice    ue(v)
 *   slice_type           ue(v)
 *   pic_parameter_set_id ue(v)
 *   frame_num            u(log2_max_frame_num_minus4 + 4)   ← TARGET
 *
 * Beim Schreiben behalten wir die exakte Bit-Anzahl (modulo max_frame_num) → die
 * Byte-Größe ändert sich nicht und nachfolgende Slice-Header-Felder bleiben unberührt.
 *
 * **Sicherheits-Annahme:**
 * Slice-Header enthält selten Emulation-Prevention-Bytes (0x000003) in den ersten
 * Bytes. Falls doch erkannt → kein Patch (BitCursor.containsEmulationStart liefert true),
 * Originalwert bleibt → Decoder muss den Reset selbst handhaben (= alter Crash-Pfad).
 */
class H264FrameNumSmoother {

    /** State pro Stream. log2 wird beim ersten SPS-Parse gesetzt. */
    @Volatile var log2MaxFrameNumMinus4: Int = -1; private set
    @Volatile var maxFrameNum: Int = 0; private set
    private var lastSeenFrameNum: Int = -1
    private var frameNumOffset: Int = 0
    private var rewriteCount: Long = 0
    private var resetCount: Long = 0
    private var skipCount: Long = 0  // Patches übersprungen wegen Emulation-Prevention

    /**
     * Parst SPS aus base64-codiertem `sprop-parameter-sets`-Wert.
     * Setzt `log2MaxFrameNumMinus4` und `maxFrameNum`.
     * Liefert true wenn erfolgreich, false wenn SPS unparsbar.
     */
    fun parseSpsFromBase64(spsBase64: String): Boolean {
        return try {
            val rawSps = Base64.decode(spsBase64, Base64.NO_WRAP or Base64.NO_PADDING)
            parseSps(rawSps)
        } catch (e: Exception) {
            AppLog.w(TAG, "SPS Base64-Decode fehlgeschlagen: ${e.message}")
            false
        }
    }

    /**
     * Parst eine NAL-Unit Type 7 (SPS).
     * Erwartet: erstes Byte = NAL-Header (0x67 typisch), Rest = SPS-RBSP mit
     * möglichen Emulation-Prevention-Bytes.
     */
    fun parseSps(spsNalUnit: ByteArray): Boolean {
        if (spsNalUnit.size < 4) return false
        // NAL-Header skip (1 Byte), Rest ist RBSP mit Emulation-Prevention
        val rbsp = stripEmulationPrevention(spsNalUnit, 1)
        val cursor = BitCursor(rbsp, 0)
        try {
            val profileIdc = cursor.readBits(8)
            cursor.readBits(8)  // constraint_set + reserved
            cursor.readBits(8)  // level_idc
            cursor.readUE()     // seq_parameter_set_id

            // High Profiles haben zusätzliche Felder vor log2_max_frame_num_minus4.
            // Baseline (66) / Main (77) haben keine — Standard für SGK-Kamera.
            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                val chromaFormatIdc = cursor.readUE()
                if (chromaFormatIdc == 3) cursor.readBit()  // separate_colour_plane_flag
                cursor.readUE()  // bit_depth_luma_minus8
                cursor.readUE()  // bit_depth_chroma_minus8
                cursor.readBit()  // qpprime_y_zero_transform_bypass_flag
                val scalingMatrixPresent = cursor.readBit()
                if (scalingMatrixPresent == 1) {
                    AppLog.w(TAG, "SPS hat seq_scaling_matrix — Parse-Skip (high-profile, ungewöhnlich)")
                    return false
                }
            }

            val log2 = cursor.readUE()
            if (log2 < 0 || log2 > 12) {
                AppLog.w(TAG, "SPS: log2_max_frame_num_minus4=$log2 außer Range — ignoriere")
                return false
            }
            log2MaxFrameNumMinus4 = log2
            maxFrameNum = 1 shl (log2 + 4)
            lastSeenFrameNum = -1
            frameNumOffset = 0
            rewriteCount = 0
            resetCount = 0
            skipCount = 0
            AppLog.i(TAG, "SPS gepatcht: profile=$profileIdc log2_max_frame_num_minus4=$log2 → " +
                "maxFrameNum=$maxFrameNum (frame_num=${log2 + 4} bits)")
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "SPS Parse-Fehler: ${e.message}")
            return false
        }
    }

    /**
     * Patcht `frame_num` in einem Slice-Header (NAL-Type 1=P-slice oder 5=IDR-slice).
     *
     * @param buf Buffer mit dem RTP-Paket
     * @param sliceBodyOffset Byte-Offset zum Beginn des Slice-Body (NACH NAL-Header)
     * @param sliceBodyLen Länge des verfügbaren Slice-Body-Bytes
     * @param isIdr true für NAL-Type 5 (IDR), false für Type 1 (P-slice)
     * @return true wenn gepatcht, false wenn übersprungen (z.B. Emulation-Prevention erkannt)
     */
    fun patchSliceFrameNum(
        buf: ByteArray,
        sliceBodyOffset: Int,
        sliceBodyLen: Int,
        isIdr: Boolean
    ): Boolean {
        if (log2MaxFrameNumMinus4 < 0 || maxFrameNum <= 0) return false  // SPS nicht gelesen
        if (sliceBodyLen < 4) return false

        // Sicherheit: prüfe ob in den ersten 8 Bytes ein 0x000003-Pattern steht
        // (= Emulation-Prevention). Slice-Header passt fast immer in 4-6 Bytes.
        val checkLen = minOf(8, sliceBodyLen)
        for (i in 0 until checkLen - 2) {
            if (buf[sliceBodyOffset + i].toInt() == 0 &&
                buf[sliceBodyOffset + i + 1].toInt() == 0 &&
                (buf[sliceBodyOffset + i + 2].toInt() and 0xFF) == 0x03) {
                skipCount++
                if (skipCount == 1L) {
                    AppLog.w(TAG, "Slice-Header hat Emulation-Prevention an Pos $i — Patch übersprungen")
                }
                return false
            }
        }

        try {
            // Zuerst: Bit-Position vor frame_num finden durch Forwärts-Parse
            val readCursor = BitCursor(buf, sliceBodyOffset)
            readCursor.readUE()  // first_mb_in_slice
            readCursor.readUE()  // slice_type
            readCursor.readUE()  // pic_parameter_set_id
            // (Annahme: separate_colour_plane_flag = 0 → kein colour_plane_id)
            val frameNumBitPos = readCursor.bitPosition()
            val nBits = log2MaxFrameNumMinus4 + 4
            val origFrameNum = readCursor.readBits(nBits)

            // Reset-Detection: neue frame_num kleiner als letzte gesehene → Stream-Boundary
            if (lastSeenFrameNum >= 0 && origFrameNum < lastSeenFrameNum) {
                frameNumOffset = (frameNumOffset + lastSeenFrameNum + 1) and (maxFrameNum - 1)
                resetCount++
                AppLog.i(TAG, "frame_num Reset erkannt: orig=$origFrameNum < last=$lastSeenFrameNum, " +
                    "neuer offset=$frameNumOffset (Reset #$resetCount)")
            }
            lastSeenFrameNum = origFrameNum

            val outFrameNum = (origFrameNum + frameNumOffset) and (maxFrameNum - 1)
            // IDR-Slices (Type 5) MÜSSEN frame_num=0 haben — H.264 Spec 7.4.3.
            // Wenn wir den Offset auf einen IDR anwenden, würde der Decoder "IDR mit frame_num != 0"
            // sehen → das ist ein Spec-Verstoß. ABER: das ist genau was wir wollen, weil der
            // Decoder dann eben NICHT denkt "neuer IDR resettet alles". Spec-konform wäre
            // hier nur der Reset selbst (frame_num=0 für IDR). Wir wählen pragmatisch den
            // smoothen Pfad: alle Slices kontinuierlich.

            if (outFrameNum == origFrameNum) {
                rewriteCount++
                return false  // kein Rewrite nötig (offset=0)
            }

            // Schreibe outFrameNum an die ermittelte Bit-Position
            val writeCursor = BitCursor(buf, sliceBodyOffset)
            writeCursor.setBitPosition(frameNumBitPos)
            writeCursor.writeBits(outFrameNum, nBits)

            rewriteCount++
            if (rewriteCount <= 3L || rewriteCount % 90L == 0L) {
                AppLog.i(TAG, "frame_num gepatcht: ${if (isIdr) "IDR" else "P"} " +
                    "$origFrameNum → $outFrameNum (offset=$frameNumOffset, count=$rewriteCount)")
            }
            return true
        } catch (e: Exception) {
            AppLog.w(TAG, "Slice-Header Patch fehlgeschlagen: ${e.message}")
            return false
        }
    }

    /** Reset-Counter für neue Session (Reconnect). lastSeenFrameNum bleibt für Continuity. */
    fun resetForNewSession() {
        // Wir behalten frameNumOffset und lastSeenFrameNum:
        // Der Decoder soll auch über Session-Boundaries hinweg einen kontinuierlich
        // wachsenden frame_num sehen. Erst bei stop()/start() volle Re-Init.
    }

    fun fullReset() {
        log2MaxFrameNumMinus4 = -1
        maxFrameNum = 0
        lastSeenFrameNum = -1
        frameNumOffset = 0
        rewriteCount = 0
        resetCount = 0
        skipCount = 0
    }

    companion object {
        private const val TAG = "H264FrameNumSmooth"

        /** Entfernt Emulation-Prevention-Bytes (0x000003 → 0x0000) ab `offset`. */
        private fun stripEmulationPrevention(data: ByteArray, offset: Int): ByteArray {
            val out = ByteArray(data.size - offset)
            var dst = 0
            var i = offset
            while (i < data.size) {
                if (i + 2 < data.size &&
                    data[i].toInt() == 0 &&
                    data[i + 1].toInt() == 0 &&
                    (data[i + 2].toInt() and 0xFF) == 0x03) {
                    out[dst++] = 0; out[dst++] = 0
                    i += 3
                } else {
                    out[dst++] = data[i]
                    i++
                }
            }
            return out.copyOf(dst)
        }
    }
}

/**
 * Bit-aligned Cursor für ByteArray. Liest und schreibt Bits MSB-first.
 *
 * **Limitation:** Berücksichtigt KEINE Emulation-Prevention-Bytes. Aufrufer muss
 * sicherstellen dass die Region keine 0x000003-Patterns enthält oder vorab strippen
 * (für Read-Only) bzw. die Operation übersprungen wird (für Write).
 */
internal class BitCursor(private val data: ByteArray, byteOffset: Int) {
    var byteIdx: Int = byteOffset
        private set
    var bitIdx: Int = 0  // 0..7 (0 = MSB)
        private set

    fun bitPosition(): Int = byteIdx * 8 + bitIdx

    fun setBitPosition(pos: Int) {
        byteIdx = pos / 8
        bitIdx = pos % 8
    }

    fun readBit(): Int {
        if (byteIdx >= data.size) throw IllegalStateException("BitCursor: out of bounds")
        val bit = ((data[byteIdx].toInt() and 0xFF) shr (7 - bitIdx)) and 1
        bitIdx++
        if (bitIdx == 8) { bitIdx = 0; byteIdx++ }
        return bit
    }

    fun readBits(n: Int): Int {
        var v = 0
        for (i in 0 until n) v = (v shl 1) or readBit()
        return v
    }

    /** Unsigned Exp-Golomb decode. */
    fun readUE(): Int {
        var leadingZeros = 0
        while (true) {
            val b = readBit()
            if (b == 1) break
            leadingZeros++
            if (leadingZeros > 31) throw IllegalStateException("readUE: too many leading zeros")
        }
        if (leadingZeros == 0) return 0
        val info = readBits(leadingZeros)
        return (1 shl leadingZeros) - 1 + info
    }

    fun writeBits(value: Int, n: Int) {
        for (i in n - 1 downTo 0) {
            val bit = (value shr i) and 1
            val byteVal = data[byteIdx].toInt() and 0xFF
            val mask = 1 shl (7 - bitIdx)
            val newByteVal = if (bit == 1) byteVal or mask else byteVal and mask.inv()
            data[byteIdx] = newByteVal.toByte()
            bitIdx++
            if (bitIdx == 8) { bitIdx = 0; byteIdx++ }
        }
    }
}
