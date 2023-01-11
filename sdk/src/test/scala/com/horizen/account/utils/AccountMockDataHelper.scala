package com.horizen.account.utils

import com.horizen.SidechainTypes
import com.horizen.account.block.{AccountBlock, AccountBlockHeader}
import com.horizen.account.history.AccountHistory
import com.horizen.block.SidechainBlockBase.GENESIS_BLOCK_PARENT_ID
import com.horizen.params.NetworkParams
import org.mockito.Mockito
import com.horizen.account.mempool.{AccountMemoryPool, MempoolMap}
import com.horizen.account.proof.SignatureSecp256k1
import com.horizen.account.proposition.AddressProposition
import com.horizen.account.receipt.{EthereumReceipt, Bloom}
import com.horizen.account.secret.PrivateKeySecp256k1
import com.horizen.account.state._
import com.horizen.account.storage.AccountStateMetadataStorageView
import com.horizen.account.transaction.EthereumTransaction
import com.horizen.account.wallet.AccountWallet
import com.horizen.block.{MainchainBlockReference, MainchainBlockReferenceData}
import com.horizen.box.Box
import com.horizen.chain.{MainchainHeaderBaseInfo, SidechainBlockInfo}
import com.horizen.companion.SidechainSecretsCompanion
import com.horizen.customtypes.{CustomPrivateKey, CustomPrivateKeySerializer}
import com.horizen.evm.interop.ProofAccountResult
import com.horizen.evm.utils.Address
import com.horizen.evm.{StateDB, StateStorageStrategy}
import com.horizen.fixtures.SidechainBlockFixture.generateMainchainBlockReference
import com.horizen.fixtures.{FieldElementFixture, SidechainRelatedMainchainOutputFixture, StoreFixture, VrfGenerator}
import com.horizen.params.MainNetParams
import com.horizen.proposition.Proposition
import com.horizen.secret.{Secret, SecretSerializer}
import com.horizen.storage.{SidechainSecretStorage, Storage}
import com.horizen.transaction.MC2SCAggregatedTransaction
import com.horizen.transaction.mainchain.{ForwardTransfer, SidechainCreation, SidechainRelatedMainchainOutput}
import com.horizen.utils.{ByteArrayWrapper, BytesUtils, Pair, WithdrawalEpochInfo}
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers
import org.scalatestplus.junit.JUnitSuite
import org.scalatestplus.mockito.MockitoSugar.mock
import org.web3j.utils.Numeric
import sparkz.util.{ModifierId, bytesToId}
import sparkz.core.consensus.ModifierSemanticValidity
import java.lang.{Byte => JByte}
import java.math.BigInteger
import java.util.{Optional, HashMap => JHashMap}
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{Success, Try}

