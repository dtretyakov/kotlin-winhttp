package sample

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import platform.windows.*
import winhttp.*
import winhttp.HINTERNET
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@ExperimentalUnsignedTypes
class HttpClient {

    private val userAgent = "WinHTTP Example/1.0"

    suspend fun execute(request: Request): Response = suspendCancellableCoroutine { cont ->
        // Request context
        val context = RequestContext({ response ->
            cont.resume(response)
        }, { throwable ->
            cont.resumeWithException(throwable)
        })

        println("Created request context")

        cont.invokeOnCancellation {
            context.dispose()
        }

        // Session
        context.createSession(userAgent)

        // Set timeouts
        context.setTimeouts(1000 * 60,
            1000 * 60 * 2,
            1000 * 60 * 2,
            1000 * 30 * 4
        )

        // Create connection
        context.createConnection(request.host, request.port)

        // Open request
        context.openRequest(request.method, request.path)

        // Append headers
        if (request.headers.isNotEmpty()) {
            context.appendRequestHeaders(request.headers)
        }

        // Add request body
        if (request.data.isNotEmpty()) {
            context.addRequestBody(request.data)
        }

        // Send request
        context.sendRequest()

        println("Request was sent")
    }
}

@Suppress("unused", "UNUSED_PARAMETER")
@ExperimentalUnsignedTypes
fun statusCallback(
    cPointer: HINTERNET?,
    dwContext: DWORD_PTR,
    dwStatus: DWORD,
    statusInfo: LPVOID?,
    statusInfoLength: DWORD
) {
    initRuntimeIfNeeded()
    val context = dwContext.toLong().toCPointer<COpaque>()?.asStableRef<RequestContext>()?.get() ?: return
    if (!context.isActive) return

    println("* Received status 0x${dwStatus.toString(16)}")

    when (dwStatus) {
        WINHTTP_CALLBACK_STATUS_WRITE_COMPLETE.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_WRITE_COMPLETE")
            context.onWriteComplete()
        }
        WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_SENDREQUEST_COMPLETE")
            context.onSendComplete()
        }
        WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_HEADERS_AVAILABLE")
            context.readHeaders()
        }
        WINHTTP_CALLBACK_STATUS_DATA_AVAILABLE.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_DATA_AVAILABLE")
            val size = statusInfo!!.toLong().toCPointer<ULongVar>()!![0].convert<Long>()
            println("Available $size bytes")
            if (size == 0L) {
                println("No more data is available")
                context.complete()
            } else {
                context.readResponseData(size)
            }
        }
        WINHTTP_CALLBACK_STATUS_READ_COMPLETE.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_READ_COMPLETE")
            val size = statusInfoLength.convert<Long>()
            println("Was read $size bytes")
            if (size != 0L) {
                context.onReadComplete(size)
            }
        }
        WINHTTP_CALLBACK_STATUS_REQUEST_ERROR.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_REQUEST_ERROR")
            val result = statusInfo!!.reinterpret<WINHTTP_ASYNC_RESULT>().pointed
            context.reject(Error("Received error ${result.dwError} - ${result.dwResult}"))
        }
        WINHTTP_CALLBACK_STATUS_SECURE_FAILURE.convert<UInt>() -> {
            println("WINHTTP_CALLBACK_STATUS_SECURE_FAILURE")
            val securityCode = statusInfo!!.reinterpret<UIntVar>().pointed.value
            println("* Received security error code 0x${securityCode.toString(16)}")
            val securityError = when (securityCode) {
                WINHTTP_CALLBACK_STATUS_FLAG_CERT_REV_FAILED.convert<UInt>() -> "Certification revocation check check failed"
                WINHTTP_CALLBACK_STATUS_FLAG_INVALID_CERT.convert<UInt>() -> "SSL certificate is invalid"
                WINHTTP_CALLBACK_STATUS_FLAG_CERT_REVOKED.convert<UInt>() -> "SSL certificate was revoked"
                WINHTTP_CALLBACK_STATUS_FLAG_INVALID_CA.convert<UInt>() -> "Invalid Certificate Authority"
                WINHTTP_CALLBACK_STATUS_FLAG_CERT_CN_INVALID.convert<UInt>() -> "SSL certificate common name is incorrect"
                WINHTTP_CALLBACK_STATUS_FLAG_CERT_DATE_INVALID.convert<UInt>() -> "SSL certificate is expired."
                WINHTTP_CALLBACK_STATUS_FLAG_SECURITY_CHANNEL_ERROR -> "Internal error while loading the SSL libraries"
                else -> "Unknown security error 0x${securityCode.toString(16)}"
            }
            context.reject(Error(securityError))
        }
    }
}