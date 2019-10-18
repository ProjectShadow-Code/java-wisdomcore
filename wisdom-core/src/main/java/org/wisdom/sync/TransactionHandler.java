package org.wisdom.sync;

import com.googlecode.concurrentlinkedhashmap.ConcurrentLinkedHashMap;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.wisdom.ApiResult.APIResult;
import org.wisdom.core.account.Transaction;
import org.wisdom.p2p.*;
import org.wisdom.service.CommandService;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Component
public class TransactionHandler implements Plugin {
    private PeerServer server;
    private static final int CACHE_SIZE = 256;
    private ConcurrentMap<String, Boolean> transactionCache;

    private static final Logger logger = LoggerFactory.getLogger(TransactionHandler.class);
    @Autowired
    private CommandService commandService;

    public TransactionHandler() {
        this.transactionCache = new ConcurrentLinkedHashMap.Builder<String, Boolean>().maximumWeightedCapacity(CACHE_SIZE).build();
    }

    @Override
    public void onMessage(Context context, PeerServer server) {
        if (context.getPayload().getCode() != WisdomOuterClass.Code.TRANSACTIONS) {
            return;
        }
        WisdomOuterClass.Transactions txs = (WisdomOuterClass.Transactions) context.getPayload().getBody();
        String key = Hex.encodeHexString(Utils.getTransactionsHash(txs.getTransactionsList()));
        if (transactionCache.containsKey(key)){
            return;
        }
        transactionCache.put(key, true);
        txs.getTransactionsList()
                .stream()
                .map(Utils::parseTransaction)
                .forEach(t -> {
            transactionCache.put(t.getHashHexString(), true);
            byte[] traninfo = t.toRPCBytes();
            APIResult apiResult=commandService.verifyTransfer(traninfo);
            if(apiResult.getCode() == 5000){
                logger.info("transaction Check failure,TxHash="+Hex.encodeHexString(t.getHash())+",message:"+apiResult.getMessage());
            }
        });
        context.relay();
    }

    @Override
    public void onStart(PeerServer server) {
        this.server = server;
    }

    public void broadcastTransactions(List<Transaction> txs) {
        List<WisdomOuterClass.Transaction> encoded = txs.stream().map(Utils::encodeTransaction).collect(Collectors.toList());

        String key = Hex.encodeHexString(Utils.getTransactionsHash(encoded));
        transactionCache.put(key, true);

        Optional.ofNullable(server)
                .ifPresent(s -> {
                    WisdomOuterClass.Transactions.Builder builder = WisdomOuterClass.Transactions.newBuilder();
                    txs.forEach(t -> transactionCache.put(t.getHashHexString(), true));
                    encoded.forEach(builder::addTransactions);
                    txs.stream().map(Utils::encodeTransaction).forEach(builder::addTransactions);
                    Util.split(builder.build()).forEach(s::broadcast);
                });
    }
}
