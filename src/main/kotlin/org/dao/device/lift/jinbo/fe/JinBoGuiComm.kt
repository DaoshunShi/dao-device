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
class Cage(
  private val config: JinBoConfig, // 电梯配置
  val lw: Int, // 梯厢宽度
  val lh: Int, // 梯厢高度
  h0: Double, // 电梯当前高度（米）
  status0: JinBoDoorStatus, // 电梯门状态
) : JPanel(),
  GuiEventListener {
  private var elevatorHeightMeters = h0 // 电梯当前高度（米）
  private var status = status0
  private var doors: List<JinBoDoor> = listOf(JinBoDoor(code = 0, status = status0))
  private var carWidth: Int = lw // 梯厢宽度随门数动态调整
  private val maxHeight: Double
  private val minHeight: Double

  init {
    // 计算电梯井的高度范围
    val floorHeights = config.floors.map { it.height }
    minHeight = floorHeights.minOrNull() ?: 0.0
    maxHeight = floorHeights.maxOrNull() ?: 0.0

    // 初始计算电梯位置
    updateHeight(h0)

    JinBoEventBus.register(config.id, this)
  }

  /**
   * 更新电梯高度（米）
   */
  private fun updateHeight(elevatorHeightMeters: Double) {
    this.elevatorHeightMeters = elevatorHeightMeters
    // 位置将在paintComponent中重新计算
  }

  /**
   * 计算电梯在面板中的像素位置
   */
  private fun calculateHPosition(): Int {
    if (height <= 0) {
      // println("[Cage DEBUG] Panel height is 0, cannot calculate position")
      return 0 // 面板未初始化
    }

    val heightRange = maxHeight - minHeight
    if (heightRange <= 0) {
      // println("[Cage DEBUG] Height range is $heightRange (min: $minHeight, max: $maxHeight)")
      return 0
    }

    // 将电梯高度归一化到 [0, 1] 范围
    val normalizedHeight = (elevatorHeightMeters - minHeight) / heightRange
    val pixelPosition = round(normalizedHeight * (height - lh)).toInt().coerceIn(0, height - lh)

    // 调试输出
    // println("[Cage DEBUG] calculateHPosition: height=$height, lh=$lh, elevatorHeight=$elevatorHeightMeters, normalized=$normalizedHeight, pixelPos=$pixelPosition")

    // 转换为面板中的位置（从底部开始）
    // 注意：面板坐标原点在左上角，所以需要从底部计算
    return pixelPosition
  }

  override fun paintComponent(g: Graphics?) {
    background = Color.WHITE

    val g2d = g as Graphics2D

    // 计算电梯当前位置
    val h = calculateHPosition()

    // 清除背景
    g2d.color = background
    g2d.fillRect(0, 0, width, height)

    // 启用抗锯齿
    g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    val centerX = width / 2
    val carX = centerX - carWidth / 2

    // 绘制文本
    g2d.color = Color.black
    g2d.font = Font("Arial", Font.PLAIN, 20)
    val text = statusLabel(status)
    val fontMetrics = g2d.fontMetrics
    val textWidth = fontMetrics.stringWidth(text)
    val textHeight = fontMetrics.height
    g2d.drawString(text, (width - textWidth) / 2, height - lh - h - textHeight / 2)

    // 门数大于 1 时按门号从左到右依次绘制，单门保持原有梯厢尺寸和样式。
    val doorWidth = (carWidth - (doors.size + 1) * DOOR_GAP) / doors.size
    doors.forEachIndexed { index, door ->
      val x = carX + DOOR_GAP + index * (doorWidth + DOOR_GAP)
      drawDoor(g2d, door, x, height - lh - h, doorWidth, lh)
    }

    // 绘制一条线：上边
    g2d.color = Color.LIGHT_GRAY
    g2d.drawLine(centerX, 0, centerX, height - lh - h)

    // 绘制一条线：下边
    g2d.drawLine(centerX, height - h, centerX, height)

    // 绘制楼层线条（根据实际楼层高度）：
    val heightRange = maxHeight - minHeight
    if (heightRange > 0) {
      // 按高度排序楼层（从低到高），过滤掉被禁用的楼层
      val sortedFloors = config.floors.filter { !it.disabled }.sortedBy { it.height }
      for (floor in sortedFloors) {
        // 计算楼层在面板中的位置
        val normalizedPosition = (floor.height - minHeight) / heightRange
        val y = height - round(normalizedPosition * (height - lh)).toInt()
        g2d.drawLine(0, y, width, y)

        // 可选：在楼层线上添加标签
        val label = floor.label
        // 重新获取fontMetrics以确保作用域正确
        val floorFontMetrics = g2d.fontMetrics
        val labelWidth = floorFontMetrics.stringWidth(label)
        g2d.drawString(label, width - labelWidth - 5, y - 2)
      }
    }
  }

  /** 按原梯厢样式绘制单扇门，并用文字标出该门的状态。 */
  private fun drawDoor(g2d: Graphics2D, door: JinBoDoor, x: Int, y: Int, width: Int, height: Int) {
    val color = when (door.status) {
      JinBoDoorStatus.OPENING -> Color.lightGray
      JinBoDoorStatus.OPEN -> Color.GREEN
      JinBoDoorStatus.CLOSING -> Color.YELLOW
      JinBoDoorStatus.CLOSE -> Color.ORANGE
      JinBoDoorStatus.ERROR -> Color.DARK_GRAY
    }
    g2d.color = color
    g2d.fillRect(x, y, width, height)

    g2d.color = when (door.status) {
      JinBoDoorStatus.OPEN -> Color.DARK_GRAY
      JinBoDoorStatus.ERROR -> Color.RED
      else -> Color.LIGHT_GRAY
    }
    g2d.drawRect(x, y, width, height)

    g2d.font = Font("Arial", Font.PLAIN, 10)
    val statusText = doorLabel(g2d, door, width)
    val fittedLabel = "${door.code} $statusText"
    val labelWidth = g2d.fontMetrics.stringWidth(fittedLabel)
    val labelHeight = g2d.fontMetrics.height
    val labelX = x + (width - labelWidth) / 2
    val labelY = y + (height + labelHeight) / 2 - 3
    g2d.color = when {
      door.status == JinBoDoorStatus.ERROR -> Color.WHITE
      door.status == JinBoDoorStatus.CLOSE -> Color.BLACK
      else -> Color.DARK_GRAY
    }
    g2d.drawString(fittedLabel, labelX, labelY)
  }

  /** 计算包含所有门的梯厢宽度，避免门数增加后每扇门被挤压。 */
  fun preferredCarWidth(): Int = PREFERRED_DOOR_WIDTH * doors.size + DOOR_GAP * (doors.size + 1)

  private fun statusLabel(status: JinBoDoorStatus): String = when (status) {
    JinBoDoorStatus.OPEN -> "Opened"
    JinBoDoorStatus.OPENING -> "Opening"
    JinBoDoorStatus.CLOSE -> "Closed"
    JinBoDoorStatus.CLOSING -> "Closing"
    JinBoDoorStatus.ERROR -> "Fault"
  }

  private fun doorLabel(g2d: Graphics2D, door: JinBoDoor, maxWidth: Int): String {
    val labels = when (door.status) {
      JinBoDoorStatus.OPEN -> listOf("Opened", "Open", "O")
      JinBoDoorStatus.OPENING -> listOf("Opening", "Open..", "O..")
      JinBoDoorStatus.CLOSE -> listOf("Closed", "Shut", "C")
      JinBoDoorStatus.CLOSING -> listOf("Closing", "Close..", "C..")
      JinBoDoorStatus.ERROR -> listOf("Fault", "Error", "E")
    }
    return labels.first { g2d.fontMetrics.stringWidth("${door.code} $it") <= maxWidth }
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic == "liftState") {
      try {
        val map: Map<String, Any> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
        // JsonHelper 按字段类型反序列化，height 为 Double，同时兼容手工状态/回放数据中的整数高度。
        val elevatorHeight = (map["h"] as Number).toDouble()
        // 调试输出
        // println("[Cage DEBUG] Received elevator height: $elevatorHeight meters, minHeight: $minHeight, maxHeight: $maxHeight")
        updateHeight(elevatorHeight)
        status = JinBoDoorStatus.valueOf(map["status"] as String)
        val rawDoors = map["doors"] as? List<*>
        doors = if (rawDoors.isNullOrEmpty()) {
          listOf(JinBoDoor(code = 0, status = status))
        } else {
          parseDoors(rawDoors).takeIf { it.isNotEmpty() } ?: listOf(JinBoDoor(code = 0, status = status))
        }
        carWidth = maxOf(lw, preferredCarWidth())
        repaint()
      } catch (e: Exception) {
        println("[Cage ERROR] Failed to process liftState event: ${e.message}")
        e.printStackTrace()
      }
    }
  }

  private fun parseDoors(rawDoors: List<*>): List<JinBoDoor> = rawDoors.mapNotNull { rawDoor ->
    val door = rawDoor as? Map<*, *> ?: return@mapNotNull null
    val code = (door["code"] as? Number)?.toInt() ?: return@mapNotNull null
    val doorStatus = (door["status"] as? String)?.let { runCatching { JinBoDoorStatus.valueOf(it) }.getOrNull() }
      ?: return@mapNotNull null
    JinBoDoor(
      code = code,
      status = doorStatus,
    )
  }

  override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
    val oldHeight = this.height
    super.setBounds(x, y, width, height)
    // 如果面板大小改变，重新计算电梯位置
    if (height != oldHeight && height > 0) {
      // 需要重新计算当前电梯高度的像素位置
      // 但当前电梯高度未知，需要从事件或状态获取
      // 暂时不处理，等待下一次事件更新
    }
  }

  private companion object {
    const val DOOR_GAP = 3
    const val PREFERRED_DOOR_WIDTH = 64
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

    // 清除背景
    g2d.color = background
    g2d.fillRect(0, 0, width, height)

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

    // 清除背景
    g2d.color = background
    g2d.fillRect(0, 0, width, height)

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

        // TCP 栏按钮：记 tcp-btn-goto，与真实 TCP socket 请求(tcp-goto)区分
        JinBoServer.request(config.id, JinBoReq(floorIdx, JinBoReqSource.Tcp), log = false)
        JinBoServer.logEvent(config.id, LiftEvent("tcp-btn-goto", "destFloor=$floorIdx"))

        repaint()
      }
    })

    JinBoEventBus.register(config.id, this)
  }

  override fun paintComponent(g: Graphics) {
    val g2d = g as Graphics2D

    // 清除背景
    g2d.color = background
    g2d.fillRect(0, 0, width, height)

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
