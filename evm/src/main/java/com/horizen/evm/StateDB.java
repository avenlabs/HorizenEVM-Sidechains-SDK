package com.horizen.evm;

import com.horizen.evm.interop.EvmLog;
import com.horizen.evm.interop.ProofAccountResult;
import com.horizen.evm.utils.Converter;

import java.math.BigInteger;
import java.util.Arrays;

public class StateDB extends ResourceHandle {
    /**
     * Code hash of an empty byte array
     */
    public static final byte[] EMPTY_CODE_HASH =
        Converter.fromHexString("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470");

    /**
     * TrieHasher.Root() of an empty byte array
     */
    public static final byte[] EMPTY_ROOT_HASH =
        Converter.fromHexString("56e81f171bcc55a6ff8345e692c0f86e5b48e01b996cadc001622fb5e363b421");

    /**
     * Opens a view on the state at the given state root hash.
     *
     * @param db   database instance
     * @param root root hash
     */
    public StateDB(Database db, byte[] root) {
        super(LibEvm.stateOpen(db.handle, root));
    }

    /**
     * Close this instance and free up any native resources. Must not use this instance afterwards.
     */
    @Override
    public void close() throws Exception {
        LibEvm.stateClose(handle);
    }

    /**
     * Finalize any pending changes, clear the current journal and reset refund counter.
     * Note: Must be called between separate transactions as rollbacks are not supported over multiple transactions,
     * therefore also invalidates all snapshots.
     */
    public void finalizeChanges() {
        LibEvm.stateFinalize(handle);
    }

    /**
     * Get current state root hash including any currently pending changes, but without committing.
     *
     * @return state root hash
     */
    public byte[] getIntermediateRoot() {
        return LibEvm.stateIntermediateRoot(handle);
    }

    /**
     * Commit any pending changes. Invalidates all snapshots taken before.
     *
     * @return updated state root hash
     */
    public byte[] commit() {
        return LibEvm.stateCommit(handle);
    }

    /**
     * Check if the account with the given address is empty
     *
     * @param address account address
     * @return true if account state is empty, otherwise false
     */
    public boolean isEmpty(byte[] address) {
        return LibEvm.stateEmpty(handle, address);
    }

    /**
     * Check if account is an EAO one. Account is considered an EOA in two cases:
     * <ol>
     * <li>account doesn't exist in the StateDB (first time it receives coins);</li>
     * <li>account has no code (code hash is a keccak256 hash of empty array).</li>
     * </ol>
     *
     * @param address account address
     * @return true if account is EOA, otherwise false
     */
    public boolean isEoaAccount(byte[] address) {
        return isEmpty(address) || Arrays.equals(getCodeHash(address), EMPTY_CODE_HASH);
    }

    /**
     * Check if an account is a smart contract account
     *
     * @param address account address
     * @return true if account is a smart contract one, otherwise false
     */
    public boolean isSmartContractAccount(byte[] address) {
        return !isEoaAccount(address);
    }

    /**
     * Get balance of given account.
     *
     * @param address account address
     * @return account balance, 0 if account not exist
     */
    public BigInteger getBalance(byte[] address) {
        return LibEvm.stateGetBalance(handle, address);
    }

    /**
     * Add amount to balance of given account. Will implicity create account if necessary.
     *
     * @param address account address
     * @param amount  amount to add to account balance
     */
    public void addBalance(byte[] address, BigInteger amount) {
        LibEvm.stateAddBalance(handle, address, amount);
    }

    /**
     * Subtract from balance of given account.
     *
     * @param address account address
     * @param amount  amount to subtract from account balance
     */
    public void subBalance(byte[] address, BigInteger amount) {
        LibEvm.stateSubBalance(handle, address, amount);
    }

    /**
     * Set balance of account balance
     *
     * @param address account address
     * @param amount  amount to assign to the account balance
     */
    public void setBalance(byte[] address, BigInteger amount) {
        LibEvm.stateSetBalance(handle, address, amount);
    }

    /**
     * Get account nonce.
     *
     * @param address account address
     * @return account nonce
     */
    public BigInteger getNonce(byte[] address) {
        return LibEvm.stateGetNonce(handle, address);
    }

