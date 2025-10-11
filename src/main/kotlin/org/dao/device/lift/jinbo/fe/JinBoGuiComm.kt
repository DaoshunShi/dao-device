package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.*
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

data class LiftEvent(val topic: String, val msg: String)

/**
 * 画电梯
 */
class LiftAndDoor(
  config: JinBoConfig, // 梯厢宽度
  val lw: Int, // 梯厢高度
  val lh: Int, // 楼层数量
  val floorNo: Int, // 电梯底部当前高度
  h0: Double,
  status0: JinBoDoorStatus, // 电梯是开着的
) : JPanel(),
  GuiEventListener {
  private var h = round(h0 * height).toInt()

  // private var o = o0
  private var status = status0

  init {
    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics?) {
    background = Color.WHITE

    val g2d = g as Graphics2D
    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2

    // 绘制文本
    g2d.color = Color.black
    g2d.font = Font("Arial", Font.PLAIN, 20)
    val fontMetrics = g2d.fontMetrics
    val textWidth = fontMetrics.stringWidth(status.name)
    val textHeight = fontMetrics.height
    g2d.drawString(status.name, (width - textWidth) / 2, height - lh - h - textHeight / 2)

    // 绘制电梯
    // 绘制门
    when (status) {
      JinBoDoorStatus.OPENING -> {
        g2d.color = Color.lightGray
        g2d.fillRect(centerX - lw / 2, height - lh - h, lw, lh)

        g2d.color = Color.LIGHT_GRAY
        g2d.drawRect(centerX - lw, height - lh - h, lw / 2, lh)
        g2d.drawRect(centerX + lw / 2, height - lh - h, lw / 2, lh)
      }

      JinBoDoorStatus.OPEN -> {
        g2d.color = Color.GREEN
        g2d.fillRect(centerX - lw / 2, height - lh - h, lw, lh)

        g2d.color = Color.DARK_GRAY
        g2d.drawRect(centerX - lw, height - lh - h, lw / 2, lh)
        g2d.drawRect(centerX + lw / 2, height - lh - h, lw / 2, lh)
      }

      JinBoDoorStatus.CLOSING -> {
        g2d.color = Color.YELLOW
        g2d.fillRect(centerX - lw / 2, height - lh - h, lw, lh)

        g2d.color = Color.LIGHT_GRAY
        g2d.drawRect(centerX - lw, height - lh - h, lw / 2, lh)
        g2d.drawRect(centerX + lw / 2, height - lh - h, lw / 2, lh)
      }

      JinBoDoorStatus.CLOSE -> {
        g2d.color = Color.ORANGE
        g2d.fillRect(centerX - lw / 2, height - lh - h, lw, lh)

        g2d.color = Color.LIGHT_GRAY
        g2d.drawRect(centerX - lw / 2, height - lh - h, lw / 2, lh)
        g2d.drawRect(centerX, height - lh - h, lw / 2, lh)
      }

      JinBoDoorStatus.ERROR -> {
        g2d.color = Color.DARK_GRAY
        g2d.fillRect(centerX - lw / 2, height - lh - h, lw, lh)

        g2d.color = Color.RED
        g2d.drawRect(centerX - lw / 2, height - lh - h, lw / 2, lh)
        g2d.drawRect(centerX, height - lh - h, lw / 2, lh)
      }
    }

    // 绘制一条线：上边
    g2d.color = Color.LIGHT_GRAY
    g2d.drawLine(centerX, 0, centerX, height - lh - h)

    // 绘制一条线：下边
    g2d.drawLine(centerX, height - h, centerX, height)

    // 绘制楼层线条：
    val floorHeight = height / floorNo
    var ch = 0
    repeat(floorNo) {
      g2d.drawLine(0, ch, width, ch)
      ch += floorHeight
    }
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic == "liftState") {
      val map: Map<String, Any> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      h = round(height * (map["h"] as Double)).toInt()
      status = JinBoDoorStatus.valueOf(map["status"] as String)
      repaint()
    }
  }
}

/**
 * 圆形内套三角，用于外部呼叫电梯按钮
 */
