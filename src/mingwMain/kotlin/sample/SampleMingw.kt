package sample

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs

@ExperimentalUnsignedTypes
actual class Sample {

    actual fun checkMe(): Int = runBlocking {
        val client = HttpClient()
        println(Dispatchers.Default)
        println(Dispatchers.Main)
        println(Dispatchers.Unconfined)
        val response = launch {
            val response = client.execute(
                Request(
                    "GET", "www.jetbrains.com", 443, "/",
                    emptyList(),
                    byteArrayOf()
                )
            )

            println("Request was completed with status code ${response.statusCode}. Headers length: ${response.headers.sumBy { it.length }}, body: ${response.body.size}")

            writeToFile("headers.txt", response.headers.joinToString("\n"))
            writeToFile("body.txt", response.body.stringFromUtf8())
        }

        response.join()

        return@runBlocking 1
    }

    private fun writeToFile(fileName: String, text: String) {
        val file = fopen(fileName, "wt")
            ?: throw Error("Cannot write file '$fileName'")
        try {
            fputs(text, file)
        } finally {
            fclose(file)
        }
    }
}

actual object Platform {
    actual val name: String = "Native"
}