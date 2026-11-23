package io.github.gcbond.openhc.platform.net

import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.io.InterruptedIOException

/**
 * VpnService 建出的 TUN 描述符，按 [PacketTunnel] 一次收发一个 IP 包。描述符要是非阻塞的。
 *
 * 读用 poll 同时等 TUN 和一根唤醒管道，[wakeup] 往管道里写一个字节，阻塞中的读立刻返回。
 * 不能靠关 TUN 描述符来叫醒读线程：别的线程阻塞在读上时关描述符叫不醒它，描述符号还可能马上被系统复用。
 * 所以停止的顺序是：[wakeup] → 等读线程退出 → [close]。
 *
 * **系统调用有两套实现**（见 [OsOps]）：安卓 5.0（API 21）起用公开的 `android.system.Os`；
 * 安卓 4.1～4.4（API 16～20）没有它，改走 `libcore.io.Libcore.os` 的反射
 * （[io.github.gcbond.openhc.platform.compat.LegacyOs]），能力一样，只是签名要靠反射找。
 * 两套都不可用时构造会抛 [IOException]，上层按「不支持有线」处理。
 */
internal class TunInterface(private val descriptor: ParcelFileDescriptor) : PacketTunnel, Closeable {

    /** 系统调用的统一入口：把两条实现（公开 Os / libcore 反射）收在同一组方法后面。 */
    internal interface OsOps {
        fun pipe(): Array<FileDescriptor>

        /** poll 一次，看 TUN 是否可读、唤醒管道是否被写。 */
        fun pollTun(tun: FileDescriptor, wakeRead: FileDescriptor, timeoutMs: Int): PollResult
        fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, maxLength: Int): Int
        fun write(fd: FileDescriptor, buffer: ByteArray, offset: Int, length: Int): Int
        fun close(fd: FileDescriptor)
    }

    /** 一次 poll 的结果：[woken] 表示唤醒管道有数据（要退出），否则看 [tunReadable]。 */
    internal class PollResult(val woken: Boolean, val tunReadable: Boolean, val failed: Boolean)

    private val fd: FileDescriptor = descriptor.fileDescriptor
    private val ops: OsOps
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor
    private val lock = Any()

    @Volatile
    private var woken = false
    private var wakeSignalled = false
    private var closed = false

    init {
        ops = newOsOps()
        val pipe = try {
            ops.pipe()
        } catch (e: Exception) {
            throw IOException("建唤醒管道失败", e)
        }
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
    }

    override fun read(buffer: ByteArray, offset: Int, maxLength: Int): Int {
        while (!woken) {
            val result = try {
                // 有唤醒管道时本可以无限等；留一个超时只是兜底，防止唤醒信号意外丢失时线程永远不退出。
                ops.pollTun(fd, wakeRead, POLL_TIMEOUT_MS)
            } catch (e: IOException) {
                return -1
            }
            if (woken || result.woken) return -1
            if (result.failed) return -1
            if (!result.tunReadable) continue
            try {
                val n = ops.read(fd, buffer, offset, maxLength)
                // TUN 正常不会读出 0 字节；读出 0 说明网卡已经没了。
                return if (n > 0) n else -1
            } catch (e: IOException) {
                // EAGAIN / EINTR 都当「这一轮没读到」，继续 poll。
                continue
            } catch (e: InterruptedIOException) {
                return -1
            }
        }
        return -1
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int): Boolean {
        if (woken) return false
        return try {
            ops.write(fd, buffer, offset, length) == length
        } catch (e: IOException) {
            false
        } catch (e: InterruptedIOException) {
            false
        }
    }

    override fun wakeup() {
        woken = true
        synchronized(lock) {
            if (closed || wakeSignalled) return
            wakeSignalled = true
            try {
                ops.write(wakeWrite, WAKE_BYTE, 0, 1)
            } catch (e: Exception) {
                // 写不进去时读线程最晚在 poll 超时后看到 woken 退出。
            }
        }
    }

    /** 关闭 TUN 与唤醒管道。必须在读写线程都退出之后调用。可重复调用。 */
    override fun close() {
        wakeup()
        synchronized(lock) {
            if (closed) return
            closed = true
            ops.close(wakeRead)
            ops.close(wakeWrite)
            try {
                descriptor.close()
            } catch (e: IOException) {
                // 关闭失败没有补救办法。
            }
        }
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 1000
        val WAKE_BYTE = byteArrayOf(1)

        /** 按系统版本挑实现：API 21+ 用公开 Os，低版本用 libcore 反射。都不行时抛 [IOException]。 */
        fun newOsOps(): OsOps =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                PublicOsOps()
            } else {
                if (!io.github.gcbond.openhc.platform.compat.LegacyOs.available) {
                    throw IOException("这个系统的 libcore 里没有可用的 Os 接口，有线连接用不了")
                }
                LegacyOsOps()
            }
    }
}