class TriangleCircle(
  val config: JinBoConfig,
  val floorIdx: Int,
  val circleRadius: Int,
  val triangleSize: Int,
  val up: Boolean,
) : JPanel(),
  GuiEventListener {

  // 颜色设置

  private val rotationAngle: Double = if (up) -PI / 2 else PI / 2

  private var active = false

  init {
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        println("Clicked on TriangleCircle")
        active = !active

        JinBoServer.request(
          config.id,
          JinBoReq(floorIdx, JinBoReqSource.OutDoor, if (up) JinBoLiftStatus.Up else JinBoLiftStatus.Down),
        )

        repaint()
      }
    })

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val centerY = height / 2

    // 绘制圆圈
    g2d.color = if (active) Color.BLUE else Color.gray
    g2d.stroke = BasicStroke(3.0f)
    g2d.drawOval(
      centerX - circleRadius,
      centerY - circleRadius,
      circleRadius * 2,
      circleRadius * 2,
    )

    // 计算三角形顶点
    val triangle = createTriangle(centerX, centerY, triangleSize, rotationAngle)

    // 绘制三角形
    g2d.color = if (active) Color.ORANGE else Color.DARK_GRAY
    g2d.fillPolygon(triangle)
    g2d.color = Color.BLACK
    g2d.drawPolygon(triangle)
  }

  /**
   * 创建三角形
   * 顺时针转的
   */
  private fun createTriangle(centerX: Int, centerY: Int, size: Int, rotation: Double): Polygon {
    val xPoints = IntArray(3)
    val yPoints = IntArray(3)

    // 计算三角形的三个顶点
    for (i in 0 until 3) {
      val angle = rotation + 2 * PI * i / 3
      xPoints[i] = (centerX + size * cos(angle)).toInt()
      yPoints[i] = (centerY + size * sin(angle)).toInt()
    }

    return Polygon(xPoints, yPoints, 3)
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic == "outside") {
      val list: List<JinBoReq> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      active =
        list.any {
          it.destFloor == floorIdx &&
            ((up && it.type == JinBoLiftStatus.Up) || (!up && it.type == JinBoLiftStatus.Down))
        }
      repaint()
    }
  }
}

/**
 * 圆形内套双三角，用于内部开关电梯门
 */
class DoubleTriangleCircle(config: JinBoConfig, circleR: Int, triSize: Int, triRad: Double) :
  JPanel(),
  GuiEventListener {

  // 圆圈和三角形的属性
  private var circleRadius = circleR
  private var triangleSize = triSize
  private var rotationAngle = triRad

  // 颜色设置
  private val circleColor = Color.BLUE
  private val triangleColor = Color.RED
  private val backgroundColor = Color.WHITE

  init {
    background = backgroundColor

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val centerY = height / 2

    // 绘制圆圈
    g2d.color = circleColor
    g2d.stroke = BasicStroke(3.0f)
    g2d.drawOval(
      centerX - circleRadius,
      centerY - circleRadius,
      circleRadius * 2,
      circleRadius * 2,
    )

    // 计算三角形顶点
    val triangle = createTriangle(centerX, centerY, triangleSize, rotationAngle)

    // 绘制三角形
    g2d.color = triangleColor
    g2d.fillPolygon(triangle)
    g2d.color = Color.BLACK
    g2d.drawPolygon(triangle)
  }

  /**
   * 创建三角形
   * 顺时针转的
   */
  private fun createTriangle(centerX: Int, centerY: Int, size: Int, rotation: Double): Polygon {
    val xPoints = IntArray(3)
    val yPoints = IntArray(3)

    // 计算三角形的三个顶点
    for (i in 0 until 3) {
      val angle = rotation + 2 * PI * i / 3
      xPoints[i] = (centerX + size * cos(angle)).toInt()
      yPoints[i] = (centerY + size * sin(angle)).toInt()
    }

    return Polygon(xPoints, yPoints, 3)
  }

  override fun onEvent(event: LiftEvent) {
    // if (event.topic == "inside") {
    //   val list: List<JinBoReq> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
    //   active =
    //     list.any {
    //       it.destFloor == floorIdx &&
    //         ((up && it.type == JinBoLiftStatus.Up) || (!up && it.type == JinBoLiftStatus.Down))
    //     }
    //   repaint()
    // }
  }
}

