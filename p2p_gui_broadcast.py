from flask import Flask, request, render_template_string, redirect, send_from_directory, url_for
import os
import threading
import socket
import json
import time

app = Flask(__name__)

HOST_IP = '0.0.0.0' 
WEB_PORT = 5000
PEER_PORT = 6000
DISCOVERY_PORT = 6001
SHARE_DIR = f'shared_{WEB_PORT}'
TTL = 4

if not os.path.exists(SHARE_DIR):
    os.makedirs(SHARE_DIR)

PEERS = set()
search_results = {}
peers_lock = threading.Lock()
results_lock = threading.Lock()

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
    </style>
</head>
<body>
    <h1>Peer-to-Peer Node</h1>
    <p>Alamat IP Anda di jaringan ini mungkin: <strong>{{host_ip}}</strong></p>

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
    <p><a href="/recheck">Refresh Hasil Pencarian</a></p>
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
    my_ip = get_my_ip()
    with results_lock:
        current_results = dict(search_results)
    return render_template_string(HTML, files=os.listdir(SHARE_DIR), host_ip=my_ip, results=current_results, web_port=WEB_PORT)

@app.route('/upload', methods=['POST'])
def upload():
    if 'file' in request.files:
        f = request.files['file']
        f.save(os.path.join(SHARE_DIR, f.filename))
    return redirect('/')

@app.route('/search')
def search():
    filename = request.args.get('filename')
    if not filename:
        return redirect('/')
    
    if filename in os.listdir(SHARE_DIR):
        with results_lock:
            search_results[filename] = {"host": get_my_ip()}
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
    s.listen(10)
    print(f"[*] Peer listener berjalan di port {PEER_PORT}")
    while True:
        try:
            conn, addr = s.accept()
            threading.Thread(target=handle_peer_connection, args=(conn, addr)).start()
        except Exception as e:
            print(f"[!] Error di listener utama: {e}")

def handle_peer_connection(conn, addr):
    try:
        data = conn.recv(1024).decode()
        message = json.loads(data)
        print(f"[*] Pesan diterima dari {addr[0]}: Tipe '{message.get('type')}'")

        msg_type = message.get('type')
        if msg_type == 'SEARCH':
            handle_search_request(message)
        elif msg_type == 'FOUND':
            with results_lock:
                search_results[message['filename']] = {"host": message['host']}
        
        elif msg_type == 'SYNC_PEERS':
            received_peers = set(message.get('peers', []))
            with peers_lock:
                newly_discovered = received_peers - PEERS - {get_my_ip()}
                if newly_discovered:
                    print(f"[*] Menemukan {len(newly_discovered)} peer baru via gossip: {newly_discovered}")
                    PEERS.update(newly_discovered)

    except Exception as e:
        print(f"[!] Error saat memproses pesan: {e}")
    finally:
        conn.close()

def handle_search_request(message):
    filename = message['filename']
    ttl = message['ttl']
    origin_ip = message['origin_ip']

    if filename in os.listdir(SHARE_DIR):
        print(f"[*] File '{filename}' ditemukan, mengirim balasan ke {origin_ip}")
        reply = json.dumps({"type": "FOUND", "filename": filename, "host": get_my_ip()})
        send_tcp_message(origin_ip, PEER_PORT, reply)
    elif ttl > 1:
        print(f"[*] File '{filename}' tidak ditemukan, meneruskan pencarian (TTL: {ttl-1})")
        broadcast_search(filename, ttl - 1, origin_ip=origin_ip)

def broadcast_search(filename, ttl, origin_ip=None):
    if origin_ip is None:
        origin_ip = get_my_ip()
    
    print(f"[*] Melakukan broadcast pencarian untuk '{filename}'...")
    with peers_lock:
        peers_to_search = list(PEERS)
    
    msg = json.dumps({"type": "SEARCH", "filename": filename, "ttl": ttl, "origin_ip": origin_ip})
    for peer_ip in peers_to_search:
        if peer_ip != origin_ip:
            send_tcp_message(peer_ip, PEER_PORT, msg)


def peer_discovery_listener():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.bind((HOST_IP, DISCOVERY_PORT))
    print(f"[*] Discovery listener (broadcast) berjalan di port {DISCOVERY_PORT}")
    my_ip = get_my_ip()
        
    while True:
        try:
            data, addr = s.recvfrom(1024)
            peer_ip = addr[0]
            if data == b'DISCOVER_P2P_NODE' and peer_ip != my_ip:
                with peers_lock:
                    is_new_peer = peer_ip not in PEERS
                    if is_new_peer:
                        print(f"[*] Peer baru ditemukan via broadcast: {peer_ip}")
                        PEERS.add(peer_ip)
                
                if is_new_peer:
                    print(f"[*] Memulai gossip dengan {peer_ip}, mengirim daftar peer kita...")
                    send_my_peer_list(peer_ip)

        except Exception as e:
            print(f"[!] Error di discovery listener: {e}")

def send_my_peer_list(recipient_ip):
    with peers_lock:
        peer_list_to_send = list(PEERS | {get_my_ip()})
    
    message = json.dumps({'type': 'SYNC_PEERS', 'peers': peer_list_to_send})
    send_tcp_message(recipient_ip, PEER_PORT, message)

def discover_peers_periodically():
    while True:
        print("[*] Mengirim discovery broadcast...")
        udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        udp.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        udp.sendto(b'DISCOVER_P2P_NODE', ('<broadcast>', DISCOVERY_PORT))
        udp.close()
        
        with peers_lock:
            if PEERS:
                print(f"[*] Daftar peer saat ini: {PEERS}")

        time.sleep(10)


def get_my_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('8.8.8.8', 80))
        ip = s.getsockname()[0]
    except Exception:
        ip = '127.0.0.1'
    finally:
        s.close()
    return ip

def send_tcp_message(ip, port, message):
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        s.settimeout(2)
        s.connect((ip, port))
        s.send(message.encode())
    except Exception as e:
        print(f"[!] Gagal mengirim pesan TCP ke {ip}:{port} - {e}")
    finally:
        s.close()


if __name__ == '__main__':
    print("--- Memulai Aplikasi P2P File Sharing dengan Arsitektur Gossip ---")
    
    threading.Thread(target=peer_listener, daemon=True).start()
    threading.Thread(target=peer_discovery_listener, daemon=True).start()
    time.sleep(1) 
    threading.Thread(target=discover_peers_periodically, daemon=True).start()
    
    print(f"[*] Web server berjalan. Buka http://{get_my_ip()}:{WEB_PORT} di browser.")
    app.run(host=HOST_IP, port=WEB_PORT, debug=False)