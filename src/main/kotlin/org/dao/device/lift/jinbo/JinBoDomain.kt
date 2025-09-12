package org.dao.device.lift.jinbo

import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList


data class JinBoConfig(
  val id: String,
  val floors: LinkedHashMap<Int, String>, // 楼层 index + label
  val costPerFloor: Int, // 上升/下降一层楼需要的时间，单位：ms
  val costDoorOp: Int, // 开门关门需要的时间，单位：ms
  val doorHoldDuration: Int, // 开门维持的时间，单位：ms
)

/**
 * 电梯运行记录
 */
data class JinBoRuntime(val config: JinBoConfig) {
  @Volatile
  var curFloor: Int = 0 // 当前楼层。电梯从 1 层开始，0 是一个合适的默认值
  
  @Volatile
  var liftStatus: JinBoLiftStatus = JinBoLiftStatus.Idle // 电梯状态
  
  @Volatile
  var doorStatus: JinBoDoorStatus = JinBoDoorStatus.ERROR // 当前楼层门状态
  
  val reqs: MutableList<JinBoReq> = CopyOnWriteArrayList() // 当前电梯的请求
  
  
  
  // 用于计算电梯当前位置、门的状态
  @Volatile
  var targetFloor: Int? = null // 目标楼层
  
  @Volatile
  var moveStartOn: Date? = null // 电梯开始移动的时间
  
  @Volatile
  var doorOpStartOn: Date? = null // 门操作：开始时间

  @Volatile
  var doorOpDoneOn: Date? = null // 门操作：完成时间
  
  fun notLifting(): Boolean {
    return liftStatus != JinBoLiftStatus.Up && liftStatus != JinBoLiftStatus.Down && moveStartOn == null
  }
}

/**
 * 电梯状态
 */
enum class JinBoLiftStatus {
  Up, // 上行
  Down, // 下行
  Door, // 开关门
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
  ERROR
}

/**
 * 电梯去某楼层的请求
 */
data class JinBoReq(
  val destFloor: Int, // 目标楼层
  val source: JinBoReqSource = JinBoReqSource.Tcp, // 请求来源
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
data class JinBoResp(
  val code: String = "0",
  val msg: String = "noError",
)

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
    fun of(runtime: JinBoRuntime): JinBoStatusResp {
      return JinBoStatusResp(
        runtime.curFloor.toString(),
        runtime.doorStatus
      )
    }
  }
}