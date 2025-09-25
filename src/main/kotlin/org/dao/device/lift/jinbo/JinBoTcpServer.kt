package org.dao.device.lift.jinbo

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.fe.LiftEvent
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

class JinBoTcpServer(
  private val liftId: String, // 电梯 ID
  private val host: String,
  private var port: Int,
  private var logTcp: Boolean,
) {
  private val logger = LoggerFactory.getLogger(javaClass)

  private val isRunning = AtomicBoolean(false)
  private var bossGroup: NioEventLoopGroup? = null
  private var workerGroup: NioEventLoopGroup? = null
  private var channel: Channel? = null
  private var serverThread: Thread? = null

  fun start() {
    if (isRunning.get()) {
      logger.info("Server is already running")
      return
    }
    serverThread = Thread {
      try {
        bossGroup = NioEventLoopGroup()
        workerGroup = NioEventLoopGroup()
        val b = ServerBootstrap()
        b.group(bossGroup, workerGroup)
          .channel(NioServerSocketChannel::class.java)
          .childHandler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
              val pipeline = ch.pipeline()
              pipeline.addLast(CustomProtocolDecoder())
              pipeline.addLast(CustomProtocolEncoder())
              pipeline.addLast(ServerHandler(liftId))
            }
          })
          .option(ChannelOption.SO_BACKLOG, 128)
          .childOption(ChannelOption.SO_KEEPALIVE, true)
        val f = b.bind(host, port).sync()
        channel = f.channel()
        logger.info("Server started on $host:$port")
        isRunning.set(true)
        f.channel().closeFuture().sync()
      } catch (e: Exception) {
        logger.error("Error during server startup: ${e.message}", e)
      } finally {
        // shutdownInternal()
      }
    }.apply {
      name = "JinBoTcpServer-$host:$port"
      start()
    }
  }

  fun shutdown() {
    if (!isRunning.get()) {
      logger.info("Server is not running")
      return
    }
    shutdownInternal()
  }

  private fun shutdownInternal() {
    try {
      channel?.close()?.sync()
      workerGroup?.shutdownGracefully()?.sync()
      bossGroup?.shutdownGracefully()?.sync()
      isRunning.set(false)
      logger.info("Server shutdown complete")
    } catch (e: Exception) {
      logger.error("Error during shutdown: ${e.message}", e)
    } finally {
      channel = null
      bossGroup = null
      workerGroup = null
    }
  }

  fun updatePort(newPort: Int, newLogTcp: Boolean) {
    Thread {
      if (newPort == port) {
        return@Thread
      }

      logger.info("Updating port from $port to $newPort")
      val wasRunning = isRunning.get()

      if (wasRunning) {
        shutdown()
        // 等待服务器完全关闭
        Thread.sleep(100) // 短暂等待确保资源释放
      }

      port = newPort
      logTcp = newLogTcp

      if (wasRunning) {
        // 重新启动服务器
        start()
      }
    }.start()
  }
}

// 协议消息类
data class ProtocolMessage(val flowNo: Byte, val command: Int, val dataLength: Int, val data: String)

// 自定义协议解码器
class CustomProtocolDecoder : ByteToMessageDecoder() {

  @Volatile
  private var started = false

  @Volatile
  private var bodySize = -1

  @Volatile
  private var flowNo: Byte = -1

  @Volatile
  private var command: Int = -1

  override fun decode(ctx: ChannelHandlerContext, buf: ByteBuf, out: MutableList<Any>) {
    // 确保有足够的字节可读
    // if (buf.readableBytes() < 8) { // 1 字节(0x5a) + 1 字节(流水号) + 2 字节(指令编号) + 4 字节(长度)
    //   return
    // }

    // 检查起始字节
    if (!started) {
      while (buf.readableBytes() > 0) {
        // 不断去掉无效的字节
        if (buf.readByte() == START_MARK) {
          started = true
          break
        }
      }
    }
    if (!started) return

    // 标记读取位置
    buf.markReaderIndex()

    // 读取流水号
    val serialNumber = buf.readByte()

    // 读取指令编号
    val commandId = buf.readUnsignedShort()

    // 读取数据区长度
    val dataLength = buf.readInt()

    // 检查是否有足够的数据可读
    if (buf.readableBytes() < dataLength) {
      // 重置读取位置
      buf.resetReaderIndex()
      return
    }

    // 读取数据区
    val data = ByteArray(dataLength)
    buf.readBytes(data)

    // 构建消息对象
    val message = ProtocolMessage(
      serialNumber,
      commandId,
      dataLength,
      String(data, StandardCharsets.UTF_8),
    )
    out.add(message)

    started = false
    bodySize = -1
    flowNo = -1
  }

  companion object {
    const val START_MARK: Byte = 0x5a.toByte()
  }
}

// 自定义协议编码器
class CustomProtocolEncoder : MessageToByteEncoder<ProtocolMessage>() {
  override fun encode(ctx: ChannelHandlerContext, msg: ProtocolMessage, out: ByteBuf) {
    // 写入起始字节
    out.writeByte(0x5a)
    // 写入流水号
    out.writeByte(msg.flowNo.toInt())
    // 写入指令编号
    out.writeShort(msg.command)
    // 写入数据区长度
    out.writeInt(msg.dataLength)
    // 写入数据区
    out.writeBytes(msg.data.toByteArray(StandardCharsets.UTF_8))
  }
}

// 服务器业务处理器
class ServerHandler(val liftId: String) : SimpleChannelInboundHandler<ProtocolMessage>() {
  private val logger = LoggerFactory.getLogger(javaClass)
  override fun channelRead0(ctx: ChannelHandlerContext, msg: ProtocolMessage) {
    // 处理接收到的消息
    // logger.debug("Received message:")
    // logger.debug("Serial Number: ${msg.flowNo}")
    // logger.debug("Command ID: ${msg.command}")
    // logger.debug("Data Length: ${msg.dataLength}")
    // logger.debug("Data: ${msg.data}")

    val respStr = when (msg.command) {
      0x3e8 -> {
        // 到指定楼层
        val req: JinBoTcpGoToReq = JsonHelper.mapper.readValue(msg.data, jacksonTypeRef())
        JinBoServer.request(liftId, JinBoReq(req.destFloor.toInt()))
        JinBoServer.logReq(liftId, LiftEvent("goto", JsonHelper.writeValueAsString(req)))
        JsonHelper.writeValueAsString(JinBoTcpResp())
      }

      0x3e9 -> {
        // 关门
        JinBoServer.close(liftId)
        JinBoServer.logReq(liftId, LiftEvent("close", ""))
        JsonHelper.writeValueAsString(JinBoTcpResp())
      }

      0x7d0 -> {
        // 获取电梯状态
        JsonHelper.writeValueAsString(JinBoServer.status(liftId))
      }

      else -> {
        ""
      }
    }

    // 示例响应
    val response = ProtocolMessage(
      msg.flowNo, // 使用相同的流水号
      msg.flowNo + 100000, // 响应指令编号
      respStr.length,
      respStr,
    )

    ctx.writeAndFlush(response)
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    logger.error("Exception caught: ${cause.message}")
    ctx.close()
  }
}

data class JinBoTcpGoToReq(val destFloor: String)

data class JinBoTcpResp(val code: String = "0", val msg: String = "noError")