package org.dao.device.lift.jinbo

import com.fasterxml.jackson.annotation.JsonIgnore
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

data class JinBoConfig(
  val id: String, // 电梯 ID
  val floors: List<JinBoFloor>, // 楼层配置。注意：严格按照 index 顺序
  val liftSpeed: Double, // 上下/下降速度，点位 m/s
  val costDoorOp: Int, // 开门关门需要的时间，单位：ms
  val doorHoldDuration: Int, // 开门维持的时间，单位：ms
)

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
  // @Volatile
  // var disabled = false // 电梯停用

  @Volatile
  var curFloor: Int = 0 // 当前楼层。电梯从 1 层开始，0 是一个合适的默认值

  @Volatile
  var liftStatus: JinBoLiftStatus = JinBoLiftStatus.Idle // 电梯状态

  @Volatile
  var lifting: Boolean = false // 上升、下降中

  @Volatile
  var doorStatus: JinBoDoorStatus = JinBoDoorStatus.CLOSE // 当前楼层门状态

  val reqs: MutableList<JinBoReq> = CopyOnWriteArrayList() // 当前电梯的请求

  // 用于计算电梯当前位置、门的状态
  @Volatile
  var h: Double = 0.0 // 电梯当前位置，单位：米

  @Volatile
  var targetFloor: Int? = null // 目标楼层

  @Volatile
  var doorOpStartOn: Date? = null // 门操作：开始时间

  @Volatile
  var doorOpDoneOn: Date? = null // 门操作：完成时间

  /**
   * 电梯允许上下行
   */
  fun canLifting(): Boolean = doorStatus != JinBoDoorStatus.CLOSE

  /**
   * 正好在楼层里，可以开关门
   */
  fun infloor(): Boolean = config.floors.any { it.height == h }
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
  val currentFloor: String, // 当前楼层
  val doorStatus: JinBoDoorStatus, // 当前门的状态
  val code: String = "0",
  val msg: String = "noError",
) {
  companion object {
    fun of(runtime: JinBoRuntime): JinBoStatusResp = JinBoStatusResp(
      runtime.curFloor.toString(),
      runtime.doorStatus,
    )
  }
}