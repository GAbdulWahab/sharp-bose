package com.offline.calling.transfer

import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32
import kotlin.math.ceil
import kotlin.math.min

data class FileChunk(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val totalChunks: Int,
    val chunkIndex: Int,
    val chunkCrc32: Long,
    val data: ByteArray,
    val senderId: String,
    val senderName: String,
    val hops: Int = 1
)

data class TransferProgress(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val isOutgoing: Boolean,
    val senderName: String,
    val progressPercent: Int,
    val speedKbps: Double,
    val isCompleted: Boolean,
    val isVerified: Boolean,
    val localFilePath: String? = null,
    val crc32Hex: String,
    val hops: Int = 1
)

class MeshFileReassembler(
    val transferId: String,
    val fileName: String,
    val fileSize: Long,
    val totalChunks: Int,
    val senderId: String,
    val senderName: String
) {
    private val receivedChunks = ConcurrentHashMap<Int, ByteArray>()
    private var startTime = System.currentTimeMillis()
    var lastChunkTime = System.currentTimeMillis()
    var hops: Int = 1

    fun addChunk(chunk: FileChunk): Boolean {
        if (chunk.transferId != transferId) return false
        
        // Verify CRC32 for chunk
        val crc = CRC32()
        crc.update(chunk.data)
        if (crc.value != chunk.chunkCrc32) {
            return false // Checksum mismatch, reject corrupted frame
        }

        receivedChunks[chunk.chunkIndex] = chunk.data
        lastChunkTime = System.currentTimeMillis()
        hops = chunk.hops
        return true
    }

    val isComplete: Boolean
        get() = totalChunks > 0 && receivedChunks.size == totalChunks

    val progressPercent: Int
        get() = if (totalChunks == 0) 0 else ((receivedChunks.size.toDouble() / totalChunks) * 100).toInt()

    fun getSpeedKbps(): Double {
        val elapsedSec = (System.currentTimeMillis() - startTime) / 1000.0
        if (elapsedSec <= 0.05) return 0.0
        val bytesSoFar = receivedChunks.values.sumOf { it.size }
        return (bytesSoFar / 1024.0) / elapsedSec
    }

    fun assemble(): ByteArray? {
        if (!isComplete) return null
        val totalBytes = ByteArray(fileSize.toInt())
        var offset = 0
        for (i in 0 until totalChunks) {
            val chunkData = receivedChunks[i] ?: return null
            System.arraycopy(chunkData, 0, totalBytes, offset, chunkData.size)
            offset += chunkData.size
        }
        return totalBytes
    }

    fun getOverallCrc32Hex(data: ByteArray): String {
        val crc = CRC32()
        crc.update(data)
        return "%08X".format(crc.value)
    }
}

class MeshFileTransferManager {
    companion object {
        val instance = MeshFileTransferManager()
        const val CHUNK_SIZE = 512 // MTU-friendly BLE L2CAP & RFCOMM frame
    }

    private val activeReassemblers = ConcurrentHashMap<String, MeshFileReassembler>()
    private val activeTransfers = ConcurrentHashMap<String, TransferProgress>()

    var onProgressUpdate: ((TransferProgress) -> Unit)? = null
    var onFileCompleted: ((TransferProgress, File) -> Unit)? = null

    fun chunkFile(
        fileBytes: ByteArray,
        fileName: String,
        senderId: String,
        senderName: String,
        targetPeerId: String = "BROADCAST"
    ): List<FileChunk> {
        val transferId = "tx-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val totalChunks = ceil(fileBytes.size.toDouble() / CHUNK_SIZE).toInt()
        val chunks = mutableListOf<FileChunk>()

        // Calculate overall CRC32 for the initial transfer record
        val overallCrc = CRC32()
        overallCrc.update(fileBytes)
        val overallCrcHex = "%08X".format(overallCrc.value)

        activeTransfers[transferId] = TransferProgress(
            transferId = transferId,
            fileName = fileName,
            fileSize = fileBytes.size.toLong(),
            isOutgoing = true,
            senderName = "YOU",
            progressPercent = 0,
            speedKbps = 0.0,
            isCompleted = false,
            isVerified = true,
            crc32Hex = overallCrcHex,
            hops = 1
        )

        for (i in 0 until totalChunks) {
            val offset = i * CHUNK_SIZE
            val len = min(CHUNK_SIZE, fileBytes.size - offset)
            val chunkData = ByteArray(len)
            System.arraycopy(fileBytes, offset, chunkData, 0, len)

            val crc = CRC32()
            crc.update(chunkData)

            chunks.add(
                FileChunk(
                    transferId = transferId,
                    fileName = fileName,
                    fileSize = fileBytes.size.toLong(),
                    totalChunks = totalChunks,
                    chunkIndex = i,
                    chunkCrc32 = crc.value,
                    data = chunkData,
                    senderId = senderId,
                    senderName = senderName,
                    hops = 1
                )
            )
        }
        return chunks
    }

    fun processIncomingChunk(chunk: FileChunk, outputDir: File): Boolean {
        val reassembler = activeReassemblers.getOrPut(chunk.transferId) {
            MeshFileReassembler(
                transferId = chunk.transferId,
                fileName = chunk.fileName,
                fileSize = chunk.fileSize,
                totalChunks = chunk.totalChunks,
                senderId = chunk.senderId,
                senderName = chunk.senderName
            )
        }

        val success = reassembler.addChunk(chunk)
        if (!success) return false

        val isComplete = reassembler.isComplete
        val progress = TransferProgress(
            transferId = chunk.transferId,
            fileName = chunk.fileName,
            fileSize = chunk.fileSize,
            isOutgoing = false,
            senderName = chunk.senderName,
            progressPercent = reassembler.progressPercent,
            speedKbps = reassembler.getSpeedKbps(),
            isCompleted = isComplete,
            isVerified = true,
            crc32Hex = "%08X".format(chunk.chunkCrc32),
            hops = chunk.hops
        )

        activeTransfers[chunk.transferId] = progress
        onProgressUpdate?.invoke(progress)

        if (isComplete) {
            val fullBytes = reassembler.assemble()
            if (fullBytes != null) {
                val overallCrc = reassembler.getOverallCrc32Hex(fullBytes)
                val sanitizedName = chunk.fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val savedFile = File(outputDir, "${System.currentTimeMillis()}_$sanitizedName")
                try {
                    FileOutputStream(savedFile).use { it.write(fullBytes) }
                    val finalProgress = progress.copy(
                        localFilePath = savedFile.absolutePath,
                        crc32Hex = overallCrc
                    )
                    activeTransfers[chunk.transferId] = finalProgress
                    onFileCompleted?.invoke(finalProgress, savedFile)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            activeReassemblers.remove(chunk.transferId)
        }

        return true
    }

    fun getAllTransfers(): List<TransferProgress> {
        return activeTransfers.values.sortedByDescending { it.transferId }
    }
}
