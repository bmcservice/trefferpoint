package de.bmcservice.trefferpoint

import android.util.Base64

/**
 * Minimaler H.264 SPS-Parser zur Extraktion von Auflösung (width, height).
 *
 * Wird benötigt damit `RtspMediaCodecPipeline` ImageReader und MediaCodec mit der
 * tatsächlichen Stream-Auflösung initialisiert — egal ob ch0 (HD/2K) oder ch1 (SD/540p).
 *
 * Parsing-Tiefe: nur bis `frame_cropping_*` (alles was für width/height nötig ist).
 * VUI-Parameter werden ignoriert. Funktioniert für Baseline und Main Profile sicher;
 * High-Profile-Felder (chroma_format_idc etc.) werden korrekt geskippt.
 */
object H264SpsParser {
    private const val TAG = "H264SpsParser"

    data class Resolution(val width: Int, val height: Int) {
        override fun toString() = "${width}x${height}"
    }

    /**
     * Parst SPS aus base64-codiertem `sprop-parameter-sets`-Wert (erste Komponente).
     * Liefert null wenn Parse fehlschlägt.
     */
    fun parseFromBase64(spsBase64: String): Resolution? {
        return try {
            val raw = Base64.decode(spsBase64, Base64.NO_WRAP or Base64.NO_PADDING)
            parseFromNal(raw)
        } catch (e: Exception) {
            AppLog.w(TAG, "SPS Base64-Decode fehlgeschlagen: ${e.message}")
            null
        }
    }

    /**
     * Parst eine SPS-NAL-Unit (erstes Byte = NAL-Header 0x67 typisch).
     */
    fun parseFromNal(spsNal: ByteArray): Resolution? {
        if (spsNal.size < 4) return null
        // NAL-Header (1 Byte) skippen, Rest ist RBSP mit Emulation-Prevention-Bytes
        val rbsp = stripEmulationPrevention(spsNal, 1)
        val cur = BitCursor(rbsp, 0)
        try {
            val profileIdc = cur.readBits(8)
            cur.readBits(8)  // constraint_set + reserved
            cur.readBits(8)  // level_idc
            cur.readUE()     // seq_parameter_set_id

            // High Profiles haben zusätzliche Felder vor log2_max_frame_num_minus4
            var chromaFormatIdc = 1  // Default 4:2:0
            val highProfiles = setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)
            if (profileIdc in highProfiles) {
                chromaFormatIdc = cur.readUE()
                if (chromaFormatIdc == 3) cur.readBit()  // separate_colour_plane_flag
                cur.readUE()  // bit_depth_luma_minus8
                cur.readUE()  // bit_depth_chroma_minus8
                cur.readBit()  // qpprime_y_zero_transform_bypass_flag
                val scalingMatrixPresent = cur.readBit()
                if (scalingMatrixPresent == 1) {
                    AppLog.w(TAG, "SPS hat seq_scaling_matrix — Skip nicht implementiert")
                    return null
                }
            }

            cur.readUE()  // log2_max_frame_num_minus4
            val pocType = cur.readUE()
            when (pocType) {
                0 -> cur.readUE()  // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    cur.readBit()  // delta_pic_order_always_zero_flag
                    cur.readSE()  // offset_for_non_ref_pic
                    cur.readSE()  // offset_for_top_to_bottom_field
                    val numRefFramesInPicOrderCntCycle = cur.readUE()
                    repeat(numRefFramesInPicOrderCntCycle) { cur.readSE() }
                }
            }
            cur.readUE()  // num_ref_frames
            cur.readBit()  // gaps_in_frame_num_value_allowed_flag

            val picWidthInMbsMinus1 = cur.readUE()
            val picHeightInMapUnitsMinus1 = cur.readUE()
            val frameMbsOnlyFlag = cur.readBit()
            if (frameMbsOnlyFlag == 0) cur.readBit()  // mb_adaptive_frame_field_flag
            cur.readBit()  // direct_8x8_inference_flag

            val widthMbs = picWidthInMbsMinus1 + 1
            val heightMbs = picHeightInMapUnitsMinus1 + 1
            var width = widthMbs * 16
            var height = heightMbs * 16 * (if (frameMbsOnlyFlag == 1) 1 else 2)

            val cropFlag = cur.readBit()
            if (cropFlag == 1) {
                val left = cur.readUE()
                val right = cur.readUE()
                val top = cur.readUE()
                val bottom = cur.readUE()
                // Subsampling-Faktoren je nach chroma_format_idc:
                // 0=monochrome (1,1), 1=4:2:0 (2,2), 2=4:2:2 (2,1), 3=4:4:4 (1,1)
                val subWidthC = when (chromaFormatIdc) { 0, 3 -> 1; else -> 2 }
                val subHeightC = when (chromaFormatIdc) { 1 -> 2; else -> 1 }
                val cropMulX = subWidthC
                val cropMulY = subHeightC * (if (frameMbsOnlyFlag == 1) 1 else 2)
                width -= (left + right) * cropMulX
                height -= (top + bottom) * cropMulY
            }

            if (width <= 0 || height <= 0 || width > 8192 || height > 8192) {
                AppLog.w(TAG, "SPS: unplausible Auflösung ${width}x${height} — verworfen")
                return null
            }
            AppLog.i(TAG, "SPS geparsed: profile=$profileIdc Auflösung ${width}x${height}")
            return Resolution(width, height)
        } catch (e: Exception) {
            AppLog.w(TAG, "SPS Parse-Fehler: ${e.message}")
            return null
        }
    }

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

    /**
     * Bit-aligned Cursor mit Exp-Golomb-Decoder. RBSP (= Emulation-Prevention bereits entfernt).
     */
    private class BitCursor(private val data: ByteArray, byteOffset: Int) {
        private var byteIdx = byteOffset
        private var bitIdx = 0  // 0..7 (0 = MSB)

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

        /** Unsigned Exp-Golomb. */
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

        /** Signed Exp-Golomb. */
        fun readSE(): Int {
            val ue = readUE()
            return if (ue and 1 == 1) (ue + 1) / 2 else -(ue / 2)
        }
    }
}
