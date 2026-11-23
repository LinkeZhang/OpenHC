package io.github.gcbond.openhc.platform.compat

import android.annotation.SuppressLint
import android.os.Build
import java.io.FileDescriptor
import java.io.IOException
import java.lang.reflect.InvocationTargetException

/**
 * 安卓 4.1（API 16～20）上跑 TUN 收发要用的系统调用。
 *
 * `android.system.Os` / `StructPollfd` / `OsConstants` 都是 API 21 才有的。4.x 上同一套能力在
 * `libcore.io.Libcore.os`（`libcore.io.Os` 接口）里，签名几乎一样，只是它是系统内部类，只能反射调。
 * 这里把用到的那几个（pipe / read / write / poll / close）包一层，出错时统一换成 [IOException] 或返回错误码，
 * 上层代码不用关心走的是哪条路。
 *
 * 反射开销只在建立连接和每次读写时几次 Method.invoke，相对一次 IP 包搬运（微秒级）可以接受；
 * 方法句柄在类初始化时就找好，不重复查找。
 */
@SuppressLint("NewApi")
internal object LegacyOs {

    /** `libcore.io.Os` 实例（`Libcore.os`）。 */
    private val os: Any
    private val readMethod: java.lang.reflect.Method
    private val writeMethod: java.lang.reflect.Method
    private val pollMethod: java.lang.reflect.Method
    private val pipeMethod: java.lang.reflect.Method
    private val closeMethod: java.lang.reflect.Method

    /** `libcore.io.StructPollfd` 的构造器和字段。 */
    private val pollfdClass: Class<*>
    private val fdField: java.lang.reflect.Field
    private val eventsField: java.lang.reflect.Field
    private val reventsField: java.lang.reflect.Field

    /** `libcore.io.ErrnoException` 的 errno 字段（取错误码用）。 */
    private val errnoField: java.lang.reflect.Field?

    /** 这几个常量在 `OsConstants` 和 `libcore.io.OsConstants` 里数值一样，直接写值，省掉反射。 */
    const val POLLIN: Short = 0x0001
    const val POLLERR: Short = 0x0008
    const val POLLHUP: Short = 0x0010
    const val POLLNVAL: Short = 0x0020

    /** Linux errno：EINTR=4、EAGAIN=11。 */
    const val EINTR = 4
    const val EAGAIN = 11

    init {
        try {
            val libcore = Class.forName("libcore.io.Libcore")
            os = libcore.getField("os").get(null)
            val osInterface = Class.forName("libcore.io.Os")
            readMethod = osInterface.getMethod(
                "read", FileDescriptor::class.java, ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            writeMethod = osInterface.getMethod(
                "write", FileDescriptor::class.java, ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            )
            pollMethod = osInterface.getMethod(
                "poll", Class.forName("[Llibcore.io.StructPollfd;"), Int::class.javaPrimitiveType,
            )
            pipeMethod = osInterface.getMethod("pipe")
            closeMethod = osInterface.getMethod("close", FileDescriptor::class.java)
            pollfdClass = Class.forName("libcore.io.StructPollfd")
            fdField = pollfdClass.getField("fd")
            eventsField = pollfdClass.getField("events")
            reventsField = pollfdClass.getField("revents")
            errnoField = try {
                Class.forName("libcore.io.ErrnoException").getField("errno")
            } catch (e: Exception) {
                null
            }
        } catch (e: Exception) {
            // 极老的系统（或厂商裁剪过 libcore）上找不到这套接口：抛出去让上层按「不支持有线」处理。
            throw IllegalStateException("这个系统的 libcore 里没有可用的 Os 接口", e)
        }
    }

    /** 一次 poll 的条目。用 [newPoll] 建，字段由本类通过反射填。 */
    class Poll(val handle: Any) {
        var ready: Short = 0
    }

    /** 建一个 poll 条目（对应 `StructPollfd{fd, events}`）。 */
    fun newPoll(fd: FileDescriptor, events: Short): Poll {
        val handle = pollfdClass.getDeclaredConstructor().newInstance()
        fdField.set(handle, fd)
        eventsField.set(handle, events)
        return Poll(handle)
    }

    /** 建一根管道，返回 `[读端, 写端]`。 */
    @Throws(IOException::class)
    fun pipe(): Array<FileDescriptor> {
        val result = try {
            pipeMethod.invoke(os) as Array<*>
        } catch (e: InvocationTargetException) {
            throw IOException("建管道失败", e.cause)
        }
        return arrayOf(result[0] as FileDescriptor, result[1] as FileDescriptor)
    }

    /**
     * 读。[timeoutMs] 是 poll 的等待毫秒（-1 表示无限等）。
     * 返回读到的字节数（>0）、0（暂时没有）、-1（管道挂断/出错）。
     */
    @Throws(IOException::class)
    fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, maxLength: Int): Int = try {
        readMethod.invoke(os, fd, buffer, offset, maxLength) as Int
    } catch (e: InvocationTargetException) {
        throw IOException("读失败（errno ${errnoOf(e.cause)}）", e.cause)
    }

    /** 写。返回写入的字节数。 */
    @Throws(IOException::class)
    fun write(fd: FileDescriptor, buffer: ByteArray, offset: Int, length: Int): Int = try {
        writeMethod.invoke(os, fd, buffer, offset, length) as Int
    } catch (e: InvocationTargetException) {
        throw IOException("写失败（errno ${errnoOf(e.cause)}）", e.cause)
    }

    /** poll 一组 fd，返回就绪个数（<=0 表示超时或出错）。就绪的事件回填到 [polls] 的 `ready` 里。 */
    @Throws(IOException::class)
    fun poll(polls: Array<Poll>, timeoutMs: Int): Int {
        val handles = java.lang.reflect.Array.newInstance(pollfdClass, polls.size)
        polls.forEachIndexed { i, p -> java.lang.reflect.Array.set(handles, i, p.handle) }
        val ready = try {
            pollMethod.invoke(os, handles, timeoutMs) as Int
        } catch (e: InvocationTargetException) {
            throw IOException("poll 失败（errno ${errnoOf(e.cause)}）", e.cause)
        }
        polls.forEach { it.ready = reventsField.get(it.handle) as Short }
        return ready
    }

    fun close(fd: FileDescriptor) {
        try {
            closeMethod.invoke(os, fd)
        } catch (e: Exception) {
            // 关不掉没有补救办法。
        }
    }

    /** 从 `ErrnoException` 里取错误码；取不到返回 -1。 */
    fun errnoOf(cause: Throwable?): Int {
        val field = errnoField ?: return -1
        return try {
            field.getInt(cause)
        } catch (e: Exception) {
            -1
        }
    }

    /** 这个系统能不能走反射这条路（能的话有线连接在 4.x 上可用）。 */
    val available: Boolean by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            true
        } else {
            try {
                // 触发 init 里的反射查找，找不到会抛 IllegalStateException。
                os
                true
            } catch (e: Throwable) {
                false
            }
        }
    }
}
