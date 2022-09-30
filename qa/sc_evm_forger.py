#!/usr/bin/env python3
import json
import time
from decimal import Decimal

from eth_abi import decode
from eth_utils import remove_0x_prefix, event_signature_to_log_topic, encode_hex, to_hex

from SidechainTestFramework.account.ac_use_smart_contract import SmartContract
from SidechainTestFramework.account.address_util import format_evm, format_eoa
from SidechainTestFramework.sc_boostrap_info import SCNodeConfiguration, SCCreationInfo, MCConnectionInfo, \
    SCNetworkConfiguration, LARGE_WITHDRAWAL_EPOCH_LENGTH
from SidechainTestFramework.sc_test_framework import SidechainTestFramework
from SidechainTestFramework.scutil import WithdrawalReqSmartContractAddress
from SidechainTestFramework.scutil import bootstrap_sidechain_nodes, \
    start_sc_nodes, AccountModelBlockVersion, EVM_APP_BINARY, generate_next_block, convertZenniesToWei, \
    convertZenToZennies, connect_sc_nodes, convertZenToWei, ForgerStakeSmartContractAddress, get_account_balance, \
    computeForgedTxFee, convertWeiToZen
from sc_evm_test_contract_contract_deployment_and_interaction import random_byte_string
from test_framework.util import assert_equal, assert_true, start_nodes, \
    websocket_port_by_mc_node_index, forward_transfer_to_sidechain, fail
from test_framework.util import hex_str_to_bytes

"""
Configuration: 
    - 2 SC nodes connected with each other
    - 1 MC node
    - SC node 1 owns a stakeAmount made out of cross chain creation output

Test:
    - Check that genesis stake (the one created during sc creation) is what we expect in terms of
      blockSignPublicKey, vrfPublicKey, ownerProposition and amount
    - Send FTs to SC1 (used for forging delegation) and SC2 (used for having some gas)
    - SC2 tries spending the genesis stake which does not own (exception expected)
    - SC1 Delegate 300 Zen and 200 Zen to SC2
    - Check that SC2 can not forge before two epochs are passed by, and afterwards it can
    - SC1 spends the genesis stake
    - SC1 can still forge blocks but after two epochs it can not anymore
    - SC1 removes all remaining stakes
    - Verify that it is not possible to forge new SC blocks from the next epoch switch on

"""


def get_sc_wallet_pubkeys(sc_node):
    wallet_propositions = sc_node.wallet_allPublicKeys()['result']['propositions']
    # pprint.pprint(wallet_propositions)
    pkey_list = []
    for p in wallet_propositions:
        if 'publicKey' in p:
            pkey_list.append(p['publicKey'])
        elif 'address' in p:
            pkey_list.append(p['address'])

    return pkey_list


def print_current_epoch_and_slot(sc_node):
    ret = sc_node.block_forgingInfo()["result"]
    print("Epoch={}, Slot={}".format(ret['bestEpochNumber'], ret['bestSlotNumber']))

def check_make_forger_stake_event(event, source_addr, owner, amount):
    assert_equal(3, len(event['topics']), "Wrong number of topics in event")
    event_id = remove_0x_prefix(event['topics'][0])
    event_signature = remove_0x_prefix(
        encode_hex(event_signature_to_log_topic('DelegateForgerStake(address,address,bytes32,uint256)')))
    assert_equal(event_signature, event_id, "Wrong event signature in topics")

    from_addr = decode(['address'], hex_str_to_bytes(event['topics'][1][2:]))[0][2:]
    assert_equal(source_addr, from_addr, "Wrong from address in topics")

    owner_addr = decode(['address'], hex_str_to_bytes(event['topics'][2][2:]))[0][2:]
    assert_equal(owner, owner_addr, "Wrong owner address in topics")

    (stake_id, value) = decode(['bytes32' ,'uint256'], hex_str_to_bytes(event['data'][2:]))
    assert_equal(convertZenToWei(amount), value, "Wrong amount in event")

