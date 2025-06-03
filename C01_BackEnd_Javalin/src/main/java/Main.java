import com.rabbitmq.jms.admin.RMQConnectionFactory;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.util.FileUtil;
import io.javalin.websocket.WsContext;

import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import javax.jms.*;
import jakarta.annotation.Resource;

public class Main {
    private static final Map<String, String> operations = new HashMap<>() {{
        put("ecb", "No operation");
        put("cbc", "No operation");
    }};
    private static final String UPLOAD_DIR = "/app/fileInput";
    private static final String COMMUNITY = "public";
    private static final Set<WsContext> clients = ConcurrentHashMap.newKeySet();

    @Resource(name = "jms/RMQTopicConnectionFactory")
    private static ConnectionFactory connectionFactory;

    @Resource(name = "jms/MyTopic")
    private static Topic topic;

    public static void main(String[] args) {
        Javalin app = Javalin.create(config -> {
            config.staticFiles.add("/public", Location.CLASSPATH);
        });

        app.get("/",ctx->ctx.redirect("/main"));

        app.ws("/ws-status", ws -> {
            ws.onConnect(ctx -> clients.add(ctx));
            ws.onClose(ctx -> clients.remove(ctx));
        });
        startJmsStatusListener();

        app.get("/main", ctx -> ctx.redirect("/main.html"));
        app.get("/about", ctx -> ctx.redirect("/about.html"));

        app.get("/api/snmp-status", ctx -> {
            Map<String, Map<String, String>> result = new HashMap<>();

            String jmsIp = "c03-jms-consumer"; // container DNS name
            String mpiIp = "c04-mpi-node";

            result.put("jmsConsumer", fetchMetrics(jmsIp));
            result.put("mpiNode", fetchMetrics(mpiIp));

            ctx.json(result);
        });

        app.get("/api/data", ctx -> {
            try{
                int noRequests = ctx.cookieStore().get("noRequests");
                if(noRequests>0){
                    JSONArray jsonReqs=new JSONArray();
                    for(int i=0;i<noRequests;i++){
                        JSONObject jsonCookieObj=new JSONObject();
                        String code=ctx.cookieStore().get("requestDBId" + i);
                        jsonCookieObj.put("code", code);
                        jsonReqs.put(jsonCookieObj);
                    }

                    HttpClient client = HttpClient.newHttpClient();
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create("http://c05-nodejs:5050/database/receiveInputedData"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(jsonReqs.toString()))
                            .build();

                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    JSONArray nodeResArray=new JSONArray(response.body());
                    List<Map<String,Object>> reqsData = new ArrayList<>();

                    for (int i = 0; i < nodeResArray.length(); i++) {
                        JSONObject obj = nodeResArray.getJSONObject(i);
                        reqsData.add(Map.of("fileName",obj.get("fileName"),
                                "aesLength",obj.get("aesLength"),
                                "requestIv",obj.get("requestIv"),
                                "mode",obj.get("mode"),
                                "operation",obj.get("operation"),
                                "_id",obj.get("_id")));
                    }

                    ctx.json(reqsData);
                }
            }catch (Exception e){
                e.printStackTrace();
                ctx.status(500).result("Internal Server Error");
            }

        });


        app.post("/send-request", ctx -> {
            operations.put(ctx.formParam("mode"), ctx.formParam("operation"));

            File uploadDir = new File("fileInput");
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }

            UploadedFile file = ctx.uploadedFile("files");
            Path filePath = Paths.get("fileInput", file.filename());
            try (InputStream inputStream = file.content()) {
                FileUtil.streamToFile(inputStream, filePath.toString());
                System.out.println("Filename: " + file.filename());
                System.out.println("Content-Type: " + file.contentType());
                System.out.println("Successfully uploaded file (" + file.filename() + ") and required data for encryption!");
            } catch (IOException e) {
                e.printStackTrace();
                ctx.status(500).result("Failed to save file.");
            }
            ctx.result("Successfully uploaded all files and required data for encryption!");

            String formData = createFormData(ctx);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://c05-nodejs:5050/database/receive-form"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(formData))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONObject nodeRes=new JSONObject(response.body());
            ctx.result("Node responded: " + response.body());

            if(nodeRes.getBoolean("acknowledged")){
                ctx.result("Succesfully sent request! This is your request code: "+nodeRes.getString("insertedId")+".");

                sendRabbitMqMessage(ctx,formData,nodeRes.getString("insertedId"));
                try{
                    int noRequests = ctx.cookieStore().get("noRequests");
                    ctx.cookieStore().set("requestDBId"+noRequests,nodeRes.getString("insertedId"));
                    ctx.cookieStore().set("noRequests",noRequests+1);
                }catch (Exception e){
                    ctx.cookieStore().set("noRequests",0);
                    int noRequests = ctx.cookieStore().get("noRequests");

                    ctx.cookieStore().set("requestDBId"+noRequests,nodeRes.getString("insertedId"));
                    ctx.cookieStore().set("noRequests",noRequests+1);
                }

            }else{
                ctx.result("Failed to sent request! Please refer to the About page for additional support.");
            }
        });

