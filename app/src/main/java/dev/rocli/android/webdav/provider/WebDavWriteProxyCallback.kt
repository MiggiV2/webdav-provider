package dev.rocli.android.webdav.provider

import android.os.ProxyFileDescriptorCallback
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okio.IOException
import okio.source
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class WebDavWriteProxyCallback(
    private val client: WebDavClient,
    private val file: WebDavFile,
    private val onSuccess: (WebDavFile) -> Unit,
    private val onFail: () -> Unit,
) : ProxyFileDescriptorCallback() {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    private val tempFile: File = File.createTempFile("webdav-upload-", null)
    private val tempStream: FileOutputStream = FileOutputStream(tempFile)
    private var nextOffset = 0L

    private val uuid = UUID.randomUUID()
    private val TAG: String = "${javaClass.simpleName}(uuid=$uuid)"

    init {
        Log.d(TAG, "init(file=${file.path})")
    }

    override fun onGetSize(): Long {
        val size = tempFile.length()
        Log.d(TAG, "onGetSize(size=$size)")
        return size
    }

    override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
        Log.d(TAG, "onWrite(offset=$offset, size=$size)")

        if (nextOffset != offset) {
            throw ErrnoException(
                "onWrite",
                OsConstants.ENOTSUP,
                IOException("Seeking is not supported by ${javaClass.simpleName}")
            )
        }
        nextOffset += size

        tempStream.write(data, 0, size)
        return size
    }

    override fun onFsync() {
        Log.d(TAG, "onFsync()")
        tempStream.flush()
    }

    override fun onRelease() {
        Log.d(TAG, "onRelease()")
        tempStream.close()

        val contentLength = tempFile.length()
        job = scope.launch {
            try {
                val source = tempFile.inputStream().source()
                val res = client.putFile(file, source, contentType = file.contentType, contentLength = contentLength)
                if (res.isSuccessful) {
                    val propRes = client.propFind(file.davPath)
                    if (propRes.isSuccessful) {
                        onSuccess(propRes.body!!)
                    } else {
                        propRes.error?.message.let { msg ->
                            Log.e(TAG, "propFind failed: ${msg}\")")
                            onFail()
                        }
                    }
                } else {
                    res.error?.message.let { msg ->
                        Log.e(TAG, "Upload failed: ${msg}\")")
                        onFail()
                    }
                }
            } finally {
                tempFile.delete()
            }
        }

        runBlocking { join() }
        Log.d(TAG, "onRelease(): Done!")
    }

    suspend fun join() {
        job?.join()
    }
}
