#!/usr/bin/env python3
import pprint
from decimal import Decimal
from eth_utils import add_0x_prefix, remove_0x_prefix, encode_hex, \
    function_signature_to_4byte_selector, to_checksum_address
from SidechainTestFramework.account.ac_chain_setup import AccountChainSetup
from SidechainTestFramework.account.ac_utils import format_evm
from SidechainTestFramework.account.httpCalls.transaction.getKeysOwnership import getKeysOwnership
from SidechainTestFramework.account.httpCalls.transaction.removeKeysOwnership import removeKeysOwnership
from SidechainTestFramework.account.httpCalls.transaction.sendKeysOwnership import sendKeysOwnership
from SidechainTestFramework.account.utils import MC_ADDR_OWNERSHIP_SMART_CONTRACT_ADDRESS
from SidechainTestFramework.scutil import generate_next_block, SLOTS_IN_EPOCH, EVM_APP_SLOT_TIME

from test_framework.util import (assert_equal, assert_true, forward_transfer_to_sidechain)

"""
Configuration: 
    - 2 SC node
    - 1 MC node

Test:
    Add a large number of relations between owned SC/MC addresses via native smart contract call and check
    the gas usage of adding a new relation is constant.
    Afterwards, remove all of them one by one and verify the gas usage is constant as well
    
"""


def get_address_with_balance(input_list):
    """
    Assumes the list in input is obtained via the RPC cmd listaddressgroupings()
    """
    for group in input_list:
        for record in group:
            addr = record[0]
            val = record[1]
            if val > 0:
                return addr, val
    return None, 0


# The activation epoch of the zendao feature, as coded in the sdk
ZENDAO_FORK_EPOCH = 7


class SCEvmMcAddressOwnershipPerfTest(AccountChainSetup):
    def __init__(self):
        super().__init__(number_of_sidechain_nodes=2,
                         block_timestamp_rewind=SLOTS_IN_EPOCH * EVM_APP_SLOT_TIME * ZENDAO_FORK_EPOCH)

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
        sc_address_checksum_fmt = to_checksum_address(self.evm_address)

        lag_list = mc_node.listaddressgroupings()
        taddr1, val = get_address_with_balance(lag_list)
        assert_true(taddr1 is not None)

        # reach the fork
        current_best_epoch = sc_node.block_forgingInfo()["result"]["bestEpochNumber"]

        for i in range(0, ZENDAO_FORK_EPOCH - current_best_epoch):
            generate_next_block(sc_node, "first node", force_switch_to_next_epoch=True)
            self.sc_sync_all()

        # try adding many mc addresses and forge a block after that

        # this can take long time
        # num_of_association = 10000

        num_of_association = 200
        num_of_tx_in_block = 16
        taddr_list = []
        tx_hash_list = []
        for i in range(num_of_association):
            taddr = mc_node.getnewaddress()
            taddr_list.append(taddr)
            mc_signature = mc_node.signmessage(taddr, sc_address_checksum_fmt)

            tx_hash_list.append(sendKeysOwnership(sc_node, nonce=i,
                                                  sc_address=sc_address,
                                                  mc_addr=taddr,
                                                  mc_signature=mc_signature)['transactionId'])
            self.sc_sync_all()

            if i % num_of_tx_in_block == 0:
                print("Generating new block (i = {})...".format(i))
                generate_next_block(sc_node, "first node")
                self.sc_sync_all()

        generate_next_block(sc_node, "first node")
        self.sc_sync_all()

        # the very first tx uses slightly more gas because it performs the smart contact initialization, therefore we
        # take the second one and compare it with the last one
        tx_hash_first = tx_hash_list[1]
        tx_hash_last = tx_hash_list[-1]

        receipt_first = sc_node.rpc_eth_getTransactionReceipt(add_0x_prefix(tx_hash_first))
        assert_true(int(receipt_first['result']['status'], 16) == 1)
        gas_used_first = receipt_first['result']['gasUsed']

        receipt_last = sc_node.rpc_eth_getTransactionReceipt(add_0x_prefix(tx_hash_last))
        assert_true(int(receipt_last['result']['status'], 16) == 1)
        gas_used_last = receipt_last['result']['gasUsed']

        assert_equal(gas_used_first, gas_used_last)

        list_associations_sc_address = getKeysOwnership(sc_node, sc_address=sc_address)

        # check we have just one sc address association
        assert_true(len(list_associations_sc_address['keysOwnership']) == 1)
        # check we have the expected associations
        assert_true(len(list_associations_sc_address['keysOwnership'][sc_address_checksum_fmt]) == num_of_association)
        for taddr in taddr_list:
            assert_true(taddr in list_associations_sc_address['keysOwnership'][sc_address_checksum_fmt])

        # execute native smart contract for getting all associations
        method = 'getAllKeyOwnerships()'
        abi_str = function_signature_to_4byte_selector(method)
        req = {
            "from": format_evm(sc_address),
            "to": format_evm(MC_ADDR_OWNERSHIP_SMART_CONTRACT_ADDRESS),
            "nonce": 3,
            "gasLimit": 230000000,
            "gasPrice": 850000000,
            "value": 0,
            "data": encode_hex(abi_str)
        }
        # currently it may fail with out of gas error if too many data are stored in the native smart contract
        response = sc_node2.rpc_eth_call(req, 'latest')
        pprint.pprint(response)
        abi_return_value = remove_0x_prefix(response['result'])
        # print(abi_return_value)
        result_string_length = len(abi_return_value)
        # we have an offset of 64 bytes and 'num_of_association' records with 3 chunks of 32 bytes
        exp_len = 32 + 32 + num_of_association * (3 * 32)
        assert_equal(result_string_length, 2 * exp_len)

        nonce = int(sc_node.rpc_eth_getTransactionCount(self.evm_address, 'latest')['result'], 16)

        tx_hash_list = []
        for i in range(num_of_association):

            tx_hash_list.append(removeKeysOwnership(sc_node, nonce=i + nonce,
                                                    sc_address=sc_address,
                                                    mc_addr=taddr_list[i])['transactionId'])
            self.sc_sync_all()

            if i % num_of_tx_in_block == 0:
                print("Generating new block (i = {})...".format(i))
                generate_next_block(sc_node, "first node")
                self.sc_sync_all()

        generate_next_block(sc_node, "first node")
        self.sc_sync_all()

        # the very last tx uses slightly less gas because it kdoes not modify any other element of the internal linked
        # list, therefore we take the previous one and compare it with the last one
        tx_hash_first = tx_hash_list[0]
        tx_hash_last = tx_hash_list[-2]

        receipt_first = sc_node.rpc_eth_getTransactionReceipt(add_0x_prefix(tx_hash_first))
        assert_true(int(receipt_first['result']['status'], 16) == 1)
        gas_used_first = receipt_first['result']['gasUsed']

        receipt_last = sc_node.rpc_eth_getTransactionReceipt(add_0x_prefix(tx_hash_last))
        assert_true(int(receipt_last['result']['status'], 16) == 1)
        gas_used_last = receipt_last['result']['gasUsed']

        assert_equal(gas_used_first, gas_used_last)

        # check that we really removed all relations
        list_associations_sc_address = getKeysOwnership(sc_node, sc_address=sc_address)
        pprint.pprint(list_associations_sc_address)
        assert_true(len(list_associations_sc_address['keysOwnership']) == 0)


if __name__ == "__main__":
    SCEvmMcAddressOwnershipPerfTest().main()
