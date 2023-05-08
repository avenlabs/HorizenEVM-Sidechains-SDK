package io.horizen.account.state

import com.google.common.primitives.Bytes
import io.horizen.account.abi.ABIUtil.{METHOD_ID_LENGTH, getABIMethodId, getArgumentsFromData, getFunctionSignature}
import io.horizen.account.proof.SignatureSecp256k1
import io.horizen.account.proposition.AddressProposition
import io.horizen.account.state.McAddrOwnershipLinkedList._
import io.horizen.account.state.McAddrOwnershipMsgProcessor._
import io.horizen.account.state.MessageProcessorUtil.LinkedListNode
import io.horizen.account.state.NativeSmartContractMsgProcessor.NULL_HEX_STRING_32
import io.horizen.account.state.events.{AddMcAddrOwnership, RemoveMcAddrOwnership}
import io.horizen.account.utils.BigIntegerUInt256.getUnsignedByteArray
import io.horizen.account.utils.Secp256k1.{PUBLIC_KEY_SIZE, SIGNATURE_RS_SIZE}
import io.horizen.account.utils.WellKnownAddresses.MC_ADDR_OWNERSHIP_SMART_CONTRACT_ADDRESS
import io.horizen.params.NetworkParams
import io.horizen.utils.BytesUtils
import io.horizen.evm.Address
import io.horizen.utils.BytesUtils.{padWithZeroBytes, toHorizenPublicKeyAddress}
import io.horizen.utils.Utils.{Ripemd160Sha256Hash, doubleSHA256Hash}
import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.asn1.x9.X9ECParameters
import org.web3j.crypto.Sign
import org.web3j.utils.Numeric
import sparkz.crypto.hash.{Blake2b256, Keccak256}

import java.nio.charset.StandardCharsets

trait McAddrOwnershipsProvider {
  private[horizen] def getListOfMcAddrOwnerships(view: BaseAccountStateView): Seq[McAddrOwnershipData]
  private[horizen] def ownershipDataExist(view: BaseAccountStateView, ownershipId: Array[Byte]): Boolean
}

