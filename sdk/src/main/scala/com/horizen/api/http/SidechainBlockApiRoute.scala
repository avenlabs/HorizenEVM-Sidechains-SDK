package com.horizen.api.http

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import com.fasterxml.jackson.annotation.JsonView
import com.horizen.SidechainNodeViewBase
import com.horizen.api.http.JacksonSupport._
import com.horizen.api.http.SidechainBlockErrorResponse._
import com.horizen.api.http.SidechainBlockRestSchema._
import com.horizen.block.{SidechainBlockBase, SidechainBlockHeaderBase}
import com.horizen.box.ZenBox
import com.horizen.consensus.{intToConsensusEpochNumber, intToConsensusSlotNumber}
import com.horizen.forge.Forger.ReceivableMessages.{GetForgingInfo, StartForging, StopForging, TryForgeNextBlockForEpochAndSlot}
import com.horizen.forge.ForgingInfo
import com.horizen.node.{NodeHistoryBase, NodeMemoryPool, NodeMemoryPoolBase, NodeState, NodeWalletBase}
import com.horizen.serialization.Views
import com.horizen.transaction.Transaction
import com.horizen.utils.BytesUtils
import scorex.core.settings.RESTApiSettings
import scorex.util.ModifierId

import java.util.{Optional => JOptional}
import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class SidechainBlockApiRoute[
  TX <: Transaction,
  H <: SidechainBlockHeaderBase,
  PM <: SidechainBlockBase[TX, H],
  NH <: NodeHistoryBase[TX, H, PM],
  NS <: NodeState,
  NW <: NodeWalletBase,
  NP <: NodeMemoryPoolBase[TX],
  NV <: SidechainNodeViewBase[TX, H, PM, NH, NS, NW, NP]](override val settings: RESTApiSettings, sidechainNodeViewHolderRef: ActorRef, sidechainBlockActorRef: ActorRef, forgerRef: ActorRef)
                                                         (implicit val context: ActorRefFactory, override val ec: ExecutionContext, implicit val c: ClassTag[NV])
  extends SidechainBaseApiRoute[TX, H, PM, NH, NS, NW, NP, NV] {

  override val route: Route = pathPrefix("block") {
    findById ~ findLastIds ~ findIdByHeight ~ getBestBlockInfo ~ getFeePayments ~ startForging ~ stopForging ~ generateBlockForEpochNumberAndSlot ~ getForgingInfo
  }

  /**
   * The sidechain block by its id.
   */
  def findById: Route = (post & path("findById")) {
    entity(as[ReqFindById]) { body =>
      withNodeView { sidechainNodeView =>
        val optionSidechainBlock = sidechainNodeView.getNodeHistory.getBlockById(body.blockId)

        if (optionSidechainBlock.isPresent) {
          val sblock = optionSidechainBlock.get()
          val sblock_serialized = sblock.serializer.toBytes(sblock)
          ApiResponseUtil.toResponse(RespFindById[TX, H, PM](BytesUtils.toHexString(sblock_serialized), sblock))
        }
        else
          ApiResponseUtil.toResponse(ErrorInvalidBlockId(s"Invalid id: ${body.blockId}", JOptional.empty()))
      }
    }
  }


  /**
   * Returns an array of number last sidechain block ids
   */
  def findLastIds: Route = (post & path("findLastIds")) {
    entity(as[ReqLastIds]) { body =>
      withNodeView { sidechainNodeView =>
        val sidechainHistory = sidechainNodeView.getNodeHistory
        val blockIds = sidechainHistory.getLastBlockIds(body.number)
        ApiResponseUtil.toResponse(RespLastIds(blockIds.asScala))
      }
    }
  }

  /**
   * Return a sidechain block Id by its height in a blockchain
   */
  def findIdByHeight: Route = (post & path("findIdByHeight")) {
    entity(as[ReqFindIdByHeight]) { body =>
      withNodeView { sidechainNodeView =>
        val sidechainHistory = sidechainNodeView.getNodeHistory
        val blockIdOptional = sidechainHistory.getBlockIdByHeight(body.height)
        if (blockIdOptional.isPresent)
          ApiResponseUtil.toResponse(RespFindIdByHeight(blockIdOptional.get()))
        else
          ApiResponseUtil.toResponse(ErrorInvalidBlockHeight(s"Invalid height: ${body.height}", JOptional.empty()))
      }
    }
  }

  /**
   * Return here best sidechain block id and height in active chain
   */
  def getBestBlockInfo: Route = (post & path("best")) {
    applyOnNodeView {
      sidechainNodeView =>
        val sidechainHistory = sidechainNodeView.getNodeHistory
        val height = sidechainHistory.getCurrentHeight
        if (height > 0) {
          val bestBlock : PM = sidechainHistory.getBestBlock
          ApiResponseUtil.toResponse(RespBest[TX, H, PM](bestBlock, height))
        } else
          ApiResponseUtil.toResponse(ErrorInvalidBlockHeight(s"Invalid height: ${height}", JOptional.empty()))
    }
  }

  /**
   * Return the list of forgers fee payments paid after the given block was applied.
   * Return empty list in case no fee payments were paid.
   */
  def getFeePayments: Route = (post & path("getFeePayments")) {
    entity(as[ReqFeePayments]) { body =>
      applyOnNodeView { sidechainNodeView =>
        val sidechainHistory = sidechainNodeView.getNodeHistory
        val feePayments = sidechainHistory.getFeePaymentsInfo(body.blockId).asScala.map(_.transaction.newBoxes().asScala).getOrElse(Seq())
        ApiResponseUtil.toResponse(RespFeePayments(feePayments))
      }
    }
  }

  def startForging: Route = (post & path("startForging")) {
    val future = forgerRef ? StartForging
    val result = Await.result(future, timeout.duration).asInstanceOf[Try[Unit]]
    result match {
      case Success(_) =>
        ApiResponseUtil.toResponse(RespStartForging)
      case Failure(e) =>
        ApiResponseUtil.toResponse(ErrorStartForging(s"Failed to start forging: ${e.getMessage}", JOptional.empty()))
    }
  }

  def stopForging: Route = (post & path("stopForging")) {
    val future = forgerRef ? StopForging
    val result = Await.result(future, timeout.duration).asInstanceOf[Try[Unit]]
    result match {
      case Success(_) =>
        ApiResponseUtil.toResponse(RespStopForging)
      case Failure(e) =>
        ApiResponseUtil.toResponse(ErrorStopForging(s"Failed to stop forging: ${e.getMessage}", JOptional.empty()))
    }
  }

  def generateBlockForEpochNumberAndSlot: Route = (post & path("generate")) {
    entity(as[ReqGenerateByEpochAndSlot]) { body =>
      val future = sidechainBlockActorRef ? TryForgeNextBlockForEpochAndSlot(intToConsensusEpochNumber(body.epochNumber), intToConsensusSlotNumber(body.slotNumber))
      val submitResultFuture = Await.result(future, timeout.duration).asInstanceOf[Future[Try[ModifierId]]]
      Await.result(submitResultFuture, timeout.duration) match {
        case Success(id) =>
          ApiResponseUtil.toResponse(RespGenerate(id.asInstanceOf[String]))
        case Failure(e) =>
          ApiResponseUtil.toResponse(ErrorBlockNotCreated(s"Block was not created: ${e.getMessage}", JOptional.empty()))
      }
    }
  }

  def getForgingInfo: Route = (post & path("forgingInfo")) {
    val future = forgerRef ? GetForgingInfo
    val result = Await.result(future, timeout.duration).asInstanceOf[Try[ForgingInfo]]
    result match {
      case Success(forgingInfo) => ApiResponseUtil.toResponse(
        RespForgingInfo(
          forgingInfo.consensusSecondsInSlot,
          forgingInfo.consensusSlotsInEpoch,
          forgingInfo.currentBestEpochAndSlot.epochNumber,
          forgingInfo.currentBestEpochAndSlot.slotNumber,
          forgingInfo.forgingEnabled
        )
      )
      case Failure(ex) => ApiResponseUtil.toResponse(ErrorGetForgingInfo(s"Failed to get forging info: ${ex.getMessage}", JOptional.empty()))
    }
  }

}


