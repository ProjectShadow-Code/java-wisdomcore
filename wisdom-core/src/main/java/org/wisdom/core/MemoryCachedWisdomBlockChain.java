package org.wisdom.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import lombok.Getter;
import org.apache.commons.dbcp2.BasicDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.tdf.common.util.HexBytes;
import org.wisdom.core.account.Transaction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
method calls after writing 2w blocks
{
  "getBlock" : 60498, 缓存命中率 66.7%
  "currentHeader" : 3,
  "getLastConfirmedBlock" : 1,
  "getAncestorBlocks" : 168,
  "getCanonicalBlocks" : 1,
  "getCanonicalHeader" : 5,
  "getCanonicalBlock" : 1,
  "getHeader" : 2399082 缓存命中率 99.16%
}
 */
// TODO: monitor average/summary time consuming for each query
@Component
public class MemoryCachedWisdomBlockChain implements WisdomBlockChain {
    private WisdomBlockChain delegate;

    // yet another a null pointer for caching
    private static final Block TRAP_VALUE = new Block();

    private static final int MAXIMUM_CACHE_SIZE = 256;

    private Cache<HexBytes, Block> blockCache = Caffeine.newBuilder()
            .maximumSize(MAXIMUM_CACHE_SIZE)
            .recordStats()
            .build();

    private Cache<HexBytes, Block> headerCache = Caffeine.newBuilder()
            .maximumSize(MAXIMUM_CACHE_SIZE)
            .recordStats()
            .build();

    private Cache<HexBytes, Boolean> hasBlockCache = Caffeine
            .newBuilder()
            .maximumSize(MAXIMUM_CACHE_SIZE)
            .recordStats()
            .build();

    private Block currentHeader;

    private Block currentBlock;

    private Block lastConfirmed;

    private ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public MemoryCachedWisdomBlockChain(
            JdbcTemplate tmpl,
            TransactionTemplate txTmpl,
            Block genesis,
            ApplicationContext ctx,
            @Value("${spring.datasource.username}") String databaseUserName,
            @Value("${clear-data}") boolean clearData,
            BasicDataSource basicDataSource) throws Exception {
        this.delegate = new RDBMSBlockChainImpl(tmpl, txTmpl, genesis, ctx, databaseUserName, clearData, basicDataSource);
    }

    // count method calls
    @Getter
    private Map<String, Long> callsCounter = new HashMap<>();

    // time consuming for each method
    @Getter
    private Map<String, Long> timeConsuming = new HashMap<>();

    public Map<String, CacheStats> getCacheStats() {
        return new HashMap<String, CacheStats>() {{
            put("blockCache", blockCache.stats());
            put("headerCache", headerCache.stats());
            put("hasBlockCache", hasBlockCache.stats());
        }};
    }

    public Map<String, Double> getHitRate() {
        return new HashMap<String, Double>() {{
            put("blockCache", blockCache.stats().hitRate());
            put("headerCache", headerCache.stats().hitRate());
            put("hasBlockCache", hasBlockCache.stats().hitRate());
        }};
    }

    private void recordMetric(String method, long duration) {
        callsCounter.put(method, callsCounter.getOrDefault(method, 0L) + 1);
        timeConsuming.put(method, timeConsuming.getOrDefault(method, 0L) + duration);
    }

    private void clearCache(byte[] hash) {
        currentHeader = null;
        currentBlock = null;
        lastConfirmed = null;
        hasBlockCache.asMap().remove(HexBytes.fromBytes(hash));
        blockCache.asMap().remove(HexBytes.fromBytes(hash));
        headerCache.asMap().remove(HexBytes.fromBytes(hash));
    }

    @Override
    public Block getGenesis() {
        return delegate.getGenesis();
    }

