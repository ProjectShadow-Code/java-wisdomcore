package org.wisdom.p2p;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @author sal 1564319846@qq.com
 * peers manager plugin
 */
@Component
public class PeersManager implements Plugin {
    private static final Logger logger = LoggerFactory.getLogger(PeersManager.class);

    @Override
    public void onMessage(Context context, PeerServer server) {
        switch (context.getPayload().getCode()) {
            case PING:
                onPing(context, server);
                break;
            case PONG:
                context.keep();
                break;
            case LOOK_UP:
                onLookup(context, server);
                break;
            case PEERS:
                onPeers(context, server);
        }
    }

    @Override
    public void onStart(PeerServer server) {

    }

    private void onPing(Context context, PeerServer server) {
        context.response(WisdomOuterClass.Pong.newBuilder().build());
        context.pend();
    }

    private void onPong(Context context, PeerServer server) {
        context.keep();
    }

    private void onLookup(Context context, PeerServer server) {
        List<String> peers = new ArrayList<>();
        for (Peer p : server.getPeers()) {
            peers.add(p.toString());
        }
        context.response(WisdomOuterClass.Peers.newBuilder().addAllPeers(peers).build());
    }

    private void onPeers(Context context, PeerServer server) {
        WisdomOuterClass.Ping ping = WisdomOuterClass.Ping.newBuilder().build();
        try {
            for (String p : context.getPayload().getPeers().getPeersList()) {
                Peer pr = Peer.parse(p);
                if(pr.equals(server.getSelf())){
                    continue;
                }
                server.dial(Peer.parse(p), ping);
            }
        } catch (Exception e) {
            logger.error("parse peer fail");
        }
    }
}