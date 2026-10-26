package org.dao.device.lift.jinbo

import java.awt.Color
import java.util.Date

// -----------------------------------------------------------------------------
// 电梯配置
// -----------------------------------------------------------------------------

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

// -----------------------------------------------------------------------------
// 电梯状态、方向与门状态
// -----------------------------------------------------------------------------

/** 电梯运行状态与请求方向。 */
enum class JinBoLiftStatus {
  Up, // 上行
  Down, // 下行
  Idle, // 空闲
  ;

  fun opposite(): JinBoLiftStatus = when (this) {
    Up -> Down
    Down -> Up
    Idle -> Idle
  }
}

/**
 * 电梯门状态。
 *
 * [openStatus] 是外部查询协议值；[displayColor] 是电梯图形中此状态的背景色。
 */
enum class JinBoDoorStatus(
  val openStatus: Int,
  val displayColor: Color,
  val displayPriority: Int,
) {
  ERROR(openStatus = 0, displayColor = Color.DARK_GRAY, displayPriority = 0),
  OPENING(openStatus = 3, displayColor = Color.LIGHT_GRAY, displayPriority = 1),
  CLOSING(openStatus = 4, displayColor = Color.YELLOW, displayPriority = 1),
  OPEN(openStatus = 1, displayColor = Color.GREEN, displayPriority = 2),
  CLOSE(openStatus = 2, displayColor = Color.ORANGE, displayPriority = 3),
}

// -----------------------------------------------------------------------------
// 呼梯请求与单门运行状态
// -----------------------------------------------------------------------------

/** 电梯请求来源。 */
enum class JinBoReqSource {
  OutDoor, // 门外
  InDoor, // 门内
  Tcp, // 外部系统
}

/** 电梯去某楼层的请求。 */
data class JinBoReq(
  val destFloor: Int, // 目标楼层
  val source: JinBoReqSource = JinBoReqSource.Tcp, // 请求来源
  val type: JinBoLiftStatus? = null, // 上行/下行 [仅用于外部请求]
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

  /** 按门自身的状态推进一次；开门、关门和延迟时间取自 [config]。 */
  fun advance(now: Date, config: JinBoDoorConfig) {
    when (status) {
      JinBoDoorStatus.OPENING -> {
        val startedAt = opStartOn ?: now.also { opStartOn = it }
        if (now.time - startedAt.time >= config.openCost) markOpen(now)
      }

      JinBoDoorStatus.OPEN -> {
        val openedAt = openOn ?: now.also { openOn = it }
        if (now.time - openedAt.time >= config.closeDelay) setStatus(JinBoDoorStatus.CLOSING, now)
      }

      JinBoDoorStatus.CLOSING -> {
        val startedAt = opStartOn ?: now.also { opStartOn = it }
        if (now.time - startedAt.time >= config.closeCost) setStatus(JinBoDoorStatus.CLOSE, now)
      }

      JinBoDoorStatus.CLOSE, JinBoDoorStatus.ERROR -> Unit
    }
  }

  /** 开始开门；已在开门中的门不重复启动，已打开的门从 [openedAt] 重新计算自动关门延迟。 */
  fun startOpening(openedAt: Date) {
    openOn = openedAt
    if (status != JinBoDoorStatus.OPENING) setStatus(JinBoDoorStatus.OPENING, openedAt)
  }

  /** 开始关门；已关闭、正在关闭或故障的门保持不变。 */
  fun startClosing(now: Date) {
    when (status) {
      JinBoDoorStatus.OPENING, JinBoDoorStatus.OPEN -> setStatus(JinBoDoorStatus.CLOSING, now)
      JinBoDoorStatus.CLOSE, JinBoDoorStatus.CLOSING, JinBoDoorStatus.ERROR -> Unit
    }
  }

  /** 同层呼梯刷新自动关门延迟；正在关闭的门重新开门。 */
  fun refreshSameFloorTimer(now: Date) {
    when (status) {
      JinBoDoorStatus.OPEN -> openOn = now
      JinBoDoorStatus.CLOSING -> startOpening(now)
      else -> Unit
    }
  }

  /** 急停结束时顺延开门和关门计时。 */
  fun shiftOperationTimers(offsetMs: Long) {
    openOn = openOn?.let { Date(it.time + offsetMs) }
    opStartOn = opStartOn?.let { Date(it.time + offsetMs) }
  }
}

/** 单扇门推进所需的开门、关门和自动关门延迟配置。 */
data class JinBoDoorConfig(
  val openCost: Int,
  val closeCost: Int,
  val closeDelay: Int,
)

// -----------------------------------------------------------------------------
// 回放帧
// -----------------------------------------------------------------------------

/**
 * 回放帧：某一仿真 tick 的电梯可视状态快照 + 该帧窗口内的事件日志。
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

/** 录制去重使用的可视状态快照；不包含采集时间和本帧日志。 */
data class Snapshot(
  val curFloor: Int,
  val h: Double,
  val liftStatus: JinBoLiftStatus,
  val emergencyStopped: Boolean,
  val reqs: List<JinBoReq>,
  val doors: List<JinBoDoor>,
)

// -----------------------------------------------------------------------------
// 外部协议响应
// -----------------------------------------------------------------------------

/** 控制电梯到指定楼层/关门的响应。 */
data class JinBoResp(val code: String = "0", val msg: String = "noError")

/** 电梯状态的响应。 */
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
)

/** 外部查询协议中的门状态：0=未启用，1=已开门，2=已关门，3=开门中，4=关门中。 */
data class JinBoDoorResp(val code: Int, val openStatus: Int)
