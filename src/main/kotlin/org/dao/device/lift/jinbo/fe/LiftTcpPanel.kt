package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoBtnService
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.enabledFloors
import org.dao.device.lift.jinbo.JinBoReqSource
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/** TCP 总控面板，提供 TCP 楼层请求入口与关门操作。 */
class LiftTcpPanel(config: JinBoConfig) : JPanel() {

  init {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.enabledFloors()

    layout = GridLayout(enabledFloors.size + 2, 1, 0, 5)

    for (floor in enabledFloors) {
      add(
        JPanel(BorderLayout()).apply {
          add(LabelCircle(config, floor.index, floor.label, 20, 20, JinBoReqSource.Tcp, "tcp"))
        },
      )
    }
    add(
      JButton("关门").apply {
        addActionListener {
          JinBoBtnService.pressClose(config.id, fromTcp = true)
        }
      },
    )
  }
}
