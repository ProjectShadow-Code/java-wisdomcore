package org.wisdom.tools;

import org.apache.commons.io.FileUtils;
import org.wisdom.p2p.GRPCClient;
import org.wisdom.p2p.Payload;
import org.wisdom.p2p.Peer;
import org.wisdom.p2p.WisdomOuterClass;

import java.io.File;
import java.util.concurrent.Executor;

public class GRPCTestTool {
    public static final Executor EXECUTOR = command -> new Thread(command).start();
    private static Peer SELF;

    static {
        try {
            SELF = Peer.newPeer("wisdom://00383e950edf55b4d34a3ece5973917edbed42a88d5873dfe18fa83b5e5eb63c04a02eb68766b4acd7a412549a58c12bad9719ee9fd7670297f9083adb4c72c5@192.168.1.142:9235");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) throws Exception {
        sendBlocks();
    }

    private static void sendBlocks() throws Exception{
        WisdomOuterClass.Message msg = WisdomOuterClass.Message.parseFrom(
                FileUtils.readFileToByteArray(new File("c:\\Users\\Sal\\bin.rpc")
                ));
        GRPCClient client = new GRPCClient(SELF).withExecutor(EXECUTOR);
        client.dialWithTTL("192.168.1.44", 9585, 1, msg)
        .thenAccept((m) -> {
            System.out.println(m.toString());
        });
    }

    private static void getBlocksAndWrite() throws Exception{
        GRPCClient client = new GRPCClient(SELF).withExecutor(EXECUTOR);
        client.dialAsyncWithTTL("192.168.1.52", 9235, 1,
                WisdomOuterClass.GetBlocks.newBuilder()
                        .setStartHeight(1)
                        .setStopHeight(256)
                        .setClipDirection(WisdomOuterClass.ClipDirection.CLIP_TAIL)
                        .build(),
                (msg, err) -> {
                    try {
                        System.out.println(new Payload(msg).getBlocks().getBlocksList().size());
                        FileUtils.writeByteArrayToFile(new File("c:\\Users\\Sal\\bin.rpc"), msg.toByteArray());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
        );
        Thread.sleep(1000);
    }
}
