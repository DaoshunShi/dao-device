package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import java.io.File
import javax.swing.Timer

/**
 * 回放器（纯逻辑层）
 *
 * 职责：
 * - 读取 logs/replay*.log 的 JSONL 帧，按 liftId 过滤、按 ts 升序，构建帧序列。
 * - 维护当前帧指针；2fps 播放，支持快进/快退、seek。
 * - 通过 onFrameChanged 报告当前帧，回放事件由 JinBoReplayService 转换。
 *
 * 说明：录制侧做了去重（相邻相同状态不落帧），故帧序列是稀疏的。
 * seek(ts) 采用“取 ts <= 目标时刻的最后一帧”，静止空档也能正确停住。
 *
 * 本类只维护回放时间，不直接操作 UI 或日志。
 */
class JinBoReplayer(private val liftId: String) {

  companion object {
    private const val LOG_DIR = "logs"
    private const val BASE_STEP_MS = 500L // 每帧推进的虚拟时间

    /**
     * 列出包含指定电梯帧的回放文件，按最后修改时间升序；新文件排在最后。
     * 回放文件可能由多台电梯共用，不能仅凭文件名判断是否有当前电梯的数据。
     */
    fun listReplayFiles(liftId: String): List<String> {
      return replayFiles(File(LOG_DIR))
        .filter { file -> file.containsFrameFor(liftId) }
        .sortedBy { it.lastModified() }
        .map { it.name }
    }

    private fun replayFiles(dir: File): List<File> = dir.listFiles { file ->
      file.isFile && file.name.isReplayFileName()
    }?.toList().orEmpty()

    private fun File.containsFrameFor(liftId: String): Boolean =
      useLines { lines ->
        lines.any { line ->
          if (line.isBlank()) return@any false
          try {
            JsonHelper.mapper.readTree(line)["liftId"].asText() == liftId
          } catch (_: Exception) {
            false
          }
        }
      }

    private fun String.isReplayFileName(): Boolean =
      this == "replay.log" || (startsWith("replay-") && endsWith(".log"))
  }

  private var frames: List<JinBoFrame> = emptyList()
  private var clockTs: Long = 0 // 虚拟时钟（毫秒），回放按真实时间推进
  private var speed = 1 // 步进倍率：正数快进，负数快退，1 = 正常（每 tick ±0.5 秒）
  private var timer: Timer? = null

  /**
   * 每次渲染时回调，供 UI 更新时间轴/时间标签/日志分色。
   * @param frames 全部帧
   * @param index  当前虚拟时钟对应的帧下标（ts <= clockTs 的最后一帧）
   * @param clockTs 当前虚拟时钟时间，时间轴游标/时间标签用它
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

  /** 从磁盘加载属于 liftId 的帧；只负责装载，不向界面渲染。 */
  fun load(fileName: String? = null): Int {
    val logDir = File(LOG_DIR)
    val files = if (fileName != null) {
      listOf(File(logDir, fileName)).filter { it.exists() }
    } else {
      replayFiles(logDir).sortedBy { it.name }
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
    speed = 1
    return frames.size
  }

  /** 开始/继续播放：每 500ms 将虚拟时钟前进/后退 500ms × |speed|。 */
  fun play() {
    if (frames.isEmpty()) return
    stopTimer()
    // 到达末尾/开头时再按播放，从边界继续
    timer = Timer(JinBoConstants.REPLAY_INTERVAL_MS.toInt()) { tick() }.apply { start() }
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

  /** 每 500ms 推进虚拟时钟（快进/快退改变倍率和方向）。 */
  private fun tick() {
    val stepMs = BASE_STEP_MS * kotlin.math.abs(speed) * if (speed >= 0) 1 else -1
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

  /** 通知调用方当前帧及虚拟时钟。 */
  private fun renderCurrent() {
    val index = indexAt(clockTs)
    if (frames.getOrNull(index) == null) return
    onFrameChanged?.invoke(frames, index, clockTs)
  }

  private fun stopTimer() {
    timer?.stop()
    timer = null
  }
}
