package org.wisdom.rpc;

import org.apache.commons.codec.binary.Hex;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wisdom.ApiResult.APIResult;
import org.wisdom.consensus.pow.EconomicModel;
import org.wisdom.core.account.Transaction;
import org.wisdom.crypto.ed25519.Ed25519PrivateKey;
import org.wisdom.encoding.JSONEncodeDecoder;
import org.wisdom.util.Address;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class RPCTest {
    private static final int HTTP_TIMEOUT = 5000;
    private static final Logger logger = LoggerFactory.getLogger(RPCTest.class);
    private static final JSONEncodeDecoder codec = new JSONEncodeDecoder();

    private static class Response {
        public int code;
        public String message;
        public Object data;
    }

    private static class GetNonceResponse {
        public int code;
        public String message;
        public long data;
    }

    private static class GetPoolAddressResponse {
        public int code;
        public String message;
        public List<Map<String, Object>> data;
    }

    public int getType() {
        String type = System.getenv("TYPE");
        if (type == null || type.equals("")) {
            return Transaction.Type.TRANSFER.ordinal();
        }
        for (Transaction.Type t : Transaction.TYPES_TABLE) {
            if (t.toString().equals(type.toUpperCase())) {
                return t.ordinal();
            }
        }
        return Integer.parseInt(type);
    }


    public int getTimes() {
        String times = System.getenv("TIMES");
        if (times == null || times.equals("")) {
            return 1;
        }
        return Integer.parseInt(times);
    }

    public byte[] getPayload() throws Exception {
        String payload = System.getenv("PAYLOAD");
        if (payload == null || payload.equals("")) {
            return new byte[]{};
        }
        return Hex.decodeHex(payload);
    }

    @Test
    public void error() {
        logger.error("=====================");
    }

    @Test
    public void getTransactionsInMemoryPool() throws Exception {
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(Hex.decodeHex(System.getenv("PRIVATE_KEY")));
        try {
            String address = Address.publicKeyToAddress(privateKey.generatePublicKey().getEncoded());
            URI uri = new URI(
                    "http",
                    null,
                    System.getenv("HOST"),
                    Integer.parseInt(System.getenv("PORT")),
                    "/getPoolAddress",
                    null, null
            );
            Map<String, String> query = new HashMap<>();
            query.put("address", address);
            GetPoolAddressResponse response = get(uri.toString(), query).thenApplyAsync(x -> codec.decode(x, GetPoolAddressResponse.class)).get();
            for (Map<String, Object> json : response.data) {
                Transaction tx = Transaction.fromRPCBytes(Hex.decodeHex((String) json.get("traninfo")));;
            }
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    //
    @Test // 测试发送事务 采用环境变量配置
    public void postTransaction() throws Exception {
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(Hex.decodeHex(System.getenv("PRIVATE_KEY")));
        Transaction tx = new Transaction();
        tx.version = Transaction.DEFAULT_TRANSACTION_VERSION;
        tx.type = getType();
        tx.amount = Long.parseLong(System.getenv("AMOUNT")) * EconomicModel.WDC;
        tx.to = Address.addressToPublicKeyHash(System.getenv("TO"));
        tx.from = privateKey.generatePublicKey().getEncoded();
        tx.gasPrice = (long) Math.ceil(
                0.02 * EconomicModel.WDC / Transaction.GAS_TABLE[tx.type]
        );
        tx.payload = getPayload();
        int times = getTimes();
        long nonce = getNonce(Hex.encodeHexString(Address.publicKeyToHash(tx.from)))
                .get() + 1;
        for (int i = 0; i < times; i++) {
            // clear cache
            tx.setHashCache(null);
            tx.nonce = nonce + i;
            tx.signature = privateKey.sign(tx.getRawForSign());
            postTransaction(tx).thenAcceptAsync(r -> {
                if (r.code == APIResult.FAIL) {
                    System.out.println("post transaction failed" + r.message);
                }
                System.out.println(new String(codec.encode(tx)));
            }).join();
        }
    }


    private CompletableFuture<byte[]> post(String url, byte[] body) {
        CloseableHttpClient httpclient = HttpClients.custom()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .setConnectionManagerShared(true)
                .build();
        return CompletableFuture.supplyAsync(() -> {
            CloseableHttpResponse resp = null;
            try {
                URI uriObject = new URI(url);
                HttpPost httppost = new HttpPost(uriObject);
                httppost.setConfig(RequestConfig.custom().setConnectTimeout(HTTP_TIMEOUT).build());
                httppost.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
                // Create a custom response handler
                resp = httpclient.execute(httppost);
                return getBody(resp);
            } catch (Exception e) {
                try {
                    resp.close();
                } catch (Exception ignored) {
                }
                throw new RuntimeException("post " + url + " fail");
            }
        });
    }


    @Test
    public void testGetNonce() throws Exception {
        Ed25519PrivateKey privateKey = new Ed25519PrivateKey(Hex.decodeHex(System.getenv("PRIVATE_KEY")));
        String publicKeyHash = Hex.encodeHexString(Address.publicKeyToHash(privateKey.generatePublicKey().getEncoded()));
        getNonce(publicKeyHash)
                .thenAccept(System.out::println)
                .join()
        ;
    }

    private CompletableFuture<Response> postTransaction(Transaction tx) {
        try {
            URI uri = new URI(
                    "http",
                    null,
                    System.getenv("HOST"),
                    Integer.parseInt(System.getenv("PORT")),
                    "/sendTransaction",
                    "traninfo=" + Hex.encodeHexString(tx.toRPCBytes()), null
            );
            return post(uri.toString(), new byte[]{}).thenApplyAsync(x -> codec.decode(x, Response.class));
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private CompletableFuture<Long> getNonce(String publicKeyHash) throws Exception {
        URI uri = new URI(
                "http",
                null,
                System.getenv("HOST"),
                Integer.parseInt(System.getenv("PORT")),
                "/sendNonce",
                "pubkeyhash=" + publicKeyHash, ""
        );
        return post(uri.toString(), new byte[]{}).thenApplyAsync((body) -> {
            GetNonceResponse getNonceResponse = codec.decode(body, GetNonceResponse.class);
            return getNonceResponse.data;
        });
    }

    private CompletableFuture<byte[]> get(final String url, Map<String, String> query) {
        CloseableHttpClient httpclient = HttpClients.custom()
                .setConnectionManager(new PoolingHttpClientConnectionManager())
                .setConnectionManagerShared(true)
                .build();

        return CompletableFuture.supplyAsync(() -> {
            CloseableHttpResponse resp = null;
            try {
                URI uriObject = new URI(url);
                URIBuilder builder = new URIBuilder()
                        .setScheme(uriObject.getScheme())
                        .setHost(uriObject.getHost())
                        .setPort(uriObject.getPort())
                        .setPath(uriObject.getPath());
                for (String k : query.keySet()) {
                    builder.setParameter(k, query.get(k));
                }
                uriObject = builder.build();
                HttpGet httpget = new HttpGet(uriObject);
                httpget.setConfig(RequestConfig.custom().setConnectTimeout(HTTP_TIMEOUT).build());
                // Create a custom response handler
                resp = httpclient.execute(httpget);
                return getBody(resp);
            } catch (Exception e) {
                try {
                    resp.close();
                } catch (Exception ignored) {

                }
                throw new RuntimeException("get " + url + " fail");
            }
        });
    }

    private static byte[] getBody(final HttpResponse response) {
        int status = response.getStatusLine().getStatusCode();
        if (status < 200 || status >= 300) {
            return null;
        }
        try {
            HttpEntity entity = response.getEntity();
            return entity != null ? EntityUtils.toByteArray(entity) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
