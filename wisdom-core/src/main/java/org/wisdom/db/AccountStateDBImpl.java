package org.wisdom.db;

import com.google.gson.internal.$Gson$Preconditions;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.tdf.common.serialize.Codec;
import org.tdf.common.store.NoDeleteStore;
import org.tdf.common.store.Store;
import org.tdf.common.trie.Trie;
import org.tdf.common.trie.TrieImpl;
import org.tdf.common.util.ByteArrayMap;
import org.tdf.common.util.HexBytes;
import org.tdf.rlp.RLPCodec;

import org.wisdom.Start;
import org.wisdom.account.PublicKeyHash;
import org.wisdom.core.Block;
import org.wisdom.core.WisdomBlockChain;

import org.wisdom.core.account.Account;
import org.wisdom.core.account.AccountDB;
import org.wisdom.core.incubator.Incubator;
import org.wisdom.core.incubator.IncubatorDB;
import org.wisdom.crypto.HashUtil;

import org.wisdom.genesis.Genesis;
import org.wisdom.store.NoDeleteByteArrayStore;
import org.wisdom.util.Address;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class AccountStateDBImpl implements AccountStateDB {
    @Getter(AccessLevel.PACKAGE)
    private static final int BLOCKS_PER_UPDATE_LOWER_BOUNDS = 4096;

    private static final String TRIE = "trie";

    private static final String DELETED = "deleted";

    private static final String STATE_ROOTS = "state-roots";

    private static final String DB_STATUS = "status";

    private static final byte[] LAST_SYNCED_HEIGHT = "last-confirmed".getBytes(StandardCharsets.US_ASCII);

    // mark whether the state of the block had been stored
    // block hash -> long
    private Store<byte[], byte[]> statusStore;

    // block hash -> state root
    @Getter(AccessLevel.PACKAGE)
    private Store<byte[], byte[]> rootStore; /////

    // removed key in trie store -> dummy, for cleaning up when data replicated too much
    private Store<byte[], byte[]> deleted;

    // store actual data of trie
    private Store<byte[], byte[]> trieStore;

    private NoDeleteStore<byte[], byte[]> noDeleteStore; /////

    private Map<Long, byte[]> heights = new TreeMap<>();

    private byte[] nullHash;

    // trie to revert
    private Trie<byte[], AccountState> stateTrie;

    private WisdomBlockChain bc;

    private AccountStateUpdater accountStateUpdater;

    private AccountDB accountDB;

    private IncubatorDB incubatorDB;

    public AccountStateDBImpl(
            DatabaseStoreFactory factory,
            Block genesis,
            WisdomBlockChain bc,
            AccountStateUpdater accountStateUpdater,
            AccountDB accountDB,
            Genesis genesisJSON,
            IncubatorDB incubatorDB
    ) throws InvalidProtocolBufferException, DecoderException {
        this.bc = bc;
        this.accountStateUpdater = accountStateUpdater;
        this.genesis = genesisJSON;
        this.accountDB = accountDB;
        this.incubatorDB = incubatorDB;
        trieStore = factory.create(TRIE, false);
        deleted = factory.create(DELETED, false);
        rootStore = factory.create(STATE_ROOTS, false);
        noDeleteStore = new NoDeleteByteArrayStore(trieStore, deleted);
        statusStore = factory.create(DB_STATUS, false);

        stateTrie = TrieImpl.newInstance(
                HashUtil::keccak256,
                noDeleteStore,
                Codec.identity(),
                Codec.newInstance(RLPCodec::encode, (x) -> RLPCodec.decode(x, AccountState.class))
        );

        nullHash = stateTrie.getRootHash();
        // put parent hash of genesis map to null hash
        rootStore.putIfAbsent(genesis.hashPrevBlock, nullHash);

        sync();
    }

    Genesis genesis;

    // sync state trie to best block
    private void sync() {
        // query for had been written
        long lastSyncedHeight = statusStore.get(LAST_SYNCED_HEIGHT).map(RLPCodec::decodeLong).orElse(-1L);

        int blocksPerUpdate = BLOCKS_PER_UPDATE_LOWER_BOUNDS;
        while (true) {
            List<Block> blocks = bc.getCanonicalBlocks(lastSyncedHeight + 1, blocksPerUpdate);
            for (Block block : blocks) {
                // get all related accounts
                Set<byte[]> all = accountStateUpdater.getRelatedAccounts(block);
                final Map<byte[], AccountState> accounts = new ByteArrayMap<>();
                all.stream()
                        .map(x -> getAccount(block.hashPrevBlock, x)
                                .orElse(accountStateUpdater.createEmpty(x)))
                        .forEach(x -> accounts.put(x.getAccount().getPubkeyHash(), x));

                Map<byte[], AccountState> updated;
                if (block.nHeight == 0) {
                    updated = accountStateUpdater.generateGenesisStates(block, genesis);
                } else {
                    updated = accountStateUpdater.updateAll(accounts, block);
                }

                heights.put(block.nHeight, block.getHash());
                statusStore.put(block.getHash(), putAccounts(block.hashPrevBlock, block.getHash(), updated.values()));
                List<Account> accountList = accountDB.getUpdatedAccounts(block.nHeight);
                for (Account account : accountList) {
                    AccountState state = updated.get(account.getPubkeyHash());
                    Incubator incubator = incubatorDB.selectIncubator(account.getPubkeyHash(), block.getnHeight());
                    if (incubator == null) {
                        continue;
                    }
//                    incubator.setCost();
                    Incubator incubator2 = state.getInterestMap().get(account.getPubkeyHash());
                    if(state.getInterestMap().get(account.getPubkeyHash()).equals(incubator)){
                        System.out.println();
                    }
                    if (!Address.publicKeyHashToAddress(account.getPubkeyHash()).equals("1PxgikfZGWW1L3eFJWpBrowjX5omFiy9ba") && state.getInterestMap().get(account.getPubkeyHash()).equals(incubator)) {
//                        System.out.println("height = " + l);
//                        System.out.println("public key hash = " + HexBytes.encode(account.getPubkeyHash()));
//                        System.out.println("address = " + new PublicKeyHash(account.getPubkeyHash()).getAddress());
//                        System.out.println("expected " + account.getIncubatecost());
//                        System.out.println("received " + state.getAccount().getIncubatecost());
                        throw new RuntimeException("invalid incubate cost update operation");
                    }

//                    if (account.getBalance() != state.getAccount().getBalance()) {
//                        System.out.println("height = " + block.nHeight);
//                        System.out.println("public key hash = " + HexBytes.encode(account.getPubkeyHash()));
//                        System.out.println("address = " + new PublicKeyHash(account.getPubkeyHash()).getAddress());
//                        System.out.println("expected " + account.getBalance());
//                        System.out.println("received " + state.getAccount().getBalance());
//                        throw new RuntimeException("invalid update operation");
//                    }
//                    if ( account.getVote() != state.getAccount().getVote()){
//                        System.out.println("height = " + block.nHeight);
//                        System.out.println("public key hash = " + HexBytes.encode(account.getPubkeyHash()));
//                        System.out.println("address = " + new PublicKeyHash(account.getPubkeyHash()).getAddress());
//                        System.out.println("expected " + account.getVote());
//                        System.out.println("received " + state.getAccount().getVote());
//                        throw new RuntimeException("invalid vote update operation");
//                    }
//                    if ( account.getMortgage() != state.getAccount().getMortgage()){
//                        System.out.println("height = " + block.nHeight);
//                        System.out.println("public key hash = " + HexBytes.encode(account.getPubkeyHash()));
//                        System.out.println("address = " + new PublicKeyHash(account.getPubkeyHash()).getAddress());
//                        System.out.println("expected " + account.getMortgage());
//                        System.out.println("received " + state.getAccount().getMortgage());
//                        throw new RuntimeException("invalid mortgage update operation");
//                    }
//                    if (!Address.publicKeyHashToAddress(account.getPubkeyHash()).equals("1PxgikfZGWW1L3eFJWpBrowjX5omFiy9ba") && account.getIncubatecost() != state.getAccount().getIncubatecost()){
//                        System.out.println("height = " + block.nHeight);
//                        System.out.println("public key hash = " + HexBytes.encode(account.getPubkeyHash()));
//                        System.out.println("address = " + new PublicKeyHash(account.getPubkeyHash()).getAddress());
//                        System.out.println("expected " + account.getIncubatecost());
//                        System.out.println("received " + state.getAccount().getIncubatecost());
//                        throw new RuntimeException("invalid incubate cost update operation");
//                    }
//                    if ( account.getNonce() != state.getAccount().getNonce()){
//                        System.out.println("height = " + block.nHeight);
//                        System.out.println("public key hash = " + HexBytes.encode(account.getPubkeyHash()));
//                        System.out.println("address = " + new PublicKeyHash(account.getPubkeyHash()).getAddress());
//                        System.out.println("expected " + account.getNonce());
//                        System.out.println("received " + state.getAccount().getNonce());
//                        throw new RuntimeException("invalid nonce update operation");
//                    }
                }
            }
            // sync trie here
            if (blocks.size() < blocksPerUpdate) break;
            lastSyncedHeight = blocks.get(blocks.size() - 1).nHeight;
        }
    }

    @Override
    public Optional<AccountState> getAccount(byte[] blockHash, byte[] publicKeyHash) {
        byte[] root = rootStore.get(blockHash)
                .orElseThrow(() -> new RuntimeException(Hex.encodeHexString(blockHash) + " not synced"));
        Trie<byte[], AccountState> trie = stateTrie.revert(root, noDeleteStore);
        try {
            return trie.get(publicKeyHash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Map<byte[], AccountState> getAccounts(byte[] blockHash, Collection<byte[]> publicKeyHashes) {
        byte[] root = rootStore.get(blockHash)
                .orElseThrow(() -> new RuntimeException(Hex.encodeHexString(blockHash) + " not synced"));
        Trie<byte[], AccountState> trie = stateTrie.revert(root, noDeleteStore);
        ByteArrayMap<AccountState> m = new ByteArrayMap<>();
        publicKeyHashes.forEach(x -> {
            Optional<AccountState> account = trie.get(x);
            if (!account.isPresent()) return;
            m.put(x, account.get());
        });
        return m;
    }

    @Override
    public byte[] putAccounts(
            // parent hash
            byte[] parentHash,
            byte[] blockHash,
            // updated account
            Collection<AccountState> accounts
    ) {
        if (rootStore.containsKey(blockHash))
            throw new RuntimeException(Hex.encodeHexString(blockHash) + " has exists");
        byte[] root = rootStore.get(parentHash)
                .orElseThrow(() -> new RuntimeException(Hex.encodeHexString(parentHash) + " not exists"));
        Trie<byte[], AccountState> trie = stateTrie.revert(root, noDeleteStore);
        for (AccountState state : accounts) {
            trie.put(state.getAccount().getPubkeyHash(), state);
        }
        byte[] newRoot = trie.commit();
        trie.flush();
        rootStore.put(blockHash, newRoot);
        return newRoot;
    }
}