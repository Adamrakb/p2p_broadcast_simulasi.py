from flask import Flask, request, render_template_string, redirect, send_from_directory, url_for
import os
import threading
import socket
import json
import time
import random 

app = Flask(__name__)

HOST_IP = '0.0.0.0'
WEB_PORT = 5000
PEER_PORT = 6000
DISCOVERY_PORT = 6001

SHARE_DIR = f'shared_{WEB_PORT}'
if not os.path.exists(SHARE_DIR):
    os.makedirs(SHARE_DIR)

PEERS = set()
search_results = {}

peers_lock = threading.Lock()
results_lock = threading.Lock()


TTL = 4

HTML = '''
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>P2P Node</title>
    <style>
        body { font-family: sans-serif; margin: 2em; background-color: #f4f4f9; color: #333; }
        h1, h2 { color: #0056b3; }
        ul { list-style-type: none; padding: 0; }
        li { background-color: #fff; border: 1px solid #ddd; margin-bottom: 8px; padding: 12px; border-radius: 4px; }
        a { color: #007bff; text-decoration: none; }
        a:hover { text-decoration: underline; }
        form { background-color: #fff; padding: 20px; border-radius: 8px; margin-bottom: 2em; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        input[type="file"], input[type="text"] { border: 1px solid #ccc; padding: 8px; width: 70%; border-radius: 4px; }
        button { background-color: #007bff; color: white; padding: 10px 15px; border: none; border-radius: 4px; cursor: pointer; }
        button:hover { background-color: #0056b3; }
        .peer-list { font-size: 0.9em; color: #555; }
    </style>
</head>
<body>
    <h1>Peer-to-Peer Node</h1>
    <p>Alamat IP Anda di jaringan ini mungkin: <strong>{{host_ip}}</strong></p>
    <p class="peer-list">Peer yang Terdeteksi: {{peers}}</p>

    <h2>Upload File</h2>
    <form method="post" enctype="multipart/form-data" action="/upload">
      <input type="file" name="file" required>
      <button type="submit">Upload</button>
    </form>

    <h2>Cari File</h2>
    <form method="get" action="/search">
      <input name="filename" placeholder="Masukkan nama file" required>
      <button type="submit">Search</button>
    </form>

    <h2>File di Peer Ini:</h2>
    <ul>
    {% for f in files %}
      <li><a href="/download/{{f}}">{{f}}</a></li>
    {% else %}
      <li>Belum ada file.</li>
    {% endfor %}
    </ul>

    <h2>Hasil Pencarian:</h2>
    <p><a href="/">Refresh Halaman & Hasil</a></p>
    <ul>
    {% for f, info in results.items() %}
      <li><strong>{{f}}</strong> ditemukan di {{info['host']}}:<a href="http://{{info['host']}}:{{web_port}}/download/{{f}}" target="_blank"> Download</a></li>
    {% else %}
      <li>Belum ada hasil. Lakukan pencarian atau refresh.</li>
    {% endfor %}
    </ul>
</body>
</html>
'''

@app.route('/')
def home():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 1))
        local_ip = s.getsockname()[0]
    except Exception:
        local_ip = '127.0.0.1'
    finally:
        s.close()
    
    with results_lock:
        current_results = dict(search_results)
    with peers_lock:
        current_peers = list(PEERS)
        
    return render_template_string(HTML, files=os.listdir(SHARE_DIR), host_ip=local_ip, results=current_results, peers=current_peers, web_port=WEB_PORT)

@app.route('/upload', methods=['POST'])
def upload():
    if 'file' in request.files:
        f = request.files['file']
        path = os.path.join(SHARE_DIR, f.filename)
        f.save(path)
    return redirect('/')

@app.route('/search')
def search():
    filename = request.args.get('filename')
    if not filename:
        return redirect('/')
    
    if filename in os.listdir(SHARE_DIR):
        with results_lock:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect(('8.8.8.8', 1))
                local_ip = s.getsockname()[0]
            except Exception:
                local_ip = '127.0.0.1'
            finally:
                s.close()
            search_results[filename] = {"host": local_ip}
    else:
        broadcast_search(filename, TTL)
    
    time.sleep(1) 
    return redirect(url_for('home'))

@app.route('/download/<filename>')
def download(filename):
    return send_from_directory(SHARE_DIR, filename, as_attachment=True)


