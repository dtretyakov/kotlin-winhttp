package sample

data class Request(
    val method: String,
    val host: String,
    val port: Int,
    val path: String,
    val headers: List<String>,
    val data: ByteArray
)