#!/usr/bin/env python3
import json
import pprint
from decimal import Decimal
from eth_abi import decode, encode_abi
from eth_utils import add_0x_prefix, remove_0x_prefix, event_signature_to_log_topic, encode_hex, \
    function_signature_to_4byte_selector
from SidechainTestFramework.account.ac_chain_setup import AccountChainSetup
from SidechainTestFramework.account.ac_utils import contract_function_static_call, __make_static_call_payload, \
    format_evm
from SidechainTestFramework.account.httpCalls.transaction.getKeysOwnership import getKeysOwnership
from SidechainTestFramework.account.httpCalls.transaction.removeKeysOwnership import removeKeysOwnership
from SidechainTestFramework.account.httpCalls.transaction.sendKeysOwnership import sendKeysOwnership
from SidechainTestFramework.account.utils import MC_ADDR_OWNERSHIP_SMART_CONTRACT_ADDRESS
from SidechainTestFramework.scutil import generate_next_block
from SidechainTestFramework.sidechainauthproxy import SCAPIException
from test_framework.util import (assert_equal, assert_true, fail, hex_str_to_bytes, assert_false,
                                 forward_transfer_to_sidechain)

"""
Configuration: 
    - 1 SC node
    - 1 MC node

Test:
    Do some test for handling relations between owned SC/MC addresses via native smart contract call:
    - Add ownership relation and check event
    - Get the list of MC addresses associated to a SC address
    - Remove an association and check event
    Do some negative tests     
"""

def get_address_with_balance(input_list):
    '''
    Assumes the list in input is obtained via the RPC cmd listaddressgroupings()
    '''
    for group in input_list:
        for record in group:
            addr = record[0]
            val  = record[1]
            if val > 0:
                return (addr, val)
    return (None, 0)


def check_add_ownership_event(event, sc_addr, mc_addr, op="add"):
    if op == "add":
        sig = 'AddMcAddrOwnership(address,bytes3,bytes32)'
    elif op == "remove":
        sig = 'RemoveMcAddrOwnership(address,bytes3,bytes32)'
    else:
        assert_false("Invalid op = " + op)

    assert_equal(2, len(event['topics']), "Wrong number of topics in event")
    event_id = remove_0x_prefix(event['topics'][0])
    event_signature = remove_0x_prefix(
        encode_hex(event_signature_to_log_topic(sig)))
    assert_equal(event_signature, event_id, "Wrong event signature in topics")

    evt_sc_addr = decode(['address'], hex_str_to_bytes(event['topics'][1][2:]))[0][2:]
    assert_equal(sc_addr, evt_sc_addr, "Wrong sc_addr address in topics")

    (mca3, mca32) = decode(['bytes3', 'bytes32'], hex_str_to_bytes(event['data'][2:]))
    evt_mc_addr = (mca3 + mca32).decode('utf-8')
    assert_equal(mc_addr, evt_mc_addr, "Wrong mc_addr string in topics")


def forge_and_check_receipt(self, sc_node, tx_hash, expected_receipt_status=1, sc_addr=None, mc_addr=None, evt_op="add"):
    generate_next_block(sc_node, "first node")
    self.sc_sync_all()

    check_receipt(sc_node, tx_hash, expected_receipt_status, sc_addr, mc_addr, evt_op)

def check_receipt(sc_node, tx_hash, expected_receipt_status=1, sc_addr=None, mc_addr=None, evt_op="add"):

    # check receipt
    receipt = sc_node.rpc_eth_getTransactionReceipt(add_0x_prefix(tx_hash))
    if not 'result' in receipt or receipt['result'] == None:
        raise Exception('Rpc eth_getTransactionReceipt cmd failed:{}'.format(json.dumps(receipt, indent=2)))

    status = int(receipt['result']['status'], 16)
    assert_true(status == expected_receipt_status)

    # if we have a succesful receipt and valid func parameters, check the event
    if (expected_receipt_status == 1):
        if (sc_addr is not None) and (mc_addr is not None) :
            assert_equal(1, len(receipt['result']['logs']), "Wrong number of events in receipt")
            event = receipt['result']['logs'][0]
            check_add_ownership_event(event, sc_addr, mc_addr, evt_op)
    else:
        assert_equal(0, len(receipt['result']['logs']), "No events should be in receipt")