    /**
     * Set account nonce.
     *
     * @param address account address
     * @param nonce   value to set account nonce to
     */
    public void setNonce(byte[] address, BigInteger nonce) {
        LibEvm.stateSetNonce(handle, address, nonce);
    }

    /**
     * Get account code hash.
     *
     * @param address account address
     * @return code hash
     */
    public byte[] getCodeHash(byte[] address) {
        return LibEvm.stateGetCodeHash(handle, address);
    }

    /**
     * Get code for the given account.
     *
     * @param address account address
     */
    public byte[] getCode(byte[] address) {
        return LibEvm.stateGetCode(handle, address);
    }

    /**
     * Set code for the given account. Will also recalculate and set code hash accordingly.
     *
     * @param address account address
     * @param code    code binary
     */
    public void setCode(byte[] address, byte[] code) {
        LibEvm.stateSetCode(handle, address, code);
    }

    /**
     * Add gas refund.
     *
     * @param gas amount to add to refund counter
     */
    public void addRefund(BigInteger gas) {
        LibEvm.refundAdd(handle, gas);
    }

    /**
     * Remove gas refund.
     *
     * @param gas amount to remove from refund counter
     */
    public void subRefund(BigInteger gas) {
        LibEvm.refundSub(handle, gas);
    }

    /**
     * Get refunded gas.
     *
     * @return refunded gas
     */
    public BigInteger getRefund() {
        return LibEvm.refundGet(handle);
    }

    /**
     * Read storage trie of given account.
     *
     * @param address  account address
     * @param key      storage key
     * @return storage value, always 32 bytes
     */
    public byte[] getStorage(byte[] address, byte[] key) {
        return LibEvm.stateGetStorage(handle, address, key);
    }

    /**
     * Read comitted storage trie of given account.
     *
     * @param address account address
     * @param key     storage key
     * @return comitted storage value, always 32 bytes
     */
    public byte[] getCommittedStorage(byte[] address, byte[] key) {
        return LibEvm.stateGetCommittedStorage(handle, address, key);
    }

    /**
     * Write to storage trie of given account.
     * Note: Do not mix RAW and CHUNKED strategies for the same key,
     * this can potentially lead to dangling nodes in the storage Trie and de facto infinite-loops.
     *
     * @param address  account address
     * @param key      storage key
     * @param value    value to store
     */
    public void setStorage(byte[] address, byte[] key, byte[] value) {
        LibEvm.stateSetStorage(handle, address, key, value);
    }

    /**
     * Get the Merkle-proof for a given account and optionally some storage keys.
     *
     * @param address account address
     * @param keys    storage keys
     * @return proofs
     */
    public ProofAccountResult getProof(byte[] address, byte[][] keys) {
        return LibEvm.stateGetProof(handle, address, keys);
    }

    /**
     * Create a lightweight snapshot at the current state.
     *
     * @return revision id of the snapshot
     */
    public int snapshot() {
        return LibEvm.stateSnapshot(handle);
    }

    /**
     * Rollback all state modifications since the snapshot with the given revision id was created.
     *
     * @param revisionId revision id of the snapshot to revert to
     */
    public void revertToSnapshot(int revisionId) {
        LibEvm.stateRevertToSnapshot(handle, revisionId);
    }

    /**
     * Get log entries created during the execution of given transaction.
     *
     * @param txHash transaction hash
     * @return log entries related to given transaction hash
     */
    public EvmLog[] getLogs(byte[] txHash) {
        return LibEvm.stateGetLogs(handle, txHash);
    }

    /**
     * Add a new log entry.
     *
     * @param evmLog log entry
     */
    public void addLog(EvmLog evmLog) {
        LibEvm.stateAddLog(handle, evmLog);
    }

    /**
     * Set tx context, used when the EVM emits new state logs.
     *
     * @param txHash  the hash of the transaction to be set in context
     * @param txIndex the index of the transaction in the block
     */
    public void setTxContext(byte[] txHash, int txIndex) {
        LibEvm.stateSetTxContext(handle, txHash, txIndex);
    }

    public boolean accessAccount(byte[] address) {
        return LibEvm.accessAccount(handle, address);
    }

    public boolean accessSlot(byte[] address, byte[] slot) {
        return LibEvm.accessSlot(handle, address, slot);
    }

    @Override
    public String toString() {
        return String.format("StateDB{handle=%d}", handle);
    }
}
