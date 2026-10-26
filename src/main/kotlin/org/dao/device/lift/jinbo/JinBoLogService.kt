package org.dao.device.lift.jinbo

import org.dao.device.lift.jinbo.event.LiftEvent
import org.dao.device.lift.jinbo.fe.LiftFrame
import java.awt.GraphicsEnvironment

/** 日志和回放帧的统一入口：同时负责录制、窗口显示和回放日志渲染。 */
object JinBoLogService {
  fun logEvent(liftId: String, event: LiftEvent) {
    val line = "${event.topic}:${event.msg}"
    JinBoRecorder.bufferLog(liftId, line)
    // 无头运行（测试、服务端模式）不创建 Swing 窗口，日志仍由录制器保存。
    if (GraphicsEnvironment.isHeadless()) return
    JinBoServer.lifts[liftId]?.frame?.logEvent(event)
  }

  fun captureFrame(lift: JinBoRuntime) {
    JinBoRecorder.capture(lift)
  }

  fun renderReplayLogs(liftId: String, frames: List<JinBoFrame>, index: Int) {
    if (GraphicsEnvironment.isHeadless()) return
    JinBoServer.lifts[liftId]?.frame?.renderReplayLogs(frames, index)
  }

  fun clearDisplay(liftId: String) {
    if (GraphicsEnvironment.isHeadless()) return
    JinBoServer.lifts[liftId]?.frame?.clearLog()
  }
}
