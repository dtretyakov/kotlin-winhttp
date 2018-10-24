package sample

import kotlinx.cinterop.*
import kotlinx.coroutines.DisposableHandle

class NativeBuffer(size: Long): DisposableHandle {
    val ptr = nativeHeap.allocArray<ByteVar>(size)

    fun toKString(): String {
        return ptr.toKString()
    }

    override fun dispose() {
        nativeHeap.free(ptr)
    }
}