        app.post("/check-request", ctx -> {
            System.out.println(ctx.body());
            JSONObject dataReceived=new JSONObject(ctx.body());
            
            String password = dataReceived.getString("requestPass");
            String requestId = dataReceived.getString("requestId");
            // Build request to Node.js backend
            JSONObject checkPayload = new JSONObject();
            checkPayload.put("password", password);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://c05-nodejs:5050/database/check-password/"+requestId))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(checkPayload.toString()))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println(response.body());
            JSONObject jsonResponse = new JSONObject(response.body());
            System.out.println(jsonResponse);

            try{
                boolean isSuccesful = jsonResponse.getBoolean("success");
                if(isSuccesful){
                    int noRequests = 0;
                    try {
                        noRequests = ctx.cookieStore().get("noRequests");
                    } catch (Exception ignored) {
                    }

                    ctx.cookieStore().set("requestDBId" + noRequests, requestId);
                    ctx.cookieStore().set("noRequests", noRequests + 1);

                    ctx.result("Succesfully access the request. Check the table below for details.");
                } else {
                    ctx.result("Password or Code are incorrect.");
                }
            }catch(Exception e){
                e.printStackTrace();
            }
                
                    
            
        });


        app.get("/download/{filename}", ctx -> {
            String filename = ctx.pathParam("filename");
            Path filePath = Paths.get(UPLOAD_DIR, filename);
            if (Files.exists(filePath)) {
                ctx.result(Files.newInputStream(filePath)).header("Content-Disposition", "attachment; filename=" + filename);
            } else {
                ctx.status(404).result("File not found");
            }
        });

