package com.heyanle.easybangumi4.download.step

import android.content.Context
import com.heyanle.easy_i18n.R
import com.heyanle.easybangumi4.download.DownloadBus
import com.heyanle.easybangumi4.download.DownloadController
import com.heyanle.easybangumi4.download.entity.DownloadItem
import com.heyanle.easybangumi4.download.utils.M3U8Utils
import com.heyanle.easybangumi4.ui.common.moeSnackBar
import com.heyanle.easybangumi4.utils.getCachePath
import com.heyanle.easybangumi4.utils.stringRes
import com.jeffmony.m3u8library.VideoProcessManager
import com.jeffmony.m3u8library.listener.IVideoTransformListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Created by heyanlin on 2023/10/2.
 */
class TranscodeStep(
    private val context: Context,
    private val downloadController: DownloadController,
    private val downloadBus: DownloadBus,
) : BaseStep {

    companion object {
        const val NAME = "transcode"
    }

    private val mainScope = MainScope()


    override fun invoke(downloadItem: DownloadItem) {
        mainScope.launch(Dispatchers.IO) {
            if (!decrypt(downloadItem)) {
                error(downloadItem, stringRes(com.heyanle.easy_i18n.R.string.decrypt_error))
                return@launch
            }
            if (!ffmpeg(
                    downloadItem = downloadItem,
                    onError = {
                        it?.moeSnackBar()
                        error(
                            downloadItem,
                            it?.message ?: stringRes(com.heyanle.easy_i18n.R.string.transcode_error)
                        )
                    },
                    onCompletely = {
                        completely(downloadItem)
                    }
                )
            ) {
                error(downloadItem, stringRes(com.heyanle.easy_i18n.R.string.transcode_error))
                return@launch
            }
        }
    }

    override fun onRemove(downloadItem: DownloadItem) {
        downloadController.updateDownloadItem(downloadItem.uuid){
            it.copy(isRemoved = true)
        }
    }

    private fun decrypt(downloadItem: DownloadItem): Boolean {
        val info = downloadBus.getInfo(downloadItem.uuid)
        val entity = downloadItem.bundle.m3U8Entity ?: return false
        val localM3U8 = File(entity.filePath)

        if (!localM3U8.exists() || !localM3U8.canRead()) {
            return false
        }
        val target = File(downloadItem.bundle.downloadFolder, "${downloadItem.uuid}.m3u8.temp")
        val realTarget = File(downloadItem.bundle.downloadFolder, "${downloadItem.uuid}.m3u8")
        File(downloadItem.bundle.downloadFolder).mkdirs()
        target.delete()
        if (!target.createNewFile() || !target.canWrite()) {
            return false
        }
        mainScope.launch {
            info.status.value = stringRes(R.string.decrypting)
            info.process.value = -1f
            info.subStatus.value = ""
        }
        // 将本地 m3u8 文件中的 ts 全都解密改写成 tsh 文件
        // 输出新的 m3u8 文件，去除 key 标签，文件路径改为 tsh
        // 如果 tsh 文件的文件头是 png，则去除文件头
        val it = localM3U8.readLines().iterator()
        val writer = target.writer(Charsets.UTF_8).buffered()
        val tsFiles = arrayListOf<File>()
        val targetTsFiles = arrayListOf<File>()
        while (it.hasNext()) {
            val line = it.next()
            if (line.startsWith("#EXTINF")) {
                if (!it.hasNext()) {
                    return false
                } else {
                    val ts = it.next()
                    val file = File(ts)
                    val targetFile = File("${ts}h")
                    // 大于 500M 的就不走自行解密了，防止 OOM，直接丢 ffmpeg 转
                    if (file.length() >= 500 * 1024 * 1024) {
                        realTarget.delete()
                        localM3U8.renameTo(realTarget)
                        return true
                    }
                    tsFiles.add(file)
                    // ts -> tsh
                    targetTsFiles.add(targetFile)

                    writer.write(line)
                    writer.newLine()
                    writer.write(targetFile.absolutePath)
                    writer.newLine()
                }

            } else if (line.startsWith("#EXT-X-KEY")) {
                // 新的 m3u8 文件不用解密了
                continue
            } else {
                writer.write(line)
                writer.newLine()
            }
        }
        writer.flush()

        // 开始解密咯！
        val needDecrypt = !entity.method.isNullOrEmpty()
        val keyFile = File(entity.filePath)
        val keyB = keyFile.readBytes()
        mainScope.launch {
            info.status.value = stringRes(R.string.decrypting)
            info.process.value = 0f
            info.subStatus.value = "0/${tsFiles.size}"
        }
        for (i in 0 until tsFiles.size.coerceAtMost(targetTsFiles.size)) {
            mainScope.launch {
                info.status.value = stringRes(R.string.decrypting)
                info.process.value = if (tsFiles.size == 0) 0f else {
                    (i + 1) / (tsFiles.size).toFloat()
                }
                info.subStatus.value = "${i + 1}/${tsFiles.size}"
            }
            val ts = tsFiles[i]
            val tsh = targetTsFiles[i]
            val parent = tsh.parentFile ?: return false

            val tshTemp = File(parent, tsh.name + ".temp")
            // ts 文件不在 tsh 文件存在 tsh.temp 文件不存在这说明该文件解密过了，直接跳过
            if (!ts.exists() && tsh.exists() && !tshTemp.exists()) {
                continue
            }
            tsh.delete()
            tshTemp.delete()
            tshTemp.createNewFile()
            if (!tshTemp.canRead() || !tshTemp.canWrite()) {
                return false
            }
            val s = ts.readBytes()
            val res = if (needDecrypt) M3U8Utils.decrypt(
                s,
                s.size,
                keyB,
                entity.iv,
                entity.method
            ) else s
            // 文件头伪装成 png
            val rr = res ?: s
            if (rr[0].toInt() == 0x89 && rr[1].toInt() == 0x50 && rr[2].toInt() == 0x4E && rr[3].toInt() == 0x47) {
                rr[0] = 0xff.toByte()
                rr[1] = 0xff.toByte()
                rr[2] = 0xff.toByte()
                rr[3] = 0xff.toByte()
            }
            tshTemp.writeBytes(rr)
            tshTemp.renameTo(tsh)
            ts.delete()
        }
        target.renameTo(realTarget)
        return true
    }

    private fun ffmpeg(
        downloadItem: DownloadItem,
        onError: (Exception?) -> Unit,
        onCompletely: () -> Unit
    ): Boolean {
        val info = downloadBus.getInfo(downloadItem.uuid)
        val m3u8 = File(downloadItem.bundle.downloadFolder, "${downloadItem.uuid}.m3u8")
        if (!m3u8.exists() || !m3u8.canRead()) {
            return false
        }
        val realTarget = File(downloadItem.bundle.filePathBeforeCopy)
        val parentFile = realTarget.parentFile ?: return false
        parentFile.mkdirs()
        val target = File(parentFile, realTarget.name + ".temp.mp4")
        mainScope.launch {
            info.status.value = stringRes(R.string.transcoding)
            info.subStatus.value = ""
            info.process.value = 0f
        }
        VideoProcessManager.getInstance().transformM3U8ToMp4(
            m3u8.absolutePath,
            target.absolutePath,
            object : IVideoTransformListener {
                override fun onTransformProgress(progress: Float) {
                    mainScope.launch {
                        info.status.value = stringRes(R.string.transcoding)
                        info.process.value = progress
                        info.subStatus.value = "${(progress * 100).toInt()}%"
                    }
                }

                override fun onTransformFailed(e: Exception?) {
                    onError(e)

                }

                override fun onTransformFinished() {
                    target.renameTo(realTarget)
                    M3U8Utils.deleteM3U8WithTs(m3u8.absolutePath)
                    onCompletely()
                }
            }
        )
        return true
    }

    private fun error(downloadItem: DownloadItem, error: String) {
        downloadController.updateDownloadItem(downloadItem.uuid) {
            it.copy(
                state = -1,
                errorMsg = error
            )
        }
    }

    private fun completely(downloadItem: DownloadItem) {
        downloadController.updateDownloadItem(downloadItem.uuid) {
            it.copy(
                state = 2
            )
        }
    }
}