package org.dao.device.lift.jinbo

import javax.swing.SwingUtilities

/**
 * 回放控制：加载记录、播放/暂停、跳转，以及回放模式的启停。
 *
 * 回放产生的按钮状态和日志分别交给 JinBoBtnService、JinBoLogService，
 * LiftReplayPanel 只负责控件和显示时间轴。
 */
class JinBoReplayService(private val liftId: String) {
  private var replayer: JinBoReplayer? = null

  val isReplaying: Boolean get() = replayer != null
  val isPlaying: Boolean get() = replayer?.isPlaying == true
  val currentTs: Long get() = replayer?.currentTs ?: 0
  val allFrames: List<JinBoFrame> get() = replayer?.allFrames.orEmpty()

  var onFrameChanged: ((frames: List<JinBoFrame>, index: Int, clockTs: Long) -> Unit)? = null

  /** 只返回包含当前电梯帧的回放文件，避免选中后得到空画面。 */
  fun listFiles(): List<String> = JinBoReplayer.listReplayFiles(liftId)

  /** 开回放并加载首帧；没有记录时返回 false，由界面负责提示。 */
  fun open(fileName: String? = null): Boolean {
    if (isReplaying) return true
    val newReplayer = JinBoReplayer(liftId).also { replayer = it }
    newReplayer.onFrameChanged = { frames, index, clockTs ->
      showFrame(frames, index)
      onFrameChanged?.invoke(frames, index, clockTs)
    }
    val loaded = if (fileName == null) newReplayer.load() else newReplayer.load(fileName)
    if (loaded == 0) {
      close()
      return false
    }
    newReplayer.seekIndex(0)
    return true
  }

  fun close() {
    replayer?.dispose()
    replayer = null
  }

  fun loadFile(fileName: String): Int {
    val current = replayer ?: return 0
    current.pause()
    return current.load(fileName)
  }

  fun seekFirstFrame() {
    replayer?.seekIndex(0)
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

  /**
   * 回放帧通过既有事件总线推给界面：按钮状态和日志分别由对应服务生成。
   * 在 EDT 上触发，保证 Swing 组件安全重绘。
   */
  private fun showFrame(frames: List<JinBoFrame>, index: Int) {
    val frame = frames.getOrNull(index) ?: return
    val run = Runnable {
      JinBoStateService.fireState(liftId, frame)
      JinBoBtnService.fireRequests(
        liftId,
        outside = frame.reqs.filter { it.source == JinBoReqSource.OutDoor },
        inside = frame.reqs.filter { it.source == JinBoReqSource.InDoor },
        tcp = frame.reqs.filter { it.source == JinBoReqSource.Tcp },
      )
      JinBoLogService.renderReplayLogs(liftId, frames, index)
    }
    if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeLater(run)
  }
}
