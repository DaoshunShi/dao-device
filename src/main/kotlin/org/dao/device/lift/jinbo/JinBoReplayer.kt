package org.dao.device.lift.jinbo

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import java.io.File
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * 回放器（纯逻辑层）
 *
 * 职责：
 * - 读取 logs/replay*.log 的 JSONL 帧，按 liftId 过滤、按 ts 升序，构建帧序列。
 * - 维护当前帧指针；1fps 单步播放，支持快进/快退（改 Timer 周期）、seek。
 * - 通过 JinBoEventBus 重放 outside/liftState/inside/tcp 四个事件，
 *   事件的 topic 与 JSON 结构与 JinBoFetcher.fetch 完全一致，绘制组件零改动即可渲染历史状态。
 *
 * 说明：录制侧做了去重（相邻相同状态不落帧），故帧序列是稀疏的。
 * seek(ts) 采用“取 ts <= 目标时刻的最后一帧”，静止空档也能正确停住。
 *
 * 本类不负责任何 UI 组件的创建；UI（开关、时间轴、日志分色）由 LiftFrame 侧驱动，
 * 通过 onFrameChanged 回调获知当前帧变化。
 */
class JinBoReplayer(private val liftId: String) {

  companion object {
    private const val LOG_DIR = "logs"
    private const val BASE_STEP_MS = 1000 // 1fps 基准步长，与仿真 tick 一致

    /** 列出 logs 目录下所有回放文件名（replay.log 及轮转文件），按名称排序 */
    fun listReplayFiles(): List<String> {
      val dir = File(LOG_DIR)
      return dir.listFiles { _, name ->
        name == "replay.log" || (name.startsWith("replay-") && name.endsWith(".log"))
      }?.map { it.name }?.sorted() ?: emptyList()
    }
  }

  private var frames: List<JinBoFrame> = emptyList()
  private var clockTs: Long = 0 // 虚拟时钟（毫秒），回放按真实时间逐秒推进
  private var speed = 1 // 步进倍率：正数快进，负数快退，1 = 正常（每 tick ±1 秒）
  private var timer: Timer? = null

  /**
   * 每次渲染时回调，供 UI 更新时间轴/时间标签/日志分色。
   * @param frames 全部帧
   * @param index  当前虚拟时钟对应的帧下标（ts <= clockTs 的最后一帧）
   * @param clockTs 当前虚拟时钟时间（逐秒），时间轴游标/时间标签用它
   */
  var onFrameChanged: ((frames: List<JinBoFrame>, index: Int, clockTs: Long) -> Unit)? = null

  val frameCount: Int get() = frames.size
  val allFrames: List<JinBoFrame> get() = frames
  val isPlaying: Boolean get() = timer?.isRunning == true

  private val firstTs get() = frames.firstOrNull()?.ts ?: 0
  private val lastTs get() = frames.lastOrNull()?.ts ?: 0

  /** 当前虚拟时钟；未加载帧时为 0 */
  val currentTs: Long get() = clockTs

  /** 虚拟时钟对应的帧下标：ts <= clockTs 的最后一帧（无则 0） */
  private fun indexAt(ts: Long): Int {
    val i = frames.indexOfLast { it.ts <= ts }
    return if (i < 0) 0 else i
  }

  /**
   * 从磁盘加载并解析属于 liftId 的帧，重置到第一帧。
   * @param fileName 指定回放文件名；为 null 时加载全部回放文件。
   * @return 加载到的帧数
   */
  fun load(fileName: String? = null): Int {
    val dir = File(LOG_DIR)
    val files = if (fileName != null) {
      listOf(File(dir, fileName)).filter { it.exists() }
    } else {
      dir.listFiles { _, name ->
        name == "replay.log" || (name.startsWith("replay-") && name.endsWith(".log"))
      }?.sortedBy { it.name } ?: emptyList()
    }

    val parsed = mutableListOf<JinBoFrame>()
    for (file in files) {
      file.forEachLine { line ->
        if (line.isBlank()) return@forEachLine
        try {
          val frame = JsonHelper.mapper.readValue(line, JinBoFrame::class.java)
          if (frame.liftId == liftId) parsed.add(frame)
        } catch (_: Exception) {
          // 跳过损坏/半写入的行
        }
      }
    }

    frames = parsed.sortedBy { it.ts }
    clockTs = firstTs
    return frames.size
  }