/**
 * 圆形内套文本，用于电梯内去指定楼层
 */
class LabelCircle(config: JinBoConfig, val floorIdx: Int, val labelStr: String, val circleR: Int, val labelSize: Int) :
  JPanel(),
  GuiEventListener {

  private var active = false

  init {
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        println("Clicked on LabelCircle")
        active = !active

        JinBoServer.request(config.id, JinBoReq(floorIdx, JinBoReqSource.InDoor))

        repaint()
      }
    })

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val centerY = height / 2

    // 绘制圆圈
    g2d.color = if (active) Color.BLUE else Color.gray
    g2d.stroke = BasicStroke(3.0f)
    g2d.drawOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
    if (active) {
      g2d.color = Color.ORANGE
      g2d.fillOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
    }

    // 绘制文本
    g2d.color = if (active) Color.WHITE else Color.DARK_GRAY
    g2d.font = Font("Arial", Font.PLAIN, labelSize)
    val fontMetrics = g2d.fontMetrics
    val textWidth = fontMetrics.stringWidth(labelStr)
    val textHeight = fontMetrics.height
    g2d.drawString(labelStr, (width - textWidth) / 2, (height - textHeight) / 2 + fontMetrics.ascent)
  }

  /**
   * 创建三角形
   * 顺时针转的
   */
  private fun createTriangle(centerX: Int, centerY: Int, size: Int, rotation: Double): Polygon {
    val xPoints = IntArray(3)
    val yPoints = IntArray(3)

    // 计算三角形的三个顶点
    for (i in 0 until 3) {
      val angle = rotation + 2 * PI * i / 3
      xPoints[i] = (centerX + size * cos(angle)).toInt()
      yPoints[i] = (centerY + size * sin(angle)).toInt()
    }

    return Polygon(xPoints, yPoints, 3)
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic == "inside") {
      val list: List<JinBoReq> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      active = list.any { it.destFloor == floorIdx }
      repaint()
    }
  }
}

/**
 * 圆形内套文本，用于电梯内去指定楼层
 */
class LabelCircle2(config: JinBoConfig, val floorIdx: Int, val labelStr: String, val circleR: Int, val labelSize: Int) :
  JPanel(),
  GuiEventListener {

  private var active = false

  init {
    addMouseListener(object : MouseAdapter() {
      override fun mouseClicked(e: MouseEvent) {
        println("Clicked on LabelCircle")
        active = !active

        JinBoServer.request(config.id, JinBoReq(floorIdx, JinBoReqSource.InDoor))

        repaint()
      }
    })

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    val g2d = g as Graphics2D

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val centerY = height / 2

    // 绘制圆圈
    g2d.color = if (active) Color.BLUE else Color.gray
    g2d.stroke = BasicStroke(3.0f)
    g2d.drawOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
    if (active) {
      g2d.color = Color.ORANGE
      g2d.fillOval(centerX - circleR, centerY - circleR, circleR * 2, circleR * 2)
    }

    // 绘制文本
    g2d.color = if (active) Color.WHITE else Color.DARK_GRAY
    g2d.font = Font("Arial", Font.PLAIN, labelSize)
    val fontMetrics = g2d.fontMetrics
    val textWidth = fontMetrics.stringWidth(labelStr)
    val textHeight = fontMetrics.height
    g2d.drawString(labelStr, (width - textWidth) / 2, (height - textHeight) / 2 + fontMetrics.ascent)
  }

  /**
   * 创建三角形
   * 顺时针转的
   */
  private fun createTriangle(centerX: Int, centerY: Int, size: Int, rotation: Double): Polygon {
    val xPoints = IntArray(3)
    val yPoints = IntArray(3)

    // 计算三角形的三个顶点
    for (i in 0 until 3) {
      val angle = rotation + 2 * PI * i / 3
      xPoints[i] = (centerX + size * cos(angle)).toInt()
      yPoints[i] = (centerY + size * sin(angle)).toInt()
    }

    return Polygon(xPoints, yPoints, 3)
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic == "tcp") {
      val list: List<JinBoReq> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      active = list.any { it.destFloor == floorIdx }
      repaint()
    }
  }
}