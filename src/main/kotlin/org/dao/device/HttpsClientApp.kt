package org.dao.device

import io.javalin.Javalin
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileInputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLProtocolException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 生产环境 HTTPS 客户端（信任 CA 证书）
 */
fun createProductionHttpsClient(): OkHttpClient = OkHttpClient.Builder()
  // .connectTimeout(10, TimeUnit.SECONDS) // 连接超时
  // .readTimeout(10, TimeUnit.SECONDS) // 读取超时
  // .writeTimeout(10, TimeUnit.SECONDS) // 写入超时
  // 无需额外证书配置，默认信任 CA 证书
  .build()

/**
 * 开发环境 HTTPS 客户端（信任自签证书，生产禁用）
 */
fun createDevHttpsClient(): OkHttpClient {
  // 1. 创建信任所有证书的 TrustManager
  val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
  })

  // 2. 初始化 SSLContext
  val sslContext = SSLContext.getInstance("TLS")
  sslContext.init(null, trustAllCerts, java.security.SecureRandom())

  // // 3. 创建跳过主机名验证的 HostnameVerifier
  // val trustAllHostnames = HostnameVerifier { _, _ -> true }

  // 4. 构建 OkHttp 客户端
  return OkHttpClient.Builder()
    // .connectTimeout(10, TimeUnit.SECONDS)
    // .readTimeout(10, TimeUnit.SECONDS)
    // .writeTimeout(10, TimeUnit.SECONDS)
    .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
    // .hostnameVerifier(trustAllHostnames)
    .build()
}

/**
 * 方式 1：信任指定的本地证书（推荐，比信任所有证书更安全）
 * @param certPath mkcert/自签证书路径（如 localhost+2.pem）
 */
fun createDevClientWithCustomCert(certPath: String): OkHttpClient {
  // 1. 加载本地证书（mkcert 生成的 .pem 文件）
  val certFactory = CertificateFactory.getInstance("X.509")
  val certInputStream = FileInputStream(certPath)
  val cert = certFactory.generateCertificate(certInputStream) as X509Certificate

  // 2. 初始化 KeyStore 并添加证书
  val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
    load(null, null) // 初始化空 KeyStore
    setCertificateEntry("local-cert", cert)
  }

  // 3. 构建信任管理器
  val trustManagerFactory = TrustManagerFactory.getInstance(
    TrustManagerFactory.getDefaultAlgorithm(),
  ).apply {
    init(keyStore)
  }
  val trustManager = trustManagerFactory.trustManagers.first() as X509TrustManager

  // 4. 初始化 SSLContext
  val sslContext = SSLContext.getInstance("TLS").apply {
    init(null, arrayOf(trustManager), java.security.SecureRandom())
  }

  // 5. 构建 OkHttp 客户端
  // val loggingInterceptor = HttpLoggingInterceptor().apply {
  //   level = HttpLoggingInterceptor.Level.BODY // 开发环境显示完整日志
  // }

  return OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .writeTimeout(10, TimeUnit.SECONDS)
    // .addInterceptor(loggingInterceptor)
    .sslSocketFactory(sslContext.socketFactory, trustManager)
    // 可选：跳过主机名验证（仅开发环境，生产禁用）
    // .hostnameVerifier { _, _ -> true }
    .build()
}

/**
 * 主程序：启动 Javalin 并暴露调用 HTTPS 接口的 API
 */
fun main() {
  // 1. 选择客户端（开发/生产）
  val okHttpClient =
    // createProductionHttpsClient() // 生产环境
    createDevHttpsClient() // 开发环境
  // createDevClientWithCustomCert("/Users/shidaoshun/192.168.11.123+2.pem")

  // 2. 启动 Javalin 服务
  val app = Javalin.create().start(9090)

  // 3. 定义接口：调用外部 HTTPS 服务
  app.get("/call-https-api") { ctx ->
    try { // 示例：调用目标 HTTPS 接口（替换为实际地址）
      // val targetUrl = "http://localhost:8000/" // 访问 http
      // val targetUrl = "https://localhost:8001/" // 访问 https
      val targetUrl = "https://192.168.11.123:8001/" // 访问 https
      // 或本地自签证书服务："https://localhost:8443/api/test"

      // 构建 OkHttp 请求
      val request = Request.Builder()
        .url(targetUrl)
        .header("Content-Type", "application/json")
        .get() // 可替换为 post()/put() 等
        .build()

      // 执行请求并处理响应
      val response: Response = okHttpClient.newCall(request).execute()

      // 将响应返回给前端
      ctx.status(response.code)
      ctx.result(response.body?.string() ?: "No response body")
    } catch (e: Exception) {
      when (e) {
        // HTTPS 专属异常
        is SSLHandshakeException -> println("证书验证失败/不被信任") // HTTP 不会抛
        is SSLPeerUnverifiedException -> println("证书域名不匹配") // HTTP 不会抛
        is SSLProtocolException -> println("TLS 协议版本/加密套件不兼容") // HTTP 不会抛
        // 通用异常（HTTP/HTTPS 都可能抛）
        is SocketTimeoutException -> println("连接/读取超时")
        is UnknownHostException -> println("域名解析失败")
      }
      ctx.result(e.toString())
    }
  }

  app.get("/call-http-api") { ctx ->
    // 示例：调用目标 HTTPS 接口（替换为实际地址）
    // val targetUrl = "http://localhost:8000/" // 访问 http
    val targetUrl = "http://localhost:8000/" // 访问 https
    // 或本地自签证书服务："https://localhost:8443/api/test"

    // 构建 OkHttp 请求
    val request = Request.Builder()
      .url(targetUrl)
      .header("Content-Type", "application/json")
      .get() // 可替换为 post()/put() 等
      .build()

    // 执行请求并处理响应
    val response: Response = okHttpClient.newCall(request).execute()

    // 将响应返回给前端
    ctx.status(response.code)
    ctx.result(response.body?.string() ?: "No response body")
  }

  // 4. 优雅关闭客户端（可选）
  // app.events { event ->
  //   event.serverStop {
  //     okHttpClient.dispatcher.executorService.shutdown()
  //     okHttpClient.connectionPool.evictAll()
  //   }
  // }

  println("Javalin server started at http://localhost:8080")
}