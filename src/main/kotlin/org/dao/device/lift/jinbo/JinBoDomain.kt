package org.dao.device.lift.jinbo

import com.fasterxml.jackson.annotation.JsonIgnore
import org.dao.device.lift.jinbo.fe.LiftFrame
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

data class JinBoConfig(
  val id: String, // 电梯 ID
  @Volatile
  var port: Int, // 启动端口
  val floors: List<JinBoFloor>, // 楼层配置。注意：严格按照 index 顺序
  @Volatile
  var liftSpeed: Double, // 上下/下降速度，点位 m/s
  @Volatile
  var openCost: Int, // 开门需要的时间，单位：ms
  @Volatile
  var closeCost: Int, // 关门需要的时间，单位：ms
  @Volatile
  var closeDelay: Int, // 开门维持的时间，单位：ms
  @Volatile
  var logTcp: Boolean = false, // 是否记录 TCP 日志
  @Volatile
  var connLimit: Int = 1, // TCP client 连接数限制
  val disabled: Boolean = false, // 整部电梯停用：为 true 时不启动该电梯
  @Volatile
  var doorCount: Int = 1, // 电梯门的数量，门编号从 0 开始
  val initialFloor: Int? = null, // 启动时所在楼层，按 floors 中的 index 匹配；未配置时使用首个可用楼层
) {
  init {
    require(doorCount >= 1) { "电梯门数量必须大于等于 1" }
    require(floors.isNotEmpty()) { "电梯楼层配置不能为空" }
    if (initialFloor != null) {
      val floor = floors.firstOrNull { it.index == initialFloor }
        ?: throw IllegalArgumentException("启动楼层 $initialFloor 不存在")
      require(!floor.disabled) { "启动楼层 $initialFloor 已停用" }
    }
  }
}

data class JinBoFloor(
  val index: Int, // 楼层索引
  val label: String, // 标签，比如 G、L1、F2 等
  val height: Double, // 楼层高度
  val disabled: Boolean = false, // 此层电梯停用
)

/**
 * 电梯运行记录
 */
data class JinBoRuntime(
  @JsonIgnore
  val config: JinBoConfig,
) {
  @Volatile
  var curFloor: Int = 0 // 当前楼层。电梯从 1 层开始，0 是一个合适的默认值

  @Volatile
  var liftStatus: JinBoLiftStatus = JinBoLiftStatus.Idle // 电梯状态

  @Volatile
  var travelDirection: JinBoLiftStatus = JinBoLiftStatus.Idle // 本轮运行方向，停靠开门期间保持

  @Volatile
  var lifting: Boolean = false // 上升、下降中

  /**
   * 每个门独立的运行状态和计时。门状态与门号一一对应，门编号从 0 开始。
   */
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
  var emergencyStopped: Boolean = false // 是否处于急停状态

  @Volatile
  var emergencyStartedAt: Date? = null // 急停开始时间

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

  /**
   * 电梯允许上下行
   */
  fun canLifting(): Boolean = doors.all { it.status == JinBoDoorStatus.CLOSE }

  /** 电梯是否正在驶向其他楼层；移动中的门操作应被拒绝。 */
  fun isMoving(): Boolean = lifting

  /** 门是否正在开启、保持开门或关门；到站停留期间不选择下一段行程。 */
  fun holdAtCurrentFloor(): Boolean = doors.any { door ->
    door.status == JinBoDoorStatus.OPEN ||
      door.status == JinBoDoorStatus.OPENING ||
      door.status == JinBoDoorStatus.CLOSING
  }

  /**
   * 正好在楼层里，可以开关门
   */
  fun canOpenDoor(): Boolean = config.floors.any { abs(it.height - h) < FLOOR_EPS }

  fun updateCfg(newCfg: JinBoConfig) {
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
    if (doors.size > newDoorCount) {
      while (doors.size > newDoorCount) {
        doors.removeAt(doors.lastIndex)
      }
    } else {
      while (doors.size < newDoorCount) {
        doors.add(JinBoDoor(doors.size))
      }
    }
  }

  companion object {
    // 楼层高度浮点比较容差（米）：h 由步进累加得到，不能用 == 直接比较
    private const val FLOOR_EPS = 1e-6
  }
}

/**
 * 电梯状态
 */
enum class JinBoLiftStatus {
  Up, // 上行
  Down, // 下行
  Idle, // 空闲
}

/**
 * 电梯门状态
 */
enum class JinBoDoorStatus {
  OPEN,
  OPENING,
  CLOSE,
  CLOSING,
  ERROR,
}

/**
 * 电梯去某楼层的请求
 */