class SCEvmMcAddressOwnership(AccountChainSetup):
    def __init__(self):
        super().__init__(number_of_sidechain_nodes=2)

    def run_test(self):
        ft_amount_in_zen = Decimal('500.0')

        self.sc_ac_setup(ft_amount_in_zen=ft_amount_in_zen)

        sc_node = self.sc_nodes[0]
        mc_node = self.nodes[0]
        
        sc_node2 = self.sc_nodes[1]
        sc_address2 = sc_node2.wallet_createPrivateKeySecp256k1()["result"]["proposition"]["address"]

        # transfer some fund from MC to SC2
        forward_transfer_to_sidechain(self.sc_nodes_bootstrap_info.sidechain_id,
                                      self.nodes[0],
                                      sc_address2,
                                      ft_amount_in_zen,
                                      self.mc_return_address)
        self.sc_sync_all()

        self.block_id = generate_next_block(sc_node, "first node", force_switch_to_next_epoch=True)
        self.sc_sync_all()


        sc_address = remove_0x_prefix(self.evm_address)

        lag_list = mc_node.listaddressgroupings()
        taddr1, val = get_address_with_balance(lag_list)

        assert_true(taddr1 is not None)

        mc_signature1 = mc_node.signmessage(taddr1, sc_address)
        print("scAddr: " + sc_address)
        print("mcAddr: " + taddr1)
        print("mcSignature: " + mc_signature1)


        # add sc/mc ownership sending a transaction with data invoking native smart contract
        ret = sendKeysOwnership(sc_node, nonce=0,
                                 sc_address=sc_address,
                                 mc_addr=taddr1,
                                 mc_signature=mc_signature1)
        pprint.pprint(ret)

        tx_hash = ret['transactionId']
        forge_and_check_receipt(self, sc_node, tx_hash, sc_addr=sc_address, mc_addr=taddr1)


        # get another address (note that mc recycles addresses)
        while True:
            taddr2 = mc_node.getnewaddress()
            if taddr2 != taddr1:
                break
            else:
                print(taddr2, "...", taddr1)

        mc_signature2 = mc_node.signmessage(taddr2, sc_address)
        print("mcAddr: " + taddr2)
        print("mcSignature: " + mc_signature2)

        # add a second owned mc address linked to the same sc address
        ret = sendKeysOwnership(sc_node, nonce=1,
                                 sc_address=sc_address,
                                 mc_addr=taddr2,
                                 mc_signature=mc_signature2)
        pprint.pprint(ret)

        tx_hash = ret['transactionId']
        forge_and_check_receipt(self, sc_node, tx_hash, sc_addr=sc_address, mc_addr=taddr2)

        # check we have both association and only them
        ret = getKeysOwnership(sc_node, sc_address=sc_address)
        pprint.pprint(ret)

        # check we have just one sc address association
        assert_true(len(ret['keysOwnership']) == 1)
        # check we have two mc address associated to sc address
        assert_true(len(ret['keysOwnership'][sc_address]) == 2)
        # check we have exactly those mc address
        assert_true(taddr1 in ret['keysOwnership'][sc_address])
        assert_true(taddr2 in ret['keysOwnership'][sc_address])

        ret = removeKeysOwnership(sc_node,
                                 sc_address=sc_address,
                                 mc_addr=taddr2)
        pprint.pprint(ret)

        tx_hash = ret['transactionId']
        forge_and_check_receipt(self, sc_node, tx_hash, sc_addr=sc_address, mc_addr=taddr2, evt_op="remove")

        ret = getKeysOwnership(sc_node, sc_address=sc_address)
        pprint.pprint(ret)

        # check we have just one sc address association
        assert_true(len(ret['keysOwnership']) == 1)
        # check we have only one mc address associated to sc address
        assert_true(len(ret['keysOwnership'][sc_address]) == 1)
        # check we have exactly that mc address
        assert_true(taddr1 in ret['keysOwnership'][sc_address])
        assert_true(taddr2 not in ret['keysOwnership'][sc_address])

        # get association for a sc address not yet associated to any mc address
        ret = getKeysOwnership(sc_node, sc_address=sc_address2)
        pprint.pprint(ret)
        # check we have no association for this sc address
        assert_true(len(ret['keysOwnership']) == 0)

        # negative cases
        # 1. try to add the an ownership already there
        try:
            sendKeysOwnership(sc_node,
                                     sc_address=sc_address,
                                     mc_addr=taddr1,
                                     mc_signature=mc_signature1)
        except RuntimeError as err:
            print("Expected exception thrown: {}".format(err))
            assert_true("already linked" in str(err) )
        else:
            fail("duplicate association should not work")


        # 2. try to add a not owned ownership. The tx is executed but the receipt has a failed status
        taddr3 = mc_node.getnewaddress()

        ret = sendKeysOwnership(sc_node, nonce=3,
                                 sc_address=sc_address,
                                 mc_addr=taddr3,
                                 mc_signature=mc_signature1)
        tx_hash = ret['transactionId']
        pprint.pprint(ret)

        forge_and_check_receipt(self, sc_node, tx_hash, expected_receipt_status=0)

        # 3. try to use invalid parameters
        # 3.1 illegal sc address
        try:
            sendKeysOwnership(sc_node,
                              sc_address="1234",
                              mc_addr=taddr2,
                              mc_signature=mc_signature2)
        except SCAPIException as err:
            print("Expected exception thrown: {}".format(str(err.error)))
            assert_true("Invalid SC address" in str(err.error))
        else:
            fail("invalid sc address should not work")


        # 3.2 illegal mc address
        try:
            sendKeysOwnership(sc_node,
                              sc_address=sc_address,
                              mc_addr="1LMcKyPmwebfygoeZP8E9jAMS2BcgH3Yip",
                              mc_signature=mc_signature2)
        except RuntimeError as err:
            print("Expected exception thrown: {}".format(str(err)))
            assert_true("Invalid input parameters" in str(err))
        else:
            fail("invalid mc address should not work")


        # 3.3 illegal mc signature
        try:
            sendKeysOwnership(sc_node,
                              sc_address=sc_address,
                              mc_addr=taddr3,
                              mc_signature="xyz")
        except RuntimeError as err:
            print("Expected exception thrown: {}".format(str(err)))
            assert_true("Invalid input parameters" in str(err))
        else:
            fail("invalid mc signature should not work")

        # 4. try to remove an ownership not stored in db.
        try:
            removeKeysOwnership(sc_node,
                            sc_address=sc_address,
                            mc_addr=taddr3)
        except Exception as err:
            print("Expected exception thrown: {}".format(err))
            assert_true("not linked" in str(err) )
        else:
            fail("duplicate association should not work")

        # 5. try to remove an ownership passing a null mc addr (not yet supported).
        try:
            removeKeysOwnership(sc_node,
                            sc_address=sc_address,
                            mc_addr=None)
        except SCAPIException as err:
            print("Expected exception thrown: {}".format(err))
            assert_true("MC address must be specified" in str(err.error) )
        else:
            fail("duplicate association should not work")

        # re-add the mc address we removed
        ret = sendKeysOwnership(sc_node, nonce=4,
                                 sc_address=sc_address,
                                 mc_addr=taddr2,
                                 mc_signature=mc_signature2)
        pprint.pprint(ret)

        tx_hash = ret['transactionId']
        forge_and_check_receipt(self, sc_node, tx_hash, sc_addr=sc_address, mc_addr=taddr2)

        # try adding 10 mc addresses and forge a block after that
        taddr_list = []
        txHash_list = []
        for i in range(10):
            taddr = mc_node.getnewaddress()
            taddr_list.append(taddr)
            mc_signature = mc_node.signmessage(taddr, sc_address)
            print("mcAddr: " + taddr)
            print("mcSignature: " + mc_signature)

            txHash_list.append(sendKeysOwnership(sc_node, nonce= 5 + i,
                                    sc_address=sc_address,
                                    mc_addr=taddr,
                                    mc_signature=mc_signature)['transactionId'])
            self.sc_sync_all()

        generate_next_block(sc_node, "first node")
        self.sc_sync_all()

        for i, txHash in enumerate(txHash_list):
            print("Index = ", i)
            check_receipt(sc_node, txHash, sc_addr=sc_address, mc_addr=taddr_list[i])

        ret = getKeysOwnership(sc_node, sc_address=sc_address)
        pprint.pprint(ret)

        # check we have just one sc address association
        assert_true(len(ret['keysOwnership']) == 1)
        # check we have the previous 2 mc addresses plus 10 just associated
        assert_true(len(ret['keysOwnership'][sc_address]) == 12)
        for taddr in taddr_list:
            assert_true(taddr in ret['keysOwnership'][sc_address])


        # remove an mc addr and check we have 11 of them
        taddr_rem = taddr_list[4]

        assert_true(len(taddr_list) == 10)
        taddr_list.remove(taddr_rem)
        assert_true(len(taddr_list) == 9)

        removeKeysOwnership(sc_node, nonce=15,
                            sc_address=sc_address,
                            mc_addr=taddr_rem)

        generate_next_block(sc_node, "first node")
        self.sc_sync_all()

        ret = getKeysOwnership(sc_node, sc_address=sc_address)
        pprint.pprint(ret)

        # check we have just one sc address association
        assert_true(len(ret['keysOwnership']) == 1)
        # check we have the expected number
        assert_true(len(ret['keysOwnership'][sc_address]) == 11)
        for taddr in taddr_list:
            assert_true(taddr in ret['keysOwnership'][sc_address])

        # add an association for a different sc address
        taddr_sc2 = mc_node.getnewaddress()
        mc_signature_sc2 = mc_node.signmessage(taddr_sc2, sc_address2)
        print("scAddr: " + sc_address2)
        print("mcAddr: " + taddr_sc2)
        print("mcSignature: " + mc_signature_sc2)

        ret = sendKeysOwnership(sc_node2,
                sc_address=sc_address2,
                mc_addr=taddr_sc2,
                mc_signature=mc_signature_sc2)
        self.sc_sync_all()
        tx_hash = ret['transactionId']
        forge_and_check_receipt(self, sc_node, tx_hash, sc_addr=sc_address2, mc_addr=taddr_sc2)

        # associate to sc address 2 a mc addr already associated to sc address 1, the tx is rejected
        mc_signature_sc2 = mc_node.signmessage(taddr1, sc_address2)
        print("mcAddr: " + taddr1)
        print("mcSignature: " + mc_signature_sc2)

        ret = sendKeysOwnership(sc_node2,
                sc_address=sc_address2,
                mc_addr=taddr1,
                mc_signature=mc_signature_sc2)
        self.sc_sync_all()
        tx_hash = ret['transactionId']
        # check the receipt has a status failed
        forge_and_check_receipt(self, sc_node, tx_hash, expected_receipt_status=0)

        ret = getKeysOwnership(sc_node)
        pprint.pprint(ret)

        # check we have two sc address associations
        assert_true(len(ret['keysOwnership']) == 2)
        # check we have the expected numbers
        assert_true(len(ret['keysOwnership'][sc_address]) == 11)
        assert_true(len(ret['keysOwnership'][sc_address2]) == 1)
        assert_true(taddr_sc2 in ret['keysOwnership'][sc_address2])

        # execute native smart contract for getting all associations
        method = 'getAllKeyOwnerships()'
        abi_str = function_signature_to_4byte_selector(method)
        req = {
            "from": format_evm(sc_address),
            "to": format_evm(MC_ADDR_OWNERSHIP_SMART_CONTRACT_ADDRESS),
            "nonce": 3,
            "gasLimit": 2300000,
            "gasPrice": 850000000,
            "value": 0,
            "data": encode_hex(abi_str)
        }
        response = sc_node2.rpc_eth_call(req, 'latest')
        abiReturnValue = remove_0x_prefix(response['result'])
        print(abiReturnValue)
        resultStringLength = len(abiReturnValue)
        # we have an offset of 64 bytes and 12 records with 3 chunks of 32 bytes
        exp_len = 32 + 32 + 12*(3*32)
        assert_equal(resultStringLength, 2*exp_len)



if __name__ == "__main__":
    SCEvmMcAddressOwnership().main()
