package org.dao.device.lift.jinbo

import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/** 日志和回放帧的统一入口：同时负责录制、窗口显示和回放日志渲染。 */
object JinBoLogService {
  fun logEvent(liftId: String, event: LiftEvent) {
    val line = "${event.topic}:${event.msg}"
    JinBoRecorder.bufferLog(liftId, line)
    // 无头运行（测试、服务端模式）不创建 Swing 窗口，日志仍由录制器保存。
    if (GraphicsEnvironment.isHeadless()) return
    val frame = JinBoServer.lifts[liftId]?.frame ?: return
    // 日志可能由请求线程触发；异步追加到 EDT，避免等待 Swing 或电梯锁。
    SwingUtilities.invokeLater { frame.logEvent(event) }
  }

}