/**
 * 公开 `android.system.Os` 的实现（API 21+）。
 *
 * 本类只在 API 21+ 被实例化，低版本上类加载器不会去解析 `android.system.*` 的符号。
 */
@android.annotation.SuppressLint("NewApi")
private class PublicOsOps : TunInterface.OsOps {
    override fun pipe(): Array<FileDescriptor> = android.system.Os.pipe()

    override fun pollTun(
        tun: FileDescriptor,
        wakeRead: FileDescriptor,
        timeoutMs: Int,
    ): TunInterface.PollResult {
        val tunPoll = android.system.StructPollfd().apply {
            fd = tun
            events = android.system.OsConstants.POLLIN.toShort()
        }
        val wakePoll = android.system.StructPollfd().apply {
            fd = wakeRead
            events = android.system.OsConstants.POLLIN.toShort()
        }
        val ready = try {
            android.system.Os.poll(arrayOf(tunPoll, wakePoll), timeoutMs)
        } catch (e: android.system.ErrnoException) {
            // EINTR：被信号打断，这一轮当没读到。
            return TunInterface.PollResult(
                woken = false,
                tunReadable = false,
                failed = e.errno != android.system.OsConstants.EINTR,
            )
        }
        if (wakePoll.revents.toInt() != 0) return TunInterface.PollResult(woken = true, tunReadable = false, failed = false)
        if (ready <= 0) return TunInterface.PollResult(woken = false, tunReadable = false, failed = false)
        val events = tunPoll.revents.toInt()
        val errBits = android.system.OsConstants.POLLERR or
            android.system.OsConstants.POLLHUP or
            android.system.OsConstants.POLLNVAL
        if (events and errBits != 0) return TunInterface.PollResult(woken = false, tunReadable = false, failed = true)
        return TunInterface.PollResult(
            woken = false,
            tunReadable = events and android.system.OsConstants.POLLIN != 0,
            failed = false,
        )
    }

    override fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, maxLength: Int): Int =
        android.system.Os.read(fd, buffer, offset, maxLength)

    override fun write(fd: FileDescriptor, buffer: ByteArray, offset: Int, length: Int): Int =
        android.system.Os.write(fd, buffer, offset, length)

    override fun close(fd: FileDescriptor) {
        try {
            android.system.Os.close(fd)
        } catch (e: android.system.ErrnoException) {
            // 同上。
        }
    }
}

/**
 * 安卓 4.1～4.4 的实现：走 `libcore.io.Libcore.os` 的反射
 * （[io.github.gcbond.openhc.platform.compat.LegacyOs]）。语义和 [PublicOsOps] 一一对应。
 */
private class LegacyOsOps : TunInterface.OsOps {

    private val legacy = io.github.gcbond.openhc.platform.compat.LegacyOs

    override fun pipe(): Array<FileDescriptor> = legacy.pipe()

    override fun pollTun(
        tun: FileDescriptor,
        wakeRead: FileDescriptor,
        timeoutMs: Int,
    ): TunInterface.PollResult {
        val pollIn = io.github.gcbond.openhc.platform.compat.LegacyOs.POLLIN
        val tunPoll = legacy.newPoll(tun, pollIn)
        val wakePoll = legacy.newPoll(wakeRead, pollIn)
        val ready = try {
            legacy.poll(arrayOf(tunPoll, wakePoll), timeoutMs)
        } catch (e: IOException) {
            // EINTR（被信号打断）当没读到；其他错误算失败。
            val errno = io.github.gcbond.openhc.platform.compat.LegacyOs.errnoOf(e.cause)
            return TunInterface.PollResult(
                woken = false,
                tunReadable = false,
                failed = errno != io.github.gcbond.openhc.platform.compat.LegacyOs.EINTR,
            )
        }
        if (wakePoll.ready.toInt() != 0) return TunInterface.PollResult(woken = true, tunReadable = false, failed = false)
        if (ready <= 0) return TunInterface.PollResult(woken = false, tunReadable = false, failed = false)
        val events = tunPoll.ready.toInt()
        val errBits = io.github.gcbond.openhc.platform.compat.LegacyOs.POLLERR.toInt() or
            io.github.gcbond.openhc.platform.compat.LegacyOs.POLLHUP.toInt() or
            io.github.gcbond.openhc.platform.compat.LegacyOs.POLLNVAL.toInt()
        if (events and errBits != 0) return TunInterface.PollResult(woken = false, tunReadable = false, failed = true)
        return TunInterface.PollResult(
            woken = false,
            tunReadable = events and io.github.gcbond.openhc.platform.compat.LegacyOs.POLLIN.toInt() != 0,
            failed = false,
        )
    }

    override fun read(fd: FileDescriptor, buffer: ByteArray, offset: Int, maxLength: Int): Int =
        legacy.read(fd, buffer, offset, maxLength)

    override fun write(fd: FileDescriptor, buffer: ByteArray, offset: Int, length: Int): Int =
        legacy.write(fd, buffer, offset, length)

    override fun close(fd: FileDescriptor) = legacy.close(fd)
}
