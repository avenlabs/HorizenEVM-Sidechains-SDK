package com.horizen.account.state

import com.horizen.account.proposition.AddressProposition
import com.horizen.utils.BytesUtils
import scorex.util.ScorexLogging

abstract class AbstractFakeSmartContractMsgProcessor extends MessageProcessor with ScorexLogging {

  val OP_CODE_LENGTH = 1
  val NULL_HEX_STRING_32 = BytesUtils.toHexString(new Array[Byte](32))

  val fakeSmartContractAddress: AddressProposition
  val fakeSmartContractCodeHash: Array[Byte]

  @throws[MessageProcessorInitializationException]
  override def init(view: AccountStateView): Unit = {
    if (!view.accountExists(fakeSmartContractAddress.address()))
    {
      view.addAccount(fakeSmartContractAddress.address(), fakeSmartContractCodeHash)

      log.debug(s"created Message Processor account ${BytesUtils.toHexString(fakeSmartContractAddress.address())}")
    }
    else
    {
      val errorMsg = s"Account ${BytesUtils.toHexString(fakeSmartContractAddress.address())} already exists"
      log.error(errorMsg)
      throw new MessageProcessorInitializationException(errorMsg)
    }
  }

  override def canProcess(msg: Message, view: AccountStateView): Boolean = {
    fakeSmartContractAddress.equals(msg.getTo) &&
      view.accountExists(msg.getTo().address())
  }

  protected def getOpCodeFromData(data: Array[Byte]): Array[Byte] ={
    require(data.length >= OP_CODE_LENGTH, s"Data length ${data.length} must be >= $OP_CODE_LENGTH")
    data.slice(0, OP_CODE_LENGTH)
  }

  protected def getArgumentsFromData(data: Array[Byte]): Array[Byte] ={
    require(data.length >= OP_CODE_LENGTH, s"Data length ${data.length} must be >= $OP_CODE_LENGTH")
    data.drop(OP_CODE_LENGTH)
  }
 }



