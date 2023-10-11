package io.horizen.account.state

import io.horizen.account.abi.ABIUtil.getABIMethodId
import io.horizen.account.utils.BigIntegerUtil.toUint256Bytes
import io.horizen.account.utils.{FeeUtils, Secp256k1}
import io.horizen.evm._
import io.horizen.utils.BytesUtils
import org.junit.{After, Before}

import java.math.BigInteger

abstract class ContractInteropTestBase extends MessageProcessorFixture {
  val initialBalance = new BigInteger("2000000000000")

  // note: the gas limit has to be ridiculously high for some tests to reach the maximum call depth of 1024 because of
  // the 63/64 rule when passing gas to a nested call:
  // - The remaining fraction of gas at depth 1024 is: (63/64)^1024
  // - The lower limit to have 10k gas available at depth 1024 is: 10k / (63/64)^1024 = ~100 billion
  // - Some gas is consumed along the way, so we give x10: 1 trillion
  val gasLimit: BigInteger = BigInteger.TEN.pow(12)

  val blockContext =
    new BlockContext(Address.ZERO, 0, FeeUtils.INITIAL_BASE_FEE, gasLimit, 1, 1, 1, 1234, null, Hash.ZERO)

  /**
   * Derived tests have to supply a native contract to the test setup.
   */
  val processorToTest: NativeSmartContractMsgProcessor

  private var processors: Seq[MessageProcessor] = _
  private var db: Database = _
  protected var stateView: AccountStateView = _

  @Before
  def setup(): Unit = {
    processors = Seq(
      processorToTest,
      new EvmMessageProcessor()
    )
    db = new MemoryDatabase()
    stateView = new AccountStateView(metadataStorageView, new StateDB(db, Hash.ZERO), processors)
    stateView.addBalance(origin, initialBalance)
    processorToTest.init(stateView)
  }

  @After
  def cleanup(): Unit = {
    stateView.close()
    db.close()
    blockContext.removeTracer()
  }

  protected def deploy(evmContractCode: Array[Byte]): Address = {
    val nonce = stateView.getNonce(origin)
    // deploy the NativeInterop contract (EVM based)
    transition(getMessage(null, data = evmContractCode))
    // get deployed contract address
    Secp256k1.generateContractAddress(origin, nonce)
  }

  protected def transition(msg: Message): Array[Byte] = {
    val transition = new StateTransition(stateView, processors, new GasPool(gasLimit), blockContext, msg)
    transition.execute(Invocation.fromMessage(msg, new GasPool(gasLimit)))
  }
}