        app.get("/download/final/{id}", ctx -> {
            String fileId = ctx.pathParam("id");

            // URL of your Node.js middleware endpoint, assuming it takes an id param
            String nodeJsUrl = "http://c05-nodejs:5050/database/download/final/" + fileId;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(nodeJsUrl))
                    .GET()
                    .build();

            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                // Copy headers like Content-Type and Content-Disposition from Node.js response to Javalin response
                ctx.status(200);
                ctx.header("Content-Type", response.headers().firstValue("Content-Type").orElse("application/octet-stream"));
                ctx.header("Content-Disposition", response.headers().firstValue("Content-Disposition").orElse("attachment; filename=\"file\""));
                
                // Stream the input directly to the client
                InputStream is = response.body();
                ctx.result(is);
            } else {
                debugErrorResponse(response);
                ctx.status(response.statusCode()).result("File not found or error from middleware. The error is:"+response.body());
            }
        });

        app.afterMatched("/get-local-request",ctx->{
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://c05-nodejs:5050/database/"))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            JSONArray nodeResArray=new JSONArray(response.body());
            List<Map<String,Object>> reqsData = new ArrayList<>();

            for (int i = 0; i < nodeResArray.length(); i++) {
                JSONObject obj = nodeResArray.getJSONObject(i);
                reqsData.add(Map.of("aesLength",obj.get("aesLength"),"requestIv",obj.get("requestIv"),"mode",obj.get("mode"),"operation",obj.get("operation")));
            }

            ctx.json(reqsData);
        });

        app.get("/finished-request", ctx -> {
            //Insert Encrypted / Decrypted Output + optiune de salvare in baza de date cu cod de acces si parola utilizata pentru criptare
        });

        app.start(7000);
    }

    private static void debugErrorResponse(HttpResponse<InputStream> response) {
        System.out.println("---- Middleware Error Debug ----");
        System.out.println("Status code: " + response.statusCode());
        System.out.println("Headers: " + response.headers());
        try (InputStream is = response.body()) {
            String errorText = new String(is.readAllBytes());
            System.out.println("Response body: " + errorText);
        } catch (IOException e) {
            System.out.println("Failed to read error response body: " + e.getMessage());
        }
        System.out.println("--------------------------------");
    }

    public static String createFormData(@NotNull Context ctx) throws UnsupportedEncodingException {

        JSONObject jsonReqBody=new JSONObject();

        jsonReqBody.put("aesLength",URLEncoder.encode(ctx.formParam("aesLength"), "UTF-8"));
        jsonReqBody.put("requestPassword",URLEncoder.encode(ctx.formParam("requestPassword"), "UTF-8"));
        jsonReqBody.put("requestIv",URLEncoder.encode(ctx.formParam("requestIv"), "UTF-8"));
        jsonReqBody.put("mode",URLEncoder.encode(ctx.formParam("mode"), "UTF-8"));
        jsonReqBody.put("operation",URLEncoder.encode(ctx.formParam("operation"), "UTF-8"));
        jsonReqBody.put("fileName",URLEncoder.encode(ctx.uploadedFiles("files").getFirst().filename(), "UTF-8"));

        return jsonReqBody.toString();
    }

    public static void returnEntireDB(@NotNull Context ctx) throws IOException, InterruptedException, URISyntaxException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://c05-nodejs:5050/database/"))
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        JSONArray nodeResArray=new JSONArray(response.body());
        List<Map<String,Object>> reqsData = new ArrayList<>();

        for (int i = 0; i < nodeResArray.length(); i++) {
            JSONObject obj = nodeResArray.getJSONObject(i);
            reqsData.add(Map.of("fileName",obj.get("fileName"),
                    "aesLength",obj.get("aesLength"),
                    "requestIv",obj.get("requestIv"),
                    "mode",obj.get("mode"),
                    "operation",obj.get("operation")));
            System.out.println(reqsData.get(i));
        }

        ctx.json(reqsData);
    }

    public static void sendRabbitMqMessage(@NotNull Context ctx, String formadata, String insertedId) {
        try {
            JSONObject jsonReqBody=new JSONObject(formadata);
            String fileUrl = "http://c01-javalin:7000/download/" + jsonReqBody.getString("fileName");

            RMQConnectionFactory factory = new RMQConnectionFactory();
            factory.setUri("amqp://guest:guest@c02-rabbitmq:5672");

            Connection connection = factory.createConnection();
            connection.start();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic("aesTopic");
            MessageProducer producer = session.createProducer(topic);

            jsonReqBody.put("id", insertedId);
            jsonReqBody.put("fileUrl", fileUrl);

            TextMessage message = session.createTextMessage(jsonReqBody.toString());
            producer.send(message);

            ctx.status(200).result("Message sent with download URL. Use this code for access without cookies: "+insertedId);
        } catch (Exception e) {
            e.printStackTrace();
            ctx.status(500).result("Failed to send message: " + e.getMessage());
        }
    }

     private static void startJmsStatusListener() {
        new Thread(() -> {
            try {
                RMQConnectionFactory factory = new RMQConnectionFactory();
                factory.setUri("amqp://guest:guest@c02-rabbitmq:5672");

                Connection connection = factory.createConnection();
                connection.start();

                Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
                Topic topic = session.createTopic("aesResultTopic");
                MessageConsumer consumer = session.createConsumer(topic);

                consumer.setMessageListener(message -> {
                    try {
                        if (message instanceof TextMessage) {
                            String resultStatus = ((TextMessage) message).getText();

                            // Push result to all connected clients
                            for (WsContext client : clients) {
                                client.send(resultStatus);
                            }

                            System.out.println("Sent to WebSocket clients: " + resultStatus);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });

                System.out.println("JMS listener started.");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public static Map<String, String> fetchMetrics(String ip) {
        Map<String, String> metrics = new HashMap<>();
        Snmp snmp = null;
        try {
            snmp = new Snmp(new DefaultUdpTransportMapping());
            snmp.listen();

            String cpuIdle = snmpGet(snmp, ip, "1.3.6.1.4.1.2021.11.9.0");
            String memFree = snmpGet(snmp, ip, "1.3.6.1.4.1.2021.4.6.0");
            String memTotal = snmpGet(snmp, ip, "1.3.6.1.4.1.2021.4.5.0");

            if (cpuIdle != null) {
                int cpuUsage = 100 - Integer.parseInt(cpuIdle);
                metrics.put("cpuUsagePercent", String.valueOf(cpuUsage));
            } else {
                metrics.put("cpuUsagePercent", "N/A");
            }

            if (memFree != null && memTotal != null) {
                int free = Integer.parseInt(memFree);
                int total = Integer.parseInt(memTotal);
                int memUsage = (int) (((double)(total - free) / total) * 100);
                metrics.put("memoryUsagePercent", String.valueOf(memUsage));
            } else {
                metrics.put("memoryUsagePercent", "N/A");
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("SNMP comunication ERROR - Check Container Agents");
        } finally {
            if (snmp != null) {
                try {
                    snmp.close();
                } catch (IOException ignored) {}
            }
        }
        return metrics;
    }

    // SNMP GET helper
    public static String snmpGet(Snmp snmp, String ip, String oidStr) {
        try {
            CommunityTarget target = new CommunityTarget();
            target.setCommunity(new OctetString(COMMUNITY));
            target.setAddress(GenericAddress.parse("udp:" + ip + "/161"));
            target.setRetries(2);
            target.setTimeout(1500);
            target.setVersion(SnmpConstants.version2c);

            PDU pdu = new PDU();
            pdu.add(new VariableBinding(new OID(oidStr)));
            pdu.setType(PDU.GET);

            ResponseEvent event = snmp.get(pdu, target);
            if (event != null && event.getResponse() != null) {
                return event.getResponse().get(0).getVariable().toString();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

}
