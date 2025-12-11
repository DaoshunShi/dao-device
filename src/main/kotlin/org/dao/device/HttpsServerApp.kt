package org.dao.device

import io.javalin.Javalin
import io.javalin.community.ssl.SslPlugin
import io.javalin.community.ssl.TlsConfig

fun main() {
  val plugin = SslPlugin { conf ->
    // 用 OpenSSL 生成的自签证书。浏览器会报“不安全”
    conf.pemFromPath("/Users/shidaoshun/server.crt", "/Users/shidaoshun/server.key")

    // 用 mkcert 生成本地信任证书，浏览器认为“安全”
    // conf.pemFromPath("/Users/shidaoshun/192.168.11.123+2.pem", "/Users/shidaoshun/192.168.11.123+2-key.pem")

    // conf.insecure = true
    // conf.secure = true
    // conf.http2 = false
    conf.insecurePort = 8000 // 默认值 80
    conf.securePort = 8001 // 默认值 443
    conf.sniHostCheck = false
    conf.tlsConfig = TlsConfig.INTERMEDIATE
  }

  val app = Javalin.create { jc ->
    jc.bundledPlugins.enableDevLogging()
    jc.registerPlugin(plugin)
  }.get("/") { ctx -> ctx.result("Hello World") }

  app.get("/c") { ctx ->
    ctx.result("macOS 本地信任证书测试成功！")
  }

  // https 访问的链接 https://localhost:8001
  // http 访问的链接 https://localhost:8000

  app.start(8080)
}