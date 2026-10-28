package org.dao.device.lift.jinbo.fe

import com.fasterxml.jackson.module.kotlin.jacksonTypeRef
import org.dao.device.common.GuiEventListener
import org.dao.device.common.JsonHelper
import org.dao.device.lift.jinbo.JinBoCommandService
import org.dao.device.lift.jinbo.JinBoConfig
import org.dao.device.lift.jinbo.JinBoServer
import org.dao.device.lift.jinbo.event.LiftEvent
import java.awt.*
import javax.swing.*

class JinBoConfigPanel(val config: JinBoConfig) :
  JPanel(),
  GuiEventListener {
  // 配置字段输入框
  private val fieldInputs: MutableMap<ConfigField, JTextField> = linkedMapOf()
  private var logTcpInput: JCheckBox? = null
  private val emergencyStopButton = JButton()

  // 状态：true为编辑状态，false为查看状态
  private var isEditMode = false

  // 定时器，用于查看状态下定时更新配置
  private val configUpdateTimer: Timer

  // 创建一个更浅的灰色背景
  private val readOnlyBackground = Color(240, 240, 240) // 更浅的灰色

  init {
    // 使用可换行布局，窗口变窄时自动换行并同步增加面板高度
    layout = WrapFlowLayout(FlowLayout.LEFT, 10, 5)

    emergencyStopButton.margin = Insets(4, 10, 4, 10)
    emergencyStopButton.addActionListener {
      JinBoCommandService.toggleEmergencyStop(config.id)
      updateEmergencyStopButton(JinBoServer.lifts[config.id]?.emc == true)
    }
    add(emergencyStopButton)

    add(
      JButton("重置").apply {
        addActionListener { JinBoCommandService.resetRequests(config.id) }
      },
    )

    val titleLabel = JLabel("电梯配置")
    add(titleLabel)

    // 添加字段
    for (configField in CONFIG_FIELDS) {
      val fieldLabel = JLabel("${configField.label}:")
      add(fieldLabel)

      val field = JTextField(configField.value(config).toString(), 6)
      field.minimumSize = Dimension(60, field.preferredSize.height)
      field.maximumSize = Dimension(80, field.preferredSize.height)
      field.setEditable(false) // 初始为只读状态
      fieldInputs[configField] = field
      add(field)
    }

    logTcpInput = JCheckBox("打印 TCP 报文")
    logTcpInput?.isSelected = config.logTcp
    add(logTcpInput)

    // 管理楼层按钮（始终显示）
    val manageFloorsButton = JButton("管理楼层")
    manageFloorsButton.addActionListener { showFloorConfigDialog() }
    add(manageFloorsButton)

    // 编辑按钮（查看状态显示）
    val editButton = JButton("编辑")
    editButton.addActionListener { enterEditMode() }
    add(editButton)

    // 保存按钮（编辑状态显示）
    val saveButton = JButton("保存")
    saveButton.addActionListener { saveConfig() }
    saveButton.isVisible = false // 初始隐藏
    add(saveButton)

    // 取消按钮（编辑状态显示）
    val cancelButton = JButton("取消")
    cancelButton.addActionListener { exitEditMode() }
    cancelButton.isVisible = false // 初始隐藏
    add(cancelButton)

    // 初始化定时器
    configUpdateTimer = Timer(3000) { updateConfig() }
    configUpdateTimer.isRepeats = true
    configUpdateTimer.start()

    // 状态切换逻辑
    addPropertyChangeListener("editMode") { evt ->
      if (java.lang.Boolean.TRUE == evt.newValue) {
        // 进入编辑状态
        editButton.isVisible = false
        saveButton.isVisible = true
        cancelButton.isVisible = true

        // 启用所有输入框
        fieldInputs.values.forEach { field ->
          field.isEditable = true
          field.background = Color.WHITE
        }
      } else {
        // 退出编辑状态
        editButton.isVisible = true
        saveButton.isVisible = false
        cancelButton.isVisible = false

        // 禁用所有输入框
        fieldInputs.values.forEach { field ->
          field.isEditable = false
          field.background = readOnlyBackground
        }
      }
    }

    // 初始设置查看状态的背景色
    fieldInputs.values.forEach { field -> field.background = readOnlyBackground }

    JinBoEventBus.register(config.id, this)
    updateEmergencyStopButton(JinBoServer.lifts[config.id]?.emc == true)
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
    val editedConfig = try {
      CONFIG_FIELDS.fold(config) { current, configField ->
        configField.apply(current, fieldInputs.getValue(configField).text)
      }.copy(logTcp = logTcpInput?.isSelected ?: false)
    } catch (e: NumberFormatException) {
      JOptionPane.showMessageDialog(this, "配置项必须是有效数字", "配置错误", JOptionPane.ERROR_MESSAGE)
      return
    } catch (e: IllegalArgumentException) {
      JOptionPane.showMessageDialog(this, e.message ?: "电梯配置无效", "配置错误", JOptionPane.ERROR_MESSAGE)
      return
    }
    if (editedConfig.doorCount < 1) {
      JOptionPane.showMessageDialog(this, "电梯门数量必须是大于等于 1 的整数", "配置错误", JOptionPane.ERROR_MESSAGE)
      return
    }

    try {
      JinBoCommandService.updateConfig(editedConfig)
    } catch (e: IllegalArgumentException) {
      JOptionPane.showMessageDialog(this, e.message ?: "电梯配置无效", "配置错误", JOptionPane.ERROR_MESSAGE)
      return
    }

    updateConfig()

    // 退出编辑模式
    exitEditMode()
  }

  // 更新配置（定时调用）
  private fun updateConfig() {
    if (!isEditMode) {
      fieldInputs.forEach { (configField, field) -> field.text = configField.value(config).toString() }
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
    private val CONFIG_FIELDS = listOf(
      ConfigField("启动端口", { it.port }, { config, text -> config.copy(port = text.toInt()) }),
      ConfigField("开门耗时", { it.openCost }, { config, text -> config.copy(openCost = text.toInt()) }),
      ConfigField("关门耗时", { it.closeCost }, { config, text -> config.copy(closeCost = text.toInt()) }),
      ConfigField("自动关门延迟时间", { it.closeDelay }, { config, text -> config.copy(closeDelay = text.toInt()) }),
      ConfigField("上升速度", { it.liftSpeed }, { config, text -> config.copy(liftSpeed = text.toDouble()) }),
      ConfigField("电梯门数量", { it.doorCount }, { config, text -> config.copy(doorCount = text.toInt()) }),
    )
  }

  private fun showFloorConfigDialog() {
    JinBoFloorConfigDialog(this, config).isVisible = true
  }

  private data class ConfigField(
    val label: String,
    val value: (JinBoConfig) -> Number,
    val apply: (JinBoConfig, String) -> JinBoConfig,
  )
}
