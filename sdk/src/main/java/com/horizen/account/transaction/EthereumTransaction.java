package com.horizen.account.transaction;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonView;
import com.horizen.account.proof.SignatureSecp256k1;
import com.horizen.serialization.Views;
import com.horizen.transaction.Transaction;
import com.horizen.transaction.TransactionSerializer;
import com.horizen.transaction.exception.TransactionSemanticValidityException;
import org.web3j.crypto.RawTransaction;
import org.web3j.utils.Numeric;

import java.util.Objects;


@JsonView(Views.Default.class)
public class EthereumTransaction extends Transaction {

    @JsonIgnoreProperties({"legacyTransaction", "eip1559Transaction"})
    private final RawTransaction transaction;

    private final SignatureSecp256k1 signature;

    private final int version = 1;

    public EthereumTransaction(RawTransaction transaction,
                               SignatureSecp256k1 signature){
        Objects.requireNonNull(transaction, "Raw Transaction can't be null.");
        Objects.requireNonNull(signature, "Signature can't be null.");
        this.transaction = transaction;
        this.signature = signature;
    }

    @Override
    public byte transactionTypeId() {
        return AccountTransactionsIdsEnum.EthereumTransaction.id();
    }

    @Override
    public byte version() {
        return version;
    }

    @Override
    public void semanticValidity() throws TransactionSemanticValidityException {
        // TODO
    }

    @Override
    public byte[] messageToSign() {
        return new byte[0];
    }

    @Override
    public TransactionSerializer serializer() {
        return EthereumTransactionSerializer.getSerializer();
    }

    public RawTransaction getTransaction() { return this.transaction; }

    public SignatureSecp256k1 getSignature() { return this.signature; }

    @Override
    public String toString() {
        return String.format(
                "EthereumTransaction{nonce=%s, gasPrice=%s, gasLimit=%s, to=%s, data=%s, Signature=%s}",
                Numeric.toHexStringWithPrefix(transaction.getNonce()),
                Numeric.toHexStringWithPrefix(transaction.getGasPrice()),
                Numeric.toHexStringWithPrefix(transaction.getGasLimit()),
                transaction.getTo(),
                transaction.getData(),
                signature.toString()
        );
    }
}
