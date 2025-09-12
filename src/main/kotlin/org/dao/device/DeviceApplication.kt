package org.dao.device

import io.javalin.Javalin
import io.javalin.http.HandlerType
import org.dao.device.lift.jinbo.JinBoLiftHandler
import org.dao.device.lift.jinbo.JinBoLiftServer

fun main() {
  DeviceApplication.boot()
}

object DeviceApplication {
  fun boot() {
    JinBoLiftServer.init()

    val app = Javalin.create { config ->
      config.bundledPlugins.enableDevLogging()
    }.get("/") { ctx -> ctx.result("Hello World") }

    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}/request", JinBoLiftHandler::request)
    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}/close", JinBoLiftHandler::closeDoor)
    app.addHttpHandler(HandlerType.POST, "api/lift/{liftId}", JinBoLiftHandler::status)

    app.start(7070)
  }
}