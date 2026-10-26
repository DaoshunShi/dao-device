package org.dao.device.lift.jinbo

import org.dao.device.lift.jinbo.fe.JinBoEventBus
import javax.swing.SwingUtilities

/**
 * 回放控制：加载记录、播放/暂停、跳转，以及回放模式的启停。
 *
 * 回放产生的按钮状态和日志分别交给 JinBoBtnService、JinBoLogService，
 * LiftReplayPanel 只负责控件和显示时间轴。
 */
class JinBoReplayService(private val liftId: String) {
  private var replayer: JinBoReplayer? = null
  private var generation = 0

  val isReplaying: Boolean get() = replayer != null
  val isPlaying: Boolean get() = replayer?.isPlaying == true
  val currentTs: Long get() = replayer?.currentTs ?: 0
  internal val frameCount: Int get() = replayer?.frameCount ?: 0
  internal val allFrames: List<JinBoFrame> get() = replayer?.allFrames.orEmpty()

  var onFrameChanged: ((frames: List<JinBoFrame>, index: Int, clockTs: Long) -> Unit)? = null

  /** 只返回包含当前电梯帧的回放文件，避免选中后得到空画面。 */
  fun listFiles(): List<String> = JinBoReplayer.listReplayFiles(liftId)

  /** 先完整加载新文件，成功后才替换当前回放并切换到回放数据源。 */
  fun openFile(fileName: String): Int {
    val next = JinBoReplayer(liftId)
    val loaded = next.load(fileName)
    if (loaded == 0) {
      next.dispose()
      return 0
    }

    val previous = replayer
    replayer?.pause()
    generation++
    replayer = next
    JinBoEventBus.setReplayMode(liftId, true)
    val currentGeneration = generation
    next.onFrameChanged = { frames, index, clockTs -> showFrame(frames, index, clockTs, currentGeneration) }
    next.seekIndex(0)
    previous?.dispose()
    return loaded
  }

  fun close() {
    generation++
    replayer?.dispose()
    replayer = null
  }

  fun playOrPause(): Boolean {
    val current = replayer ?: return false
    if (current.isPlaying) current.pause() else current.play()
    return current.isPlaying
  }

  fun jumpBy(offsetMs: Long) {
    val current = replayer ?: return
    current.seek(current.currentTs + offsetMs)
    if (current.isPlaying) current.play()
  }

  fun seek(ts: Long) {
    replayer?.seek(ts)
  }

  fun rewind() {
    replayer?.rewind()
  }

  fun fastForward() {
    replayer?.fastForward()
  }

  fun dispose() {
    close()
  }

  /** 回放器可能在 Swing 定时器线程运行，界面更新统一投递到 EDT。 */
  private fun showFrame(frames: List<JinBoFrame>, index: Int, clockTs: Long, expectedGeneration: Int) {
    val frame = frames.getOrNull(index) ?: return
    val render = Runnable {
      if (generation != expectedGeneration) return@Runnable
      JinBoStateService.fireState(liftId, frame)
      JinBoBtnService.fireFrame(liftId, frame)
      JinBoLogService.renderReplayLogs(liftId, frames, index)
      onFrameChanged?.invoke(frames, index, clockTs)
    }
    if (SwingUtilities.isEventDispatchThread()) render.run() else SwingUtilities.invokeLater(render)
  }
}