def check_spend_forger_stake_event(event, owner, stake_id):
    assert_equal(2, len(event['topics']), "Wrong number of topics in event")
    event_id = remove_0x_prefix(event['topics'][0])
    event_signature = remove_0x_prefix(
        encode_hex(event_signature_to_log_topic('WithdrawForgerStake(address,bytes32)')))
    assert_equal(event_signature, event_id, "Wrong event signature in topics")

    owner_addr = decode(['address'], hex_str_to_bytes(event['topics'][1][2:]))[0][2:]
    assert_equal(owner, owner_addr, "Wrong owner address in topics")

    (stake,) = decode(['bytes32'], hex_str_to_bytes(event['data'][2:]))
    assert_equal(stake_id, to_hex(stake)[2:],"Wrong stake id in data")


class SCEvmForger(SidechainTestFramework):
    sc_nodes_bootstrap_info = None
    number_of_mc_nodes = 1
    number_of_sidechain_nodes = 2
    sc_creation_amount = 100

    def setup_nodes(self):
        return start_nodes(self.number_of_mc_nodes, self.options.tmpdir)

    def sc_setup_network(self, split=False):
        self.sc_nodes = self.sc_setup_nodes()
        print("Connecting sc nodes...")
        connect_sc_nodes(self.sc_nodes[0], 1)
        self.sc_sync_all()

    def sc_setup_chain(self):
        mc_node = self.nodes[0]
        sc_node_1_configuration = SCNodeConfiguration(
            MCConnectionInfo(address="ws://{0}:{1}".format(mc_node.hostname, websocket_port_by_mc_node_index(0)))
        )
        sc_node_2_configuration = SCNodeConfiguration(
            MCConnectionInfo(address="ws://{0}:{1}".format(mc_node.hostname, websocket_port_by_mc_node_index(0)))
        )
        network = SCNetworkConfiguration(
            SCCreationInfo(mc_node, self.sc_creation_amount, LARGE_WITHDRAWAL_EPOCH_LENGTH),
            sc_node_1_configuration, sc_node_2_configuration)
        self.sc_nodes_bootstrap_info = bootstrap_sidechain_nodes(self.options, network,
                                                                 block_timestamp_rewind=720 * 120 * 10,
                                                                 blockversion=AccountModelBlockVersion)

    def sc_setup_nodes(self):
        return start_sc_nodes(self.number_of_sidechain_nodes, dirname=self.options.tmpdir,
                              binary=[EVM_APP_BINARY] * 2)#, extra_args=[[], ['-agentlib']])

    def run_test(self):

        mc_node = self.nodes[0]
        sc_node_1 = self.sc_nodes[0]
        sc_node_2 = self.sc_nodes[1]

        # blocksign and vrf pub keys are concatenated in custom data param in sc creation (33+32 bytes), get them from mc cmd
        sc_info = mc_node.getscinfo(self.sc_nodes_bootstrap_info.sidechain_id)['items'][0]
        sc_cr_txhash = sc_info['creatingTxHash']
        sc_cr_tx = mc_node.getrawtransaction(str(sc_cr_txhash), 1)

        sc_cr_out_addr_str = sc_cr_tx['vsc_ccout'][0]['address']
        sc_cr_owner_proposition = sc_cr_out_addr_str[24:]

        sc_info_custom_data = sc_info['customData']
        sc_cr_vrf_pub_key = sc_info_custom_data[:66]
        sc_cr_sign_pub_key = sc_info_custom_data[66:]

        # check we have all keys in wallet
        pkey_list = get_sc_wallet_pubkeys(sc_node_1)
        assert_true(sc_cr_owner_proposition in pkey_list, "sc cr owner propostion not in wallet")
        assert_true(sc_cr_vrf_pub_key in pkey_list, "sc cr vrf pub key not in wallet")
        assert_true(sc_cr_sign_pub_key in pkey_list, "sc cr block signer pub key not in wallet")

        # get stake info from genesis block (no owner pub key here)
        sc_genesis_block = sc_node_1.block_best()
        stakeInfo = sc_genesis_block["result"]["block"]["header"]["forgingStakeInfo"]
        stakeAmount = stakeInfo['stakeAmount']
        stakeSignPubKey = stakeInfo["blockSignPublicKey"]["publicKey"]
        stakeVrfPublicKey = stakeInfo["vrfPublicKey"]["publicKey"]

        # check both nodes see the same stake list and same contract amount
        assert_equal(
            sc_node_1.transaction_allForgingStakes()["result"],
            sc_node_2.transaction_allForgingStakes()["result"])
        assert_equal(
            get_account_balance(sc_node_1, ForgerStakeSmartContractAddress),
            get_account_balance(sc_node_2, ForgerStakeSmartContractAddress))

        # get owner pub key from the node stake list (we have only 1 item)
        stakeList = sc_node_1.transaction_allForgingStakes()["result"]['stakes']
        assert_equal(len(stakeList), 1)
        stakeOwnerProposition = stakeList[0]['forgerStakeData']["ownerPublicKey"]["address"]

        # check stake info are as expected
        assert_equal(stakeAmount, convertZenToZennies(self.sc_creation_amount), "Forging stake amount is wrong.")
        assert_equal(stakeSignPubKey, sc_cr_sign_pub_key, "Forging stake block sign key is wrong.")
        assert_equal(stakeVrfPublicKey, sc_cr_vrf_pub_key, "Forging stake vrf key is wrong.")
        assert_equal(stakeOwnerProposition, sc_cr_owner_proposition, "Forging stake owner proposition is wrong.")

        # the balance of the smart contract is as expected
        assert_equal(convertZenniesToWei(stakeAmount), get_account_balance(sc_node_1, ForgerStakeSmartContractAddress),
                     "Contract address balance is wrong.")

        stake_id_genesis = stakeList[0]['stakeId']

        # transfer a small fund from MC to SC2 at a new evm address, do not mine mc block
        # this is for enabling SC 2 gas fee payment when sending txes
        evm_address_sc_node_2 = sc_node_2.wallet_createPrivateKeySecp256k1()["result"]["proposition"]["address"]

        ft_amount_in_zen_2 = Decimal('1.0')
        ft_amount_in_zennies_2 = convertZenToZennies(ft_amount_in_zen_2)
        ft_amount_in_wei_2 = convertZenniesToWei(ft_amount_in_zennies_2)

        forward_transfer_to_sidechain(self.sc_nodes_bootstrap_info.sidechain_id,
                                      mc_node,
                                      evm_address_sc_node_2,
                                      ft_amount_in_zen_2,
                                      mc_return_address=mc_node.getnewaddress(),
                                      generate_block=False)

        time.sleep(2)  # MC needs this

        # transfer some fund from MC to SC1 at a new evm address, then mine mc block
        evm_address_sc_node_1 = sc_node_1.wallet_createPrivateKeySecp256k1()["result"]["proposition"]["address"]

        ft_amount_in_zen = Decimal('500.0')
        ft_amount_in_zennies = convertZenToZennies(ft_amount_in_zen)
        ft_amount_in_wei = convertZenniesToWei(ft_amount_in_zennies)

        forward_transfer_to_sidechain(self.sc_nodes_bootstrap_info.sidechain_id,
                                      mc_node,
                                      evm_address_sc_node_1,
                                      ft_amount_in_zen,
                                      mc_return_address=mc_node.getnewaddress(),
                                      generate_block=True)
        self.sync_all()

        # Generate SC block and check that FTs appears in SCs node wallet
        generate_next_block(sc_node_1, "first node")
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # balance is in wei
        initial_balance_2 = get_account_balance(sc_node_2, evm_address_sc_node_2)
        assert_equal(ft_amount_in_wei_2, initial_balance_2)

        initial_balance_1 = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(ft_amount_in_wei, initial_balance_1)

        # try spending the stake by a sc node which does not own it
        forg_spend_res_2 = sc_node_2.transaction_spendForgingStake(
            json.dumps({"stakeId": str(stake_id_genesis)}))
        assert_true('error' in forg_spend_res_2, "The command should fail")
        assert_equal(forg_spend_res_2['error']['description'], "Forger Stake Owner not found")

        # Try to delegate stake to a fake smart contract. It should fail.
        sc2_blockSignPubKey = sc_node_2.wallet_createPrivateKey25519()["result"]["proposition"]["publicKey"]
        sc2_vrfPubKey = sc_node_2.wallet_createVrfSecret()["result"]["proposition"]["publicKey"]

        forgerStake1_amount = 300  # Zen
        forgerStakes = {"forgerStakeInfo": {
            "ownerAddress": WithdrawalReqSmartContractAddress,  # SC node 1 is an owner
            "blockSignPublicKey": sc2_blockSignPubKey,  # SC node 2 is a block signer
            "vrfPubKey": sc2_vrfPubKey,
            "value": convertZenToZennies(forgerStake1_amount)  # in Satoshi
        }
        }

        makeForgerStakeJsonRes = sc_node_1.transaction_makeForgerStake(json.dumps(forgerStakes))
        if "result" not in makeForgerStakeJsonRes:
            fail("make forger stake with fake smart contract as owner should create a tx: " + json.dumps(makeForgerStakeJsonRes))
        else:
            print("Transaction created as expected")
        generate_next_block(sc_node_1, "first node")
        self.sc_sync_all()

        # Checking the receipt
        receipt = sc_node_1.rpc_eth_getTransactionReceipt(makeForgerStakeJsonRes['result']['transactionId'])
        status = int(receipt['result']['status'], 16)
        assert_equal(0, status, "Make forger stake with fake smart contract as owner should create a failed tx")
        # Check the logs
        assert_equal(0, len(receipt['result']['logs']), "Wrong number of events in receipt")


        # Check balance
        gas_fee_paid, _, _ = computeForgedTxFee(sc_node_1, makeForgerStakeJsonRes['result']['transactionId'])
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 - gas_fee_paid, account_1_balance)
        initial_balance_1 = account_1_balance

        # Try to delegate stake to a smart contract. It should fail.
        initial_secret = random_byte_string(length=20)
        smart_contract_type = 'TestDeployingContract'
        smart_contract_type = SmartContract(smart_contract_type)

        tx_hash, smart_contract_address = smart_contract_type.deploy(sc_node_1, initial_secret,
                                                      fromAddress=format_evm(evm_address_sc_node_1),
                                                      gasLimit=10000000,
                                                      gasPrice=900000000)
        self.sc_sync_all()
        generate_next_block(sc_node_1, "first node")

        self.sc_sync_all()
        # Check balance
        gas_fee_paid, _, _ = computeForgedTxFee(sc_node_1, tx_hash)
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 - gas_fee_paid, account_1_balance)
        initial_balance_1 = account_1_balance

        forgerStakes["forgerStakeInfo"]["ownerAddress"] = format_eoa(smart_contract_address)

        makeForgerStakeJsonRes = sc_node_1.transaction_makeForgerStake(json.dumps(forgerStakes))

        if "result" not in makeForgerStakeJsonRes:
            fail("make forger stake with fake smart contract as owner should create a tx: " + json.dumps(
                makeForgerStakeJsonRes))
        else:
            print("Transaction created as expected")
        generate_next_block(sc_node_1, "first node")
        self.sc_sync_all()

        # Checking the receipt
        receipt = sc_node_1.rpc_eth_getTransactionReceipt(makeForgerStakeJsonRes['result']['transactionId'])
        status = int(receipt['result']['status'], 16)
        assert_equal(0, status, "Make forger stake with fake smart contract as owner should create a failed tx")

        # Check the logs
        assert_equal(0, len(receipt['result']['logs']), "Wrong number of events in receipt")

        # Check balance
        gas_fee_paid, _, _ = computeForgedTxFee(sc_node_1, makeForgerStakeJsonRes['result']['transactionId'])
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 - gas_fee_paid, account_1_balance)
        initial_balance_1 = account_1_balance

        # SC1 Delegate 300 Zen and 200 Zen to SC node 2 - expected stake is 500 Zen

        forgerStake1_amount = 300  # Zen
        forgerStakes["forgerStakeInfo"]["ownerAddress"] = evm_address_sc_node_1
        makeForgerStakeJsonRes = sc_node_1.transaction_makeForgerStake(json.dumps(forgerStakes))
        if "result" not in makeForgerStakeJsonRes:
            fail("make forger stake failed: " + json.dumps(makeForgerStakeJsonRes))
        else:
            print("Forger stake created: " + json.dumps(makeForgerStakeJsonRes))
        tx_hash = makeForgerStakeJsonRes['result']['transactionId']
        self.sc_sync_all()

        # Generate SC block on SC node (keep epoch)
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=False)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Checking the receipt
        receipt = sc_node_1.rpc_eth_getTransactionReceipt(tx_hash)
        status = int(receipt['result']['status'], 16)
        assert_equal(1, status)

        # Check the logs
        assert_equal(1, len(receipt['result']['logs']), "Wrong number of events in receipt")
        event = receipt['result']['logs'][0]
        check_make_forger_stake_event(event, evm_address_sc_node_1, evm_address_sc_node_1,
                                      forgerStake1_amount)

        stakeList = sc_node_1.transaction_allForgingStakes()["result"]['stakes']
        assert_equal(2, len(stakeList))

        # reserve a small amount for fee payments
        amount_for_fees_zen = Decimal('0.01')

        #Check balance
        gas_fee_paid, _, _ = computeForgedTxFee(sc_node_1, tx_hash)
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 - convertZenToWei(forgerStake1_amount) - gas_fee_paid, account_1_balance)
        initial_balance_1 = account_1_balance

        value_spent = forgerStake1_amount + convertWeiToZen(gas_fee_paid)
        forgerStake2_amount = ft_amount_in_zen - amount_for_fees_zen - Decimal(value_spent)
        forgerStakes = {"forgerStakeInfo": {
            "ownerAddress": evm_address_sc_node_1,  # SC node 1 is an owner
            "blockSignPublicKey": sc2_blockSignPubKey,  # SC node 2 is a block signer
            "vrfPubKey": sc2_vrfPubKey,
            "value": convertZenToZennies(forgerStake2_amount)  # in Satoshi
        }
        }
        makeForgerStakeJsonRes = sc_node_1.transaction_makeForgerStake(json.dumps(forgerStakes))
        if "result" not in makeForgerStakeJsonRes:
            fail("make forger stake failed: " + json.dumps(makeForgerStakeJsonRes))
        else:
            print("Forger stake created: " + json.dumps(makeForgerStakeJsonRes))
        self.sc_sync_all()
        tx_hash = makeForgerStakeJsonRes['result']['transactionId']

        # Generate SC block on SC node (keep epoch)
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=False)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Checking the receipt
        receipt = sc_node_1.rpc_eth_getTransactionReceipt(tx_hash)
        status = int(receipt['result']['status'], 16)
        assert_equal(1, status)

        # Check the logs
        assert_equal(1, len(receipt['result']['logs']), "Wrong number of events in receipt")
        event = receipt['result']['logs'][0]
        check_make_forger_stake_event(event, evm_address_sc_node_1, evm_address_sc_node_1,
                                      forgerStake2_amount)
        # we now have 3 stakes
        stakeList = sc_node_1.transaction_allForgingStakes()["result"]['stakes']
        assert_equal(3, len(stakeList))

        #Check balance
        gas_fee_paid, _, _ = computeForgedTxFee(sc_node_1, tx_hash)
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 - convertZenToWei(forgerStake2_amount) - gas_fee_paid, account_1_balance)
        initial_balance_1 = account_1_balance

        # Verify SC node 2 can not forge yet
        exception_occurs = False
        try:
            print("SC2 Trying to generate a block: should fail...")
            generate_next_block(sc_node_2, "second node", force_switch_to_next_epoch=False)
        except Exception as e:
            exception_occurs = True
            print("We had an exception as expected: {}".format(str(e)))
        finally:
            assert_true(exception_occurs, "No forging stakes expected for SC node 2.")
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Generate SC block on SC node forcing epoch change
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=True)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Verify SC node 2 can not forge yet
        exception_occurs = False
        try:
            print("Trying to generate a block: should fail...")
            generate_next_block(sc_node_2, "second node", force_switch_to_next_epoch=False)
        except Exception as e:
            exception_occurs = True
            print("We had an exception as expected: {}".format(str(e)))
        finally:
            assert_true(exception_occurs, "No forging stakes expected for SC node 2.")
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Generate SC block on SC node forcing epoch change
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=True)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # After 2 epoch switches SC2 can now forge
        generate_next_block(sc_node_2, "second node", force_switch_to_next_epoch=False)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # check we have the expected stake total amount
        assert_equal(
            convertZenToWei(forgerStake1_amount) +
            convertZenToWei(forgerStake2_amount) +
            convertZenniesToWei(stakeAmount),
            get_account_balance(sc_node_1, ForgerStakeSmartContractAddress))

        # spend the genesis stake
        print("SC1 spends genesis stake...")
        spendForgerStakeJsonRes = sc_node_1.transaction_spendForgingStake(
            json.dumps({"stakeId": str(stake_id_genesis)}))
        if "result" not in spendForgerStakeJsonRes:
            fail("spend forger stake failed: " + json.dumps(spendForgerStakeJsonRes))
        else:
            print("Forger stake removed: " + json.dumps(spendForgerStakeJsonRes))
        self.sc_sync_all()
        tx_hash = spendForgerStakeJsonRes['result']['transactionId']

        # Generate SC block on SC node 1 (keep epoch)
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=False)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # check the genesis staked amount has been transferred from contract to owner address
        assert_equal(convertZenniesToWei(stakeAmount), get_account_balance(sc_node_1, sc_cr_owner_proposition))
        assert_equal(
            convertZenToWei(forgerStake1_amount) +
            convertZenToWei(forgerStake2_amount),
            get_account_balance(sc_node_1, ForgerStakeSmartContractAddress))

        #Check balance
        gas_fee_paid, _, _ = computeForgedTxFee(sc_node_1, tx_hash)
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 - gas_fee_paid, account_1_balance)
        initial_balance_1 = account_1_balance

        # Generate SC block on SC node 1 switching epoch
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=True)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Generate SC block on SC node 1 keeping epoch
        generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=False)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Verify SC node 1 now can not forge anymore if switching epoch
        exception_occurs = False
        try:
            print("SC1 Trying to generate a block: should fail...")
            generate_next_block(sc_node_1, "first node", force_switch_to_next_epoch=True)
        except Exception as e:
            exception_occurs = True
            print("We had an exception as expected: {}".format(str(e)))
        finally:
            assert_true(exception_occurs, "No forging stakes expected for SC node 1.")

        stakeList = sc_node_1.transaction_allForgingStakes()["result"]['stakes']
        assert_equal(2, len(stakeList))

        stakeId_1 = stakeList[0]['stakeId']
        stakeId_2 = stakeList[1]['stakeId']

        # balance is in wei
        final_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1, final_balance)
        initial_balance_1 = final_balance

        bal_sc_cr_prop = get_account_balance(sc_node_1, sc_cr_owner_proposition)
        assert_equal(convertZenniesToWei(stakeAmount), bal_sc_cr_prop)
        assert_equal(
            convertZenToWei(forgerStake1_amount) +
            convertZenToWei(forgerStake2_amount),
            get_account_balance(sc_node_1, ForgerStakeSmartContractAddress), "Contract address balance is wrong.")

        # SC1 remove all the remaining stakes
        spendForgerStakeJsonRes = sc_node_1.transaction_spendForgingStake(
            json.dumps({"stakeId": str(stakeId_1)}))
        if "result" not in spendForgerStakeJsonRes:
            fail("spend forger stake failed: " + json.dumps(spendForgerStakeJsonRes))
        else:
            print("Forger stake removed: " + json.dumps(spendForgerStakeJsonRes))
        self.sc_sync_all()
        tx_hash = spendForgerStakeJsonRes['result']['transactionId']

        # Generate SC block on SC node 1
        generate_next_block(sc_node_2, "second node", force_switch_to_next_epoch=True)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Checking the receipt
        receipt = sc_node_1.rpc_eth_getTransactionReceipt(tx_hash)
        status = int(receipt['result']['status'], 16)
        assert_equal(1, status)

        # Check the logs
        assert_equal(1, len(receipt['result']['logs']), "Wrong number of events in receipt")
        event = receipt['result']['logs'][0]
        check_spend_forger_stake_event(event, evm_address_sc_node_1, stakeId_1)


        stakeList = sc_node_1.transaction_allForgingStakes()["result"]['stakes']
        assert_equal(len(stakeList), 1)

        #Check balance
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 + convertZenToWei(forgerStake1_amount), account_1_balance)
        initial_balance_1 = account_1_balance


        # TODO when we have no more ForgerStakes the SC is dead!!!
        # proposal: prevent spending of last stake (a minimal stake must be added beforehand)
        spendForgerStakeJsonRes = sc_node_1.transaction_spendForgingStake(
            json.dumps({"stakeId": str(stakeId_2)}))
        if "result" not in spendForgerStakeJsonRes:
            fail("spend forger stake failed: " + json.dumps(spendForgerStakeJsonRes))
        else:
            print("Forger stake removed: " + json.dumps(spendForgerStakeJsonRes))
        self.sc_sync_all()
        tx_hash = spendForgerStakeJsonRes['result']['transactionId']

        # Generate SC block on SC node
        generate_next_block(sc_node_2, "first node", force_switch_to_next_epoch=True)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # we have no more stakes!!
        stakeList = sc_node_1.transaction_allForgingStakes()["result"]['stakes']
        assert_equal(len(stakeList), 0)

        # all balance is now at the expected owner address
        # Checking the receipt
        receipt = sc_node_1.rpc_eth_getTransactionReceipt(tx_hash)
        status = int(receipt['result']['status'], 16)
        assert_equal(1, status)

        # Check the logs
        assert_equal(1, len(receipt['result']['logs']), "Wrong number of events in receipt")
        event = receipt['result']['logs'][0]
        check_spend_forger_stake_event(event, evm_address_sc_node_1, stakeId_2)

        #Check balance
        account_1_balance = get_account_balance(sc_node_1, evm_address_sc_node_1)
        assert_equal(initial_balance_1 + convertZenToWei(forgerStake2_amount), account_1_balance)

        # Generate SC block on SC node keeping current epoch
        generate_next_block(sc_node_2, "first node", force_switch_to_next_epoch=False)
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)

        # Try to generate one more SC block switching epoch, that should fail because while the forging itself will
        # be successful (the forger info points to two epoch earlier), the block can not be applied
        # since consensus epoch info are not valid (empty list of stakes)
        exception_occurs = False
        try:
            print("Trying to generate a block: should fail...")
            generate_next_block(sc_node_2, "first node", force_switch_to_next_epoch=True)
            self.sc_sync_all()
            print_current_epoch_and_slot(sc_node_1)
        except Exception as e:
            exception_occurs = True
            print("We had an exception as expected: {}".format(str(e)))

        '''
        finally:
            assert_true(exception_occurs, "No forging stakes expected for SC node 1.")
        self.sc_sync_all()
        print_current_epoch_and_slot(sc_node_1)
        '''
        input("_")


if __name__ == "__main__":
    SCEvmForger().main()