data class JinBoReq(
  val destFloor: Int, // 目标楼层
  val source: JinBoReqSource = JinBoReqSource.Tcp, // 请求来源
  val type: JinBoLiftStatus? = null, // 上行/下行 [仅用于外部请求]
  @get:JsonIgnore
  val doorCodes: List<Int> = emptyList(), // 本次停靠需要打开的门号；空表示默认 0 号门
)

/**
 * 单个电梯门的运行状态。
 *
 * - [openOn] 记录到站或收到同层呼梯的时刻，自动关门延迟从这里开始计算；
 * - [opStartOn] 记录本次开门或关门操作的开始时间。
 */
data class JinBoDoor(
  val code: Int,
  var status: JinBoDoorStatus = JinBoDoorStatus.CLOSE,
  var openOn: Date? = null,
  var opStartOn: Date? = null,
) {
  /** 门开到到位；自动关门计时从到站或收到同层呼梯时开始，不在这里重置。 */
  fun markOpen(now: Date) {
    status = JinBoDoorStatus.OPEN
    openOn = openOn ?: now
    opStartOn = null
  }

  fun setStatus(newStatus: JinBoDoorStatus, now: Date) {
    status = newStatus
    openOn = if (newStatus == JinBoDoorStatus.CLOSE || newStatus == JinBoDoorStatus.ERROR) null else openOn
    opStartOn = if (newStatus == JinBoDoorStatus.OPENING || newStatus == JinBoDoorStatus.CLOSING) now else null
  }
}

/**
 * 回放帧：某一仿真 tick 的电梯可视状态快照 + 该帧窗口内的事件日志
 *
 * reqs 可还原 outside/inside/tcp 三个事件（按 source 过滤）；
 * h/doorStatus/doors/emergencyStopped 可还原 liftState 事件。
 */
data class JinBoFrame(
  val liftId: String,
  val ts: Long, // 采集时刻，System.currentTimeMillis()
  val curFloor: Int,
  val h: Double,
  val liftStatus: JinBoLiftStatus,
  val doorStatus: JinBoDoorStatus,
  val emergencyStopped: Boolean,
  val reqs: List<JinBoReq>, // 请求快照
  val logs: List<String>, // 本帧窗口内产生的事件日志行
  val doors: List<JinBoDoor> = emptyList(), // 多门快照；旧回放帧缺失该字段时为空
)

/**
 * 电梯请求来源
 */
enum class JinBoReqSource {
  OutDoor, // 门外
  InDoor, // 门内
  Tcp, // 外部系统
}

/**
 * 控制电梯到指定楼层/关门的响应
 */
data class JinBoResp(val code: String = "0", val msg: String = "noError")

/**
 * 电梯状态的响应
 */
data class JinBoStatusResp(
  val retCode: Int = 0,
  val retMsg: String = "success",
  val currentFloor: String, // 当前楼层
  val doorStatus: JinBoDoorStatus, // 兼容单门调用方：聚合门状态
  val emergencyStopped: Boolean, // 是否处于急停状态
  val doors: List<JinBoDoorResp> = emptyList(), // 每个门的独立状态
  val destFloor: Int? = null, // 当前目标楼层
  val disabled: Boolean = false, // 是否停用（运行中的电梯始终为 false）
  val autoMode: Boolean = true,
  val fault: Boolean = false,
  val people: Int = 0,
  val code: String = "0", // 兼容旧响应字段
  val msg: String = "noError", // 兼容旧响应字段
) {
  companion object {
    fun of(runtime: JinBoRuntime): JinBoStatusResp {
      val doorStatus = runtime.doors.firstOrNull { it.status != JinBoDoorStatus.CLOSE }?.status
        ?: JinBoDoorStatus.CLOSE
      return JinBoStatusResp(
        currentFloor = runtime.curFloor.toString(),
        doorStatus = doorStatus,
        emergencyStopped = runtime.emergencyStopped,
        doors = runtime.doors.map { door ->
          val openStatus = when (door.status) {
            JinBoDoorStatus.OPEN -> 1
            JinBoDoorStatus.CLOSE -> 2
            JinBoDoorStatus.OPENING -> 3
            JinBoDoorStatus.CLOSING -> 4
            JinBoDoorStatus.ERROR -> 0
          }
          JinBoDoorResp(door.code, openStatus)
        },
        destFloor = runtime.targetFloor,
        disabled = runtime.config.disabled,
        fault = runtime.emergencyStopped,
      )
    }
  }
}

/** 外部查询协议中的门状态：0=未启用，1=已开门，2=已关门，3=开门中，4=关门中。 */
data class JinBoDoorResp(val code: Int, val openStatus: Int)
