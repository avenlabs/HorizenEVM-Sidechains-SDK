package com.horizen.account.performance

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.testkit.TestActorRef
import com.horizen.account.AccountSidechainNodeViewHolder
import com.horizen.account.block.AccountBlock
import com.horizen.account.fixtures.EthereumTransactionFixture
import com.horizen.account.history.AccountHistory
import com.horizen.account.mempool.AccountMemoryPool
import com.horizen.account.state.{AccountState, AccountStateView, MessageProcessor}
import com.horizen.account.storage.{AccountHistoryStorage, AccountStateMetadataStorage}
import com.horizen.account.transaction.EthereumTransaction
import com.horizen.account.utils.ZenWeiConverter
import com.horizen.account.wallet.AccountWallet
import com.horizen.consensus.ConsensusDataStorage
import com.horizen.evm.Database
import com.horizen.fixtures._
import com.horizen.params.NetworkParams
import com.horizen.storage.SidechainSecretStorage
import com.horizen.utils.{ByteArrayWrapper, BytesUtils}
import com.horizen.{SidechainSettings, SidechainTypes, WalletSettings}
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.{Before, Test}
import org.mockito.{ArgumentMatchers, Mockito}
import org.scalatestplus.junit.JUnitSuite
import org.web3j.crypto.{ECKeyPair, Keys}
import sparkz.core.VersionTag
import sparkz.core.utils.NetworkTimeProvider

import java.io.{BufferedWriter, FileWriter}
import java.math.BigInteger
import java.util.Calendar
import scala.collection.concurrent.TrieMap

