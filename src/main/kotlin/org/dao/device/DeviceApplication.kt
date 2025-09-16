package org.dao.device

import io.javalin.Javalin
import io.javalin.http.HandlerType
import org.dao.device.lift.jinbo.JinBoHandler
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lift.jinbo.JinBoTcpServer
import org.dao.device.lift.jinbo.JinBoTcpServerManager

fun main() {
  DeviceApplication.boot()
}

object DeviceApplication {
  fun boot() {
    JinBoServer.init()
    JinBoTcpServerManager.init()

    val app = Javalin.create { config ->
      config.bundledPlugins.enableDevLogging()
    }.get("/") { ctx -> ctx.result("Hello World") }

    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}/request", JinBoHandler::request)
    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}/close", JinBoHandler::closeDoor)
    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}", JinBoHandler::status)

    app.start(7070)
  }
}