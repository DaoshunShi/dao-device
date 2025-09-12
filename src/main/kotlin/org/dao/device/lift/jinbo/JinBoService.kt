package org.dao.device.lift.jinbo

import org.slf4j.LoggerFactory
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 仿真金博电梯
 *
 * 暂定：只实现单部电梯的控制，不实现电梯组
 * 暂定：电梯移动的最小时间单位是秒
 * 判断电梯是否在某一层：curFloor = 1 && liftStatus !in [Up, Down]
 * 电梯到达下一层的条件：当前时间 - moveStartOn >= costPerFloor
 * TODO 基于电梯高度计算当前楼层
 *
 * 配置：
 * - 电梯 ID
 * - 总楼层，每层的 label (map)
 * - 上升/下降一层楼需要的时间
 * - 开门关门需要的时间
 * - 开门维持的时间
 * 运行状态
 * - 电梯目前的目标楼层
 *   - 外部按的 -> 不显示在电梯内
 *   - 内部按的 + tcp 请求的 -> 显示在电梯内
 * - 电梯到达顶层、底层后，清理所有目标楼层
 *
 */
object MockJinBoService {
  private val logger = LoggerFactory.getLogger(javaClass)
  
  private val executor: ExecutorService = Executors.newSingleThreadExecutor()
  private var worker: Future<*>? = null
  
  private val lifts: MutableMap<String, JinBoRuntime> = ConcurrentHashMap()
  
  
  fun init() {
    // 加载 lifts
    worker = executor.submit { process() }
  }
  
  fun dispose() {
    worker?.cancel(true)
  }
  
  private fun process() {
    while (!Thread.interrupted()) {
      for (lift in lifts.values) {
        processLift(lift)
      }
      Thread.sleep(1000)
    }
  }
  
  /**
   * 处理电梯上行、下行、关门
   *
   * 门开门、关门、故障时，电梯不能上下移动
   */
  private fun processLift(lift: JinBoRuntime) {
    val now = Date()
    // 处理开门关门
    if (lift.doorStatus in listOf(JinBoDoorStatus.OPENING, JinBoDoorStatus.CLOSING)) {
      if (lift.doorOpStartOn == null) {
        lift.doorOpStartOn = now
      } else if (now.time - lift.doorOpStartOn!!.time >= lift.config.costDoorOp) {
        if (lift.doorStatus == JinBoDoorStatus.OPENING) {
          lift.doorStatus = JinBoDoorStatus.OPEN
        } else if (lift.doorStatus == JinBoDoorStatus.CLOSING) {
          lift.doorStatus = JinBoDoorStatus.CLOSE
        }
        lift.doorOpStartOn = null
        lift.doorOpDoneOn = now
      }
    }
    
    if (lift.doorStatus != JinBoDoorStatus.CLOSE) {
      // 处理上行、下行
      if (lift.liftStatus == JinBoLiftStatus.Up) {
        // 上行
        if (lift.moveStartOn == null) {
          lift.moveStartOn = now
        } else {
          val f = (now.time - lift.moveStartOn!!.time) / lift.config.costPerFloor
          if (f >= 1) {
            lift.curFloor++
            lift.moveStartOn = now
          }
          if (lift.curFloor == lift.targetFloor) {
            // 到达目标楼层，标记开门
            lift.liftStatus = JinBoLiftStatus.Door
            lift.targetFloor = null
            lift.moveStartOn = null
            lift.doorStatus = JinBoDoorStatus.OPENING
            lift.doorOpStartOn = now
          }
        }
      } else if (lift.liftStatus == JinBoLiftStatus.Down) {
        // 下行
        if (lift.moveStartOn == null) {
          lift.moveStartOn = now
        } else {
          val f = (now.time - lift.moveStartOn!!.time) / lift.config.costPerFloor
          if (f >= 1) {
            lift.curFloor--
            lift.moveStartOn = now
          }
          if (lift.curFloor == lift.targetFloor) {
            // 到达目标楼层，标记开门
            lift.liftStatus = JinBoLiftStatus.Door
            lift.targetFloor = null
            lift.moveStartOn = null
            lift.doorStatus = JinBoDoorStatus.OPENING
            lift.doorOpStartOn = now
          }
        }
      } else {
        // 处理当前楼层的请求
        
        // 找第一个请求
        val req = lift.reqs.firstOrNull()
        if (req != null) {
        
        }
      }
    }
    
    
  }
  
  /**
   * 请求电梯到指定楼层
   */
  fun request(liftId: String, req: JinBoReq) {
    val lift = mustGetLift(liftId)
    synchronized(lift) {
      lift.reqs.add(req)
    }
  }
  
  /**
   * 请求电梯关门
   */
  fun close(liftId: String): JinBoResp {
    val lift = mustGetLift(liftId)
    synchronized(lift) {
      if (lift.notLifting()) {
        lift.doorOpStartOn = Date()
        lift.liftStatus
        return JinBoResp()
      } else {
        logger.warn("电梯正在运行，不能关门")
        return JinBoResp("400", "$liftId 电梯正在运行中，不能关门")
      }
    }
  }
  
  /**
   * 获取电梯信息
   */
  fun getLift(liftId: String): JinBoStatusResp {
    val lift = mustGetLift(liftId)
    return JinBoStatusResp.of(lift)
  }
  
  private fun mustGetLift(liftId: String): JinBoRuntime {
    return lifts[liftId] ?: throw RuntimeException("$liftId 电梯不存在")
  }
  
}
