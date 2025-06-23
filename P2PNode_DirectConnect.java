import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class P2PNode_DirectConnect {

    private static final int WEB_PORT = 8000;
    private static final int TCP_PORT = 6000;
    private static final String SHARE_DIR = "shared_files";

    private static final Set<String> PEERS = ConcurrentHashMap.newKeySet();
    private static final List<String> SYSTEM_LOGS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, JSONObject> searchResults = new ConcurrentHashMap<>();
    private static String MY_IP = "127.0.0.1";

    private static final String HTML_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="id">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>P2P Direct Connect Node</title>
            <style>
                body { font-family: sans-serif; margin: 0; padding: 0; background-color: #f0f2f5; color: #333; }
                .container { max-width: 960px; margin: 2em auto; padding: 0 15px; }
                h1, h2 { color: #1d2129; }
                .card { background-color: #fff; padding: 25px; border-radius: 8px; margin-bottom: 1.5em; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                ul { list-style-type: none; padding: 0; }
                li { background-color: #f8f9fa; border: 1px solid #ddd; margin-bottom: 8px; padding: 12px; border-radius: 4px; display: flex; justify-content: space-between; align-items: center; }
                a { color: #007bff; text-decoration: none; }
                a:hover { text-decoration: underline; }
                input[type="file"], input[type="text"] { border: 1px solid #ccc; padding: 10px; width: 75%; border-radius: 4px; }
                button { background-color: #007bff; color: white; padding: 11px 20px; border: none; border-radius: 4px; cursor: pointer; font-weight: bold; }
                button:hover { background-color: #0056b3; }
                .log-panel { height: 200px; background-color: #282c34; color: #abb2bf; overflow-y: scroll; padding: 15px; border-radius: 4px; font-family: "Courier New", monospace; font-size: 0.9em; margin-top: 1em;}
                .log-panel div { padding-bottom: 5px; }
            </style>
        </head>
        <body>
        <div class="container">
            <h1>P2P Direct Connect Node</h1>
            <div class="card">
                <p>Alamat IP Anda: <strong>{{host_ip}}</strong> | Peer Terhubung: <strong>{{peers_count}}</strong></p>
                <p style="font-size:0.9em; color:#555;">Daftar Peer: {{peers_list}}</p>
            </div>
            <div class="card">
                <h2>Upload & Cari File</h2>
                <form method="post" enctype="multipart/form-data" action="/upload" style="margin-bottom: 20px;">
                  <input type="file" name="file" required>
                  <button type="submit">Upload</button>
                </form>
                <form method="get" action="/search">
                  <input name="filename" placeholder="Masukkan nama file" required>
                  <button type="submit">Search</button>
                </form>
            </div>
            <div class="card">
                <h2>Log Aktivitas Sistem</h2>
                <div id="log-panel" class="log-panel"></div>
                <small>Log diperbarui setiap 3 detik.</small>
            </div>
            <div class="card">
                <h2>File di Peer Ini</h2>
                <ul>{{file_list}}</ul>
            </div>
            <div class="card">
                <h2>Hasil Pencarian</h2>
                <ul>{{search_results}}</ul>
            </div>
        </div>
        <script>
            function fetchLogs() {
                fetch('/get_logs')
                    .then(response => response.json())
                    .then(data => {
                        const logPanel = document.getElementById('log-panel');
                        logPanel.innerHTML = '';
                        data.logs.forEach(log => {
                            const logEntry = document.createElement('div');
                            logEntry.textContent = log;
                            logPanel.appendChild(logEntry);
                        });
                        logPanel.scrollTop = logPanel.scrollHeight;
                    });
            }
            setInterval(fetchLogs, 3000);
            document.addEventListener('DOMContentLoaded', fetchLogs);
        </script>
        </body>
        </html>
        """;

    public static void main(String[] args) throws IOException {
        MY_IP = getMyIp();
        logMessage("--- Aplikasi P2P Direct Connect Dimulai ---");
        logMessage("Alamat IP Anda adalah: " + MY_IP);
        logMessage("Berikan IP ini ke teman Anda.");

        Files.createDirectories(Paths.get(SHARE_DIR));

        new Thread(P2PNode_DirectConnect::startTcpListener).start();
        new Thread(P2PNode_DirectConnect::startPeerConnector).start();
        startHttpServer();
    }

    private static void startTcpListener() {
        logMessage("Listener TCP berjalan di port " + TCP_PORT);
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleTcpConnection(clientSocket)).start();
            }
        } catch (IOException e) {
            logMessage("[ERROR] Listener TCP gagal: " + e.getMessage());
        }
    }
    
    private static void startPeerConnector() {
        try { Thread.sleep(2000); } catch (InterruptedException e) {}

        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("\n-------------------------------------------------");
            System.out.print("Masukkan Alamat IP teman untuk dihubungi (atau 'exit' untuk keluar): ");
            String targetIp = scanner.nextLine();

            if ("exit".equalsIgnoreCase(targetIp)) {
                break;
            }
            if (targetIp == null || targetIp.trim().isEmpty()) {
                continue;
            }
            if (targetIp.equals(MY_IP)) {
                System.out.println("Anda tidak bisa terhubung ke diri sendiri.");
                continue;
            }

            logMessage("Mencoba terhubung langsung ke " + targetIp + "...");
            sendIntroduction(targetIp);
        }
        scanner.close();
    }
    
    private static void sendIntroduction(String targetIp) {
        JSONObject message = new JSONObject();
        synchronized(PEERS) {
            PEERS.add(MY_IP);
            message.put("type", "HELLO_GOSSIP");
            message.put("peers", new JSONArray(PEERS));
        }
        sendTcpMessage(targetIp, TCP_PORT, message.toString());
    }

    private static void handleTcpConnection(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
            String line = reader.readLine();
            if (line == null) return;
            
            JSONObject message = new JSONObject(line);
            String msgType = message.optString("type");
            logMessage("Menerima pesan '" + msgType + "' dari " + clientSocket.getInetAddress().getHostAddress());
            
            switch (msgType) {
                case "HELLO_GOSSIP":
                    handleGossip(message);
                    break;
                case "SEARCH":
                    handleSearchRequest(message);
                    break;
                case "FOUND":
                    handleFoundReply(message);
                    break;
            }
        } catch (Exception e) {
            logMessage("[ERROR] Gagal memproses pesan TCP: " + e.getMessage());
        }
    }

    private static void handleGossip(JSONObject message) {
        JSONArray receivedPeers = message.getJSONArray("peers");
        Set<String> newPeers = new HashSet<>();
        for (int i = 0; i < receivedPeers.length(); i++) {
            newPeers.add(receivedPeers.getString(i));
        }

        boolean peerListChanged;
        synchronized(PEERS) {
            peerListChanged = PEERS.addAll(newPeers);
        }

        if (peerListChanged) {
            logMessage("Daftar peer diperbarui via gossip. Total peer diketahui: " + PEERS.size());
        }
    }

    private static void handleSearchRequest(JSONObject message) {
        String filename = message.getString("filename");
        String originIp = message.getString("origin_ip");
        File file = new File(SHARE_DIR, filename);

        if (file.exists()) {
            JSONObject reply = new JSONObject();
            reply.put("type", "FOUND");
            reply.put("filename", filename);
            reply.put("host", MY_IP);
            sendTcpMessage(originIp, TCP_PORT, reply.toString());
        }
    }

    private static void handleFoundReply(JSONObject message) {
        searchResults.put(message.getString("filename"), message);
    }
    
    private static void broadcastSearchToKnownPeers(String filename) {
        logMessage("Mengirim pencarian '" + filename + "' ke semua peer yang diketahui...");
        JSONObject message = new JSONObject();
        message.put("type", "SEARCH");
        message.put("filename", filename);
        message.put("origin_ip", MY_IP);

        Set<String> peersCopy;
        synchronized(PEERS) {
            peersCopy = new HashSet<>(PEERS);
        }

        for (String peerIp : peersCopy) {
            if (!peerIp.equals(MY_IP)) {
                sendTcpMessage(peerIp, TCP_PORT, message.toString());
            }
        }
    }

    private static void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(WEB_PORT), 0);
        server.createContext("/", P2PNode_DirectConnect::handleHttpRequest);
        server.createContext("/upload", P2PNode_DirectConnect::handleUploadRequest);
        server.createContext("/download", P2PNode_DirectConnect::handleDownloadRequest);
        server.createContext("/get_logs", P2PNode_DirectConnect::handleGetLogsRequest);
        server.createContext("/search", P2PNode_DirectConnect::handleSearchHttp);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        logMessage("Server HTTP berjalan di http://" + MY_IP + ":" + WEB_PORT);
    }

    private static void handleHttpRequest(HttpExchange exchange) throws IOException {
        File dir = new File(SHARE_DIR);
        File[] files = dir.listFiles();
        StringBuilder fileListHtml = new StringBuilder();
        if (files != null) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            for (File file : files) {
                fileListHtml.append("<li>").append(file.getName()).append("</li>");
            }
        }
        if (fileListHtml.length() == 0) fileListHtml.append("<li>Belum ada file.</li>");

        StringBuilder searchResultHtml = new StringBuilder();
        List<JSONObject> sortedResults = new ArrayList<>(searchResults.values());
        sortedResults.sort(Comparator.comparing(o -> o.getString("filename")));

        for (JSONObject result : sortedResults) {
            String filename = result.getString("filename");
            String host = result.getString("host");
            searchResultHtml.append(String.format(
                "<li><span><strong>%s</strong> @ %s</span> <a href=\"http://%s:%d/download?file=%s\" target=\"_blank\">Download</a></li>",
                filename, host, host, WEB_PORT, URLEncoder.encode(filename, StandardCharsets.UTF_8)
            ));
        }
        if (searchResultHtml.length() == 0) searchResultHtml.append("<li>Belum ada hasil.</li>");

        String response = HTML_TEMPLATE
            .replace("{{host_ip}}", MY_IP)
            .replace("{{peers_count}}", String.valueOf(PEERS.size()))
            .replace("{{peers_list}}", PEERS.toString())
            .replace("{{file_list}}", fileListHtml.toString())
            .replace("{{search_results}}", searchResultHtml.toString());

        sendHttpResponse(exchange, 200, "text/html", response);
    }
    
    private static void handleSearchHttp(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String filename = query.split("=")[1];
        String decodedFilename = URLDecoder.decode(filename, StandardCharsets.UTF_8);
        
        File localFile = new File(SHARE_DIR, decodedFilename);
        if (localFile.exists()) {
             handleFoundReply(new JSONObject().put("filename", decodedFilename).put("host", MY_IP));
        }

        broadcastSearchToKnownPeers(decodedFilename);

        try { Thread.sleep(500); } catch (InterruptedException e) {}
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
    }

    private static void handleUploadRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendHttpResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String boundary = contentType.split("boundary=")[1];
            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
            
            InputStream in = exchange.getRequestBody();
            byte[] bodyBytes = in.readAllBytes();
            
            int partStartIndex = indexOf(bodyBytes, "Content-Disposition".getBytes(), 0);
            if(partStartIndex == -1) throw new IOException("Content-Disposition tidak ditemukan.");
            
            String headers = getHeaders(bodyBytes, partStartIndex, boundaryBytes);
            String filename = getFilenameFromHeaders(headers);
            if (filename == null || filename.isEmpty()) throw new IOException("Nama file tidak ditemukan di header.");
            
            filename = Paths.get(filename).getFileName().toString();

            int fileDataStartIndex = findBodyStart(bodyBytes, partStartIndex);
            if (fileDataStartIndex == -1) throw new IOException("Pemisah header-body tidak ditemukan.");
            fileDataStartIndex += 4;

            int fileDataEndIndex = indexOf(bodyBytes, boundaryBytes, fileDataStartIndex);
            if(fileDataEndIndex == -1) fileDataEndIndex = bodyBytes.length;
            
            if (fileDataEndIndex > 2 && bodyBytes[fileDataEndIndex - 2] == '\r' && bodyBytes[fileDataEndIndex - 1] == '\n') {
                fileDataEndIndex -= 2;
            }

            File outputFile = new File(SHARE_DIR, filename);
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                fos.write(bodyBytes, fileDataStartIndex, fileDataEndIndex - fileDataEndIndex);
            }
            logMessage("File '" + filename + "' berhasil di-upload dari web.");
        } catch (Exception e) {
            logMessage("[ERROR] Gagal saat memproses upload: " + e.getMessage());
            e.printStackTrace();
        } finally {
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
        }
    }
    
    // --- ## PERBAIKAN FUNGSI HELPER ## ---
    private static String getHeaders(byte[] data, int fromIndex, byte[] boundary) {
        byte[] separator = {13, 10, 13, 10}; // \r\n\r\n
        int headerEndIndex = indexOf(data, separator, fromIndex);
        if(headerEndIndex == -1) return "";
        return new String(data, fromIndex, headerEndIndex - fromIndex);
    }
    
    private static String getFilenameFromHeaders(String headers) {
        if(headers == null) return null;
        for (String line : headers.split("\r\n")) {
            if (line.trim().toLowerCase().startsWith("content-disposition:")) {
                for (String part : line.split(";")) {
                    if (part.trim().startsWith("filename")) {
                        return part.split("=")[1].replace("\"", "").trim();
                    }
                }
            }
        }
        return null;
    }

    private static int findBodyStart(byte[] data, int fromIndex) {
        byte[] separator = {13, 10, 13, 10};
        return indexOf(data, separator, fromIndex);
    }

    private static int indexOf(byte[] source, byte[] target, int fromIndex) {
        for (int i = fromIndex; i <= source.length - target.length; i++) {
            boolean found = true;
            for (int j = 0; j < target.length; j++) {
                if (source[i + j] != target[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    private static void handleDownloadRequest(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String filename = URLDecoder.decode(query.split("=")[1], StandardCharsets.UTF_8);
        File file = new File(SHARE_DIR, filename);
        if (file.exists()) {
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            exchange.sendResponseHeaders(200, file.length());
            try (OutputStream os = exchange.getResponseBody()) {
                Files.copy(file.toPath(), os);
            }
        } else {
            sendHttpResponse(exchange, 404, "text/plain", "404 File Not Found");
        }
    }
    
    private static void handleGetLogsRequest(HttpExchange exchange) throws IOException {
        JSONArray logArray;
        synchronized(SYSTEM_LOGS) { logArray = new JSONArray(SYSTEM_LOGS); }
        JSONObject responseJson = new JSONObject();
        responseJson.put("logs", logArray);
        sendHttpResponse(exchange, 200, "application/json", responseJson.toString());
    }

    private static void logMessage(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        String formattedMessage = "[" + timestamp + "] " + message;
        System.out.println(formattedMessage);
        synchronized (SYSTEM_LOGS) {
            SYSTEM_LOGS.add(formattedMessage);
            if (SYSTEM_LOGS.size() > 50) SYSTEM_LOGS.remove(0);
        }
    }

    private static String getMyIp() {
        List<String> ignoredKeywords = Arrays.asList("virtual", "vmnet", "vpn", "loopback", "bluetooth");
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                String interfaceName = ni.getDisplayName().toLowerCase();
                boolean shouldBeIgnored = false;
                for (String keyword : ignoredKeywords) { if (interfaceName.contains(keyword)) { shouldBeIgnored = true; break; } }
                if (ni.isUp() && !ni.isLoopback() && !shouldBeIgnored) {
                    Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddr = inetAddresses.nextElement();
                        if (inetAddr instanceof Inet4Address && !inetAddr.isLinkLocalAddress()) { return inetAddr.getHostAddress(); }
                    }
                }
            }
        } catch (SocketException e) { logMessage("[ERROR] Tidak bisa mendapatkan alamat IP lokal: " + e.getMessage()); }
        return "127.0.0.1";
    }
    
    private static void sendHttpResponse(HttpExchange exchange, int code, String contentType, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
    }

    private static void sendTcpMessage(String ip, int port, String message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 2000);
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                out.println(message);
            }
        } catch (IOException e) { logMessage("[ERROR] Gagal kirim TCP ke " + ip + ":" + port + " - " + e.getMessage()); }
    }
}