object ContractInteropTestBase {
  // compiled EVM byte-code of NativeInterop contract
  // source: libevm/native/test/NativeInterop.sol
  def nativeInteropContractCode: Array[Byte] = BytesUtils.fromHexString(
    "6080604052600080546001600160a01b031916692222222222222222222217905534801561002c57600080fd5b506107048061003c6000396000f3fe60806040526004361061004e5760003560e01c806367a7dbb41461005a5780637d286e4814610071578063b63fc52914610084578063cb14b856146100af578063e08b6262146100c457600080fd5b3661005557005b600080fd5b34801561006657600080fd5b5061006f6100f9565b005b61006f61007f3660046103d4565b61019e565b34801561009057600080fd5b50610099610245565b6040516100a691906103f8565b60405180910390f35b3480156100bb57600080fd5b5061006f6102c9565b3480156100d057600080fd5b506100e46100df366004610495565b61031e565b60405163ffffffff90911681526020016100a6565b6000805460408051600481526024810182526020810180516001600160e01b031663f6ad3c2360e01b179052905183926001600160a01b0316916127109161014191906104ce565b6000604051808303818686f4925050503d806000811461017d576040519150601f19603f3d011682016040523d82523d6000602084013e610182565b606091505b50909250905081151560000361019a57805160208201fd5b5050565b6000816001600160a01b03163460405160006040518083038185875af1925050503d80600081146101eb576040519150601f19603f3d011682016040523d82523d6000602084013e6101f0565b606091505b505090508061019a5760405162461bcd60e51b815260206004820152601860248201527f6661696c656420746f207472616e736665722076616c75650000000000000000604482015260640160405180910390fd5b606060008054906101000a90046001600160a01b03166001600160a01b031663f6ad3c236127106040518263ffffffff1660e01b81526004016000604051808303818786fa15801561029b573d6000803e3d6000fd5b50505050506040513d6000823e601f3d908101601f191682016040526102c4919081019061056d565b905090565b60008054604080517ff6ad3c23f0605b9ed84e6ad346e341d181873063303443c922270a3f389ee85e80825260048083019093526001600160a01b03909316939091602091839190829087612710f250505050565b60006001600160a01b03831663e08b62623061033b85600161067f565b6040516001600160e01b031960e085901b1681526001600160a01b03909216600483015263ffffffff1660248201526044016020604051808303816000875af19250505080156103a8575060408051601f3d908101601f191682019092526103a5918101906106b1565b60015b6103b35750806103b6565b90505b92915050565b6001600160a01b03811681146103d157600080fd5b50565b6000602082840312156103e657600080fd5b81356103f1816103bc565b9392505050565b602080825282518282018190526000919060409081850190868401855b82811015610476578151805185528681015187860152858101516001600160a01b031686860152606080820151908601526080808201519086015260a0908101516001600160f81b0319169085015260c09093019290850190600101610415565b5091979650505050505050565b63ffffffff811681146103d157600080fd5b600080604083850312156104a857600080fd5b82356104b3816103bc565b915060208301356104c381610483565b809150509250929050565b6000825160005b818110156104ef57602081860181015185830152016104d5565b506000920191825250919050565b634e487b7160e01b600052604160045260246000fd5b60405160c0810167ffffffffffffffff81118282101715610536576105366104fd565b60405290565b604051601f8201601f1916810167ffffffffffffffff81118282101715610565576105656104fd565b604052919050565b6000602080838503121561058057600080fd5b825167ffffffffffffffff8082111561059857600080fd5b818501915085601f8301126105ac57600080fd5b8151818111156105be576105be6104fd565b6105cc848260051b0161053c565b818152848101925060c09182028401850191888311156105eb57600080fd5b938501935b828510156106735780858a0312156106085760008081fd5b610610610513565b85518152868601518782015260408087015161062b816103bc565b90820152606086810151908201526080808701519082015260a0808701516001600160f81b0319811681146106605760008081fd5b90820152845293840193928501926105f0565b50979650505050505050565b63ffffffff8181168382160190808211156106aa57634e487b7160e01b600052601160045260246000fd5b5092915050565b6000602082840312156106c357600080fd5b81516103f18161048356fea264697066735822122062599b043536bb9eb9f7572b2e7a5f215cfa0d6c9fca05ca97d98fa995a4cd8064736f6c63430008140033"
  )

  // compiled EVM byte-code of the Storage contract,
  // source: libevm/native/test/Storage.sol
  // note: the constructor parameter is appended at the end
  def storageContractCode(initialValue: BigInteger): Array[Byte] = BytesUtils.fromHexString(
    "608060405234801561001057600080fd5b5060405161023638038061023683398101604081905261002f916100f6565b6000819055604051339060008051602061021683398151915290610073906020808252600c908201526b48656c6c6f20576f726c642160a01b604082015260600190565b60405180910390a2336001600160a01b03166000805160206102168339815191526040516100bf906020808252600a908201526948656c6c6f2045564d2160b01b604082015260600190565b60405180910390a26040517ffe1a3ad11e425db4b8e6af35d11c50118826a496df73006fc724cb27f2b9994690600090a15061010f565b60006020828403121561010857600080fd5b5051919050565b60f98061011d6000396000f3fe60806040526004361060305760003560e01c80632e64cec1146035578063371303c01460565780636057361d14606a575b600080fd5b348015604057600080fd5b5060005460405190815260200160405180910390f35b348015606157600080fd5b506068607a565b005b606860753660046086565b600055565b6000546075906001609e565b600060208284031215609757600080fd5b5035919050565b6000821982111560be57634e487b7160e01b600052601160045260246000fd5b50019056fea264697066735822122080d9db531d29b1bd6b4e16762726b70e2a94f0b40ee4e2ab534d9b879cf1c25664736f6c634300080f00330738f4da267a110d810e6e89fc59e46be6de0c37b1d5cd559b267dc3688e74e0"
  ) ++ toUint256Bytes(initialValue)

  val STORAGE_RETRIEVE_ABI_ID = getABIMethodId("retrieve()")

