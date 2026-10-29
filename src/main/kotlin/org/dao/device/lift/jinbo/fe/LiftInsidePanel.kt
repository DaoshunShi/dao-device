package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoCommandService
import org.dao.device.lift.jinbo.JinBoReqSource
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JPanel

/** 梯内面板，提供楼层选择以及开门、关门操作。 */
class LiftInsidePanel(config: JinBoConfig) : JPanel() {

  init {
    // 获取启用的楼层并按索引降序排列（顶层在上）
    val enabledFloors = config.enabledFloors()

    // 动态设置网格布局：启用的楼层数 + 2（开门/关门按钮） x 1
    layout = GridLayout(enabledFloors.size + 2, 1, 0, 5)

    for (floor in enabledFloors) {
      add(
        JPanel(BorderLayout()).apply {
          add(LabelCircle(config, floor.index, floor.label, 20, 20, JinBoReqSource.InDoor, "inside"))
        },
      )
    }
    add(
      JButton("开门").apply {
        addActionListener {
          JinBoCommandService.pressOpen(config.id)
        }
      },
    )
    add(
      JButton("关门").apply {
        addActionListener {
          JinBoCommandService.pressClose(config.id)
        }
      },
    )
  }
}
