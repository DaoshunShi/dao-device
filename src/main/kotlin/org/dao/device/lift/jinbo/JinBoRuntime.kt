package org.dao.device.lift.jinbo

import com.fasterxml.jackson.annotation.JsonIgnore
import org.dao.device.lift.jinbo.fe.LiftFrame
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/** 一部电梯的可变运行状态。 */
data class JinBoRuntime(
  @JsonIgnore
  val config: JinBoConfig,
) {
  @Volatile
  var curFloor: Int = 0 // 当前楼层。电梯从 1 层开始，0 是一个合适的默认值

  @Volatile
  var liftStatus: JinBoLiftStatus = JinBoLiftStatus.Idle // 电梯状态

  @Volatile
  var direction: JinBoLiftStatus = JinBoLiftStatus.Idle // 本轮运行方向，停靠开门期间保持

  /** 每个门独立的运行状态和计时。门状态与门号一一对应，门编号从 0 开始。 */
  val doors: MutableList<JinBoDoor> = CopyOnWriteArrayList(
    (0 until config.doorCount).map { JinBoDoor(it) },
  )

  val reqs: MutableList<JinBoReq> = CopyOnWriteArrayList() // 当前电梯的请求

  // 用于计算电梯当前位置、门的状态
  @Volatile
  var h: Double = 0.0 // 电梯当前位置，单位：米

  @Volatile
  var targetFloor: Int? = null // 目标楼层

  @Volatile
  var lastMoveOn: Long? = null // 上一次计算移动位移的时间，单位：ms

  @Volatile
  var emc: Boolean = false // 是否处于急停状态

  @Volatile
  var emcStartedOn: Date? = null // 急停开始时间

  // 电梯 TCP 服务器
  @JsonIgnore
  private var tcpServer: JinBoTcpServer? = null

  // 电梯 UI
  @get:JsonIgnore
  val frame by lazy { LiftFrame(config) }

  fun init() {
    setInitialFloor()
    tcpServer = JinBoTcpServer(config.id, "0.0.0.0", config.port, config.logTcp, config.connLimit)
    tcpServer?.start()

    frame.isVisible = true
  }

  /** 按 JSON 的 initialFloor 设置启动楼层；未配置时使用首个可用楼层。 */
  fun setInitialFloor() {
    val initialFloor = config.initialFloor
      ?.let { index -> config.floors.first { it.index == index } }
      ?: config.floors.firstOrNull { !it.disabled }
      ?: throw IllegalStateException("电梯 ${config.id} 没有可用楼层")
    curFloor = initialFloor.index
    h = initialFloor.height
  }

  fun dispose() {
    tcpServer?.shutdown()
    tcpServer = null

    frame.isVisible = false
  }

  /** 电梯是否正在驶向其他楼层；移动中的门操作应被拒绝。 */
  fun isMoving(): Boolean = liftStatus != JinBoLiftStatus.Idle

  /** 是否存在已经打开的门。 */
  fun hasOpenDoor(): Boolean = doors.any { it.status == JinBoDoorStatus.OPEN }

  /** 是否无需处理当前 tick。 */
  fun isSimulationIdle(): Boolean =
    reqs.isEmpty() && !isMoving() && targetFloor == null && allDoorsClosed() && !emc

  /** 是否所有门都已关闭。 */
  fun allDoorsClosed(): Boolean = doors.all { it.status == JinBoDoorStatus.CLOSE }

  /** 门是否正在开启、保持开门或关门；到站停留期间不选择下一段行程。 */
  fun holdAtCurrentFloor(): Boolean = hasOpenDoor() || doors.any { door ->
    door.status == JinBoDoorStatus.OPENING || door.status == JinBoDoorStatus.CLOSING
  }

  fun updateConfig(newCfg: JinBoConfig) {
    require(newCfg.doorCount >= 1) { "电梯门数量必须大于等于 1" }

    config.port = newCfg.port
    config.liftSpeed = newCfg.liftSpeed
    config.openCost = newCfg.openCost
    config.closeCost = newCfg.closeCost
    config.closeDelay = newCfg.closeDelay
    config.logTcp = newCfg.logTcp
    config.connLimit = newCfg.connLimit

    // 连接数上限热更新到运行中的 TCP 服务器（不需重启）
    tcpServer?.connLimit = newCfg.connLimit
    tcpServer?.updatePort(newCfg.port, newCfg.logTcp)

    updateDoors(newCfg.doorCount)
  }

  /** 将门列表调整到指定数量；减少门时同步移除对应门的计时。 */
  fun updateDoors(newDoorCount: Int) {
    require(newDoorCount >= 1) { "电梯门数量必须大于等于 1" }
    if (config.doorCount == newDoorCount && doors.size == newDoorCount) return

    config.doorCount = newDoorCount
    // 已开的门保持当前状态；新增门从关闭态开始，删除门时一并移除其计时
    repeat((doors.size - newDoorCount).coerceAtLeast(0)) {
      doors.removeAt(doors.lastIndex)
    }
    repeat((newDoorCount - doors.size).coerceAtLeast(0)) {
      doors.add(JinBoDoor(doors.size))
    }
  }
}
