package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoServer
import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.function.Consumer
import javax.swing.*
import javax.swing.table.DefaultTableModel

class JinBoConfigPanel(val config: JinBoConfig) :
  JPanel(),
  GuiEventListener {
  // 配置字段输入框
  private val fieldInputs: MutableMap<String?, JTextField?> = HashMap<String?, JTextField?>()
  private var logTcpInput: JCheckBox? = null
  private val emergencyStopButton = JButton()

  // 状态：true为编辑状态，false为查看状态
  private var isEditMode = false

  // 定时器，用于查看状态下定时更新配置
  private val configUpdateTimer: Timer?

  // 创建一个更浅的灰色背景
  private val readOnlyBackground = Color(240, 240, 240) // 更浅的灰色

  init {
    // 使用可换行布局，窗口变窄时自动换行并同步增加面板高度
    layout = WrapFlowLayout(FlowLayout.LEFT, 10, 5)

    emergencyStopButton.margin = Insets(4, 10, 4, 10)
    emergencyStopButton.addActionListener {
      JinBoServer.toggleEmergencyStop(config.id)
      updateEmergencyStopButton(JinBoServer.lifts[config.id]?.emergencyStopped == true)
    }
    add(emergencyStopButton)

    val titleLabel = JLabel("电梯配置")
    add(titleLabel)

    // 添加字段
    for (fieldName in FIELD_NAMES) {
      val fieldLabel = JLabel("$fieldName:")
      add(fieldLabel)

      val field = JTextField(6)
      field.minimumSize = Dimension(60, field.preferredSize.height)
      field.maximumSize = Dimension(80, field.preferredSize.height)
      field.setEditable(false) // 初始为只读状态
      fieldInputs[fieldName] = field
      add(field)
    }

    logTcpInput = JCheckBox("打印 TCP 报文")
    logTcpInput?.isSelected = config.logTcp
    add(logTcpInput)

    // 管理楼层按钮（始终显示）
    val manageFloorsButton = JButton("管理楼层")
    manageFloorsButton.addActionListener(ActionListener { e: ActionEvent? -> showFloorConfigDialog() })
    add(manageFloorsButton)

    // 编辑按钮（查看状态显示）
    val editButton = JButton("编辑")
    editButton.addActionListener(ActionListener { e: ActionEvent? -> enterEditMode() })
    add(editButton)

    // 保存按钮（编辑状态显示）
    val saveButton = JButton("保存")
    saveButton.addActionListener(ActionListener { e: ActionEvent? -> saveConfig() })
    saveButton.isVisible = false // 初始隐藏
    add(saveButton)

    // 取消按钮（编辑状态显示）
    val cancelButton = JButton("取消")
    cancelButton.addActionListener(ActionListener { e: ActionEvent? -> exitEditMode() })
    cancelButton.isVisible = false // 初始隐藏
    add(cancelButton)

    // 初始化定时器
    configUpdateTimer = Timer(3000, ActionListener { e: ActionEvent? -> updateConfig() })
    configUpdateTimer.isRepeats = true
    configUpdateTimer.start()

    // 保存按钮引用
    val finalSaveButton = saveButton
    val finalCancelButton = cancelButton
    val finalEditButton = editButton

    // 状态切换逻辑
    addPropertyChangeListener(
      "editMode",
      PropertyChangeListener { evt: PropertyChangeEvent? ->
        if (java.lang.Boolean.TRUE == evt!!.getNewValue()) {
          // 进入编辑状态
          finalEditButton.isVisible = false
          finalSaveButton.isVisible = true
          finalCancelButton.isVisible = true

          // 启用所有输入框
          fieldInputs.values.forEach(
            Consumer { field: JTextField? ->
              field!!.isEditable = true
              field.background = Color.WHITE
            },
          )
        } else {
          // 退出编辑状态
          finalEditButton.isVisible = true
          finalSaveButton.isVisible = false
          finalCancelButton.isVisible = false

          // 禁用所有输入框
          fieldInputs.values.forEach(
            Consumer { field: JTextField? ->
              field!!.isEditable = false
              field.background = readOnlyBackground // 使用更浅的灰色
            },
          )
        }
      },
    )

    // 初始设置查看状态的背景色
    fieldInputs.values.forEach(
      Consumer { field: JTextField? ->
        field!!.background = readOnlyBackground // 使用更浅的灰色
      },
    )

    JinBoEventBus.register(config.id, this)
    updateEmergencyStopButton(JinBoServer.lifts[config.id]?.emergencyStopped == true)
  }

  // 进入编辑模式
  private fun enterEditMode() {
    isEditMode = true
    firePropertyChange("editMode", false, true)
  }

  // 退出编辑模式
  private fun exitEditMode() {
    isEditMode = false
    firePropertyChange("editMode", true, false)
  }

  // 保存配置
  private fun saveConfig() {
    // 打印所有输入框的值
    println("当前配置:")
    fieldInputs.forEach { (name: String?, field: JTextField?) ->
      println("$name: ${field!!.text}")
    }

    // 这里可以添加保存到配置文件的逻辑
    // 例如: saveConfigToFile();
    JinBoServer.updateConfig(
      config.copy(
        port = fieldInputs["启动端口"]!!.text.toInt(),
        openCost = fieldInputs["开门耗时"]!!.text.toInt(),
        closeCost = fieldInputs["关门耗时"]!!.text.toInt(),
        closeDelay = fieldInputs["自动关门延迟时间"]!!.text.toInt(),
        liftSpeed = fieldInputs["上升速度"]!!.text.toDouble(),
        logTcp = logTcpInput?.isSelected ?: false,
      ),
    )

    updateConfig()

    // 退出编辑模式
    exitEditMode()
  }

  // 更新配置（定时调用）
  private fun updateConfig() {
    if (!isEditMode) {
      // 这里可以添加从配置文件或服务读取最新配置的逻辑
      // 例如: Map<String, String> latestConfig = loadLatestConfig();

      // 模拟更新配置
      fieldInputs["启动端口"]?.text = config.port.toString()
      fieldInputs["开门耗时"]?.text = config.openCost.toString()
      fieldInputs["关门耗时"]?.text = config.closeCost.toString()
      fieldInputs["自动关门延迟时间"]?.text = config.closeDelay.toString()
      fieldInputs["上升速度"]?.text = config.liftSpeed.toString()
      // logTcpInput?.isSelected = config.logTcp
    }
  }

  private fun updateEmergencyStopButton(emergencyStopped: Boolean) {
    emergencyStopButton.text = if (emergencyStopped) "解除急停" else "急停"
    emergencyStopButton.background =
      if (emergencyStopped) Color(255, 204, 204) else (UIManager.getColor("Button.background") ?: Color(238, 238, 238))
    emergencyStopButton.foreground =
      if (emergencyStopped) Color(160, 0, 0) else (UIManager.getColor("Button.foreground") ?: Color.BLACK)
    emergencyStopButton.isOpaque = true
    emergencyStopButton.border = BorderFactory.createCompoundBorder(
      BorderFactory.createLineBorder(if (emergencyStopped) Color(180, 0, 0) else Color.GRAY),
      BorderFactory.createEmptyBorder(2, 4, 2, 4),
    )
  }

  override fun onEvent(event: LiftEvent) {
    if (event.topic != "liftState") {
      return
    }

    try {
      val map: Map<String, Any> = JsonHelper.mapper.readValue(event.msg, jacksonTypeRef())
      updateEmergencyStopButton(map["emergencyStopped"] as? Boolean == true)
    } catch (_: Exception) {
    }
  }

  companion object {
    // 配置字段名称
    private val FIELD_NAMES = arrayOf<String?>(
      "启动端口",
      "开门耗时",
      "关门耗时",
      "自动关门延迟时间",
      "上升速度",
    )
  }

  private fun showFloorConfigDialog() {
    val dialog = JDialog()
    dialog.title = "楼层配置 - 电梯 ${config.id}"
    dialog.layout = BorderLayout()
    dialog.setSize(600, 400)
    dialog.setLocationRelativeTo(this) // 居中显示

    // 创建表格模型
    val columnNames = arrayOf("楼层索引", "标签", "高度 (米)", "禁用")
    val tableModel = DefaultTableModel(columnNames, 0)

    // 填充数据
    for (floor in config.floors.sortedBy { it.index }) {
      tableModel.addRow(
        arrayOf(
          floor.index.toString(),
          floor.label,
          floor.height.toString(),
          if (floor.disabled) "是" else "否",
        ),
      )
    }

    val table = JTable(tableModel)
    table.fillsViewportHeight = true

    val scrollPane = JScrollPane(table)
    dialog.add(scrollPane, BorderLayout.CENTER)

    // 添加底部按钮面板
    val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT))

    val closeButton = JButton("关闭")
    closeButton.addActionListener { dialog.dispose() }
    buttonPanel.add(closeButton)

    dialog.add(buttonPanel, BorderLayout.SOUTH)

    dialog.isModal = true
    dialog.isVisible = true
  }
}