package sample

data class Response(
    val statusCode: Int,
    val headers: List<String>,
    val body: ByteArray
)