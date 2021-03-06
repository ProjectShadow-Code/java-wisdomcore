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

import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.util.Arrays;
import org.springframework.context.ApplicationContext;
import org.wisdom.ApiResult.APIResult;
import org.wisdom.ipc.IpcConfig;
import org.wisdom.p2p.entity.GetBlockQuery;
import org.wisdom.p2p.entity.Status;
import org.wisdom.encoding.JSONEncodeDecoder;
import org.wisdom.core.Block;
import org.wisdom.core.TransactionPool;
import org.wisdom.core.WisdomBlockChain;
import org.wisdom.core.account.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.wisdom.service.Impl.CommandServiceImpl;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;

import static org.wisdom.controller.ConsensusResult.ERROR;
import static org.wisdom.controller.ConsensusResult.SUCCESS;

@RestController
public class ConsensusController {
    static final int MAX_BLOCKS_IN_TRANSIT_PER_PEER = 50;
    private static final Logger logger = LoggerFactory.getLogger(ConsensusController.class);

    @Value("${wisdom.consensus.enable-mining}")
    boolean enableMining;

    @Autowired
    private JSONEncodeDecoder codec;

    @Autowired
    private WisdomBlockChain bc;

    @Autowired
    private Block genesis;

    @Autowired
    private TransactionPool pool;

    @Autowired
    private CommandServiceImpl commandService;

    @Autowired
    private PacketCache cache;

    @Autowired
    private ApplicationContext context;

    @Value("${wisdom.consensus.allow-fork}")
    private boolean allowFork;

    @Autowired
    IpcConfig ipcConfig;

    @GetMapping(value = "/consensus/blocks")
    public Object handleGetBlocks(
            @RequestParam(name = "start") long start,
            @RequestParam(name = "stop") long stop,
            @RequestParam(name = "clipFromStop") boolean clipFromStop
    ) {
        // clip interval
        GetBlockQuery query = new GetBlockQuery(start, stop).clip(MAX_BLOCKS_IN_TRANSIT_PER_PEER, clipFromStop);

        logger.info("get blocks received start height = " + start + " stop height = " + stop);

        List<Block> blocksToSend = bc.getBlocksBetween(query.start, query.stop, MAX_BLOCKS_IN_TRANSIT_PER_PEER, clipFromStop);
        if (blocksToSend != null && blocksToSend.size() > 0) {
            return new String(codec.encodeBlocks(blocksToSend));
        }
        return ERROR("blocks not founded between " + start + " " + stop);
    }

    @GetMapping(value = "/consensus/status", produces = "application/json")
    public Object getStatus() {
        ConsensuEntity.Status status = new ConsensuEntity.Status();
        Block best = bc.getTopHeader();
        status.version = best.nVersion;
        status.currentHeight = best.nHeight;
        status.bestBlockHash = best.getHash();
        status.genesisHash = genesis.getHash();
        return codec.encode(status);
    }

    @PostMapping(value = "/consensus/status", produces = "application/json")
    public Object handleStatus(@RequestBody byte[] body, HttpServletRequest request) {
        Block header = bc.getTopHeader();
        Status status = codec.decode(body, Status.class);
        if (status == null) {
            logger.error("invalid request accepted from " + request.getRemoteAddr());
            return ERROR("invalid request incountered");
        }
        logger.info("receive message type = status, height = " + status.currentHeight + " hash = " + Hex.encodeHexString(status.bestBlockHash));
        if (!Arrays.areEqual(status.genesisHash, genesis.getHash())) {
            return ERROR("genesis hash not equal");
        }
        if (header.nHeight > status.currentHeight) {
            return SUCCESS("received");
        }
        if (header.nHeight == status.currentHeight && bc.containsBlock(status.bestBlockHash)) {
            return SUCCESS("received");
        }

        // clip interval
        GetBlockQuery query = new GetBlockQuery(
                header.nHeight, status.currentHeight
        ).clip(
                MAX_BLOCKS_IN_TRANSIT_PER_PEER, false
        );
        if (ipcConfig.getP2pMode().equals("rest")) {
            context.getBean(SyncClient.class).getBlocks(query.start, query.stop, false);
        }
        return SUCCESS("received, start synchronizing");
    }

    @PostMapping(value = "/consensus/blocks", produces = "application/json")
    public Object handleProposal(@RequestBody byte[] body, HttpServletRequest request) {
        if (!allowFork) {
            return ERROR("reject proposal, because fork is not allowed");
        }
        Block b = codec.decodeBlock(body);
        if (b == null) {
            logger.error("invalid request accepted from " + request.getRemoteAddr());
            return ERROR("invalid block proposal");
        }
        if (ipcConfig.getP2pMode().equals("rest")) {
            context.getBean(SyncClient.class).receiveBlocks(Collections.singletonList(b));
        }
        return SUCCESS("proposal received");
    }

    @PostMapping(value = "/consensus/transactions", produces = "application/json")
    public Object handleTransactions(@RequestBody byte[] body, HttpServletRequest request) {
        Packet packet = codec.decode(body, Packet.class);
        if (packet == null || packet.ttl == 0) {
            return ERROR("ttl timeout");
        }
        if (cache.hasReceived(packet)) {
            return ERROR("the packet has recevied before");
        }
        List<Transaction> txs = codec.decodeTransactions(packet.data);
        if (txs == null || txs.size() == 0) {
            return SUCCESS("transaction received successful");
        }
        for (Transaction tran : txs) {
            byte[] traninfo = tran.toRPCBytes();
            APIResult apiResult = commandService.verifyTransfer(traninfo);
            if (apiResult.getCode() == 5000) {
                logger.info("transaction Check failure,TxHash=" + Hex.encodeHexString(tran.getHash()) + ",message:" + apiResult.getMessage());
                return ERROR("transaction Check failure,TxHash=" + Hex.encodeHexString(tran.getHash()) + ",message:" + apiResult.getMessage());
            }
        }
        return SUCCESS("transaction received successful");
    }
}
