package org.wisdom.pool;

import org.apache.commons.codec.binary.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;
import org.wisdom.command.Configuration;
import org.wisdom.core.Block;
import org.wisdom.core.WisdomBlockChain;
import org.wisdom.core.account.AccountDB;
import org.wisdom.core.account.Transaction;
import org.wisdom.ipc.IpcConfig;
import org.wisdom.keystore.crypto.RipemdUtility;
import org.wisdom.keystore.crypto.SHA3Utility;

import java.util.*;

@Component
@EnableScheduling
public class ClearPoolCronTask implements SchedulingConfigurer {

    @Autowired
    IpcConfig ipcConfig;

    @Autowired
    AdoptTransPool adoptTransPool;

    @Autowired
    PeningTransPool peningTransPool;

    @Autowired
    WisdomBlockChain wisdomBlockChain;

    @Autowired
    Configuration configuration;

    @Autowired
    AccountDB accountDB;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        taskRegistrar.addTriggerTask(() -> {
            //adoptTransPool
            List<TransPool> list = adoptTransPool.getAll();
            Map<String, String> maps = new HashMap<>();
            for (TransPool transPool : list) {
                Transaction t = transPool.getTransaction();
                long nonce = t.nonce;
                byte[] from = t.from;
                byte[] frompubhash = RipemdUtility.ripemd160(SHA3Utility.keccak256(from));
                //nonce
                long nownonce = accountDB.getNonce(frompubhash);
                if (nownonce >= nonce) {
                    maps.put(Hex.encodeHexString(frompubhash), adoptTransPool.getKeyTrans(t));
                    continue;
                }
                long daysBetween = (new Date().getTime() - transPool.getDatetime() + 1000000) / (60 * 60 * 24 * 1000);
                if (daysBetween >= configuration.getPoolcleardays()) {
                    maps.put(Hex.encodeHexString(frompubhash), adoptTransPool.getKeyTrans(t));
                    continue;
                }
                //db
                Transaction transaction = wisdomBlockChain.getTransaction(t.getHash());
                if (transaction != null) {
                    maps.put(Hex.encodeHexString(frompubhash), adoptTransPool.getKeyTrans(t));
                }
            }
            if (maps.size() > 0) {
                adoptTransPool.remove(maps);
            }
            //peningTransPool
            List<TransPool> pendinglist = peningTransPool.getAll();
            List<String> pendinglists = new ArrayList<>();
            List<Transaction> updatelist = new ArrayList<>();
            Map<String, Long> map = new HashMap<>();
            for (TransPool transPool : pendinglist) {
                Transaction t = transPool.getTransaction();
                long nonce = t.nonce;
                byte[] from = t.from;
                String fromhex = Hex.encodeHexString(from);
                byte[] frompubhash = RipemdUtility.ripemd160(SHA3Utility.keccak256(from));
                //nonce
                long nownonce = accountDB.getNonce(frompubhash);
                if (nownonce >= nonce) {
                    pendinglists.add(peningTransPool.getKeyTrans(t));
                    map.put(fromhex, t.nonce);
                    continue;
                }
                long daysBetween = (new Date().getTime() - transPool.getDatetime() + 1000000) / (60 * 60 * 24 * 1000);
                if (daysBetween >= configuration.getPoolcleardays()) {
                    pendinglists.add(peningTransPool.getKeyTrans(t));
                    map.put(fromhex, t.nonce);
                    continue;
                }
                //db
                Transaction transaction = wisdomBlockChain.getTransaction(t.getHash());
                if (transaction != null) {
                    pendinglists.add(peningTransPool.getKeyTrans(t));
                    map.put(fromhex, t.nonce);
                    continue;
                }
                //高度
                if (transPool.getState() == 1) {
                    long height = transPool.getHeight();
                    long nowheight = wisdomBlockChain.getCurrentTotalWeight();
                    if (nowheight >= height) {
                        Block b = wisdomBlockChain.getCanonicalBlock(height);
                        if (b != null) {
                            boolean state = true;
                            for (Transaction tx : b.body) {
                                if (Arrays.equals(tx.getHash(), t.getHash())) {
                                    state = false;
                                    break;
                                }
                            }
                            if (state) {
                                updatelist.add(t);
                            }
                        }
                    }
                }
            }
            if (updatelist.size() > 0) {
                peningTransPool.updatePool(updatelist, 0, 0);
            }
            if (pendinglists.size() > 0) {
                peningTransPool.remove(pendinglists, map);
            }
        }, triggerContext -> {
            //任务触发，可修改任务的执行周期
            CronTrigger trigger = new CronTrigger(ipcConfig.getClearCycle());
            return trigger.nextExecutionTime(triggerContext);
        });
    }

}
