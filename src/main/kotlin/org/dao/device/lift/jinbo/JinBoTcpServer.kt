package org.dao.device.lift.jinbo

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.ByteBuf
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder
import java.nio.charset.StandardCharsets

class JinBoTcpServer(private val host: String, private val port: Int) {

  fun start() {
    val bossGroup = NioEventLoopGroup()
    val workerGroup = NioEventLoopGroup()

    try {
      val b = ServerBootstrap()
      b.group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel::class.java)
        .childHandler(object : ChannelInitializer<SocketChannel>() {
          override fun initChannel(ch: SocketChannel) {
            val pipeline = ch.pipeline()
            pipeline.addLast(CustomProtocolDecoder())
            pipeline.addLast(CustomProtocolEncoder())
            pipeline.addLast(ServerHandler())
          }
        })
        .option(ChannelOption.SO_BACKLOG, 128)
        .childOption(ChannelOption.SO_KEEPALIVE, true)

      val f = b.bind(host, port).sync()
      println("Server started on $host:$port")
      f.channel().closeFuture().sync()
    } finally {
      workerGroup.shutdownGracefully()
      bossGroup.shutdownGracefully()
    }
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
class ServerHandler : SimpleChannelInboundHandler<ProtocolMessage>() {
  override fun channelRead0(ctx: ChannelHandlerContext, msg: ProtocolMessage) {
    // 处理接收到的消息
    println("Received message:")
    println("Serial Number: ${msg.flowNo}")
    println("Command ID: ${msg.command}")
    println("Data Length: ${msg.dataLength}")
    println("Data: ${msg.data}")

    // 示例响应
    val responseJson = "{\"status\":\"success\",\"message\":\"Received your data\"}"
    val response = ProtocolMessage(
      msg.flowNo, // 使用相同的流水号
      0x81, // 响应指令编号
      responseJson.length,
      responseJson,
    )

    ctx.writeAndFlush(response)
  }

  override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
    cause.printStackTrace()
    ctx.close()
  }
}

fun main() {
  val host = "0.0.0.0"
  val port = 8080
  JinBoTcpServer(host, port).start()
}