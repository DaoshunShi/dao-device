package org.dao.device.common

import org.apache.commons.io.FileUtils
import org.apache.commons.io.IOUtils
import java.io.File
import java.nio.charset.StandardCharsets

object FileHelper {

  fun readFileToString(filePath: String): String? = try {
    val file = File(filePath)
    if (file.exists()) {
      FileUtils.readFileToString(file, StandardCharsets.UTF_8)
    } else {
      println("文件不存在: $filePath")
      null
    }
  } catch (e: Exception) {
    println("读取文件失败: $filePath, 错误: ${e.message}")
    e.printStackTrace()
    null
  }
}