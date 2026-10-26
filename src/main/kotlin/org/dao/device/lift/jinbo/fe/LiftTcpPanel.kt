package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/** TCP 总控面板，提供 TCP 楼层请求入口与关门操作。 */
class LiftTcpPanel(config: JinBoConfig) : JPanel() {

  init {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.floors
      .filter { !it.disabled }
      .sortedByDescending { it.index }

    // 动态设置网格布局：启用的楼层数 + 2（预留空间） x 1
    layout = GridLayout(enabledFloors.size + 2, 1, 0, 5)

    for (floor in enabledFloors) {
      add(
        JPanel(BorderLayout()).apply {
          add(LabelCircle2(config, floor.index, floor.label, 20, 20))
        },
      )
    }
    add(
      JButton("关门").apply {
        addActionListener {
          JinBoServer.logEvent(config.id, LiftEvent("tcp-btn-close", "TCP栏关门按钮"))
          JinBoServer.close(config.id)
        }
      },
    )
  }
}
