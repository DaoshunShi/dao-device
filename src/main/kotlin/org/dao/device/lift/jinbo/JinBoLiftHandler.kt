package org.dao.device.lift.jinbo

import io.javalin.http.Context
import org.slf4j.LoggerFactory

object JinBoLiftHandler {
  private val logger = LoggerFactory.getLogger(javaClass)

  fun request(ctx: Context) {
    val liftId = ctx.pathParam("liftId")
    val req = ctx.bodyAsClass(JinBoReq::class.java)
    logger.info("list request, lift: $liftId, request: $req")

    val resp = JinBoLiftServer.request(liftId, req)
    ctx.json(resp)
  }

  fun closeDoor(ctx: Context) {
    val liftId = ctx.pathParam("liftId")
    logger.info("lift close, lift: $liftId")

    val resp = JinBoLiftServer.close(liftId)
    ctx.json(resp)
  }

  fun status(ctx: Context) {
    val liftId = ctx.pathParam("liftId")
    logger.info("lift status, lift: $liftId")

    val resp = JinBoLiftServer.status(liftId)
    ctx.json(resp)
  }
}