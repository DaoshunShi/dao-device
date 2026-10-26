package org.dao.device.lift.jinbo.fe

import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.lift.jinbo.JinBoDoorStatus
import org.dao.device.lift.jinbo.JinBoFrame
import org.dao.device.lift.jinbo.JinBoLiftStatus
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 回放时间轴
 *
 * 固定显示当前帧所在“自然整点小时”窗口 [hourStart, hourStart+1h)，不满一小时也铺满整小时。
 * 当前帧跨小时时窗口整块切换。轨道横向占满整行。
 * 轨道上方为秒格状态条：按每秒电梯状态分层标色（当前秒/移动/有请求/无）。
 * 点击或拖动 → 映射到窗口内时间 → 回调 onSeek(ts)。
 */
class ReplayTimeline(
  private val onSeek: (ts: Long) -> Unit,
) : JPanel() {

  companion object {
    private const val HOUR_MS = 3600_000L
    private const val HOUR_SEC = 3600
    private const val TICK_MS = 300_000L // 刻度间隔：5 分钟

    // 秒格状态
    private const val S_NONE = 0 // 无移动无请求
    private const val S_REQ = 1 // 有请求
    private const val S_MOVE = 2 // 电梯移动中

    private val COLOR_NONE = Color(230, 230, 230)
    private val COLOR_REQ = Color(245, 170, 70) // 橙：有请求
    private val COLOR_MOVE = Color(90, 170, 240) // 蓝：移动中
    private val COLOR_CURRENT = Color(220, 60, 60) // 红：当前秒
  }

  private var frames: List<JinBoFrame> = emptyList()
  private var currentTs: Long = 0
  private var windowStart: Long = 0 // 当前可视小时窗起点（整点）
  private var emptyMessage = "无回放数据"

  // 窗口内每秒的状态（S_NONE/S_REQ/S_MOVE），下标 0..3599
  private var secStates = IntArray(HOUR_SEC)

  internal val frameCount: Int get() = frames.size

  init {
    background = Color.WHITE
    // BoxLayout 会沿 Y 轴把子组件拉伸；对齐方式和固定高度避免控制行覆盖状态条。
    alignmentX = JComponent.LEFT_ALIGNMENT
    preferredSize = Dimension(0, 56)
    minimumSize = Dimension(0, 56)
    maximumSize = Dimension(Int.MAX_VALUE, 56)
    val onMouse = { x: Int ->
      if (frames.isNotEmpty()) onSeek(xToTs(x))
    }
    addMouseListener(object : MouseAdapter() {
      override fun mousePressed(e: MouseEvent) = onMouse(e.x)
      override fun mouseClicked(e: MouseEvent) = onMouse(e.x)
    })
    addMouseMotionListener(object : MouseMotionAdapter() {
      override fun mouseDragged(e: MouseEvent) = onMouse(e.x)
    })
  }

  /** 设置帧序列（打开回放/切换文件时调用一次） */
  fun setFrames(frames: List<JinBoFrame>) {
    this.frames = frames
    currentTs = frames.firstOrNull()?.ts ?: 0
    windowStart = hourFloor(currentTs)
    rebuildSecStates()
    repaint()
  }

  /** 设置无帧时要显示的原因，例如当前文件不含此电梯的帧。 */
  fun setEmptyMessage(message: String) {
    emptyMessage = message
  }

  /** 更新游标到指定帧时间；跨小时则切换窗口并重算秒格 */
  fun setCurrent(ts: Long) {
    val newWindow = hourFloor(ts)
    currentTs = ts
    if (newWindow != windowStart) {
      windowStart = newWindow
      rebuildSecStates()
    }
    repaint()
  }

  private fun hourFloor(ts: Long): Long = (ts / HOUR_MS) * HOUR_MS
  private val windowEnd get() = windowStart + HOUR_MS

  /**
   * 重算窗口内每秒状态：帧是稀疏的（去重），某秒状态 = ts <= 该秒的最后一帧。
   */
  private fun rebuildSecStates() {
    val states = IntArray(HOUR_SEC)
    if (frames.isEmpty()) {
      secStates = states
      return
    }
    // 对每一秒，取该秒起点时间上不晚于它的最后一帧
    var fi = 0
    var lastState = S_NONE
    // 最后一帧时间：其后的秒尚无录制数据，保持空白（不沿用最后状态）
    val lastTs = frames.last().ts
    // 找到窗口起点之前的最后一帧作为初值
    while (fi < frames.size && frames[fi].ts < windowStart) {
      lastState = frameState(frames[fi])
      fi++
    }
    for (sec in 0 until HOUR_SEC) {
      val secTs = windowStart + sec * 1000L
      while (fi < frames.size && frames[fi].ts <= secTs + 999) {
        lastState = frameState(frames[fi])
        fi++
      }
      // 超过最后录制帧的秒：无数据，空白
      states[sec] = if (secTs > lastTs) S_NONE else lastState
    }
    secStates = states
  }

  private fun frameState(f: JinBoFrame): Int = when {
    f.liftStatus == JinBoLiftStatus.Up || f.liftStatus == JinBoLiftStatus.Down -> S_MOVE
    // 有请求，或门处于非关闭状态（开门/关门中、开着）→ 活跃（橙）
    f.reqs.isNotEmpty() || f.doorStatus != JinBoDoorStatus.CLOSE -> S_REQ
    else -> S_NONE
  }

  /** 时间 → x 像素（基于当前小时窗，占满整行） */
  private fun tsToX(ts: Long): Int {
    val ratio = ((ts - windowStart).toDouble() / HOUR_MS).coerceIn(0.0, 1.0)
    return (ratio * width).toInt()
  }

  /** x 像素 → 时间（点击/拖动，限定在窗口内） */
  private fun xToTs(x: Int): Long {
    val ratio = (x.toDouble() / width.coerceAtLeast(1)).coerceIn(0.0, 1.0)
    return windowStart + (ratio * HOUR_MS).toLong()
  }

  override fun paintComponent(g: Graphics) {
    super.paintComponent(g)
    val g2 = g as Graphics2D
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

    if (frames.isEmpty()) {
      g2.color = Color.GRAY
      g2.drawString(emptyMessage, 4, height / 2)
      return
    }

    val stripTop = 4
    val stripH = 12
    val trackY = stripTop + stripH + 14
    val timelineTs = currentTs.coerceIn(frames.first().ts, frames.last().ts)

    // 秒格状态条：逐像素列映射到秒，取该秒状态着色
    drawSecStrip(g2, stripTop, stripH)

    // 轨道基线（占满整行）
    g2.color = Color(210, 210, 210)
    g2.stroke = BasicStroke(2f)
    g2.drawLine(0, trackY, width, trackY)

    // 窗口内每 5 分钟刻度 + 标签
    drawTicks(g2, trackY)

    // 游标（贯穿秒格条与轨道）
    val cx = tsToX(timelineTs)
    g2.color = COLOR_CURRENT
    g2.stroke = BasicStroke(2f)
    g2.drawLine(cx, stripTop, cx, trackY + 8)
    g2.fillOval(cx - 4, trackY - 4, 8, 8)

    // 游标时间标签
    g2.color = Color.DARK_GRAY
    val label = DateFormatUtils.format(timelineTs, "HH:mm:ss")
    val lw = g2.fontMetrics.stringWidth(label)
    val lx = (cx - lw / 2).coerceIn(0, (width - lw).coerceAtLeast(0))
    g2.drawString(label, lx, trackY + 20)
  }

  /** 逐像素列绘制秒格状态条；当前秒用红色覆盖 */
  private fun drawSecStrip(g2: Graphics2D, top: Int, h: Int) {
    val w = width.coerceAtLeast(1)
    val curSec = ((currentTs - windowStart) / 1000L).toInt().coerceIn(0, HOUR_SEC - 1)
    for (x in 0 until w) {
      val sec = (x.toDouble() / w * HOUR_SEC).toInt().coerceIn(0, HOUR_SEC - 1)
      val color = when {
        sec == curSec -> COLOR_CURRENT
        secStates[sec] == S_MOVE -> COLOR_MOVE
        secStates[sec] == S_REQ -> COLOR_REQ
        else -> COLOR_NONE
      }
      g2.color = color
      g2.drawLine(x, top, x, top + h)
    }
    // 边框
    g2.color = Color(180, 180, 180)
    g2.stroke = BasicStroke(1f)
    g2.drawRect(0, top, w - 1, h)
  }

  /** 窗口内每 5 分钟画刻度；整点、半点标 HH:mm，其余标 mm */
  private fun drawTicks(g2: Graphics2D, trackY: Int) {
    g2.color = Color(160, 160, 160)
    g2.stroke = BasicStroke(1f)
    var t = windowStart
    while (t <= windowEnd) {
      val x = tsToX(t)
      g2.drawLine(x, trackY - 5, x, trackY + 5)
      val minute = ((t - windowStart) / 60_000L).toInt()
      val text = if (minute == 0 || minute == 60) {
        DateFormatUtils.format(t, "HH:mm")
      } else {
        DateFormatUtils.format(t, "mm")
      }
      val w = g2.fontMetrics.stringWidth(text)
      val tx = (x - w / 2).coerceIn(0, (width - w).coerceAtLeast(0))
      g2.drawString(text, tx, trackY + 18)
      t += TICK_MS
    }
  }
}
