package sample

import kotlinx.atomicfu.*
import kotlinx.cinterop.*
import kotlinx.coroutines.DisposableHandle
import platform.windows.*
import winhttp.*

@ExperimentalUnsignedTypes
class RequestContext(
    private val onComplete: (Response) -> Unit,
    private val onException: (Throwable) -> Unit
) : DisposableHandle {
    private val reference: StableRef<RequestContext> = StableRef.create(this)
    private var hSession: COpaquePointer? = null
    private var hConnect: COpaquePointer? = null
    private var hRequest: COpaquePointer? = null
    private var isDisposed = false
    private val buffer: AtomicRef<NativeBuffer?> = atomic(null)
    private var requestBody: Pinned<ByteArray>? = null
    private val responseBody = StringBuilder()
    private var responseHeaders: String? = null

    val isActive: Boolean
        get() = !isDisposed

    fun complete() {
        val response = Response(
            200,
            (responseHeaders ?: "").lines(),
            responseBody.toString().toUtf8()
        )
        dispose()

        println("Received HTTP status ${response.statusCode} response")

        onComplete(response)
    }

    fun reject(e: Throwable) {
        println("Request has failed $e")
        dispose()
        onException(e)
    }

    fun createSession(userAgent: String) {
        hSession = WinHttpOpen(userAgent, WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, null, null, WINHTTP_FLAG_ASYNC)
        if (hSession == null) {
            reject(Error("Unable to create session"))
        }
    }

    fun setTimeouts(resolveTimeout: Int, connectTimeout: Int, sendTimeout: Int, receiveTimeout: Int) {
        if (WinHttpSetTimeouts(hSession, resolveTimeout, connectTimeout, sendTimeout, receiveTimeout) == 0) {
            reject(Error("Unable to set timeouts"))
        }
    }

    fun createConnection(host: String, port: Int) {
        hConnect = WinHttpConnect(hSession, host, port.convert(), 0)
        if (hConnect == null) {
            reject(Error("Unable to create connection"))
        }
    }

    fun openRequest(method: String, path: String) {
        hRequest = WinHttpOpenRequest(hConnect, method, path, null, null, null, WINHTTP_FLAG_SECURE)
        if (hRequest == null) {
            reject(Error("Unable to open request"))
        }
    }

    fun appendRequestHeaders(requestHeaders: List<String>) {
        val headers = requestHeaders.joinToString("\n")
        if (WinHttpAddRequestHeaders(hRequest, headers, (-1).convert(), WINHTTP_ADDREQ_FLAG_ADD) == 0) {
            reject(Error("Unable to add request header"))
        }
    }

    fun addRequestBody(body: ByteArray) {
        requestBody = body.pin()
    }

    fun sendRequest() {
        // Set status callback
        val function = staticCFunction(::statusCallback)
        if (WinHttpSetStatusCallback(hRequest, function, WINHTTP_CALLBACK_FLAG_ALL_COMPLETIONS, 0) != null) {
            reject(Error("Callback already exists"))
        }

        // Send request
        val reference = reference.asCPointer().rawValue.toLong().convert<ULong>()
        if (WinHttpSendRequest(hRequest, null, 0, null, 0, 0, reference) == 0) {
            reject(Error("Unable to send request: ${GetHResultFromLastError()}"))
        }
    }

    fun readHeaders(): Unit = memScoped {
        val dwSize = alloc<UIntVar>()

        if (WinHttpQueryHeaders(hRequest, WINHTTP_QUERY_RAW_HEADERS_CRLF, null, null, dwSize.ptr, null) == 0) {
            if (GetLastError() != ERROR_INSUFFICIENT_BUFFER.convert<UInt>()) {
                reject(Error("Unable to query headers length: ${GetHResultFromLastError()}"))
                return@memScoped
            }
        }

        var size = getLength(dwSize)
        println("Allocated buffer of $size bytes for headers")
        val buffer = allocArray<ShortVar>(size + 1)
        if (WinHttpQueryHeaders(hRequest, WINHTTP_QUERY_RAW_HEADERS_CRLF, null, buffer, dwSize.ptr, null) == 0) {
            reject(Error("Unable to query headers: ${GetHResultFromLastError()}"))
            return@memScoped
        }

        size = getLength(dwSize)
        println("Received $size chars")

        responseHeaders = String(CharArray(size) {
            buffer[it].toChar()
        })

        queryDataLength()
    }

    fun readResponseData(size: Long): Unit = memScoped {
        println("Allocating buffer for $size bytes")
        val nativeBuffer = NativeBuffer(size + 1)
        val oldBuffer = buffer.getAndSet(nativeBuffer)
        oldBuffer?.dispose()

        val dwSize = alloc<UIntVar>().apply {
            value = size.convert()
        }

        if (WinHttpReadData(hRequest, nativeBuffer.ptr, dwSize.value, null) == 0) {
            reject(Error("Error ${GetHResultFromLastError()} in WinHttpReadData."))
            return@memScoped
        }
    }

    override fun dispose() {
        if (isDisposed) return

        hRequest?.let {
            WinHttpSetStatusCallback(it, null, 0, 0)
            WinHttpCloseHandle(it)
        }
        hConnect?.let {
            WinHttpCloseHandle(it)
        }
        hSession?.let {
            WinHttpCloseHandle(it)
        }

        buffer.getAndSet(null)?.dispose()

        requestBody?.unpin()
        requestBody = null

        reference.dispose()
        isDisposed = true
    }

    fun onSendComplete() {
        requestBody?.let { pinned ->
            // Write request data
            if (WinHttpWriteData(hRequest, pinned.addressOf(0), pinned.get().size.convert(), null) == 0) {
                reject(Error("Unable to write request data: ${GetHResultFromLastError()}"))
            }
            return
        }

        receiveResponse()
    }

    fun onWriteComplete() {
        receiveResponse()
    }

    fun onReadComplete(length: Long) {
        println("Received $length bytes")
        val nativeBuffer = buffer.getAndSet(null)
        if (nativeBuffer == null) {
            reject(Error("Response buffer is null"))
        } else {
            responseBody.append(nativeBuffer.toKString())
            nativeBuffer.dispose()

            queryDataLength()
        }
    }

    private fun getLength(dwSize: UIntVar) = (dwSize.value / ShortVar.size.convert()).convert<Int>()

    private fun queryDataLength(): Boolean {
        if (WinHttpQueryDataAvailable(hRequest, null) == 0) {
            reject(Error("Unable to query data length: ${GetHResultFromLastError()}"))
            return false
        }

        return true
    }

    private fun receiveResponse() {
        if (WinHttpReceiveResponse(hRequest, null) == 0) {
            reject(Error("Unable to complete request: ${GetHResultFromLastError()}"))
        }
    }
}