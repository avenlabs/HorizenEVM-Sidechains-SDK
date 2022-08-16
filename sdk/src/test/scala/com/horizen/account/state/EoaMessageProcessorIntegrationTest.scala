package com.horizen.account.state

import com.horizen.account.proposition.AddressProposition
import com.horizen.fixtures.SecretFixture
import com.horizen.utils.ClosableResourceHandler
import org.junit.Assert.{assertArrayEquals, assertEquals, assertFalse, assertTrue}
import org.junit.Test
import org.scalatestplus.junit.JUnitSuite
import org.scalatestplus.mockito.MockitoSugar
import scorex.crypto.hash.Keccak256

import java.math.BigInteger

class EoaMessageProcessorIntegrationTest
  extends JUnitSuite
    with MockitoSugar
    with SecretFixture
    with MessageProcessorFixture
    with ClosableResourceHandler {

  @Test
  def canProcess(): Unit = {
    val toAddress: AddressProposition = getAddressProposition(12345L)
    val value: java.math.BigInteger = java.math.BigInteger.TWO
    val emptyData: Array[Byte] = Array.emptyByteArray
    val msg: Message = getMessage(toAddress, value, emptyData)

    usingView { stateView =>

      // Test 1: to account doesn't exist, so considered as EOA
      assertTrue("Processor expected to BE ABLE to process message", EoaMessageProcessor.canProcess(msg, stateView))

      // Test 2: to account exists and has NO code hash defined, so considered as EOA
      // declare account with some coins
      stateView.addBalance(toAddress.address(), BigInteger.ONE)
      assertTrue("Processor expected to BE ABLE to process message", EoaMessageProcessor.canProcess(msg, stateView))

      // Test 3: to account exists and has code hash defined, so considered as Smart contract account
      val codeHash: Array[Byte] = Keccak256.hash("abcd".getBytes())
      stateView.addAccount(toAddress.address(), codeHash)
      assertFalse("Processor expected to UNABLE to process message", EoaMessageProcessor.canProcess(msg, stateView))

      // Test 4: "to" is null -> smart contract declaration case
      val data: Array[Byte] = new Array[Byte](100)
      val contractDeclarationMessage = getMessage(toAddress, value, data)
      assertFalse("Processor expected to UNABLE to process message", EoaMessageProcessor.canProcess(contractDeclarationMessage, stateView))
    }

  }

  @Test
  def process(): Unit = {
    val toAddress: AddressProposition = getAddressProposition(12345L)
    val value: java.math.BigInteger = java.math.BigInteger.TWO
    val emptyData: Array[Byte] = Array.emptyByteArray
    val msg: Message = getMessage(toAddress, value, emptyData)

    usingView { stateView =>
      val fromInitialValue: BigInteger = msg.getValue.multiply(BigInteger.TEN)
      stateView.addBalance(msg.getFrom.address(), fromInitialValue)

      val returnData = EoaMessageProcessor.process(msg, stateView)
      assertArrayEquals("Different return data found", Array.emptyByteArray, returnData)

      assertEquals("Different from account value found", fromInitialValue.subtract(msg.getValue), stateView.getBalance(msg.getFrom.address()))
      assertEquals("Different to account value found", msg.getValue, stateView.getBalance(msg.getTo.address()))
    }
  }
}