object SidechainBlockRestSchema {

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqFindById(blockId: String) {
    require(blockId.length == 64, s"Invalid id $blockId. Id length must be 64")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespFindById[TX <: Transaction,
    H <: SidechainBlockHeaderBase,
    PM <: SidechainBlockBase[TX, H]]
  (blockHex: String, block: PM) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqLastIds(number: Int) {
    require(number > 0, s"Invalid number $number. Number must be > 0")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespLastIds(lastBlockIds: Seq[String]) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqFindIdByHeight(height: Int) {
    require(height > 0, s"Invalid height $height. Height must be > 0")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqFeePayments(blockId: String) {
    require(blockId.length == 64, s"Invalid id $blockId. Id length must be 64")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespFeePayments(feePayments: Seq[ZenBox]) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqGenerateByEpochAndSlot(epochNumber: Int, slotNumber: Int)

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespFindIdByHeight(blockId: String) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespBest[TX <: Transaction,
    H <: SidechainBlockHeaderBase,
    PM <: SidechainBlockBase[TX, H]](block: PM, height: Int) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] object RespStartForging extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] object RespStopForging extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespForgingInfo(consensusSecondsInSlot: Int,
                                          consensusSlotsInEpoch: Int,
                                          bestEpochNumber: Int,
                                          bestSlotNumber: Int,
                                          forgingEnabled: Boolean) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqSubmit(blockHex: String) {
    require(blockHex.nonEmpty, s"Invalid hex data $blockHex. String must be not empty")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespSubmit(blockId: String) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class ReqGenerate(number: Int) {
    require(number > 0, s"Invalid number $number. Number must be > 0")
  }

  @JsonView(Array(classOf[Views.Default]))
  private[api] case class RespGenerate(blockId: String) extends SuccessResponse

  @JsonView(Array(classOf[Views.Default]))
  private[api] object RespGenerateSkipSlot extends SuccessResponse {
    val result = "No block is generated due no eligible forger box are present, skip slot"
  }

}

object SidechainBlockErrorResponse {

  case class ErrorInvalidBlockId(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0101"
  }

  case class ErrorInvalidBlockHeight(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0102"
  }

  case class ErrorBlockTemplate(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0103"
  }

  case class ErrorBlockNotAccepted(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0104"
  }

  case class ErrorBlockNotCreated(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0105"
  }

  case class ErrorStartForging(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0106"
  }

  case class ErrorStopForging(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0107"
  }

  case class ErrorGetForgingInfo(description: String, exception: JOptional[Throwable]) extends ErrorResponse {
    override val code: String = "0108"
  }
}