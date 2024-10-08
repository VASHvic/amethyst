/**
 * Copyright (c) 2024 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.vitorpamplona.amethyst.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import com.abedelazizshe.lightcompressorlibrary.config.AppSpecificStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.vitorpamplona.amethyst.R
import com.vitorpamplona.amethyst.service.checkNotInMainThread
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.default
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class MediaCompressor {
    suspend fun compress(
        uri: Uri,
        contentType: String?,
        applicationContext: Context,
        onReady: (Uri, String?, Long?) -> Unit,
        onError: (Int) -> Unit,
        mediaQuality: CompressorQuality,
    ) {
        if (mediaQuality != CompressorQuality.UNCOMPRESSED) {
            checkNotInMainThread()

            if (contentType?.startsWith("video", true) == true) {
                val videoQuality =
                    when (mediaQuality) {
                        CompressorQuality.VERY_LOW -> VideoQuality.VERY_LOW
                        CompressorQuality.LOW -> VideoQuality.LOW
                        CompressorQuality.MEDIUM -> VideoQuality.MEDIUM
                        CompressorQuality.HIGH -> VideoQuality.HIGH
                        CompressorQuality.VERY_HIGH -> VideoQuality.VERY_HIGH
                        else -> VideoQuality.MEDIUM // dodgy
                    }
                Log.d("MediaCompressor", "Using video compression $mediaQuality")
                VideoCompressor.start(
                    // => This is required
                    context = applicationContext,
                    // => Source can be provided as content uris
                    uris = listOf(uri),
                    isStreamable = false,
                    // THIS STORAGE
                    // sharedStorageConfiguration = SharedStorageConfiguration(
                    //    saveAt = SaveLocation.movies, // => default is movies
                    //    videoName = "compressed_video" // => required name
                    // ),
                    // OR AND NOT BOTH
                    appSpecificStorageConfiguration = AppSpecificStorageConfiguration(),
                    configureWith =
                        Configuration(
                            quality = videoQuality,
                            // => required name
                            videoNames = listOf(UUID.randomUUID().toString()),
                        ),
                    listener =
                        object : CompressionListener {
                            override fun onProgress(
                                index: Int,
                                percent: Float,
                            ) {
                            }

                            override fun onStart(index: Int) {
                                // Compression start
                            }

                            override fun onSuccess(
                                index: Int,
                                size: Long,
                                path: String?,
                            ) {
                                if (path != null) {
                                    Log.d("MediaCompressor", "Video compression success. Compressed size [$size]")
                                    onReady(Uri.fromFile(File(path)), contentType, size)
                                } else {
                                    Log.d("MediaCompressor", "Video compression successful, but returned null path")
                                    onError(R.string.compression_returned_null)
                                }
                            }

                            override fun onFailure(
                                index: Int,
                                failureMessage: String,
                            ) {
                                // keeps going with original video
                                Log.d("MediaCompressor", "Video compression failed: $failureMessage")
                                onReady(uri, contentType, null)
                            }

                            override fun onCancelled(index: Int) {
                                onError(R.string.compression_cancelled)
                            }
                        },
                )
            } else if (
                contentType?.startsWith("image", true) == true &&
                !contentType.contains("gif") &&
                !contentType.contains("svg")
            ) {
                val imageQuality =
                    when (mediaQuality) {
                        CompressorQuality.VERY_LOW -> 40
                        CompressorQuality.LOW -> 50
                        CompressorQuality.MEDIUM -> 60
                        CompressorQuality.HIGH -> 80
                        CompressorQuality.VERY_HIGH -> 90
                        else -> 60
                    }
                try {
                    Log.d("MediaCompressor", "Using image compression $mediaQuality")
                    val tempFile = from(uri, contentType, applicationContext)
                    val compressedImageFile =
                        Compressor.compress(applicationContext, tempFile) {
                            default(width = 640, format = Bitmap.CompressFormat.JPEG, quality = imageQuality)
                        }
                    Log.d("MediaCompressor", "Image compression success. Original size [${tempFile.length()}], new size [${compressedImageFile.length()}]")
                    onReady(compressedImageFile.toUri(), contentType, compressedImageFile.length())
                } catch (e: Exception) {
                    Log.d("MediaCompressor", "Image compression failed: ${e.message}")
                    if (e is CancellationException) throw e
                    e.printStackTrace()
                    onReady(uri, contentType, null)
                }
            } else {
                onReady(uri, contentType, null)
            }
        } else {
            Log.d("MediaCompressor", "UNCOMPRESSED quality selected, skip compression and continue with original media.")
            onReady(uri, contentType, null)
        }
    }

    fun from(
        uri: Uri?,
        contentType: String?,
        context: Context,
    ): File {
        val extension =
            contentType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: ""

        val inputStream = context.contentResolver.openInputStream(uri!!)
        val fileName: String = UUID.randomUUID().toString() + ".$extension"
        val splitName: Array<String> = splitFileName(fileName)
        val tempFile = File.createTempFile(splitName[0], splitName[1])
        inputStream?.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(1024 * 50)
                var read: Int = input.read(buffer)
                while (read != -1) {
                    output.write(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
        }

        return tempFile
    }

    private fun splitFileName(fileName: String): Array<String> {
        var name = fileName
        var extension = ""
        val i = fileName.lastIndexOf(".")
        if (i != -1) {
            name = fileName.substring(0, i)
            extension = fileName.substring(i)
        }
        return arrayOf(name, extension)
    }

    fun intToCompressorQuality(mediaQualityFloat: Int): CompressorQuality =
        when (mediaQualityFloat) {
            0 -> CompressorQuality.LOW
            1 -> CompressorQuality.MEDIUM
            2 -> CompressorQuality.HIGH
            3 -> CompressorQuality.UNCOMPRESSED
            else -> CompressorQuality.MEDIUM
        }

    fun compressorQualityToInt(compressorQuality: CompressorQuality): Int =
        when (compressorQuality) {
            CompressorQuality.LOW -> 0
            CompressorQuality.MEDIUM -> 1
            CompressorQuality.HIGH -> 2
            CompressorQuality.UNCOMPRESSED -> 3
            else -> 1
        }
}

enum class CompressorQuality {
    VERY_LOW,
    LOW,
    MEDIUM,
    HIGH,
    VERY_HIGH,
    UNCOMPRESSED,
}