class AccountSidechainNodeViewHolderPerfTest
    extends JUnitSuite
      with MockedSidechainNodeViewHolderFixture
      with EthereumTransactionFixture
      with StoreFixture
      with sparkz.core.utils.SparkzEncoding {
  var historyMock: AccountHistory = _
  var state: AccountState = _
  var stateViewMock: AccountStateView = _
  var wallet: AccountWallet = _
  var mempool: AccountMemoryPool = _

  implicit val actorSystem: ActorSystem = ActorSystem("sc_nvh_mocked")
  var mockedNodeViewHolderRef: ActorRef = _

  val nonces = TrieMap[ByteArrayWrapper, BigInteger]()

  @Before
  def setUp(): Unit = {
    historyMock = mock[AccountHistory]

    stateViewMock = mock[AccountStateView]
    Mockito
      .when(stateViewMock.getBalance(ArgumentMatchers.any[Array[Byte]]))
      .thenReturn(ZenWeiConverter.MAX_MONEY_IN_WEI) // Has always enough balance
    Mockito.when(stateViewMock.isEoaAccount(ArgumentMatchers.any[Array[Byte]])).thenReturn(true)
    Mockito.when(stateViewMock.baseFee).thenReturn(BigInteger.ZERO)

    Mockito.when(stateViewMock.getNonce(ArgumentMatchers.any[Array[Byte]])).thenAnswer { answer =>
      {
        nonces.getOrElse(new ByteArrayWrapper(answer.getArgument(0).asInstanceOf[Array[Byte]]), BigInteger.ZERO)
      }
    }

    wallet = mock[AccountWallet]
    Mockito.when(wallet.scanOffchain(ArgumentMatchers.any[SidechainTypes#SCAT])).thenReturn(wallet)
  }

  @Test
  // @Ignore
  def txModifyTest(): Unit = {
    val out = new BufferedWriter(new FileWriter("log/txModifyTest.txt", true))

    val cal = Calendar.getInstance()
    try {
      out.write("*********************************************************************\n\n")
      out.write("*                Adding transaction performance test                 \n\n")
      out.write("*********************************************************************\n\n")

      out.write(s"Date and time of the test: ${cal.getTime}\n\n")

      val nodeViewHolder = getMockedAccountSidechainNodeViewHolder

      val numOfTxs = 100000
      val numOfTxsPerSpammerAccounts = 100
      val numOfTxsPerNormalAccounts = 5
      val normalSpammerRatio = 20
      assertTrue(
        "Invalid test parameters",
        numOfTxs % (numOfTxsPerSpammerAccounts + normalSpammerRatio * numOfTxsPerNormalAccounts) == 0
      )
      val numOfSpammerAccount = numOfTxs / (numOfTxsPerSpammerAccounts + normalSpammerRatio * numOfTxsPerNormalAccounts)
      val numOfNormalAccount = normalSpammerRatio * numOfSpammerAccount
      out.write(s"Total number of transactions:                    $numOfTxs\n")
      out.write(s"Number of spammer accounts:                      $numOfSpammerAccount\n")
      out.write(s"Number of transactions for each spammer account: $numOfTxsPerSpammerAccounts\n")
      out.write(s"Number of normal accounts:                       $numOfNormalAccount\n")
      out.write(s"Number of transactions for each normal account:  $numOfTxsPerNormalAccounts\n")

      val listOfTxs = scala.collection.mutable.ListBuffer[EthereumTransaction]()

      println(s"*************** Adding transaction performance test ***************")
      println(s"Total number of transaction: $numOfTxs")

      listOfTxs ++= createTransactions(numOfNormalAccount, numOfTxsPerNormalAccounts, orphanIdx = -1)

      listOfTxs ++= createTransactions(numOfSpammerAccount, numOfTxsPerSpammerAccounts, orphanIdx = -1)

      println("Starting test direct order")
      val numOfSnapshots = 10
      val numOfTxsPerSnapshot = numOfTxs / numOfSnapshots
      var listOfSnapshots = new scala.collection.mutable.ListBuffer[Long]()
      var startTime = System.currentTimeMillis()
      var intermediate = startTime
      listOfTxs.zipWithIndex.foreach { case (tx, idx) =>
        nodeViewHolder.txModify(tx.asInstanceOf[SidechainTypes#SCAT])
        if ((idx + 1) % numOfTxsPerSnapshot == 0) {
          val updateTime = System.currentTimeMillis()
          listOfSnapshots += (updateTime - intermediate)
          intermediate = updateTime
        }
      }
      var totalTime = System.currentTimeMillis() - startTime
      assertEquals(numOfTxs, mempool.size)

      out.write(s"\n********************* Direct order test results *********************\n")

      println(s"Total time $totalTime ms")
      var timePerTx: Float = totalTime.toFloat / numOfTxs
      println(s"Average time per transaction ${timePerTx} ms")
      println(
        s"Average time per transactions in Snapshots ${listOfSnapshots.map(res => res.toFloat / numOfTxsPerSnapshot).mkString(",")} "
      )
      out.write(s"Duration of the test:                      $totalTime ms\n")
      out.write(s"Average time per transaction:             ${timePerTx} ms\n")
      out.write(s"Average time per transaction in snapshots:\n")
      listOfSnapshots.map(res => res.toFloat / numOfTxsPerSnapshot).zipWithIndex.foreach { case (res, idx) =>
        out.write(s"Snapshot $idx: $res ms\n")
      }
      out.write(s"Number of transactions per snapshot: $numOfTxsPerSnapshot\n")

      println("Starting test reverse order")
      // Resetting MemPool
      mempool = AccountMemoryPool.createEmptyMempool(state)

      val reverseList = listOfTxs.reverse
      listOfSnapshots = new scala.collection.mutable.ListBuffer[Long]()
      startTime = System.currentTimeMillis()
      intermediate = startTime
      reverseList.zipWithIndex.foreach { case (tx, idx) =>
        nodeViewHolder.txModify(tx.asInstanceOf[SidechainTypes#SCAT])
        if ((idx + 1) % numOfTxsPerSnapshot == 0) {
          val updateTime = System.currentTimeMillis()
          listOfSnapshots += (updateTime - intermediate)
          intermediate = updateTime
        }
      }
      totalTime = System.currentTimeMillis() - startTime
      assertEquals(numOfTxs, mempool.size)
      println(s"Total time $totalTime ms")
      timePerTx = totalTime.toFloat / numOfTxs
      println(s"Time per transactions ${timePerTx} ms")
      println(
        s"Average time per transactions in Snapshots ${listOfSnapshots.map(res => res.toFloat / numOfTxsPerSnapshot)} "
      )
      out.write(s"\n********************* Reverse order test results *********************\n")
      out.write(s"Duration of the test:                      $totalTime ms\n")
      out.write(s"Average time per transaction:             ${timePerTx} ms\n")
      out.write(s"Average time per transaction in snapshots:\n")
      listOfSnapshots.map(res => res.toFloat / numOfTxsPerSnapshot).zipWithIndex.foreach { case (res, idx) =>
        out.write(s"Snapshot $idx: $res ms\n")
      }
      out.write(s"Number of transactions per snapshot: $numOfTxsPerSnapshot\n\n\n")

    } finally {
      out.close()
    }
  }

  @Test
  def updateMemPoolTest(): Unit = {
    val out = new BufferedWriter(new FileWriter("log/updateMemPoolTest.txt", true))

    val cal = Calendar.getInstance()
    try {
      out.write("*********************************************************************\n\n")
      out.write("*                Updating Memory Pool performance test               \n\n")
      out.write("*********************************************************************\n\n")

      out.write(s"Date and time of the test: ${cal.getTime}\n\n")

      val nodeViewHolder = getMockedAccountSidechainNodeViewHolder

      val numOfTxs = 10000
      val numOfTxsPerSpammerAccounts = 100
      val numOfTxsPerNormalAccounts = 5
      val normalSpammerRatio = 20
      val numOfSpammerAccount = numOfTxs / (numOfTxsPerSpammerAccounts + normalSpammerRatio * numOfTxsPerNormalAccounts)
      val numOfNormalAccount = normalSpammerRatio * numOfSpammerAccount
      val numOfTxsInBlock = 1400

      out.write(s"Total number of transactions:                    $numOfTxs\n")
      out.write(s"Number of spammer accounts:                      $numOfSpammerAccount\n")
      out.write(s"Number of transactions for each spammer account: $numOfTxsPerSpammerAccounts\n")
      out.write(s"Number of normal accounts:                       $numOfNormalAccount\n")
      out.write(s"Number of transactions for each normal account:  $numOfTxsPerNormalAccounts\n")
      out.write(s"Number of transactions for each block:           $numOfTxsInBlock\n")

      assertTrue(
        "Invalid test parameters",
        numOfTxs % (numOfTxsPerSpammerAccounts + normalSpammerRatio * numOfTxsPerNormalAccounts) == 0
      )
      println("************** Testing with one block to apply")

      val listOfNormalTxs = createTransactions(numOfNormalAccount, numOfTxsPerNormalAccounts, orphanIdx = 2)

      val listOfSpammerTxs = createTransactions(numOfNormalAccount, numOfTxsPerNormalAccounts, orphanIdx = 75)

      val listOfTxs = listOfSpammerTxs ++ listOfNormalTxs
      listOfTxs.foreach(tx => nodeViewHolder.txModify(tx.asInstanceOf[SidechainTypes#SCAT]))
      assertEquals(numOfTxs, mempool.size)

      val appliedBlock: AccountBlock = mock[AccountBlock]
      val listOfTxsInBlock =
        (listOfSpammerTxs.take(numOfSpammerAccount) ++ listOfNormalTxs.take(
          numOfTxsInBlock - numOfSpammerAccount
        )).toSeq
      Mockito.when(appliedBlock.transactions).thenReturn(listOfTxsInBlock.asInstanceOf[Seq[SidechainTypes#SCAT]])
      // Update the nonces
      listOfTxsInBlock.foreach(tx =>
        nonces.put(new ByteArrayWrapper(tx.getFrom.address()), tx.getNonce.add(BigInteger.ONE))
      )

      println("Starting test")
      val startTime = System.currentTimeMillis()
      val newMemPool = nodeViewHolder.updateMemPool(Seq(), Seq(appliedBlock), mempool, state)
      val updateTime = System.currentTimeMillis() - startTime
      assertEquals(numOfTxs - numOfTxsInBlock, newMemPool.size)
      println(s"total time $updateTime ms")
      out.write(s"\n********************* Testing with one block to apply results *********************\n")
      out.write(s"Duration of the test:                      $updateTime ms\n")

      println("************** Testing with one rollback block and one to apply")
      mempool = newMemPool
      val rollBackBlock = appliedBlock
      // restore the mempool so its size is again numOfTxs
      val additionalTxs = createTransactions(numOfTxsInBlock, 1, -1)
      additionalTxs.foreach(tx => nodeViewHolder.txModify(tx.asInstanceOf[SidechainTypes#SCAT]))
      assertEquals(numOfTxs, mempool.size)

      // the block to applied will have 1000 txs of the rolledBack block and 400 from the additionalTxs
      val appliedBlock2: AccountBlock = mock[AccountBlock]
      val listOfTxsInBlock2 = listOfTxsInBlock.take(1000) ++ additionalTxs.take(400)
      Mockito.when(appliedBlock2.transactions).thenReturn(listOfTxsInBlock2.asInstanceOf[Seq[SidechainTypes#SCAT]])

      // Update the nonces
      // First reapplying the txs in the rollBackBlock, then the ones in appliedBlock2
      listOfTxsInBlock.foreach(tx => nonces.put(new ByteArrayWrapper(tx.getFrom.address()), tx.getNonce))
      listOfTxsInBlock2.foreach(tx =>
        nonces.put(new ByteArrayWrapper(tx.getFrom.address()), tx.getNonce.add(BigInteger.ONE))
      )
      println("Starting test")
      val startTime2 = System.currentTimeMillis()
      val newMemPool2 = nodeViewHolder.updateMemPool(Seq(rollBackBlock), Seq(appliedBlock2), mempool, state)
      val updateTime2 = System.currentTimeMillis() - startTime2
      assertEquals(numOfTxs, newMemPool2.size)
      println(s"total time $updateTime2 ms")
      out.write(s"\n********************* Testing rollback block and one to apply results *********************\n")
      out.write(s"Duration of the test:                      $updateTime2 ms\n")

    } finally {
      out.close()
    }
  }

  @Test
  // @Ignore
  def takeTest(): Unit = {
    val out = new BufferedWriter(new FileWriter("log/takeTest.txt", true))

    val cal = Calendar.getInstance()
    try {
      out.write("*********************************************************************\n\n")
      out.write("*        Ordering executable transactions performance test          *\n\n")
      out.write("*********************************************************************\n\n")

      out.write(s"Date and time of the test: ${cal.getTime}\n\n")

      val nodeViewHolder = getMockedAccountSidechainNodeViewHolder

      val numOfTxs = 100000
      val numOfTxsPerAccount = 5
      val numOfAccounts = numOfTxs / numOfTxsPerAccount
      assertTrue("Invalid test parameters", numOfTxs % (numOfTxsPerAccount) == 0)
      out.write(s"Total number of transactions:            $numOfTxs\n")
      out.write(s"Number of accounts:                      $numOfAccounts\n")
      out.write(s"Number of transactions for each account: $numOfTxsPerAccount\n")

      println(s"************** Test ordering executable transactions with $numOfTxs in mem pool **************")

      val listOfTxs = createTransactions(numOfAccounts, numOfTxsPerAccount)

      listOfTxs.foreach { tx =>
        nodeViewHolder.txModify(tx.asInstanceOf[SidechainTypes#SCAT])
      }

      println("Starting test")

      val startTime = System.currentTimeMillis()
      val executablesTxs = mempool.take(mempool.size)
      val totalTime = System.currentTimeMillis() - startTime
      assertEquals(numOfTxs, executablesTxs.size)

      println(s"Total time $totalTime ms")
      out.write(s"\n********************* Test results *********************\n")
      out.write(s"Duration of the test:                      $totalTime ms\n")
    } finally {
      out.close()
    }

  }

  def createTransactions(
      numOfAccount: Int,
      numOfTxsPerAccount: Int,
      orphanIdx: Int = -1
  ): scala.collection.mutable.ListBuffer[EthereumTransaction] = {
    val toAddr = "0x00112233445566778899AABBCCDDEEFF01020304"
    val value = BigInteger.valueOf(12)

    val baseGas = 10000
    val maxGasFee = BigInteger.valueOf(baseGas + numOfAccount * numOfTxsPerAccount)
    val listOfAccounts: scala.collection.mutable.ListBuffer[Option[ECKeyPair]] =
      new scala.collection.mutable.ListBuffer[Option[ECKeyPair]]
    val listOfTxs = new scala.collection.mutable.ListBuffer[EthereumTransaction]

    val gasBuilder = new CircularPriorityGasBuilder(baseGas, 17)

    (1 to numOfAccount).foreach(_ => {
      listOfAccounts += Some(Keys.createEcKeyPair())
    })

    (0 until numOfTxsPerAccount).foreach(nonceTx => {
      val currentNonce = BigInteger.valueOf(nonceTx)

      listOfAccounts.zipWithIndex.foreach {
        case (pair, idx) => {
          if (idx % 10 == 0 && orphanIdx >= 0 && nonceTx >= orphanIdx) { // Create orphans
            listOfTxs += createEIP1559Transaction(
              value,
              nonce = BigInteger.valueOf(nonceTx + 1),
              pairOpt = pair,
              gasFee = maxGasFee,
              priorityGasFee = gasBuilder.nextPriorityGas(),
              to = toAddr
            )
          } else
            listOfTxs += createEIP1559Transaction(
              value,
              nonce = currentNonce,
              pairOpt = pair,
              gasFee = maxGasFee,
              priorityGasFee = gasBuilder.nextPriorityGas(),
              to = toAddr
            )
        }
      }
    })
    listOfTxs
  }

  class CircularPriorityGasBuilder(baseGas: Int, period: Int) {
    var counter: Int = 0

    def nextPriorityGas(): BigInteger = {
      if (counter == period) {
        counter = 0
      }
      val gas = baseGas + counter
      counter = counter + 1
      BigInteger.valueOf(gas)
    }
  }

  class MockedAccountSidechainNodeViewHolder(
      sidechainSettings: SidechainSettings,
      params: NetworkParams,
      timeProvider: NetworkTimeProvider,
      historyStorage: AccountHistoryStorage,
      consensusDataStorage: ConsensusDataStorage,
      stateMetadataStorage: AccountStateMetadataStorage,
      stateDbStorage: Database,
      customMessageProcessors: Seq[MessageProcessor],
      secretStorage: SidechainSecretStorage,
      genesisBlock: AccountBlock
  ) extends AccountSidechainNodeViewHolder(
        sidechainSettings,
        params,
        timeProvider,
        historyStorage,
        consensusDataStorage,
        stateMetadataStorage,
        stateDbStorage,
        customMessageProcessors,
        secretStorage,
        genesisBlock
      ) {
    override def txModify(tx: SidechainTypes#SCAT): Unit = super.txModify(tx)

    override def minimalState(): AccountState = state

    override def history(): AccountHistory = historyMock

    override def vault(): AccountWallet = wallet

    override def memoryPool(): AccountMemoryPool = mempool

    override protected def genesisState: (HIS, MS, VL, MP) = (history, state, wallet, mempool)

    override def updateMemPool(
        blocksRemoved: Seq[AccountBlock],
        blocksApplied: Seq[AccountBlock],
        memPool: AccountMemoryPool,
        state: AccountState
    ): AccountMemoryPool = super.updateMemPool(blocksRemoved, blocksApplied, memPool, state)

  }

  def getMockedAccountSidechainNodeViewHolder()(implicit
      actorSystem: ActorSystem
  ): MockedAccountSidechainNodeViewHolder = {
    val sidechainSettings = mock[SidechainSettings]
    val mockWalletSettings: WalletSettings = mock[WalletSettings]
    Mockito.when(mockWalletSettings.maxTxFee).thenReturn(100L)
    Mockito.when(sidechainSettings.wallet).thenReturn(mockWalletSettings)
    val params: NetworkParams = mock[NetworkParams]
    val timeProvider: NetworkTimeProvider = mock[NetworkTimeProvider]

    val historyStorage: AccountHistoryStorage = mock[AccountHistoryStorage]
    val consensusDataStorage: ConsensusDataStorage = mock[ConsensusDataStorage]
    val stateMetadataStorage: AccountStateMetadataStorage = mock[AccountStateMetadataStorage]
    Mockito.when(stateMetadataStorage.isEmpty).thenReturn(true)
    val stateDbStorage: Database = mock[Database]
    val customMessageProcessors: Seq[MessageProcessor] = Seq()
    val secretStorage: SidechainSecretStorage = mock[SidechainSecretStorage]
    val genesisBlock: AccountBlock = mock[AccountBlock]

    val versionTag: VersionTag = VersionTag @@ BytesUtils.toHexString(getVersion.data())

    state = new AccountState(params, timeProvider, versionTag, stateMetadataStorage, stateDbStorage, Seq()) {
      override def getView: AccountStateView = stateViewMock
    }

    mempool = AccountMemoryPool.createEmptyMempool(state)

    val nodeViewHolderRef: TestActorRef[MockedAccountSidechainNodeViewHolder] = TestActorRef(
      Props(
        new MockedAccountSidechainNodeViewHolder(
          sidechainSettings,
          params,
          timeProvider,
          historyStorage,
          consensusDataStorage,
          stateMetadataStorage,
          stateDbStorage,
          customMessageProcessors,
          secretStorage,
          genesisBlock
        )
      )
    )
    nodeViewHolderRef.underlyingActor
  }

}
