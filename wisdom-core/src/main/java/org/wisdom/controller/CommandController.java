/*
 * Copyright (c) [2018]
 * This file is part of the java-wisdomcore
 *
 * The java-wisdomcore is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The java-wisdomcore is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the java-wisdomcore. If not, see <http://www.gnu.org/licenses/>.
 */

package org.wisdom.controller;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.wisdom.ApiResult.APIResult;
import org.wisdom.core.Block;
import org.wisdom.core.WisdomBlockChain;
import org.wisdom.core.account.Transaction;
import org.wisdom.db.WisdomRepository;
import org.wisdom.encoding.JSONEncodeDecoder;
import org.wisdom.ipc.IpcConfig;
import org.wisdom.service.CommandService;
import org.wisdom.sync.TransactionHandler;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

@RestController
public class CommandController {
    private static Logger logger = LoggerFactory.getLogger(CommandController.class);

    public static final int CONFIRMED = 2000;
    public static final int NOT_CONFIRMED = 2100;

    @Value("${wisdom.consensus.enable-mining}")
    boolean enableMining;

    @Autowired
    CommandService commandService;

    @Autowired
    WisdomBlockChain bc;

    @Autowired
    JSONEncodeDecoder encodeDecoder;

    @Autowired
    Block genesis;

    @Autowired
    RPCClient RPCClient;

    @Autowired
    private WisdomRepository wisdomRepository;

    @Autowired
    private TransactionHandler transactionHandler;

    @Autowired
    IpcConfig ipcConfig;

    @PostMapping(value = {"/sendTransaction", "/sendIncubator", "/sendInterest",
            "/sendShare", "/sendDeposit", "/sendCost", "/sendVote", "/sendExitVote",
            "/sendDeployContract", "/sendCallContract"})
    public Object sendTransaction(@RequestParam(value = "traninfo") String traninfo) {
        try {
            byte[] traninfos = Hex.decodeHex(traninfo.toCharArray());
            APIResult result = commandService.verifyTransfer(traninfos);
            if (result.getCode() == 2000) {
                Transaction t = (Transaction) result.getData();
                if (ipcConfig.getP2pMode().equals("rest")) {
                    RPCClient.broadcastTransactions(Collections.singletonList(t));
                } else {
                    transactionHandler.broadcastTransactions(Collections.singletonList(t));
                }
            }
            return result;
        } catch (DecoderException e) {
            APIResult apiResult = new APIResult();
            apiResult.setCode(5000);
            apiResult.setMessage("Error");
            return apiResult;
        }
    }

    @RequestMapping(value = "/getTransactionHeight", method = RequestMethod.POST)
    public Object getTransactionHeight(@RequestParam("height") int height, String type) {
        try {
            if (type == null || type.equals("")) {//默认转账事务
                return commandService.getTransactionList(height, 1);
            } else {//全部事务
                int types = Integer.valueOf(type);
                return commandService.getTransactionList(height, types);
            }
        } catch (Exception e) {
            return ConsensusResult.ERROR("Blockhash exception error");
        }
    }

    @RequestMapping(value = "/getTransactionBlcok", method = RequestMethod.POST)
    public Object getTransactionBlcok(@RequestParam("blockhash") String blockhash, String type) {
        try {
            byte[] block_hash = Hex.decodeHex(blockhash.toCharArray());
            if (type == null || type.equals("")) {//默认转账事务
                return commandService.getTransactionBlock(block_hash, 1);
            } else {
                int types = Integer.valueOf(type);
                return commandService.getTransactionBlock(block_hash, types);
            }
        } catch (DecoderException e) {
            return ConsensusResult.ERROR("Blockhash exception error");
        }
    }


    @RequestMapping(method = RequestMethod.GET, value = "/block/{id}", produces = "application/json")
    public Object getBlock(@PathVariable("id") String id) {
        Block b;
        try {
            int height = Integer.parseInt(id);
            if (height < 0) {
                b = wisdomRepository.getBestBlock();
            } else {
                b = bc.getBlockByHeight(height);
            }
            if (b == null) {
                return ConsensusResult.ERROR("cannot find block at height = " + height);
            }
            if (b.nHeight == 0) {
                b = b.toHeader();
            }
            return encodeDecoder.encode(b);
        } catch (Exception e) {
            return handleGetBlockByHash(id);
        }
    }

    private Object handleGetBlockByHash(String hash) {
        try {
            byte[] h = Hex.decodeHex(hash);
            Block b = bc.getBlockByHash(h);
            if (b != null) {
                return encodeDecoder.encodeBlock(b);
            }
            return ConsensusResult.ERROR("cannot find transaction where hash = " + hash);
        } catch (Exception e) {
            return ConsensusResult.ERROR("invalid hex " + hash);
        }
    }

    @RequestMapping(method = RequestMethod.GET, value = "/transaction/{txHash}", produces = "application/json")
    public Object getTransactionByHash(@PathVariable("txHash") String hash) {
        try {
            byte[] h = Hex.decodeHex(hash.toCharArray());
            Transaction tx = bc.getTransaction(h);
            if (tx != null) {
                return encodeDecoder.encodeTransaction(tx);
            }
        } catch (Exception e) {
            return ConsensusResult.ERROR("invalid transaction hash hex string " + hash);
        }
        return ConsensusResult.ERROR("the transaction " + hash + " not exists");
    }

    @RequestMapping(method = RequestMethod.GET, value = "/transaction", produces = "application/json")
    public Object getTransactionByTo(@RequestParam("to") String hash) {
        try {
            byte[] h = Hex.decodeHex(hash.toCharArray());
            Transaction tx = bc.getTransactionByTo(h);
            if (tx != null) {
                return encodeDecoder.encodeTransaction(tx);
            }
        } catch (Exception e) {
            return ConsensusResult.ERROR("invalid pubkey hex string " + hash);
        }
        return ConsensusResult.ERROR("the transaction where to = " + hash + " not exists");
    }

    @RequestMapping(method = RequestMethod.GET, value = "/height")
    public Object getCurrentHeight() {
        Block current = bc.getTopHeader();
        return APIResult.newFailResult(APIResult.SUCCESS, "success", current.nHeight);
    }

    @RequestMapping(method = RequestMethod.GET, value = "/transactionConfirmed")
    public Object getConfirms(@RequestParam("txHash") String hash) {
        try {
            Block current = bc.getTopHeader();
            Transaction tx = bc.getTransaction(Hex.decodeHex(hash));
            long res = NOT_CONFIRMED;
            if (current.nHeight - tx.height > 2) {
                res = CONFIRMED;
            }
            return APIResult.newFailResult(2000, "success", res);
        } catch (Exception e) {
            return APIResult.newFailResult(5000, "fail");
        }
    }

    @RequestMapping(method = RequestMethod.GET, value = "/blockHash")
    public Object getBlockHash(@RequestParam("txHash") String hash) {
        try {
            Transaction tx = bc.getTransaction(Hex.decodeHex(hash));
            Map<String, Object> res = new HashMap<>();
            res.put("blockHash", Hex.encodeHexString(tx.blockHash));
            res.put("height", tx.height);
            return APIResult.newFailResult(2000, "success", res);
        } catch (Exception e) {
            return APIResult.newFailResult(5000, "fail");
        }
    }
}