  val STORAGE_INC_ABI_ID = getABIMethodId("inc()")


  // compiled EVM byte-code of NativeCaller contract
  // source: libevm/native/test/NativeCaller.sol
  def nativeCallerContractCode: Array[Byte] = BytesUtils.fromHexString(
    "6080604052600080546001600160a01b03191663deadbeef17905534801561002657600080fd5b506105a5806100366000396000f3fe608060405234801561001057600080fd5b506004361061004c5760003560e01c806326afe20e146100515780634560bfed14610072578063ee880d141461007a578063ff01556b14610082575b600080fd5b61005961008a565b60405163ffffffff909116815260200160405180910390f35b610059610161565b6100596102c4565b610059610468565b6000805460408051600481526024810182526020810180516001600160e01b031662dc4c0f60e61b17905290516001600160a01b03909216918391829184916161a8916100d791906104e9565b60006040518083038160008787f1925050503d8060008114610115576040519150601f19603f3d011682016040523d82523d6000602084013e61011a565b606091505b5091509150816101455760405162461bcd60e51b815260040161013c90610518565b60405180910390fd5b808060200190518101906101599190610542565b935050505090565b6000805460408051600481526024810182526020810180516001600160e01b0316632e64cec160e01b17905290516001600160a01b0390921691839182918491612710916101af91906104e9565b6000604051808303818686fa925050503d80600081146101eb576040519150601f19603f3d011682016040523d82523d6000602084013e6101f0565b606091505b509150915060008180602001905181019061020b9190610542565b60408051600481526024810182526020810180516001600160e01b031662dc4c0f60e61b179052905191925060009182916001600160a01b038816916161a891610254916104e9565b60006040518083038160008787f1925050503d8060008114610292576040519150601f19603f3d011682016040523d82523d6000602084013e610297565b606091505b5091509150816102b95760405162461bcd60e51b815260040161013c90610518565b509095945050505050565b6000805460408051600481526024810182526020810180516001600160e01b031662dc4c0f60e61b17905290516001600160a01b03909216918391829184916161a89161031191906104e9565b6000604051808303818686fa925050503d806000811461034d576040519150601f19603f3d011682016040523d82523d6000602084013e610352565b606091505b5091509150811561039e5760405162461bcd60e51b815260206004820152601660248201527514dd185d1a58d8d85b1b081cda1bdd5b190819985a5b60521b604482015260640161013c565b60408051600481526024810182526020810180516001600160e01b031662dc4c0f60e61b179052905160009182916001600160a01b038716916161a8916103e591906104e9565b60006040518083038160008787f1925050503d8060008114610423576040519150601f19603f3d011682016040523d82523d6000602084013e610428565b606091505b50915091508161044a5760405162461bcd60e51b815260040161013c90610518565b8080602001905181019061045e9190610542565b9550505050505090565b60008060009054906101000a90046001600160a01b03166001600160a01b031663371303c06161a86040518263ffffffff1660e01b81526004016020604051808303818786fa1580156104bf573d6000803e3d6000fd5b50505050506040513d601f19601f820116820180604052508101906104e49190610542565b905090565b6000825160005b8181101561050a57602081860181015185830152016104f0565b506000920191825250919050565b60208082526010908201526f63616c6c2073686f756c6420776f726b60801b604082015260600190565b60006020828403121561055457600080fd5b815163ffffffff8116811461056857600080fd5b939250505056fea26469706673582212201c690f955e8985b81f3c7ff4b74ff844752fd7d91d3a6f4b0c9d63937c66cbb364736f6c63430008140033"
  )

  val NATIVE_CALLER_NESTED_ABI_ID = getABIMethodId("testNestedCalls()")

  val NATIVE_CALLER_STATIC_READONLY_ABI_ID = getABIMethodId("testStaticCallOnReadonlyMethod()")

  val NATIVE_CALLER_STATIC_READWRITE_ABI_ID = getABIMethodId("testStaticCallOnReadwriteMethod()")

  val NATIVE_CALLER_STATIC_RW_CONTRACT_ABI_ID = getABIMethodId("testStaticCallOnReadwriteMethodContractCall()")

  val NATIVE_CONTRACT_RETRIEVE_ABI_ID = getABIMethodId("retrieve()")

  val NATIVE_CONTRACT_INC_ABI_ID = getABIMethodId("inc()")
}
