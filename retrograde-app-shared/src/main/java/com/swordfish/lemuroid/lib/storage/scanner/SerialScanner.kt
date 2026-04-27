package com.swordfish.lemuroid.lib.storage.scanner

import com.swordfish.lemuroid.common.files.FileUtils
import com.swordfish.lemuroid.common.kotlin.indexOf
import com.swordfish.lemuroid.common.kotlin.kiloBytes
import com.swordfish.lemuroid.common.kotlin.megaBytes
import com.swordfish.lemuroid.lib.library.SystemID
import timber.log.Timber
import java.io.InputStream
import java.nio.charset.Charset
import kotlin.math.ceil
import kotlin.math.roundToInt

object SerialScanner {
    private val READ_BUFFER_SIZE = 64.kiloBytes()

    data class DiskInfo(val serial: String?, val systemID: SystemID?)

    @ExperimentalUnsignedTypes
    private val MAGIC_NUMBERS =
        listOf(
            MagicNumber(
                0x0010,
                ubyteArrayOf(
                    0x53U,
                    0x45U,
                    0x47U,
                    0x41U,
                    0x44U,
                    0x49U,
                    0x53U,
                    0x43U,
                    0x53U,
                    0x59U,
                    0x53U,
                    0x54U,
                    0x45U,
                    0x4dU,
                ).toByteArray(),
                SystemID.SEGACD,
            ),
        )

    private val SEGA_CD_REGEX = Regex("([A-Z]+)?-?([0-9]+) ?-?([0-9]*)")

    private val PS_SERIAL_REGEX = Regex("^([A-Z]+)-?([0-9]+)")
    private val PS_SERIAL_REGEX2 = Regex("^([A-Z]+)_?([0-9]{3})\\.([0-9]{2})")

    private const val PS_SERIAL_MAX_SIZE = 12

    fun extractInfo(
        fileName: String,
        inputStream: InputStream,
    ): DiskInfo {
        Timber.d("Extracting disk info for $fileName")
        inputStream.buffered(READ_BUFFER_SIZE).use {
            return when (FileUtils.extractExtension(fileName)) {
                "pbp" -> extractInfoForPBP(it)
                "iso", "bin" -> standardExtractInfo(it)
                "3ds" -> extractInfoFor3DS(it)
                else -> DiskInfo(null, null)
            }
        }
    }

    private fun standardExtractInfo(openedStream: InputStream): DiskInfo {
        openedStream.mark(READ_BUFFER_SIZE)
        val header = readByteArray(openedStream, ByteArray(READ_BUFFER_SIZE))

        val detectedSystem =
            MAGIC_NUMBERS
                .firstOrNull {
                    header.copyOfRange(it.offset, it.offset + it.numbers.size).contentEquals(it.numbers)
                }
                ?.systemID

        Timber.d("SystemID detected via magic numbers: $detectedSystem")

        openedStream.reset()

        return when (detectedSystem) {
            SystemID.SEGACD ->
                runCatching { extractInfoForSegaCD(openedStream) }
                    .getOrDefault(DiskInfo(null, SystemID.SEGACD))

            else -> DiskInfo(null, null)
        }
    }

    private fun extractInfoFor3DS(openedStream: InputStream): DiskInfo {
        Timber.d("Parsing 3DS game")
        openedStream.mark(0x2000)
        openedStream.skip(0x1150)

        val rawSerial = String(readByteArray(openedStream, ByteArray(10)), Charsets.US_ASCII)

        openedStream.reset()

        Timber.d("Found 3DS serial: $rawSerial")
        return DiskInfo(rawSerial, SystemID.NINTENDO_3DS)
    }

    private fun extractInfoForSegaCD(openedStream: InputStream): DiskInfo {
        Timber.d("Parsing SegaCD game")
        openedStream.mark(20000)
        openedStream.skip(0x193)

        val rawSerial = String(readByteArray(openedStream, ByteArray(16)), Charsets.US_ASCII)

        Timber.d("Detected SegaCD raw serial read: $rawSerial")

        openedStream.reset()
        openedStream.skip(0x200)

        val regionID = String(readByteArray(openedStream, ByteArray(1)), Charsets.US_ASCII)

        Timber.d("Detected SegaCD region: $regionID")

        val groups = SEGA_CD_REGEX.find(rawSerial)?.groupValues

        // The following rules come from here: https://github.com/libretro/RetroArch/pull/11719/files
        // and some guess work. They are by no means complete.
        val prefix = groups?.get(1)
        val num = groups?.get(2)
        var postfix = groups?.get(3)

        if (regionID == "E") {
            postfix = "50"
        }

        if (postfix == "00") {
            postfix = null
        }

        val finalSerial =
            sequenceOf(prefix, num, postfix)
                .filterNotNull()
                .filter { it.isNotBlank() }
                .joinToString("-") { it.trim() }

        Timber.i("SegaCD final serial: $finalSerial")
        return DiskInfo(finalSerial, SystemID.SEGACD)
    }

    private fun extractInfoForPBP(openedStream: InputStream): DiskInfo {
        return DiskInfo(null, null)
    }

    private fun parsePSXSerial(serial: String): String? {
        return sequenceOf(
            PS_SERIAL_REGEX.find(serial)?.groupValues?.let { "${it[1]}-${it[2]}" },
            PS_SERIAL_REGEX2.find(serial)?.groupValues?.let { "${it[1]}-${it[2]}${it[3]}" },
        ).filter { it != null }
            .firstOrNull()
    }

    private fun textSearch(
        queries: List<String>,
        openedStream: InputStream,
        resultSize: Int,
        streamSize: Int,
        windowSize: Int = 8.kiloBytes(),
        skipSize: Int = windowSize - resultSize,
        charset: Charset = Charsets.US_ASCII,
    ): Sequence<String> {
        val byteQueries = queries.map { it.toByteArray(charset) }
        return movingWidnowSequence(openedStream, windowSize, (skipSize).toLong())
            .take(ceil(streamSize.toDouble() / skipSize.toDouble()).roundToInt())
            .flatMap { serial ->
                byteQueries.asSequence()
                    .map { serial.indexOf(it) }
                    .filter { it >= 0 }
                    .map { serial to it }
            }
            .map { (bytes, index) ->
                val serialBytes = bytes.copyOfRange(index, index + resultSize)
                String(serialBytes, charset)
            }
            .filterNotNull()
    }

    private fun movingWidnowSequence(
        inputStream: InputStream,
        windowSize: Int,
        windowSkip: Long,
    ) = sequence {
        val buffer = ByteArray(windowSize)
        do {
            inputStream.mark(windowSize)
            yield(readByteArray(inputStream, buffer))
            inputStream.reset()
        } while (inputStream.skip(windowSkip) != 0L)
    }

    private fun readByteArray(
        inputStream: InputStream,
        byteArray: ByteArray,
    ): ByteArray {
        val readBytes = inputStream.read(byteArray)
        return if (readBytes < byteArray.size) {
            byteArray.copyOf(readBytes)
        } else {
            byteArray
        }
    }
}
