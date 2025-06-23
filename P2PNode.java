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

public class P2PNode {

    private static final int WEB_PORT = 8000;
    private static final int TCP_PORT = 6000;
    private static final int UDP_PORT = 6001;
    private static final String SHARE_DIR = "shared_files";

    private static final Set<String> PEERS = ConcurrentHashMap.newKeySet();
    private static final List<String> SYSTEM_LOGS = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, JSONObject> searchResults = new ConcurrentHashMap<>();

    private static final String HTML_TEMPLATE = """
        <!DOCTYPE html>
        <html lang="id">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>P2P Broadcast Node</title>
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
            <h1>P2P Broadcast Node</h1>
            <div class="card">
                <p>Alamat IP Anda: <strong>{{host_ip}}</strong> | Peer Terdeteksi: <strong>{{peers_count}}</strong></p>
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
                <small>Log diperbarui setiap 3 detik. <a href="/">Refresh Halaman Penuh</a></small>
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
        logMessage("--- Aplikasi P2P Node Dimulai (Bahasa: Java) ---");
        Files.createDirectories(Paths.get(SHARE_DIR));

        new Thread(P2PNode::startUdpListener).start();
        new Thread(P2PNode::startTcpListener).start();
        new Thread(P2PNode::startPeriodicDiscovery).start();
        startHttpServer();
    }

    private static void startUdpListener() {
        logMessage("Listener UDP (Discovery & Search) berjalan di port " + UDP_PORT);
        try (DatagramSocket socket = new DatagramSocket(UDP_PORT)) {
            byte[] buffer = new byte[1024];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String senderIp = packet.getAddress().getHostAddress();
                if (senderIp.equals(getMyIp())) continue;

                try {
                    String messageStr = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    JSONObject message = new JSONObject(messageStr);
                    if ("SEARCH".equals(message.optString("type"))) {
                        handleSearchRequest(message);
                    }
                } catch (Exception e) {
                    String discoveryMessage = new String(packet.getData(), 0, packet.getLength());
                    if ("P2P_DISCOVERY_JAVA".equals(discoveryMessage)) {
                        if (!PEERS.contains(senderIp)) {
                            PEERS.add(senderIp);
                            logMessage("Peer baru terdeteksi via broadcast: " + senderIp);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logMessage("[ERROR] Listener UDP gagal: " + e.getMessage());
        }
    }

    private static void startTcpListener() {
        logMessage("Listener TCP untuk balasan 'FOUND' berjalan di port " + TCP_PORT);
        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleTcpConnection(clientSocket)).start();
            }
        } catch (IOException e) {
            logMessage("[ERROR] Listener TCP gagal: " + e.getMessage());
        }
    }

    private static void startPeriodicDiscovery() {
        while (true) {
            try {
                byte[] message = "P2P_DISCOVERY_JAVA".getBytes();
                try (DatagramSocket socket = new DatagramSocket()) {
                    socket.setBroadcast(true);
                    InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
                    DatagramPacket packet = new DatagramPacket(message, message.length, broadcastAddress, UDP_PORT);
                    socket.send(packet);
                }
                if (!PEERS.isEmpty()) {
                    logMessage("Daftar peer saat ini: " + PEERS);
                }
                Thread.sleep(5000 + (long) (Math.random() * 5000));
            } catch (Exception e) {
                logMessage("[ERROR] Gagal mengirim broadcast discovery: " + e.getMessage());
            }
        }
    }

    private static void handleSearchRequest(JSONObject message) {
        String filename = message.getString("filename");
        String originIp = message.getString("origin_ip");
        File file = new File(SHARE_DIR, filename);

        if (file.exists()) {
            try {
                JSONObject reply = new JSONObject();
                reply.put("type", "FOUND");
                reply.put("filename", filename);
                reply.put("host", getMyIp());
                sendTcpMessage(originIp, TCP_PORT, reply.toString());
            } catch (Exception e) {
                 logMessage("[ERROR] Gagal membuat JSON balasan: " + e.getMessage());
            }
        }
    }

    private static void handleTcpConnection(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))) {
            String line = reader.readLine();
            JSONObject message = new JSONObject(line);
            if ("FOUND".equals(message.optString("type"))) {
                searchResults.put(message.getString("filename"), message);
                logMessage("Notifikasi FOUND untuk '" + message.getString("filename") + "' diterima dari " + message.getString("host"));
            }
        } catch (Exception e) {
            logMessage("[ERROR] Gagal membaca pesan TCP: " + e.getMessage());
        }
    }

    private static void startHttpServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(WEB_PORT), 0);
        server.createContext("/", P2PNode::handleHttpRequest);
        server.createContext("/upload", P2PNode::handleUploadRequest);
        server.createContext("/download", P2PNode::handleDownloadRequest);
        server.createContext("/get_logs", P2PNode::handleGetLogsRequest);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        logMessage("Server HTTP berjalan di http://" + getMyIp() + ":" + WEB_PORT);
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
            .replace("{{host_ip}}", getMyIp())
            .replace("{{peers_count}}", String.valueOf(PEERS.size()))
            .replace("{{file_list}}", fileListHtml.toString())
            .replace("{{search_results}}", searchResultHtml.toString());

        sendHttpResponse(exchange, 200, "text/html", response);
    }

    private static void handleUploadRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendHttpResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        try {
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String boundary = "--" + contentType.split("boundary=")[1];
            InputStream in = exchange.getRequestBody();
            byte[] data = in.readAllBytes();
            
            String rawFilename = getFilenameFromHeaders(getHeaders(data, 0));
            if (rawFilename != null && !rawFilename.isEmpty()) {
                String cleanFilename = Paths.get(rawFilename).getFileName().toString();
                int fileDataStartIndex = findBodyStart(data, 0) + 4;
                int fileDataEndIndex = indexOf(data, boundary.getBytes(StandardCharsets.UTF_8), fileDataStartIndex);
                if (fileDataEndIndex == -1) fileDataEndIndex = data.length;
                if (fileDataEndIndex > 2 && data[fileDataEndIndex - 2] == '\r' && data[fileDataEndIndex - 1] == '\n') {
                    fileDataEndIndex -= 2;
                }
                File outputFile = new File(SHARE_DIR, cleanFilename);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(data, fileDataStartIndex, fileDataEndIndex - fileDataStartIndex);
                }
                logMessage("File '" + cleanFilename + "' berhasil di-upload dari web.");
            } else {
                logMessage("[WARN] Gagal mendapatkan nama file dari upload.");
            }
        } catch (Exception e) {
            logMessage("[ERROR] Gagal saat memproses upload: " + e.getMessage());
        } finally {
            exchange.getResponseHeaders().set("Location", "/");
            exchange.sendResponseHeaders(302, -1);
        }
    }

    private static String getHeaders(byte[] data, int fromIndex) {
        byte[] separator = {13, 10, 13, 10}; // \r\n\r\n
        int headerEndIndex = indexOf(data, separator, fromIndex);
        if(headerEndIndex == -1) return "";
        int headerStartIndex = indexOf(data, "Content-Disposition".getBytes(), fromIndex);
        return new String(data, headerStartIndex, headerEndIndex - headerStartIndex);
    }
    
    private static String getFilenameFromHeaders(String headers) {
        if(headers == null) return null;
        for (String part : headers.split(";")) {
            if (part.trim().startsWith("filename")) {
                return part.split("=")[1].replace("\"", "").trim();
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
        synchronized(SYSTEM_LOGS) {
            logArray = new JSONArray(SYSTEM_LOGS);
        }
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
    
    // --- ## FUNGSI getMyIp() VERSI FINAL DENGAN LOGIKA PRIORITAS ## ---
    private static String getMyIp() {
        // Daftar kata kunci untuk antarmuka virtual yang akan diabaikan
        List<String> ignoredKeywords = Arrays.asList("virtual", "vmnet", "vpn", "loopback");
        
        try {
            // Mengambil semua antarmuka jaringan yang ada di komputer
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                String interfaceName = ni.getDisplayName().toLowerCase();

                // Cek apakah antarmuka ini harus diabaikan
                boolean shouldBeIgnored = false;
                for (String keyword : ignoredKeywords) {
                    if (interfaceName.contains(keyword)) {
                        shouldBeIgnored = true;
                        break;
                    }
                }
                
                // Jika antarmuka aktif dan TIDAK perlu diabaikan
                if (ni.isUp() && !ni.isLoopback() && !shouldBeIgnored) {
                    Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                    while (inetAddresses.hasMoreElements()) {
                        InetAddress inetAddr = inetAddresses.nextElement();
                        // Kita cari alamat IPv4 yang valid
                        if (inetAddr instanceof Inet4Address && !inetAddr.isLinkLocalAddress()) {
                            // Langsung kembalikan IP dari antarmuka fisik pertama yang ditemukan
                            logMessage("[INFO] IP dipilih dari antarmuka: '" + ni.getDisplayName() + "' -> " + inetAddr.getHostAddress());
                            return inetAddr.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            logMessage("[ERROR] Tidak bisa mendapatkan alamat IP lokal: " + e.getMessage());
        }
        
        // Jika tidak ada IP yang cocok sama sekali, kembalikan loopback sebagai fallback terakhir
        logMessage("[WARN] Tidak ada antarmuka jaringan yang cocok ditemukan. Menggunakan 127.0.0.1 sebagai fallback.");
        return "127.0.0.1";
    }
    
    private static void sendHttpResponse(HttpExchange exchange, int code, String contentType, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendTcpMessage(String ip, int port, String message) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 2000);
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
                out.println(message);
            }
        } catch (IOException e) {
            logMessage("[ERROR] Gagal kirim TCP ke " + ip + ":" + port + " - " + e.getMessage());
        }
    }
}
