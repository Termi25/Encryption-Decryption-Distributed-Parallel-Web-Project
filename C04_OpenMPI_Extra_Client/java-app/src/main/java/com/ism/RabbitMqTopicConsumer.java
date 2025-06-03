package com.ism;

import com.rabbitmq.jms.admin.RMQConnectionFactory;
import org.json.JSONObject;

import javax.jms.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class RabbitMqTopicConsumer {

    private static final String SAVE_DIR = "/home/mpiuser/data"; // Shared volume with MPI container
    private static final String NODEJS_UPLOAD_URL = "http://c05-nodejs:5050/database/uploadProcessedFile";
    private static final String NODEJS_UPDATE_IV_URL = "http://c05-nodejs:5050/database/updateIv";

    // Thread pool for processing messages asynchronously
    private static final ExecutorService executor = Executors.newFixedThreadPool(4);

    public static void main(String[] args) throws JMSException {
        RMQConnectionFactory factory = new RMQConnectionFactory();
        factory.setUri("amqp://guest:guest@c02-rabbitmq:5672");

        Connection connection = factory.createConnection();
        connection.start();

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic("aesTopic");
        MessageConsumer consumer = session.createConsumer(topic);

        consumer.setMessageListener(message -> {
            executor.submit(() -> {
                try {
                    if (!(message instanceof TextMessage)) {
                        System.out.println("Received non-text message");
                        return;
                    }

                    String jsonText = ((TextMessage) message).getText();
                    JSONObject json = new JSONObject(jsonText);

                    String objectId   = json.getString("id");
                    String fileUrl    = json.getString("fileUrl");
                    String fileName   = json.getString("fileName");
                    String aesLength  = json.optString("aesLength");
                    String aesKey     = json.optString("requestPassword");
                    String mode       = json.optString("mode");
                    String operation  = json.optString("operation");

                    int keyLengthBits;
                    try {
                        keyLengthBits = Integer.parseInt(aesLength.trim());
                        if (keyLengthBits != 128 && keyLengthBits != 192 && keyLengthBits != 256) {
                            throw new IllegalArgumentException("Invalid AES key length. Only 128, 192, or 256 bits supported.");
                        }
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Invalid AES key length: not a number", e);
                    }

                    int expectedBytes = keyLengthBits / 8;
                    if (aesKey.length() != expectedBytes) {
                        throw new IllegalArgumentException(
                            String.format("AES key length mismatch: expected %d bytes (for %d-bit), got %d",
                                expectedBytes, keyLengthBits, aesKey.length()));
                    }

                    String requestIv = json.optString("requestIv").trim();

                    if ("CBC".equalsIgnoreCase(mode)) {
                        if (requestIv.isEmpty() || requestIv.equalsIgnoreCase("null")) {
                            byte[] ivBytes = new byte[16];
                            new java.security.SecureRandom().nextBytes(ivBytes);
                            requestIv = bytesToHex(ivBytes);
                            System.out.printf("[INFO] Auto-generated IV for CBC mode: %s%n", requestIv);
                        }
                        sendIvUpdate(objectId, requestIv);
                    } else {
                        System.out.println("[INFO] Skipping IV update for mode: " + mode);
                    }

                    System.out.printf("""
                            Received message:
                              ID: %s
                              File URL: %s
                              File Name: %s
                              AES Length: %s
                              Request IV: %s
                              Mode: %s
                              Operation: %s
                            """, objectId, fileUrl, fileName, aesLength, requestIv, mode, operation);

                    byte[] fileContent = downloadFileFromUrl(fileUrl);
                    saveFile(fileName, fileContent);

                    runHybridApp(objectId, fileName, aesKey.trim(), aesLength, requestIv, mode, operation);

                    String processedFileName;
                    if ("encrypt".equalsIgnoreCase(operation)) {
                        processedFileName = fileName + ".out";
                    } else if ("decrypt".equalsIgnoreCase(operation)) {
                        processedFileName = fileName.replace(".out", "");
                    } else {
                        throw new IllegalArgumentException("Unsupported operation: " + operation);
                    }

                    uploadProcessedFile(objectId, processedFileName);

                    System.out.printf("Processed file and updated database object with ID: %s%n", objectId);

                } catch (Exception e) {
                    System.err.println("[ERROR] Failed to process message:");
                    e.printStackTrace();
                }
            });
        });

        // Clean shutdown of executor on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ignored) {
                executor.shutdownNow();
            }
        }));

        System.out.println("Listening for messages... Press Ctrl+C to stop.");
        try {
            Thread.sleep(Long.MAX_VALUE);
        } catch (InterruptedException ignored) {}
    }

    // ... rest of your methods (downloadFileFromUrl, saveFile, runHybridApp, uploadProcessedFile, etc.) unchanged ...
    
    private static byte[] downloadFileFromUrl(String fileUrl) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(fileUrl))
                .GET()
                .build();

        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to download file: HTTP " + response.statusCode());
        }

        return response.body();
    }

    private static void saveFile(String fileName, byte[] content) throws IOException {
        Path dir = Paths.get(SAVE_DIR);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }

        Path filePath = dir.resolve(fileName);
        Files.write(filePath, content);
        System.out.println("Saved file to: " + filePath.toAbsolutePath());
    }

    private static void runHybridApp(String objectId, String fileName, String aesKey, String aesLength, String requestIv,
                                     String mode, String operation) throws IOException, InterruptedException {

        if (aesKey.length() != 16 && aesKey.length() != 24 && aesKey.length() != 32) {
            System.err.printf("[WARNING] AES key length invalid: expected 16/24/32, got %d%n", aesKey.length());
        }

        System.out.printf("Using AES key: '%s' (length: %d)%n", aesKey, aesKey.length());

        ProcessBuilder pb = new ProcessBuilder(
                "mpirun",
                "--mca", "plm", "isolated",
                "--mca", "btl", "tcp,self",
                "--host", "c04-mpi-node:1,c03-jms-consumer:1",
                "-np", "2",
                "--oversubscribe",
                "/home/mpiuser/data/hybrid",
                "--file", fileName,
                "--operation", operation,
                "--mode", mode,
                "--key", aesKey,
                "--iv", requestIv,
                "--keylen", aesLength
        );

        pb.directory(new File(SAVE_DIR));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[hybrid] " + line);
            }
        }

        int exitCode = process.waitFor();
        System.out.println("Hybrid program exited with code " + exitCode);
        if(exitCode==0){
            notifySuccessToJavalin(objectId, fileName, operation, "success");
        }
    }

    private static void uploadProcessedFile(String id, String fileName) throws IOException, InterruptedException {
        Path path = Paths.get(SAVE_DIR, fileName);
        System.out.println(fileName);
        if (!Files.exists(path)) {
            System.err.println("Processed file not found: " + path.toAbsolutePath());
            return;
        }
        byte[] fileBytes = Files.readAllBytes(path);

        String boundary = "----Boundary" + System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"id\"\r\n\r\n");
        sb.append(id).append("\r\n");

        sb.append("--").append(boundary).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n");
        sb.append("Content-Type: application/octet-stream\r\n\r\n");

        byte[] preamble = sb.toString().getBytes();
        byte[] ending = ("\r\n--" + boundary + "--\r\n").getBytes();

        byte[] body = new byte[preamble.length + fileBytes.length + ending.length];
        System.arraycopy(preamble, 0, body, 0, preamble.length);
        System.arraycopy(fileBytes, 0, body, preamble.length, fileBytes.length);
        System.arraycopy(ending, 0, body, preamble.length + fileBytes.length, ending.length);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NODEJS_UPLOAD_URL))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.printf("Upload response: %d - %s%n", response.statusCode(), response.body());
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static void sendIvUpdate(String id, String iv) throws Exception {
        String json = String.format("{\"requestIv\":\"%s\"}", iv);
        System.out.println("Package body sent to requestIv path: " + json);

        String urlWithId = NODEJS_UPDATE_IV_URL + "/" + id;

        HttpClient client = HttpClient.newHttpClient();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlWithId))
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json")
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        System.out.printf("Update IV response: %d - %s%n", response.statusCode(), response.body());
    }

    private static void notifySuccessToJavalin(String objectId, String fileName, String operation, String status) {
        try {
            RMQConnectionFactory factory = new RMQConnectionFactory();
            factory.setUri("amqp://guest:guest@c02-rabbitmq:5672");

            Connection connection = factory.createConnection();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic("aesResultTopic");
            MessageProducer producer = session.createProducer(topic);

            JSONObject payload = new JSONObject();
            payload.put("id", objectId);
            payload.put("fileName", fileName);
            payload.put("operation", operation);
            payload.put("status", status);

            TextMessage message = session.createTextMessage(payload.toString());
            producer.send(message);

            System.out.printf("[INFO] Sent result to aesResultTopic: %s%n", payload);

            producer.close();
            session.close();
            connection.close();
        } catch (Exception e) {
            System.err.println("[ERROR] Failed to notify Javalin:");
            e.printStackTrace();
        }
    }

}