def peer_listener():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.bind((HOST_IP, PEER_PORT))
    s.listen(5)
    print(f"[*] Peer listener berjalan di port {PEER_PORT}")
    while True:
        conn, addr = s.accept()
        try:
            data = conn.recv(1024).decode()
            message = json.loads(data)
            print(f"[*] Pesan diterima dari {addr[0]}: {message}")

            if message['type'] == 'SEARCH':
                handle_search_request(message, message['origin_ip'])
            elif message['type'] == 'FOUND':
                with results_lock:
                    search_results[message['filename']] = {"host": message['host']}
        except Exception as e:
            print(f"[!] Error saat memproses pesan: {e}")
        finally:
            conn.close()

def handle_search_request(message, origin_ip):
    filename = message['filename']
    ttl = message['ttl']

    if filename in os.listdir(SHARE_DIR):
        print(f"[*] File '{filename}' ditemukan, mengirim balasan ke {origin_ip}")
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            try:
                s.connect(('8.8.8.8', 1))
                local_ip = s.getsockname()[0]
            except Exception:
                local_ip = '127.0.0.1'
            finally:
                s.close()

            reply = json.dumps({"type": "FOUND", "filename": filename, "host": local_ip})
            s_reply = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s_reply.connect((origin_ip, PEER_PORT))
            s_reply.send(reply.encode())
            s_reply.close()
        except Exception as e:
            print(f"[!] Gagal mengirim balasan FOUND: {e}")
    
    elif ttl > 1:
        print(f"[*] File '{filename}' tidak ditemukan, meneruskan pencarian (TTL: {ttl-1})")
        broadcast_search(filename, ttl - 1, origin_ip=origin_ip)

def broadcast_search(filename, ttl, origin_ip=None):
    if origin_ip is None:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            s.connect(('8.8.8.8', 1))
            origin_ip = s.getsockname()[0]
        except Exception:
            origin_ip = '127.0.0.1'
        finally:
            s.close()
    
    print(f"[*] Melakukan broadcast pencarian untuk '{filename}'...")
    with peers_lock:
        peers_to_search = list(PEERS)
    
    msg = json.dumps({"type": "SEARCH", "filename": filename, "ttl": ttl, "origin_ip": origin_ip})
    for peer_ip in peers_to_search:
        if peer_ip == origin_ip:
            continue
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.settimeout(1)
            s.connect((peer_ip, PEER_PORT))
            s.send(msg.encode())
            s.close()
        except Exception as e:
            print(f"[!] Tidak dapat terhubung ke peer {peer_ip}: {e}")


def discover_peers():
    """PERBAIKAN FINAL: Mengirim broadcast lebih sering dan acak untuk keandalan maksimal."""
    while True:
        print("[*] Mengirim discovery broadcast...")
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        udp.sendto(b'DISCOVER_P2P_FILE_SHARING', ('<broadcast>', DISCOVERY_PORT))
        udp.close()
        
        sleep_interval = random.uniform(5, 10) 
        time.sleep(sleep_interval)

def peer_discovery_listener():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((HOST_IP, DISCOVERY_PORT))
    print(f"[*] Discovery listener berjalan di port {DISCOVERY_PORT}")
    
    my_ip = '127.0.0.1'
    try:
        s_local_ip = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s_local_ip.connect(('8.8.8.8', 1))
        my_ip = s_local_ip.getsockname()[0]
        s_local_ip.close()
    except Exception:
        pass
        
    while True:
        data, addr = s.recvfrom(1024)
        if data == b'DISCOVER_P2P_FILE_SHARING' and addr[0] != my_ip:
            with peers_lock:
                if addr[0] not in PEERS:
                    print(f"[*] Peer baru ditemukan: {addr[0]}")
                    PEERS.add(addr[0])


if __name__ == '__main__':
    print("--- Memulai Aplikasi P2P File Sharing ---")
    
    threading.Thread(target=peer_listener, daemon=True).start()
    threading.Thread(target=peer_discovery_listener, daemon=True).start()
    
    time.sleep(1) 
    
    threading.Thread(target=discover_peers, daemon=True).start()
    
    print(f"[*] Web server berjalan. Buka http://<alamat-ip-anda>:{WEB_PORT} di browser.")
    app.run(host=HOST_IP, port=WEB_PORT, debug=False)