case class AccountMockDataHelper(genesis: Boolean)
    extends JUnitSuite
      with StoreFixture
      with SidechainRelatedMainchainOutputFixture
      with MessageProcessorFixture {

  def getMockedAccoutMemoryPool: AccountMemoryPool = {
    val memoryPool: AccountMemoryPool = mock[AccountMemoryPool]

    // executable transaction IDs list
    val executableTxsIdList: Iterable[ModifierId] = List(bytesToId(new Array[Byte](32)),bytesToId(new Array[Byte](32)),bytesToId(new Array[Byte](32)))
    Mockito.when(memoryPool.getExecutableTransactions).thenReturn(executableTxsIdList.toList.asJava)

    // non executable transaction IDs list
    val nonExecutableTxsIdList: Iterable[ModifierId] = List(bytesToId(new Array[Byte](32)))
    Mockito.when(memoryPool.getNonExecutableTransactions).thenReturn(nonExecutableTxsIdList.toList.asJava)

    // default signature for all txs
    val defaultSignature = new SignatureSecp256k1(
      BytesUtils.fromHexString("1c"),
      BytesUtils.fromHexString("805c658ac084be6da079d96bd4799bef3aa4578c8e57b97c3c6df9f581551023"),
      BytesUtils.fromHexString("568277f09a64771f5b4588ff07f75725a8e40d2c641946eb645152dcd4c93f0d")
    )

    // executable address/nonces/transactions map
    val executableTxsMap = TrieMap.empty[SidechainTypes#SCP, MempoolMap#TxByNonceMap]

    // proposition 1 executable transactions
    val executableNonceTxsMap1: mutable.TreeMap[BigInteger, SidechainTypes#SCAT] = new mutable.TreeMap[BigInteger, SidechainTypes#SCAT]()
    val proposition1 = new AddressProposition(BytesUtils.fromHexString("15532e34426cd5c37371ff455a5ba07501c0f522"))
    val toProposition = new AddressProposition(BytesUtils.fromHexString("15532e34426cd5c37371ff455a5ba07501c0f522"))

    val executableTx1 = new EthereumTransaction(
      1997L, Optional.of(toProposition), BigInteger.valueOf(16L), BigInteger.valueOf(15467876L), BigInteger.valueOf(454545L),
      FeeUtils.INITIAL_BASE_FEE.add(BigInteger.valueOf(100)), BigInteger.valueOf(15000000L), BytesUtils.fromHexString("bd54d1f34e34a90f7dc5efe0b3d65fa4"),
      defaultSignature)
    val executableTx2 = new EthereumTransaction(
      1997L, Optional.of(toProposition), BigInteger.valueOf(24L), BigInteger.valueOf(15467876L), BigInteger.valueOf(454545L),
      FeeUtils.INITIAL_BASE_FEE.add(BigInteger.valueOf(100)), BigInteger.valueOf(4800000L), BytesUtils.fromHexString("8c64fe48688ab096dfb6ac2eeefcf213"),
      defaultSignature)

    executableNonceTxsMap1.put(BigInteger.valueOf(16),executableTx1.asInstanceOf[SidechainTypes#SCAT])
    executableNonceTxsMap1.put(BigInteger.valueOf(24),executableTx2.asInstanceOf[SidechainTypes#SCAT])
    executableTxsMap.put(proposition1.asInstanceOf[SidechainTypes#SCP],executableNonceTxsMap1)

    // proposition 2 executable transactions
    val executableNonceTxsMap2: mutable.TreeMap[BigInteger, SidechainTypes#SCAT] = new mutable.TreeMap[BigInteger, SidechainTypes#SCAT]()
    val proposition2 = new AddressProposition(BytesUtils.fromHexString("b039865dbea73df08e23f185847bab8e6a44108d"))

    val executableTx3 = new EthereumTransaction(
      1997L, Optional.of(toProposition), BigInteger.valueOf(32L), BigInteger.valueOf(15467876L), BigInteger.valueOf(454545L),
      FeeUtils.INITIAL_BASE_FEE.add(BigInteger.valueOf(100)), BigInteger.valueOf(18000000L), BytesUtils.fromHexString("bd54d1f34e34a90f7dc5efe0b3d65fa4"),
      defaultSignature)

    executableNonceTxsMap2.put(BigInteger.valueOf(32), executableTx3.asInstanceOf[SidechainTypes#SCAT])
    executableTxsMap.put(proposition2.asInstanceOf[SidechainTypes#SCP], executableNonceTxsMap2)

    // mock getExecutableTransactionsMap method call
    Mockito.when(memoryPool.getExecutableTransactionsMap).thenReturn(executableTxsMap)

    // non executable address/nonces/transactions map
    val nonExecutableTxsMap = TrieMap.empty[SidechainTypes#SCP, MempoolMap#TxByNonceMap]

    // proposition 1 non executable transactions
    val nonExecutableNonceTxsMap1: mutable.TreeMap[BigInteger, SidechainTypes#SCAT] = new mutable.TreeMap[BigInteger, SidechainTypes#SCAT]()

    val nonExecutableTx1 = new EthereumTransaction(
      1997L, Optional.of(toProposition), BigInteger.valueOf(40L), BigInteger.valueOf(15467876L), BigInteger.valueOf(454545L),
      FeeUtils.INITIAL_BASE_FEE.add(BigInteger.valueOf(100)), BigInteger.valueOf(63000000L), BytesUtils.fromHexString("4aa64a075647e3621bbc14b03e4087903f2c9503"),
      defaultSignature)

    nonExecutableNonceTxsMap1.put(BigInteger.valueOf(40), nonExecutableTx1.asInstanceOf[SidechainTypes#SCAT])
    nonExecutableTxsMap.put(proposition1.asInstanceOf[SidechainTypes#SCP], nonExecutableNonceTxsMap1)

    // mock getExecutableTransactionsMap method call
    Mockito.when(memoryPool.getNonExecutableTransactionsMap).thenReturn(nonExecutableTxsMap)

    // return the mocked memory pool
    memoryPool
  }

  def getMockedAccountHistory(
      block: Option[AccountBlock],
      parentBlock: Option[AccountBlock] = None,
      genesisBlockId: Option[String] = None
  ): AccountHistory = {
    val history: AccountHistory = mock[AccountHistory]
    val blockId = block.get.id
    val height = if (genesis) 2 else 1

    Mockito.when(history.params).thenReturn(mock[NetworkParams])

    Mockito.when(history.blockIdByHeight(any())).thenReturn(None)
    Mockito.when(history.blockIdByHeight(2)).thenReturn(Option(blockId))

    if (genesis) {
      Mockito.when(history.params.sidechainGenesisBlockParentId).thenReturn(bytesToId(GENESIS_BLOCK_PARENT_ID))
      Mockito.when(history.params.sidechainGenesisBlockParentId).thenReturn(bytesToId(new Array[Byte](32)))
      Mockito.when(history.blockIdByHeight(1)).thenReturn(genesisBlockId)
    }
    Mockito.when(history.getCurrentHeight).thenReturn(height)

    Mockito.when(history.getBlockById(any())).thenReturn(Optional.empty[AccountBlock])
    Mockito.when(history.getBlockById(blockId)).thenReturn(Optional.of(block.get))

    Mockito.when(history.getStorageBlockById(any())).thenReturn(None)
    Mockito.when(history.getStorageBlockById(blockId)).thenReturn(Some(block.get))
    if (parentBlock.nonEmpty) {
      val parentId = parentBlock.get.id
      val blockInfo = new SidechainBlockInfo(
        height,
        0,
        parentId,
        86400L * 2,
        ModifierSemanticValidity.Unknown,
        MainchainHeaderBaseInfo
          .getMainchainHeaderBaseInfoSeqFromBlock(block.get, FieldElementFixture.generateFieldElement()),
        SidechainBlockInfo.mainchainReferenceDataHeaderHashesFromBlock(block.get),
        WithdrawalEpochInfo(0, 0),
        Option(VrfGenerator.generateVrfOutput(0)),
        parentId
      )
      Mockito.when(history.getBlockById(parentId)).thenReturn(Optional.of(parentBlock.get))
      Mockito.when(history.getStorageBlockById(parentId)).thenReturn(Some(parentBlock.get))
      Mockito.when(history.blockInfoById(blockId)).thenReturn(blockInfo)
    }

    Mockito.when(history.getBlockHeightById(any())).thenReturn(Optional.of[Integer](1))
    history
  }

  def getMockedBlock(
      baseFee: BigInteger = FeeUtils.INITIAL_BASE_FEE,
      gasUsed: Long = 0L,
      gasLimit: Long = FeeUtils.GAS_LIMIT,
      blockId: sparkz.util.ModifierId = null,
      parentBlockId: sparkz.util.ModifierId = null,
      txs: Seq[SidechainTypes#SCAT] = Seq.empty[SidechainTypes#SCAT]
  ): AccountBlock = {
    val block: AccountBlock = mock[AccountBlock]

    val scCr1: SidechainCreation = mock[SidechainCreation]
    val ft1: ForwardTransfer = getForwardTransfer(getPrivateKey25519.publicImage(), MainNetParams().sidechainId)
    val ft2: ForwardTransfer = getForwardTransfer(getPrivateKey25519.publicImage(), MainNetParams().sidechainId)

    val mc2scTransactionsOutputs: Seq[SidechainRelatedMainchainOutput[_ <: Box[_ <: Proposition]]] =
      Seq(scCr1, ft1, ft2)
    val aggTx = new MC2SCAggregatedTransaction(
      mc2scTransactionsOutputs.asJava,
      MC2SCAggregatedTransaction.MC2SC_AGGREGATED_TRANSACTION_VERSION
    )

    var mcBlockRef: MainchainBlockReference = generateMainchainBlockReference()
    mcBlockRef = new MainchainBlockReference(
      mcBlockRef.header,
      MainchainBlockReferenceData(mcBlockRef.header.hash, Some(aggTx), None, None, Seq(), None)
    ) {
      override def semanticValidity(params: NetworkParams): Try[Unit] = Success(Unit)
    }

    val forgerAddress: AddressProposition = new AddressProposition(
      BytesUtils.fromHexString("1234567891011121314112345678910111213141")
    )
    Mockito.when(block.header).thenReturn(mock[AccountBlockHeader])
    Mockito.when(block.header.parentId).thenReturn(parentBlockId)
    Mockito.when(block.id).thenReturn(blockId)
    Mockito.when(block.header.baseFee).thenReturn(baseFee)
    Mockito.when(block.header.gasUsed).thenReturn(gasUsed)
    Mockito.when(block.header.gasLimit).thenReturn(gasLimit)
    Mockito
      .when(block.header.forgerAddress)
      .thenReturn(forgerAddress)
    Mockito.when(block.sidechainTransactions).thenReturn(txs)
    Mockito.when(block.transactions).thenReturn(txs)
    Mockito.when(block.mainchainHeaders).thenReturn(Seq(mcBlockRef.header))
    Mockito.when(block.mainchainBlockReferencesData).thenReturn(Seq(mcBlockRef.data))
    Mockito
      .when(block.header.stateRoot)
      .thenReturn(BytesUtils.fromHexString("1234567891011121314112345678910111213141"))
    Mockito.when(block.header.logsBloom).thenReturn(mock[Bloom])
    Mockito.when(block.header.logsBloom.getBytes).thenReturn(new Array[Byte](256))
    Mockito
      .when(block.header.sidechainTransactionsMerkleRootHash)
      .thenReturn(BytesUtils.fromHexString("1234567891011121314112345678910111213141"))
    Mockito
      .when(block.header.stateRoot)
      .thenReturn(BytesUtils.fromHexString("1234567891011121314112345678910111213141"))
    Mockito
      .when(block.header.receiptsRoot)
      .thenReturn(BytesUtils.fromHexString("1234567891011121314112345678910111213141"))
    Mockito.when(block.header.bytes).thenReturn(new Array[Byte](256))
    Mockito.when(block.timestamp).thenReturn(1000000000L)
    block
  }

  def getMockedState(receipt: EthereumReceipt, txHash: Array[Byte]): AccountState = {
    val state: AccountState = mock[AccountState]
    val stateDB: StateDB = mock[StateDB]
    val metadataStorageView: AccountStateMetadataStorageView = {
      mock[AccountStateMetadataStorageView]
    }

    val mockMsgProcessor: MessageProcessor = setupMockMessageProcessor
    val msgProcessors = Seq(mockMsgProcessor)
    val stateView = new AccountStateView(metadataStorageView, stateDB, msgProcessors) {
      override lazy val withdrawalReqProvider: WithdrawalRequestProvider =
        msgProcessors.find(_.isInstanceOf[WithdrawalRequestProvider]).get.asInstanceOf[WithdrawalRequestProvider]
      override lazy val forgerStakesProvider: ForgerStakesProvider =
        msgProcessors.find(_.isInstanceOf[ForgerStakesProvider]).get.asInstanceOf[ForgerStakesProvider]

    }
    Mockito.when(state.getView).thenReturn(stateView)
    Mockito.when(state.getView.getTransactionReceipt(any())).thenReturn(None)
    Mockito.when(state.getView.getTransactionReceipt(txHash)).thenReturn(Some(receipt))
    if (state.getView != null) {
      Mockito.when(state.getView.getBalance(any())).thenReturn(BigInteger.valueOf(99999999999999999L))
      Mockito
        .when(state.getView.getBalance(Numeric.hexStringToByteArray("0x1234567891011121314151617181920212223242")))
        .thenReturn(BigInteger.valueOf(123L))
      Mockito.when(state.getView.getCode(any())).thenReturn(Numeric.hexStringToByteArray("0x"))
      Mockito
        .when(state.getView.getCode(Numeric.hexStringToByteArray("0x1234567891011121314151617181920212223242")))
        .thenReturn(Numeric.hexStringToByteArray("0x1234"))
      Mockito.when(state.getView.getNonce(any())).thenReturn(BigInteger.ZERO)
      Mockito
        .when(state.getView.getNonce(Numeric.hexStringToByteArray("0x1234567891011121314151617181920212223242")))
        .thenReturn(BigInteger.ONE)
      Mockito.when(state.getView.getRefund).thenReturn(BigInteger.ONE)

      val proofRes: ProofAccountResult = mock[ProofAccountResult]
      proofRes.address = Address.fromBytes(Numeric.hexStringToByteArray("0x1234567891011121314151617181920212223242"))
      proofRes.accountProof = Array("123")
      proofRes.nonce = BigInteger.ONE
      proofRes.balance = BigInteger.valueOf(123L)
      proofRes.codeHash = null
      proofRes.storageHash = null
      proofRes.storageProof = null

      Mockito.when(state.getView.getProof(any(), any())).thenReturn(proofRes)
      if (stateDB != null) {
        Mockito
          .when(stateDB.getStorage(any(), any(), any()))
          .thenReturn(Numeric.hexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"))
        Mockito
          .when(
            stateDB.getStorage(
              Numeric.hexStringToByteArray("0x1234567890123456789012345678901234567890"),
              Numeric.hexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"),
              StateStorageStrategy.RAW
            )
          )
          .thenReturn(
            Numeric.hexStringToByteArray("0x1411111111111111111111111111111111111111111111111111111111111111")
          )
        Mockito
          .when(
            stateDB.getStorage(
              Numeric.hexStringToByteArray("0x1234567891011121314151617181920212223242"),
              Numeric.hexStringToByteArray("0000000000000000000000000000000000000000000000000000000000000000"),
              StateStorageStrategy.RAW
            )
          )
          .thenReturn(
            Numeric.hexStringToByteArray("0x1511111111111111111111111111111111111111111111111111111111111111")
          )
      }
    }
    Mockito.when(state.getBalance(any())).thenReturn(BigInteger.valueOf(999999999999999999L))
    Mockito
      .when(state.getBalance(Numeric.hexStringToByteArray("0x1234567891011121314151617181920212223242")))
      .thenReturn(BigInteger.ZERO)
    Mockito.when(state.getStateDbViewFromRoot(any())).thenReturn(stateView)

    state
  }

  private def setupMockMessageProcessor = {
    val mockMsgProcessor = mock[MessageProcessor]
    Mockito
      .when(mockMsgProcessor.canProcess(ArgumentMatchers.any[Message], ArgumentMatchers.any[BaseAccountStateView]))
      .thenReturn(true)
    Mockito
      .when(
        mockMsgProcessor.process(
          ArgumentMatchers.any[Message],
          ArgumentMatchers.any[BaseAccountStateView],
          ArgumentMatchers.any[GasPool],
          ArgumentMatchers.any[BlockContext]
        )
      )
      .thenReturn(Array.empty[Byte])
    mockMsgProcessor
  }

  def getMockedWallet(secret: PrivateKeySecp256k1): AccountWallet = {
    val wallet = new AccountWallet("seed".getBytes(), getMockedSecretStorage(secret))

    wallet
  }

  def getMockedSecretStorage(secret: PrivateKeySecp256k1): SidechainSecretStorage = {
    val mockedSecretStorage: Storage = mock[Storage]
    val secretList = new ListBuffer[Secret]()
    val customSecretList = new ListBuffer[Secret]()
    val storedSecretList = new ListBuffer[Pair[ByteArrayWrapper, ByteArrayWrapper]]()
    val secretVersions = new ListBuffer[ByteArrayWrapper]()
    val customSecretSerializers: JHashMap[JByte, SecretSerializer[SidechainTypes#SCS]] = new JHashMap()
    customSecretSerializers.put(
      CustomPrivateKey.SECRET_TYPE_ID,
      CustomPrivateKeySerializer.getSerializer.asInstanceOf[SecretSerializer[SidechainTypes#SCS]]
    )
    val sidechainSecretsCompanion = SidechainSecretsCompanion(customSecretSerializers)

    // Set base Secrets data
    secretList ++= getPrivateKey25519List(5).asScala
    customSecretList ++= getCustomPrivateKeyList(5).asScala

    secretVersions += getVersion
    storedSecretList.append({
      val key = new ByteArrayWrapper(secret.publicImage().address())
      val value = new ByteArrayWrapper(sidechainSecretsCompanion.toBytes(secret))
      new Pair(key, value)
    })

    // Mock get and update methods of SecretStorage
    Mockito.when(mockedSecretStorage.getAll).thenReturn(storedSecretList.asJava)

    new SidechainSecretStorage(mockedSecretStorage, sidechainSecretsCompanion)
  }
}
