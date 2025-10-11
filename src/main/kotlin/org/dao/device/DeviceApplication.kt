package org.dao.device

import io.javalin.Javalin
import io.javalin.http.HandlerType
import org.dao.device.lift.jinbo.JinBoHandler
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lv.LogViewerService

fun main() {
  DeviceApplication.boot()
}

object DeviceApplication {
  fun boot() {
    // JinBo 电梯
    JinBoServer.init()

    // 日志查看器
    LogViewerService.init()

    val app = Javalin.create { config ->
      config.bundledPlugins.enableDevLogging()
    }.get("/") { ctx -> ctx.result("Hello World") }

    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}/request", JinBoHandler::request)
    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}/close", JinBoHandler::closeDoor)
    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}", JinBoHandler::status)

    app.start(7070)
  }
}