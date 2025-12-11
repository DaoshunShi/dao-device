package org.dao.device

import io.javalin.Javalin
import io.javalin.http.Context
import io.javalin.http.HttpStatus

fun main() {
  val app = Javalin.create().start(7070)

  // 通用表单接口：自动区分 x-www-form-urlencoded / form-data
  app.post("/api/form/parse") { ctx ->
    // 1. 获取并解析 Content-Type 头
    val contentType = ctx.contentType() ?: run {
      ctx.status(HttpStatus.BAD_REQUEST)
      ctx.json(mapOf("code" to 400, "msg" to "请求头 Content-Type 不能为空"))
      return@post
    }

    // 2. 区分请求格式
    when {
      // 判定：x-www-form-urlencoded
      contentType.startsWith("application/x-www-form-urlencoded") -> {
        handleFormUrlEncoded(ctx)
      }
      // 判定：multipart/form-data（form-data）
      contentType.startsWith("multipart/form-data") -> {
        handleFormData(ctx)
      }
      // 不支持的格式
      else -> {
        ctx.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
        ctx.json(
          mapOf(
            "code" to 415,
            "msg" to "仅支持 application/x-www-form-urlencoded 或 multipart/form-data 格式",
            "received" to contentType,
          ),
        )
      }
    }
  }

  println("服务启动成功：http://localhost:7070")
}

/**
 * 处理 x-www-form-urlencoded 格式请求
 */
fun handleFormUrlEncoded(ctx: Context) {
  // 获取 x-www-form-urlencoded 参数（Javalin 自动解码）
  val username = ctx.formParam("username") ?: "默认值"
  val password = ctx.formParam("password") ?: "默认值"

  ctx.json(
    mapOf(
      "code" to 200,
      "msg" to "解析 x-www-form-urlencoded 成功",
      "data" to mapOf(
        "username" to username,
        "password" to password,
        "contentType" to ctx.contentType(),
      ),
    ),
  )
}

/**
 * 处理 multipart/form-data 格式请求（支持普通参数 + 文件）
 */
fun handleFormData(ctx: Context) {
  // 1. 获取 form-data 普通参数
  val username = ctx.formParam("username") ?: "默认值"
  val age = ctx.formParam("age")?.toIntOrNull() ?: 0

  // 2. 可选：获取 form-data 中的文件（如果有）
  val file = ctx.uploadedFile("avatar") // 文件名参数名：avatar
  val fileInfo = file?.let {
    mapOf(
      "fileName" to it.filename(),
      "contentType" to it.contentType(),
      "size" to it.size(),
    )
  } ?: "无文件"

  ctx.json(
    mapOf(
      "code" to 200,
      "msg" to "解析 multipart/form-data 成功",
      "data" to mapOf(
        "username" to username,
        "age" to age,
        "fileInfo" to fileInfo,
        "contentType" to ctx.contentType(),
      ),
    ),
  )
}