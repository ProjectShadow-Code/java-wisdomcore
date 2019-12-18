package org.wisdom.db;

import org.tdf.common.util.ByteArrayMap;
import org.tdf.rlp.RLP;
import org.tdf.rlp.RLPDecoding;
import org.wisdom.core.account.Account;
import org.wisdom.core.incubator.Incubator;

import java.util.Arrays;
import java.util.Map;

/**
 * 包含了账户的所有信息，包括余额，孵化的金额等，提供深拷贝方法
 */
public class AccountState {
    @RLP(0)
    private Account account;
    @RLP(1)
    @RLPDecoding(as = ByteArrayMap.class)
    private Map<byte[], Incubator> interestMap;
    @RLP(2)
    @RLPDecoding(as = ByteArrayMap.class)
    private Map<byte[], Incubator> ShareMap;
    @RLP(3)
    private int type;//0是普通地址,1是合约代币，2是多重签名
    @RLP(4)
    private byte[] Contract;//合约RLP
    @RLP(5)
    @RLPDecoding(as = ByteArrayMap.class)
    private Map<byte[], Long> TokensMap;

    public AccountState() {
    }

    public AccountState(byte[] pubkeyHash) {
        this();
        account.setPubkeyHash(pubkeyHash);
    }

    public AccountState(Account account, Map<byte[], Incubator> interestMap, Map<byte[], Incubator> shareMap, int type, byte[] Contract, Map<byte[], Long> TokensMap) {
        this.account = account;
        this.interestMap = interestMap;
        this.ShareMap = shareMap;
        this.type = type;
        this.Contract = Contract;
        this.TokensMap = TokensMap;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public Map<byte[], Incubator> getInterestMap() {
        return interestMap;
    }

    public void setInterestMap(Map<byte[], Incubator> interestMap) {
        this.interestMap = interestMap;
    }

    public Map<byte[], Incubator> getShareMap() {
        return ShareMap;
    }

    public void setShareMap(Map<byte[], Incubator> shareMap) {
        ShareMap = shareMap;
    }

    public int getType() {
        return type;
    }

    public void setType(int type) {
        this.type = type;
    }

    public byte[] getContract() {
        return Contract;
    }

    public void setContract(byte[] contract) {
        Contract = contract;
    }

    public Map<byte[], Long> getTokensMap() {
        return TokensMap;
    }

    public void setTokensMap(Map<byte[], Long> tokensMap) {
        TokensMap = tokensMap;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountState account = (AccountState) o;
        return Arrays.equals(account.getAccount().getPubkeyHash(), account.getAccount().getPubkeyHash());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(account.getPubkeyHash());
    }

    public AccountState copy() {
        AccountState accountState = new AccountState();
        accountState.setAccount(account.copy());
        accountState.setInterestMap(new ByteArrayMap<>(interestMap));
        accountState.setShareMap(new ByteArrayMap<>(ShareMap));
        accountState.setType(type);
        accountState.setContract(Contract);
        accountState.setTokensMap(new ByteArrayMap<>(TokensMap));
        return accountState;
    }
}
