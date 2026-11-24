package com.wxgy.checkin

/**
 * 现场照片的内存暂存区。
 * 拍完直接交给打卡流程，用完即清；任何环节都不写手机本地存储。
 */
object PhotoHolder {
    @Volatile
    private var jpeg: ByteArray? = null

    fun put(bytes: ByteArray) {
        jpeg = bytes
    }

    fun take(): ByteArray? {
        val b = jpeg
        jpeg = null
        return b
    }

    fun clear() {
        jpeg = null
    }
}