case class McAddrOwnershipMsgProcessor(params: NetworkParams) extends NativeSmartContractMsgProcessor with McAddrOwnershipsProvider {

  override val contractAddress: Address = MC_ADDR_OWNERSHIP_SMART_CONTRACT_ADDRESS
  override val contractCode: Array[Byte] = Keccak256.hash("McAddrOwnershipSmartContractCode")

  // ecdsa curve y^2 mod p = (x^3 + 7) mod p
  val ecParameters: X9ECParameters = SECNamedCurves.getByName("secp256k1")

  val networkParams: NetworkParams = params

  override def init(view: BaseAccountStateView): Unit = {
    super.init(view)
    // set the initial value for the linked list last element (null hash)

    // check we do not have this key set to any value yet
    val initialTip = view.getAccountStorage(contractAddress, LinkedListTipKey)

    // getting a not existing key from state DB using RAW strategy as the api is doing
    // gives 32 bytes filled with 0 (CHUNK strategy gives an empty array instead)
    if (!initialTip.sameElements(NULL_HEX_STRING_32))
      throw new MessageProcessorInitializationException("initial tip already set")

    view.updateAccountStorage(contractAddress, LinkedListTipKey, LinkedListNullValue)
  }

  def existsOwnershipData(view: BaseAccountStateView, ownershipId: Array[Byte]): Boolean = {
    // do the RAW-strategy read even if the record is actually multi-line in stateDb. It will save some gas.
    val data = view.getAccountStorage(contractAddress, ownershipId)
    // getting a not existing key from state DB using RAW strategy
    // gives an array of 32 bytes filled with 0, while using CHUNK strategy
    // gives an empty array instead
    !data.sameElements(NULL_HEX_STRING_32)
  }

  private def addMcAddrOwnership(view: BaseAccountStateView, ownershipId: Array[Byte], scAddress: AddressProposition, mcTransparentAddress: String): Unit = {

    // add a new node to the linked list pointing to this obj data
    addNewNodeToList(view, ownershipId)

    val mcAddrOwnershipData = McAddrOwnershipData(scAddress.address().toStringNoPrefix, mcTransparentAddress)

    // store the ownership data
    view.updateAccountStorageBytes(contractAddress, ownershipId,
      McAddrOwnershipDataSerializer.toBytes(mcAddrOwnershipData))
  }

  private def removeMcAddrOwnership(view: BaseAccountStateView, ownershipId: Array[Byte]) : Unit =
  {
    val nodeToRemoveId = Blake2b256.hash(ownershipId)

    // we assume that the caller have checked that the data really exists in the stateDb.
    // in this case we must necessarily have a linked list node
    val nodeToRemove = findLinkedListNode(view, nodeToRemoveId).get

    // modify previous node if any
    modifyNode(view, nodeToRemove.previousNodeKey) { previousNode =>
      LinkedListNode(previousNode.dataKey, previousNode.previousNodeKey, nodeToRemove.nextNodeKey)
    }

    // modify next node if any
    modifyNode(view, nodeToRemove.nextNodeKey) { nextNode =>
      LinkedListNode(nextNode.dataKey, nodeToRemove.previousNodeKey, nextNode.nextNodeKey)
    } getOrElse {
      // if there is no next node, we update the linked list tip to point to the previous node, promoted to be the new tip
      view.updateAccountStorage(contractAddress, LinkedListTipKey, nodeToRemove.previousNodeKey)
    }

    // remove the stake
    view.removeAccountStorageBytes(contractAddress, ownershipId)

    // remove the node from the linked list
    view.removeAccountStorageBytes(contractAddress, nodeToRemoveId)
  }

  def isValidOwnershipSignature(scAddress: AddressProposition, mcTransparentAddress: String, mcSignature: SignatureSecp256k1): Boolean = {
    // get a signature data obj for the verification
    val v_barr = getUnsignedByteArray(mcSignature.getV)
    val r_barr = padWithZeroBytes(getUnsignedByteArray(mcSignature.getR), SIGNATURE_RS_SIZE)
    val s_barr = padWithZeroBytes(getUnsignedByteArray(mcSignature.getS), SIGNATURE_RS_SIZE)

    val signatureData = new Sign.SignatureData(v_barr, r_barr, s_barr)

    val hashedMsg = getMcHashedMsg(BytesUtils.toHexString(scAddress.pubKeyBytes()))

    // verify MC message signature
    val recPubKey = Sign.signedMessageHashToKey(hashedMsg, signatureData)
    val recUncompressedPubKeyBytes = Bytes.concat(Array[Byte](0x04), Numeric.toBytesPadded(recPubKey, PUBLIC_KEY_SIZE))
    val ecpointRec = ecParameters.getCurve.decodePoint(recUncompressedPubKeyBytes)
    val recCompressedPubKeyBytes = ecpointRec.getEncoded(true)
    val mcPubkeyhash = Ripemd160Sha256Hash(recCompressedPubKeyBytes)
    val computedTaddr = toHorizenPublicKeyAddress(mcPubkeyhash, params)

    computedTaddr.equals(mcTransparentAddress)

  }

  // this reproduces the MC way of getting a message for signing it via rpc signmessage cmd
  private def getMcHashedMsg(messageToSignString: String) = {
    // this is the magic string prepended in zend to the message to be signed*/
    val strMessageMagic = "Zcash Signed Message:\n"
    // compute the message to be signed. Similarly to what MC does, we must prepend the size of the byte buffers
    // we are using
    val messageMagicBytes = strMessageMagic.getBytes(StandardCharsets.UTF_8)
    val mmb2 = Bytes.concat(Array[Byte](messageMagicBytes.length.asInstanceOf[Byte]), messageMagicBytes)

    // TODO: currently size < 256 which is ok for a sc address; make it generic with int_to_bytes
    val messageToSignBytes = messageToSignString.getBytes(StandardCharsets.UTF_8)
    val mts2 = Bytes.concat(Array[Byte](messageToSignBytes.length.asInstanceOf[Byte]), messageToSignBytes)
    // hash the message as MC does (double sha256)
    doubleSHA256Hash(Bytes.concat(mmb2, mts2))
  }

  def doAddNewOwnershipCmd(msg: Message, view: BaseAccountStateView): Array[Byte] = {

    // check that message contains a nonce, in the context of RPC calls the nonce might be missing
    if (msg.getNonce == null) {
      throw new ExecutionRevertedException("Call must include a nonce")
    }

    // check that msg.value is zero
    if (msg.getValue.signum() != 0) {
      throw new ExecutionRevertedException("Value must not be zero")
    }

    // check that sender account exists
    if (!view.accountExists(msg.getFrom) ) {
      throw new ExecutionRevertedException(s"Sender account does not exist: ${msg.getFrom}")
    }

    val inputParams = getArgumentsFromData(msg.getData)

    val cmdInput = AddNewOwnershipCmdInputDecoder.decode(inputParams)
    val scAddress = new AddressProposition(cmdInput.scAddress)
    val mcTransparentAddress = cmdInput.mcTransparentAddress
    val mcSignature = cmdInput.mcSignature

    if (!msg.getFrom.equals(cmdInput.scAddress)) {
      throw new ExecutionRevertedException(
        s"sc account ${msg.getFrom.toStringNoPrefix} is not the one specified in input ${cmdInput.scAddress.toStringNoPrefix}")
    }

    // compute ownershipId
    val newOwnershipId = getOwnershipId(scAddress.address(), mcTransparentAddress)

    // check we do not already have this obj in the db
    if (existsOwnershipData(view, newOwnershipId)) {
      throw new ExecutionRevertedException(
        s"Ownership ${BytesUtils.toHexString(newOwnershipId)} already exists")
    }

    // verify the ownership validating the signature
    if (!isValidOwnershipSignature(scAddress, mcTransparentAddress, mcSignature)) {
      throw new ExecutionRevertedException(s"Ownership ${BytesUtils.toHexString(newOwnershipId)} has not a valid mc signature")
    }

    // add the obj to stateDb
    addMcAddrOwnership(view, newOwnershipId, scAddress, mcTransparentAddress)
    log.debug(s"Added ownership to stateDb: newOwnershipId=${BytesUtils.toHexString(newOwnershipId)}," +
      s" scAddress=$scAddress, mcPubKeyBytes=$mcTransparentAddress, mcSignature=$mcSignature")

    val addNewMcAddrOwnershipEvt = AddMcAddrOwnership(scAddress.address(), mcTransparentAddress)
    val evmLog = getEthereumConsensusDataLog(addNewMcAddrOwnershipEvt)
    view.addLog(evmLog)

    // result in case of success execution might be useful for RPC commands
    newOwnershipId
  }

  def doRemoveOwnershipCmd(msg: Message, view: BaseAccountStateView): Array[Byte] = {
    // check that message contains a nonce, in the context of RPC calls the nonce might be missing
    if (msg.getNonce == null) {
      throw new ExecutionRevertedException("Call must include a nonce")
    }

    // check that msg.value is zero
    if (msg.getValue.signum() != 0) {
      throw new ExecutionRevertedException("Value must not be zero")
    }

    // check that sender account exists
    if (!view.accountExists(msg.getFrom) ) {
      throw new ExecutionRevertedException(s"Sender account does not exist: ${msg.getFrom}")
    }

    val inputParams = getArgumentsFromData(msg.getData)

    val cmdInput = RemoveOwnershipCmdInputDecoder.decode(inputParams)
    val scAddress = new AddressProposition(cmdInput.scAddress)

    if (!msg.getFrom.equals(cmdInput.scAddress)) {
      throw new ExecutionRevertedException(
        s"sc account ${msg.getFrom.toStringNoPrefix} is not the one specified in input ${cmdInput.scAddress.toStringNoPrefix}")
    }

    cmdInput.mcTransparentAddressOpt match {
      case Some(mcTransparentAddress) =>
        // compute ownershipId
        val ownershipId = getOwnershipId(scAddress.address(), mcTransparentAddress)

        // check we do not already have this obj in the db
        if (!existsOwnershipData(view, ownershipId)) {
          throw new ExecutionRevertedException(
            s"Ownership ${BytesUtils.toHexString(ownershipId)} does not exists")
        }

        // remove the obj from stateDb
        removeMcAddrOwnership(view, ownershipId)
        log.debug(s"Removed ownership from stateDb: newOwnershipId=${BytesUtils.toHexString(ownershipId)}," +
          s" scAddress=$scAddress, mcPubKeyBytes=$mcTransparentAddress")

        // TODO handle correct event
        val removeMcAddrOwnershipEvt = RemoveMcAddrOwnership(scAddress.address(), mcTransparentAddress)
        val evmLog = getEthereumConsensusDataLog(removeMcAddrOwnershipEvt)
        view.addLog(evmLog)

        // result in case of success execution might be useful for RPC commands
        ownershipId

      case None =>
        // TODO handle none case, we should remove all sc address association
      throw new ExecutionRevertedException(
          s"Invalid null mc address")
    }
 }

  override def getListOfMcAddrOwnerships(view: BaseAccountStateView): Seq[McAddrOwnershipData] = {
    var ownershipsList = Seq[McAddrOwnershipData]()
    var nodeReference = view.getAccountStorage(contractAddress, LinkedListTipKey)

    while (!linkedListNodeRefIsNull(nodeReference)) {
      val (item: McAddrOwnershipData, prevNodeReference: Array[Byte]) = getListItem(view, nodeReference)
      ownershipsList = item +: ownershipsList
      nodeReference = prevNodeReference
    }
    ownershipsList
  }

   override def ownershipDataExist(view: BaseAccountStateView, ownershipId: Array[Byte]): Boolean = {
    existsOwnershipData(view, ownershipId)
  }

  @throws(classOf[ExecutionFailedException])
  override def process(msg: Message, view: BaseAccountStateView, gas: GasPool, blockContext: BlockContext): Array[Byte] = {
    val gasView = view.getGasTrackedView(gas)
    getFunctionSignature(msg.getData) match {
      case AddNewOwnershipCmd => doAddNewOwnershipCmd(msg, gasView)
      case RemoveOwnershipCmd => doRemoveOwnershipCmd(msg, gasView)
      case opCodeHex => throw new ExecutionRevertedException(s"op code not supported: $opCodeHex")
    }
  }

}

object McAddrOwnershipMsgProcessor {

  val LinkedListTipKey: Array[Byte] = Blake2b256.hash("OwnershipTip")
  val LinkedListNullValue: Array[Byte] = Blake2b256.hash("OwnershipNull")

  val AddNewOwnershipCmd: String = getABIMethodId("sendKeysOwnership(address,bytes3,bytes32,bytes1,bytes32,bytes32)")
  val RemoveOwnershipCmd: String = getABIMethodId("removeKeysOwnership(address,bytes3,bytes32)")

  // ensure we have strings consistent with size of opcode
  require(
    AddNewOwnershipCmd.length == 2 * METHOD_ID_LENGTH,
    RemoveOwnershipCmd.length == 2 * METHOD_ID_LENGTH
  )


  def getOwnershipId(scAddress: Address, mcAddress: String): Array[Byte] = {
    Keccak256.hash(Bytes.concat(scAddress.toBytes, mcAddress.getBytes(StandardCharsets.UTF_8)))
  }
}

