package org.dao.device.lift.jinbo

object JinBoTcpServerManager {

  val server = JinBoTcpServer("0.0.0.0", 8080)

  fun init() {
    server.start()
  }
}