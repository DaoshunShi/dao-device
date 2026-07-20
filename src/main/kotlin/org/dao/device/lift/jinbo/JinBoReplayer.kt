package org.dao.device.lift.jinbo

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.fe.JinBoEventBus
import org.dao.device.lift.jinbo.fe.LiftEvent
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
  }

  private var frames: List<JinBoFrame> = emptyList()
  private var index = 0
  private var speed = 1 // 播放速度倍率：正数快进，负数快退，1 = 正常
  private var timer: Timer? = null

  /** 当前帧变化时回调（携带帧序列、当前索引），供 UI 更新时间轴/日志分色 */
  var onFrameChanged: ((frames: List<JinBoFrame>, index: Int) -> Unit)? = null

  val frameCount: Int get() = frames.size
  val currentIndex: Int get() = index
  val allFrames: List<JinBoFrame> get() = frames
  val isPlaying: Boolean get() = timer?.isRunning == true

  /**
   * 从磁盘加载并解析属于 liftId 的帧，重置到第一帧。
   * @return 加载到的帧数
   */
  fun load(): Int {
    val dir = File(LOG_DIR)
    val files = dir.listFiles { _, name ->
      name == "replay.log" || (name.startsWith("replay-") && name.endsWith(".log"))
    }?.sortedBy { it.name } ?: emptyList()

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
    index = 0
    return frames.size
  }

  /** 开始/继续播放 */
  fun play() {
    if (frames.isEmpty()) return
    stopTimer()
    val period = (BASE_STEP_MS / kotlin.math.abs(speed)).coerceAtLeast(1)
    timer = Timer(period) { tick() }.apply { start() }
  }

  /** 暂停播放（保留当前指针） */
  fun pause() {
    stopTimer()
  }

  /** 快进：提升正向速度倍率（1→2→4，上限 8） */
  fun fastForward() {
    speed = when {
      speed <= 0 -> 1
      speed < 8 -> speed * 2
      else -> 8
    }
    if (isPlaying) play() // 以新周期重启
  }

  /** 快退：设为反向播放（-1→-2→-4，上限 -8） */
  fun rewind() {
    speed = when {
      speed >= 0 -> -1
      speed > -8 -> speed * 2
      else -> -8
    }
    if (isPlaying) play()
  }

  /** 跳转到最接近且不晚于 ts 的帧 */
  fun seek(ts: Long) {
    if (frames.isEmpty()) return
    val i = frames.indexOfLast { it.ts <= ts }
    index = if (i < 0) 0 else i
    renderCurrent()
  }

  /** 跳转到指定帧索引 */
  fun seekIndex(i: Int) {
    if (frames.isEmpty()) return
    index = i.coerceIn(0, frames.size - 1)
    renderCurrent()
  }

  /** 停止并释放定时器（关闭回放时调用） */
  fun dispose() {
    stopTimer()
    frames = emptyList()
    index = 0
  }

  private fun tick() {
    val next = index + if (speed >= 0) 1 else -1
    if (next < 0) {
      index = 0
      renderCurrent()
      pause()
      return
    }
    if (next > frames.size - 1) {
      index = frames.size - 1
      renderCurrent()
      pause() // 播放到末尾自动停
      return
    }
    index = next
    renderCurrent()
  }

  /** 渲染当前帧：重放 4 个事件 + 通知 UI */
  private fun renderCurrent() {
    val frame = frames.getOrNull(index) ?: return
    fireFrame(frame)
    onFrameChanged?.invoke(frames, index)
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
