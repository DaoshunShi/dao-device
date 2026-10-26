package org.dao.device.lift.jinbo.fe

import org.dao.device.lift.jinbo.JinBoConfig
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/** 以只读表格展示电梯楼层配置。 */
class JinBoFloorConfigDialog(
  owner: Component,
  config: JinBoConfig,
) : JDialog() {
  init {
    title = "楼层配置 - 电梯 ${config.id}"
    layout = BorderLayout()
    setSize(600, 400)
    setLocationRelativeTo(owner)

    val table = JTable(
      DefaultTableModel(arrayOf("楼层索引", "标签", "高度 (米)", "禁用"), 0).apply {
        config.floors.sortedBy { it.index }.forEach { floor ->
          addRow(
            arrayOf(
              floor.index.toString(),
              floor.label,
              floor.height.toString(),
              if (floor.disabled) "是" else "否",
            ),
          )
        }
      },
    ).apply { fillsViewportHeight = true }
    add(JScrollPane(table), BorderLayout.CENTER)

    val closeButton = JButton("关闭").apply { addActionListener { dispose() } }
    add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(closeButton) }, BorderLayout.SOUTH)
    isModal = true
  }
}