    @Override
    public boolean containsBlock(byte[] hash) {
        final String method = "containsBlock";
        long start = System.currentTimeMillis();
        try {
            return hasBlockCache.get(HexBytes.fromBytes(hash), (x) -> delegate.containsBlock(x.getBytes()));
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Block getTopHeader() {
        final String method = "getTopHeader";
        long start = System.currentTimeMillis();
        try {
            if (currentHeader != null) return currentHeader;
            currentHeader = delegate.getTopHeader();
            return currentHeader;
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Block getTopBlock() {
        final String method = "getTopBlock";
        long start = System.currentTimeMillis();
        try {
            if (currentBlock != null) return currentBlock;
            currentBlock = delegate.getTopBlock();
            return currentBlock;
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Block getHeaderByHash(byte[] blockHash) {
        final String method = "getHeaderByHash";
        long start = System.currentTimeMillis();
        try {
            Block h = headerCache.get(HexBytes.fromBytes(blockHash), (x) -> {
                Block header = delegate.getHeaderByHash(x.getBytes());
                return header == null ? TRAP_VALUE : header;
            });
            return h == TRAP_VALUE ? null : h;
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Block getBlockByHash(byte[] blockHash) {
        final String method = "getBlockByHash";
        long start = System.currentTimeMillis();
        try {
            Block b = blockCache.get(HexBytes.fromBytes(blockHash), (x) -> {
                Block block = delegate.getBlockByHash(x.getBytes());
                return block == null ? TRAP_VALUE : block;
            });
            return b == TRAP_VALUE ? null : b;
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public List<Block> getHeadersSince(long startHeight, int headersCount) {
        final String method = "getHeadersSince";
        long start = System.currentTimeMillis();
        try {
            return delegate.getHeadersSince(startHeight, headersCount);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public List<Block> getBlocksSince(long startHeight, int headersCount) {
        final String method = "getBlocksSince";
        long start = System.currentTimeMillis();
        try {
            return delegate.getBlocksSince(startHeight, headersCount);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public List<Block> getHeadersBetween(long startHeight, long stopHeight, int sizeLimit, boolean clipInitial) {
        final String method = "getHeadersBetween";
        long start = System.currentTimeMillis();
        try {
            return delegate.getHeadersBetween(startHeight, stopHeight, sizeLimit, clipInitial);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }


    @Override
    public List<Block> getBlocksBetween(long startHeight, long stopHeight, int sizeLimit, boolean clipInitial) {
        final String method = "getBlocksBetween";
        long start = System.currentTimeMillis();
        try {
            return delegate.getBlocksBetween(startHeight, stopHeight, sizeLimit, clipInitial);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Block getHeaderByHeight(long height) {
        final String method = "getHeaderByHeight";
        long start = System.currentTimeMillis();
        try {
            return delegate.getHeaderByHeight(height);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Block getBlockByHeight(long height) {
        final String method = "getBlockByHeight";
        long start = System.currentTimeMillis();
        try {
            return delegate.getBlockByHeight(height);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean writeBlock(Block block) {
        boolean ret = delegate.writeBlock(block);
        clearCache(block.getHash());
        if (ret) {
            headerCache.put(HexBytes.fromBytes(block.getHash()), block);
            blockCache.put(HexBytes.fromBytes(block.getHash()), block);
            hasBlockCache.put(HexBytes.fromBytes(block.getHash()), true);
        }
        return ret;
    }

    @Override
    public List<Block> getAncestorHeaders(byte[] bhash, long anum) {
        final String method = "getAncestorHeaders";
        long start = System.currentTimeMillis();
        try {
            return delegate.getAncestorHeaders(bhash, anum);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public List<Block> getAncestorBlocks(byte[] bhash, long anum) {
        final String method = "getAncestorBlocks";
        long start = System.currentTimeMillis();
        try {
            return delegate.getAncestorBlocks(bhash, anum);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public long getTopHeight() {
        final String method = "getTopHeight";
        long start = System.currentTimeMillis();
        try {
            return delegate.getTopHeight();
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean containsTransaction(byte[] txHash) {
        final String method = "hasTransaction";
        long start = System.currentTimeMillis();
        try {
            return delegate.containsTransaction(txHash);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public boolean containsPayload(int type, byte[] payload) {
        final String method = "containsPayload";
        long start = System.currentTimeMillis();
        try {
            return delegate.containsPayload(type, payload);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Transaction getTransaction(byte[] txHash) {
        final String method = "getTransaction";
        long start = System.currentTimeMillis();
        try {
            return delegate.getTransaction(txHash);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public Transaction getTransactionByTo(byte[] pubKeyHash) {
        final String method = "getTransactionByTo";
        long start = System.currentTimeMillis();
        try {
            return delegate.getTransactionByTo(pubKeyHash);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }

    @Override
    public List<Transaction> getTransactionsByFrom(byte[] publicKey, int offset, int limit) {
        return delegate.getTransactionsByFrom(publicKey, offset, limit);
    }

    @Override
    public List<Transaction> getTransactionsByTypeAndFrom(int type, byte[] publicKey, int offset, int limit) {
        return delegate.getTransactionsByTypeAndFrom(type, publicKey, offset, limit);
    }

    @Override
    public List<Transaction> getTransactionsByTo(byte[] publicKeyHash, int offset, int limit) {
        return delegate.getTransactionsByTo(publicKeyHash, offset, limit);
    }

    @Override
    public List<Transaction> getTransactionsByTypeAndTo(int type, byte[] to, int offset, int limit) {
        return delegate.getTransactionsByTypeAndTo(type, to, offset, limit);
    }

    @Override
    public List<Transaction> getTransactionsByFromAndTo(byte[] from, byte[] to, int offset, int limit) {
        return delegate.getTransactionsByFromAndTo(from, to, offset, limit);
    }

    @Override
    public List<Transaction> getTransactionsByTypeFromAndTo(int type, byte[] from, byte[] to, int offset, int limit) {
        return delegate.getTransactionsByTypeFromAndTo(type, from, to, offset, limit);
    }

    @Override
    public long countBlocksAfter(long timestamp) {
        final String method = "countBlocksAfter";
        long start = System.currentTimeMillis();
        try {
            return delegate.countBlocksAfter(timestamp);
        } finally {
            recordMetric(method, System.currentTimeMillis() - start);
        }
    }


}
