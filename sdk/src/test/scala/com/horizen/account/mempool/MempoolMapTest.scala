package com.horizen.account.mempool

import com.horizen.SidechainTypes
import com.horizen.account.fixtures.EthereumTransactionFixture
import com.horizen.account.transaction.EthereumTransaction
import org.junit.Assert._
import org.junit._
import org.scalatestplus.junit.JUnitSuite
import org.scalatestplus.mockito._
import org.web3j.crypto.Keys

import java.math.BigInteger
import scala.util.Random

class MempoolMapTest
  extends JUnitSuite
    with EthereumTransactionFixture
    with SidechainTypes
    with MockitoSugar {

  @Before
  def setUp(): Unit = {
  }


  @Test
  def testAddExecutableTx(): Unit = {
    val account1KeyPair = Keys.createEcKeyPair

    val mempoolMap = MempoolMap()
    val account1InitialStateNonce = BigInteger.ZERO
    val value = BigInteger.TEN

    val account1ExecTransaction0 = createEIP1559Transaction(value, account1InitialStateNonce, Option(account1KeyPair))
    assertFalse("Empty Mempool contains tx", mempoolMap.contains(account1ExecTransaction0))
    assertFalse("Empty Mempool contains tx account info", mempoolMap.containsAccountInfo(account1ExecTransaction0.getFrom))

    assertTrue("It should not be possible adding a tx to a not initialized account", mempoolMap.add(account1ExecTransaction0).isFailure)

    mempoolMap.initializeAccount(account1InitialStateNonce, account1ExecTransaction0.getFrom)

    assertFalse("Empty Mempool contains tx", mempoolMap.contains(account1ExecTransaction0))
    assertTrue("Initialized Mempool doesn't contain tx account info", mempoolMap.containsAccountInfo(account1ExecTransaction0.getFrom))

    var res = mempoolMap.add(account1ExecTransaction0)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 1, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1ExecTransaction0))
    assertEquals("Wrong number of executable transactions", 1, res.get.executableTxs(account1ExecTransaction0.getFrom).size)
    assertEquals("Added transaction is not executable", account1ExecTransaction0.id(), res.get.executableTxs(account1ExecTransaction0.getFrom)(account1ExecTransaction0.getNonce))
    assertTrue("Non executable transactions map is not empty", !res.get.nonExecutableTxs.contains(account1ExecTransaction0.getFrom))

    res = mempoolMap.add(account1ExecTransaction0)
    assertTrue("Adding twice the same tx should not fail", res.isSuccess)
    assertEquals("Wrong number of total transactions", 1, res.get.all.size)


    val account1ExecTransaction1 = createEIP1559Transaction(value, account1InitialStateNonce.add(BigInteger.ONE), Option(account1KeyPair))
    res = mempoolMap.add(account1ExecTransaction1)
    assertTrue("Adding second transaction to same account failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 2, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1ExecTransaction1))
    val account1ExecTxMap = res.get.executableTxs(account1ExecTransaction1.getFrom)
    assertEquals("Wrong number of executable transactions", 2, account1ExecTxMap.size)
    assertEquals("Added transaction is not executable", account1ExecTransaction1.id(), account1ExecTxMap(account1ExecTransaction1.getNonce))
    assertTrue("Non executable transactions map is not empty", !res.get.nonExecutableTxs.contains(account1ExecTransaction1.getFrom))


    val account2KeyPair = Keys.createEcKeyPair
    val account2InitialStateNonce = BigInteger.valueOf(4576)
    val account2ExecTransaction0 = createEIP1559Transaction(value, account2InitialStateNonce, Option(account2KeyPair))

    mempoolMap.initializeAccount(account2InitialStateNonce, account2ExecTransaction0.getFrom)
    res = mempoolMap.add(account2ExecTransaction0)
    assertTrue("Adding transaction to account 2 failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 3, res.get.all.size)
    assertTrue("Mempool doesn't contain account 2 transaction", res.get.contains(account2ExecTransaction0))
    assertEquals("Wrong number of accounts", 2, res.get.executableTxs.size)
    assertEquals("Wrong number of executable transactions", 1, res.get.executableTxs(account2ExecTransaction0.getFrom).size)
    assertEquals("Added transaction is not executable", account2ExecTransaction0.id(), res.get.executableTxs(account2ExecTransaction0.getFrom)(account2ExecTransaction0.getNonce))
    assertTrue("Non executable transactions map is not empty", !res.get.nonExecutableTxs.contains(account2ExecTransaction0.getFrom))

  }

  @Test
  def testAddNonExecutableTx(): Unit = {
    val account1KeyPair = Keys.createEcKeyPair

    val mempoolMap = MempoolMap()
    val account1InitialStateNonce = BigInteger.ZERO
    val value = BigInteger.TEN

    val account1NonExecTransaction0 = createEIP1559Transaction(value, BigInteger.TWO, Option(account1KeyPair))
    assertFalse("Empty Mempool contains tx", mempoolMap.contains(account1NonExecTransaction0))
    assertFalse("Empty Mempool contains tx account info", mempoolMap.containsAccountInfo(account1NonExecTransaction0.getFrom))

    assertTrue("It should not be possible adding a tx to a not initialized account", mempoolMap.add(account1NonExecTransaction0).isFailure)

    mempoolMap.initializeAccount(account1InitialStateNonce, account1NonExecTransaction0.getFrom)

    assertFalse("Empty Mempool contains tx", mempoolMap.contains(account1NonExecTransaction0))
    assertTrue("Initialized Mempool doesn't contain tx account info", mempoolMap.containsAccountInfo(account1NonExecTransaction0.getFrom))

    var res = mempoolMap.add(account1NonExecTransaction0)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 1, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1NonExecTransaction0))
    assertEquals("Wrong number of non executable transactions", 1, res.get.nonExecutableTxs(account1NonExecTransaction0.getFrom).size)
    assertEquals("Added transaction is executable", account1NonExecTransaction0.id(), res.get.nonExecutableTxs(account1NonExecTransaction0.getFrom)(account1NonExecTransaction0.getNonce))
    assertTrue("Executable transactions map is not empty", !res.get.executableTxs.contains(account1NonExecTransaction0.getFrom))

    res = mempoolMap.add(account1NonExecTransaction0)
    assertTrue("Adding twice the same tx should not fail", res.isSuccess)

    val account1NonExecTransaction1 = createEIP1559Transaction(value, BigInteger.ONE, Option(account1KeyPair))
    res = mempoolMap.add(account1NonExecTransaction1)
    assertTrue("Adding second transaction to same account failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 2, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1NonExecTransaction1))
    assertEquals("Wrong number of non executable transactions", 2, res.get.nonExecutableTxs(account1NonExecTransaction1.getFrom).size)
    assertEquals("Added transaction is executable", account1NonExecTransaction1.id(), res.get.nonExecutableTxs(account1NonExecTransaction1.getFrom)(account1NonExecTransaction1.getNonce))
    assertTrue("Executable transactions map is not empty", !res.get.executableTxs.contains(account1NonExecTransaction1.getFrom))

    val account1ExecTransaction0 = createEIP1559Transaction(value, account1InitialStateNonce, Option(account1KeyPair))
    res = mempoolMap.add(account1ExecTransaction0)
    assertTrue("Adding third transaction to same account failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 3, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1ExecTransaction0))
    assertTrue("Non executable transactions map is not empty", !res.get.nonExecutableTxs.contains(account1ExecTransaction0.getFrom))
    assertEquals("Wrong number of executable transactions", 3, res.get.executableTxs(account1ExecTransaction0.getFrom).size)

    val orderedListOfTxs = res.get.executableTxs(account1ExecTransaction0.getFrom).values.toList
    assertEquals("Wrong first tx", account1ExecTransaction0.id(), orderedListOfTxs.head)
    assertEquals("Wrong second tx", account1NonExecTransaction1.id(), orderedListOfTxs(1))
    assertEquals("Wrong third tx", account1NonExecTransaction0.id(), orderedListOfTxs(2))

    val account1ExecTransaction1 = createEIP1559Transaction(value, account1NonExecTransaction0.getNonce.add(BigInteger.ONE), Option(account1KeyPair))
    res = mempoolMap.add(account1ExecTransaction1)
    assertTrue("Adding third transaction to same account failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 4, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1ExecTransaction1))
    assertTrue("Non executable transactions map is not empty", !res.get.nonExecutableTxs.contains(account1ExecTransaction0.getFrom))
    assertEquals("Wrong number of executable transactions", 4, res.get.executableTxs(account1ExecTransaction1.getFrom).size)

    val account1NonExecTransaction2 = createEIP1559Transaction(value, account1NonExecTransaction0.getNonce.add(BigInteger.TEN), Option(account1KeyPair))
    res = mempoolMap.add(account1NonExecTransaction2)
    assertTrue("Adding transaction to same account failed", res.isSuccess)
    assertEquals("Wrong number of total transactions", 5, res.get.all.size)
    assertTrue("Mempool doesn't contain transaction", res.get.contains(account1NonExecTransaction2))
    assertEquals("Wrong number of non executable transactions", 1, res.get.nonExecutableTxs(account1NonExecTransaction2.getFrom).size)
    assertEquals("Added transaction is executable", account1NonExecTransaction2.id(), res.get.nonExecutableTxs(account1NonExecTransaction2.getFrom)(account1NonExecTransaction2.getNonce))
    assertEquals("Wrong number of executable transactions", 4, res.get.executableTxs(account1NonExecTransaction2.getFrom).size)

  }

  @Test
  def testAddSameNonce(): Unit = {
    val account1KeyPair = Keys.createEcKeyPair

    val mempoolMap = MempoolMap()
    val account1InitialStateNonce = BigInteger.ZERO
    val value = BigInteger.TEN

    val nonExecNonce = BigInteger.TEN
    val nonExecGasFeeCap = BigInteger.valueOf(100)
    val nonExecGasTipCap = BigInteger.valueOf(80)
    val account1NonExecTransaction0 = createEIP1559Transaction(value, nonExecNonce, Option(account1KeyPair), nonExecGasFeeCap, nonExecGasTipCap)
    mempoolMap.initializeAccount(account1InitialStateNonce, account1NonExecTransaction0.getFrom)
    var res = mempoolMap.add(account1NonExecTransaction0)
    assertTrue("Adding transaction failed", res.isSuccess)

    //Create some additional non exec txs
    (1 to 5).foreach(_ => {
      val nonce = BigInteger.valueOf(Random.nextInt(10000) + nonExecNonce.intValue() + 1)
      val tx = createEIP1559Transaction(value, nonce, Option(account1KeyPair))
      val res = mempoolMap.add(tx)
      assertTrue("Adding transaction failed", res.isSuccess)
    }
    )


    val account1NonExecTransactionSameNonceLowerFee = createEIP1559Transaction(BigInteger.valueOf(123), nonExecNonce, Option(account1KeyPair), BigInteger.ONE, BigInteger.ONE)
    res = mempoolMap.add(account1NonExecTransactionSameNonceLowerFee)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertFalse("Mempool contains transaction with lower gas fee", res.get.contains(account1NonExecTransactionSameNonceLowerFee))

    val account1NonExecTransactionSameNonceSameFee = createEIP1559Transaction(BigInteger.valueOf(123), nonExecNonce, Option(account1KeyPair), account1NonExecTransaction0.getGasPrice, BigInteger.ONE)
    res = mempoolMap.add(account1NonExecTransactionSameNonceSameFee)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertFalse("Mempool contains transaction with same gas fee", res.get.contains(account1NonExecTransactionSameNonceSameFee))

    val higherFee = account1NonExecTransaction0.getGasPrice.add(BigInteger.ONE)
    val account1NonExecTransactionSameNonceHigherFee = createEIP1559Transaction(BigInteger.valueOf(123), nonExecNonce, Option(account1KeyPair), higherFee, higherFee)
    res = mempoolMap.add(account1NonExecTransactionSameNonceHigherFee)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertTrue("Mempool doesn't contain transaction with higher gas fee", res.get.contains(account1NonExecTransactionSameNonceHigherFee))
    assertFalse("Mempool still contains old transaction with lower gas fee", res.get.contains(account1NonExecTransaction0))

    val account1ExecTransaction0 = createEIP1559Transaction(value, account1InitialStateNonce, Option(account1KeyPair), BigInteger.valueOf(100), BigInteger.valueOf(80))
    res = mempoolMap.add(account1ExecTransaction0)
    assertTrue("Adding transaction failed", res.isSuccess)
    //Create some additional exec txs
    (1 to 5).foreach(i => {
      val nonce = account1InitialStateNonce.add(BigInteger.valueOf(i))
      val tx = createEIP1559Transaction(value, nonce, Option(account1KeyPair))
      val res = mempoolMap.add(tx)
      assertTrue("Adding transaction failed", res.isSuccess)
    }
    )

    val account1ExecTransactionSameNonceLowerFee = createEIP1559Transaction(BigInteger.valueOf(123), account1InitialStateNonce, Option(account1KeyPair), BigInteger.ONE, BigInteger.ONE)
    res = mempoolMap.add(account1ExecTransactionSameNonceLowerFee)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertFalse("Mempool contains exec transaction with lower gas fee", res.get.contains(account1ExecTransactionSameNonceLowerFee))

    val account1ExecTransactionSameNonceSameFee = createEIP1559Transaction(BigInteger.valueOf(123), account1InitialStateNonce, Option(account1KeyPair), account1ExecTransaction0.getGasPrice, account1ExecTransaction0.getGasPrice)
    res = mempoolMap.add(account1ExecTransactionSameNonceSameFee)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertFalse("Mempool contains transaction with same gas fee", res.get.contains(account1ExecTransactionSameNonceSameFee))

    val execTxHigherFee = account1ExecTransaction0.getGasPrice.add(BigInteger.ONE)
    val account1ExecTransactionSameNonceHigherFee = createEIP1559Transaction(BigInteger.valueOf(123), account1InitialStateNonce, Option(account1KeyPair), execTxHigherFee, execTxHigherFee)
    res = mempoolMap.add(account1ExecTransactionSameNonceHigherFee)
    assertTrue("Adding transaction failed", res.isSuccess)
    assertTrue("Mempool doesn't contain transaction with higher gas fee", res.get.contains(account1ExecTransactionSameNonceHigherFee))
    assertFalse("Mempool still contains old transaction with lower gas fee", res.get.contains(account1ExecTransaction0))
  }

  @Test
  def testRemove(): Unit = {
    val account1KeyPair = Keys.createEcKeyPair

    val mempoolMap = MempoolMap()
    val account1InitialStateNonce = BigInteger.ZERO
    val value = BigInteger.TEN
    val account1NonExecTransaction0 = createEIP1559Transaction(value, BigInteger.TWO, Option(account1KeyPair))

    var res = mempoolMap.remove(account1NonExecTransaction0)
    assertTrue("Removing a transaction from not existing account failed", res.isSuccess)

    mempoolMap.initializeAccount(account1InitialStateNonce, account1NonExecTransaction0.getFrom)

    res = mempoolMap.remove(account1NonExecTransaction0)
    assertTrue("Removing a not existing transaction failed", res.isSuccess)

    mempoolMap.add(account1NonExecTransaction0)
    res = mempoolMap.remove(account1NonExecTransaction0)
    assertTrue("Removing transaction failed", res.isSuccess)
    assertFalse("Transaction is still in the mempool", mempoolMap.contains(account1NonExecTransaction0))
    assertTrue("Non executable transactions map is not empty", !res.get.nonExecutableTxs.contains(account1NonExecTransaction0.getFrom))
    assertFalse("Mempool still contains account info", res.get.containsAccountInfo(account1NonExecTransaction0.getFrom))

    val account1ExecTransaction0 = createEIP1559Transaction(value, account1InitialStateNonce, Option(account1KeyPair))

    res = mempoolMap.remove(account1ExecTransaction0)
    assertTrue("Removing a transaction from not existing account failed", res.isSuccess)

    mempoolMap.initializeAccount(account1InitialStateNonce, account1ExecTransaction0.getFrom)

    mempoolMap.add(account1ExecTransaction0)
    res = mempoolMap.remove(account1ExecTransaction0)
    assertTrue("Removing transaction failed", res.isSuccess)
    assertFalse("Transaction is still in the mempool", mempoolMap.contains(account1ExecTransaction0))
    assertTrue("Executable transactions map is not empty", !res.get.executableTxs.contains(account1ExecTransaction0.getFrom))
    assertFalse("Mempool still contains account info", res.get.containsAccountInfo(account1ExecTransaction0.getFrom))


    mempoolMap.initializeAccount(account1InitialStateNonce, account1ExecTransaction0.getFrom)

    //Create some additional exec txs
    var txToRemove: EthereumTransaction = null
    (0 to 5).foreach(i => {
      val nonce = account1InitialStateNonce.add(BigInteger.valueOf(i))
      val tx = createEIP1559Transaction(value, nonce, Option(account1KeyPair))
      if (i == 3)
        txToRemove = tx
      val res = mempoolMap.add(tx)
      assertTrue("Adding transaction failed", res.isSuccess)
    }
    )

    val account1NonExecTransaction1 = createEIP1559Transaction(value, BigInteger.valueOf(1000), Option(account1KeyPair))
    res = mempoolMap.remove(account1NonExecTransaction1)
    assertTrue("Removing a transaction from not existing account failed", res.isSuccess)

    res = mempoolMap.remove(txToRemove)
    assertTrue(s"Removing transaction failed ${res}", res.isSuccess)
    assertFalse("Transaction is still in the mempool", mempoolMap.contains(txToRemove))
    assertEquals("Wrong number of executable transactions", 3, res.get.executableTxs(account1NonExecTransaction1.getFrom).size)
    assertEquals("Wrong number of non executable transactions", 2, res.get.nonExecutableTxs(account1NonExecTransaction1.getFrom).size)
    assertEquals("Wrong nonce", txToRemove.getNonce, res.get.nonces(account1NonExecTransaction1.getFrom))


  }

}
