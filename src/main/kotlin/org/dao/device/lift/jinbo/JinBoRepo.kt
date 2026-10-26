package org.dao.device.lift.jinbo

import org.dao.device.common.JsonHelper
import java.io.File

object JinBoRepo {
  private const val DEFAULT_LIFT_SPEED = 2.0
  private const val CONFIG_DIR = "config"

  fun load(): MutableMap<String, JinBoRuntime> {
    val configDir = File(CONFIG_DIR)

    // 如果config目录不存在或没有配置文件，使用默认配置
    if (!configDir.exists() || !configDir.isDirectory) {
      println("Config directory '$CONFIG_DIR' not found, using default configuration")
      return createDefaultLifts().associateBy { it.config.id }.toMutableMap()
    }

    val configFiles = configDir.listFiles { _, name -> name.endsWith(".json") }
    if (configFiles.isNullOrEmpty()) {
      println("No JSON configuration files found in '$CONFIG_DIR', using default configuration")
      return createDefaultLifts().associateBy { it.config.id }.toMutableMap()
    }

    val lifts = mutableListOf<JinBoRuntime>()
    var anyParsed = false // 是否至少成功解析了一个配置文件（含被禁用的）
    for (configFile in configFiles) {
      try {
        val config = JsonHelper.mapper.readValue(configFile, JinBoConfig::class.java)
        anyParsed = true
        if (config.disabled) {
          println("Skipped disabled elevator from ${configFile.name}: ${config.id}")
          continue
        }
        lifts.add(JinBoRuntime(config))
        println("Loaded elevator configuration from ${configFile.name}: ${config.id} with ${config.floors.size} floors")
      } catch (e: Exception) {
        println("Failed to load configuration from ${configFile.name}: ${e.message}")
      }
    }

    // 仅当所有配置文件都解析失败时才回退到默认配置；
    // 若配置能解析但全部被禁用，则返回空（尊重用户的禁用意图，不启动默认电梯）。
    if (lifts.isEmpty() && !anyParsed) {
      println("All configuration files failed to load, using default configuration")
      return createDefaultLifts().associateBy { it.config.id }.toMutableMap()
    }

    return lifts.associateBy { it.config.id }.toMutableMap()
  }

  private fun createDefaultLifts(): List<JinBoRuntime> {
    // 向后兼容的默认配置（4层电梯）
    return listOf(JinBoRuntime(createDefaultConfig()))
  }

  /**
   * 测试方法：验证配置加载功能
   */
  @JvmStatic
  fun main(args: Array<String>) {
    println("Testing configuration loading...")

    // 然后加载配置
    val lifts = load()

    println("\nLoaded ${lifts.size} elevator(s):")
    for ((id, runtime) in lifts) {
      val config = runtime.config
      println("  Elevator $id:")
      println("    - Port: ${config.port}")
      println("    - Initial floor: ${config.initialFloor ?: "first enabled"}")
      println("    - Floors: ${config.floors.size}")
      println("    - Enabled floors: ${config.floors.count { !it.disabled }}")
      println("    - Floor details:")
      for (floor in config.floors.sortedBy { it.index }) {
        println("      ${floor.index}: ${floor.label} (${floor.height}m) ${if (floor.disabled) "[DISABLED]" else ""}")
      }
    }

    println("\nConfiguration loading test completed.")
  }

  private fun createDefaultConfig() = JinBoConfig(
    "default",
    8080,
    listOf(
      JinBoFloor(1, "L1", 0.0),
      JinBoFloor(2, "L2", 3.0),
      JinBoFloor(3, "L3", 6.0),
      JinBoFloor(4, "L4", 9.0),
    ),
    DEFAULT_LIFT_SPEED,
    1 * 1000,
    1 * 1000,
    3 * 1000,
  )
}