  /** 开始/继续播放：虚拟时钟每 tick 前进/后退 |speed| 秒 */
  fun play() {
    if (frames.isEmpty()) return
    stopTimer()
    // 到达末尾/开头时再按播放，从边界继续
    timer = Timer(BASE_STEP_MS) { tick() }.apply { start() }
  }

  /** 暂停播放（保留虚拟时钟位置） */
  fun pause() {
    stopTimer()
  }

  /** 快进：提升正向步进倍率（1→2→4→8） */
  fun fastForward() {
    speed = when {
      speed <= 0 -> 1
      speed < 8 -> speed * 2
      else -> 8
    }
  }

  /** 快退：转为反向步进（-1→-2→-4→-8） */
  fun rewind() {
    speed = when {
      speed >= 0 -> -1
      speed > -8 -> speed * 2
      else -> -8
    }
  }

  /** 跳转虚拟时钟到 ts（限定在录制范围内），渲染对应帧 */
  fun seek(ts: Long) {
    if (frames.isEmpty()) return
    clockTs = ts.coerceIn(firstTs, lastTs)
    renderCurrent()
  }

  /** 跳转到指定帧索引对应的时刻（供打开回放时定位首帧） */
  fun seekIndex(i: Int) {
    if (frames.isEmpty()) return
    clockTs = frames[i.coerceIn(0, frames.size - 1)].ts
    renderCurrent()
  }

  /** 停止并释放定时器（关闭回放时调用） */
  fun dispose() {
    stopTimer()
    frames = emptyList()
    clockTs = 0
  }

  /** 每 tick 按真实时间逐秒推进虚拟时钟（快进/快退改变步进秒数与方向） */
  private fun tick() {
    val stepMs = BASE_STEP_MS.toLong() * kotlin.math.abs(speed) * if (speed >= 0) 1 else -1
    val next = clockTs + stepMs
    when {
      next <= firstTs -> {
        clockTs = firstTs
        renderCurrent()
        pause()
      }
      next >= lastTs -> {
        clockTs = lastTs
        renderCurrent()
        pause() // 播放到末尾自动停
      }
      else -> {
        clockTs = next
        renderCurrent()
      }
    }
  }

  /** 渲染当前虚拟时钟对应的帧：重放 4 个事件 + 通知 UI（携带 clockTs 供逐秒显示） */
  private fun renderCurrent() {
    val index = indexAt(clockTs)
    val frame = frames.getOrNull(index) ?: return
    fireFrame(frame)
    onFrameChanged?.invoke(frames, index, clockTs)
  }

  /**
   * 通过 event-bus 重放一帧，topic 与结构完全对齐 JinBoFetcher.fetch。
   * 在 EDT 上触发，保证 Swing 组件安全重绘。
   */
  private fun fireFrame(frame: JinBoFrame) {
    val run = Runnable {
      JinBoEventBus.fire(
        liftId,
        LiftEvent("outside", JsonHelper.mapper.writeValueAsString(
          frame.reqs.filter { it.source == JinBoReqSource.OutDoor },
        )),
      )
      JinBoEventBus.fire(
        liftId,
        LiftEvent("liftState", JsonHelper.mapper.writeValueAsString(
          mapOf(
            "h" to frame.h,
            "status" to frame.doorStatus,
            "doors" to frame.doors,
            "emergencyStopped" to frame.emergencyStopped,
          ),
        )),
      )
      JinBoEventBus.fire(
        liftId,
        LiftEvent("inside", JsonHelper.mapper.writeValueAsString(
          frame.reqs.filter { it.source == JinBoReqSource.InDoor },
        )),
      )
      JinBoEventBus.fire(
        liftId,
        LiftEvent("tcp", JsonHelper.mapper.writeValueAsString(
          frame.reqs.filter { it.source == JinBoReqSource.Tcp },
        )),
      )
    }
    if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeLater(run)
  }

  private fun stopTimer() {
    timer?.stop()
    timer = null
  }
}
