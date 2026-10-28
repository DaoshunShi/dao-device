package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.apache.commons.lang3.time.DateFormatUtils
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoBtnService
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoRequestEntry
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BorderLayout
import java.util.Date
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/** 请求队列面板；按帧展示 JinBoRuntime.reqs，不从按钮状态反推请求。 */
class LiftReqsPanel(private val config: JinBoConfig) : JPanel(BorderLayout()), GuiEventListener {

  private val requestArea = JTextArea().apply {
    isEditable = false
    lineWrap = false
  }

  init {
    add(JScrollPane(requestArea), BorderLayout.CENTER)
    JinBoEventBus.register(config.id, this)
    SwingUtilities.invokeLater { render(JinBoBtnService.requests(config.id)) }
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic != "reqs") return
    val entries = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef<List<JinBoRequestEntry>>())
    // 已在 EDT 上时不能再排队，否则较旧的 reqs 快照可能晚于新点击覆盖界面。
    if (SwingUtilities.isEventDispatchThread()) render(entries) else SwingUtilities.invokeLater { render(entries) }
  }

  private fun render(entries: List<JinBoRequestEntry>) {
    requestArea.text = if (entries.isEmpty()) {
      "空"
    } else {
      entries.joinToString("\n") { entry ->
        val req = entry.req
        val floorLabel = config.floors.firstOrNull { it.index == req.destFloor }?.label ?: req.destFloor.toString()
        val queuedAt = if (entry.queuedAt > 0) {
          DateFormatUtils.format(Date(entry.queuedAt), "HH:mm:ss.SSS")
        } else {
          "--:--:--.---"
        }
        "$queuedAt | $floorLabel | " +
          "${req.source.name} | ${req.type?.name ?: "Idle"} | " +
          "门=${req.doorCodes.ifEmpty { listOf(0) }.joinToString(",")}"
      }
    }
  }
}
