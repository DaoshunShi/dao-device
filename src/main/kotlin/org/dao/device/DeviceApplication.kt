package org.dao.device

import io.javalin.Javalin

fun main() {
  DeviceApplication.boot()
  print("hello world")
}

object DeviceApplication {
  fun boot() {
    val app = Javalin.create { config ->
      config.bundledPlugins.enableDevLogging()
    }.get("/") { ctx -> ctx.result("Hello World") }.start(7070)
  }
}