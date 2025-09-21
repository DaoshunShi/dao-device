package org.dao.device.lift.jinbo.gui

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.beans.PropertyChangeEvent
import java.beans.PropertyChangeListener
import java.util.function.Consumer
import javax.swing.*

class JinBoConfigPanel : JPanel() {
  // 配置字段输入框
  private val fieldInputs: MutableMap<String?, JTextField?> = HashMap<String?, JTextField?>()

  // 状态：true为编辑状态，false为查看状态
  private var isEditMode = false

  // 定时器，用于查看状态下定时更新配置
  private val configUpdateTimer: Timer?

  // 创建一个更浅的灰色背景
  private val readOnlyBackground = Color(240, 240, 240) // 更浅的灰色

  init {
    // 设置 BoxLayout 水平布局
    layout = BoxLayout(this, BoxLayout.X_AXIS)

    // 创建左侧固定标签面板
    val titleLabel = JLabel("电梯配置")
    val titlePanel = JPanel(BorderLayout())
    titlePanel.add(titleLabel, BorderLayout.CENTER)
    titlePanel.preferredSize = Dimension(100, 0) // 设置固定宽度
    add(titlePanel)

    // 添加水平弹性空间
    add(Box.createHorizontalGlue())

    // 创建中间配置字段区域，使用水平 BoxLayout
    val fieldsPanel = JPanel()
    fieldsPanel.layout = BoxLayout(fieldsPanel, BoxLayout.X_AXIS)
    fieldsPanel.border = BorderFactory.createEmptyBorder(5, 5, 5, 5)

    // 添加字段
    for (fieldName in FIELD_NAMES) {
      val fieldLabel = JLabel("$fieldName:")
      fieldsPanel.add(fieldLabel)

      // 添加水平间距
      fieldsPanel.add(Box.createHorizontalStrut(5))

      val field = JTextField(15)
      field.setEditable(false) // 初始为只读状态
      fieldInputs[fieldName] = field
      fieldsPanel.add(field)

      // 添加水平间距
      fieldsPanel.add(Box.createHorizontalStrut(10))
    }

    add(fieldsPanel)

    // 添加水平弹性空间
    add(Box.createHorizontalGlue())

    // 创建右侧按钮区域
    val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0))

    // 编辑按钮（查看状态显示）
    val editButton = JButton("编辑")
    editButton.addActionListener(ActionListener { e: ActionEvent? -> enterEditMode() })
    buttonPanel.add(editButton)

    // 保存按钮（编辑状态显示）
    val saveButton = JButton("保存")
    saveButton.addActionListener(ActionListener { e: ActionEvent? -> saveConfig() })
    saveButton.isVisible = false // 初始隐藏
    buttonPanel.add(saveButton)

    // 取消按钮（编辑状态显示）
    val cancelButton = JButton("取消")
    cancelButton.addActionListener(ActionListener { e: ActionEvent? -> exitEditMode() })
    cancelButton.isVisible = false // 初始隐藏
    buttonPanel.add(cancelButton)

    add(buttonPanel)

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

    // 退出编辑模式
    exitEditMode()
  }

  // 更新配置（定时调用）
  private fun updateConfig() {
    if (!isEditMode) {
      // 这里可以添加从配置文件或服务读取最新配置的逻辑
      // 例如: Map<String, String> latestConfig = loadLatestConfig();

      // 模拟更新配置
      fieldInputs["启动端口"]?.text = "8080"
      fieldInputs["开门耗时"]?.text = "2"
      fieldInputs["关门耗时"]?.text = "3"
      fieldInputs["自动关门延迟时间"]?.text = "5"
      fieldInputs["上升速度"]?.text = "1.5"
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
}